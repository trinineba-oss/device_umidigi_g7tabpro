package dev.g7tabpro.gsipatch

/**
 * Orchestrates the whole patch against an image that has already been copied to
 * its destination. Everything happens in place, so the caller only needs room
 * for one copy of the image rather than a working directory as well.
 */
object GsiPatcher {

    data class Options(
        val targetRelease: String = "13",
        val targetPatch: String = "2025-09-05",
        val dropFec: Boolean = true
    )

    interface Progress {
        fun stage(message: String)
        fun hashing(done: Long, total: Long) {}
    }

    object Silent : Progress {
        override fun stage(message: String) {}
    }

    class Report(
        val partitionName: String,
        val imageSize: Long,
        val buildPropPaths: List<String>,
        val changes: List<String>,
        val oldRootDigest: String,
        val newRootDigest: String,
        val algorithm: String,
        val fecDropped: Boolean,
        val signingKeyReplaced: Boolean
    ) {
        override fun toString(): String = buildString {
            appendLine("partition    : " + partitionName)
            appendLine("image size   : " + imageSize)
            buildPropPaths.forEach { appendLine("build.prop   : " + it) }
            changes.forEach { appendLine("  changed    : " + it) }
            appendLine("root digest  : " + oldRootDigest)
            appendLine("          -> : " + newRootDigest)
            appendLine("re-signed    : " + algorithm +
                if (signingKeyReplaced) " (embedded key replaced with the AOSP test key)" else "")
            append("fec          : " + if (fecDropped) "dropped (descriptor zeroed)" else "unchanged")
        }
    }

    /**
     * Every build.prop that can define the version properties -- not just the
     * first one found.
     *
     * ro. properties are write-once, so if any partition's build.prop sets
     * `ro.build.version.release` before /system/build.prop is read, that value
     * wins and patching /system alone silently achieves nothing. Android 16
     * GSIs hit this: /system/product/etc/build.prop sets the generic
     * `ro.build.version.release` as well as its own ro.product.* form, and the
     * device boots reporting the unpatched version while the file on disk
     * plainly reads the patched one.
     *
     * A14 and A15 GSIs do not carry these duplicates, which is why single-file
     * patching appeared to work for them.
     */
    /** Exposed so [Preflight] checks exactly the set that gets patched. */
    fun buildPropPaths(): List<String> = BUILD_PROP_PATHS

    private val BUILD_PROP_PATHS = listOf(
        "/system/build.prop",
        "/build.prop",
        "/system/product/etc/build.prop",
        "/product/etc/build.prop",
        "/system/system_ext/etc/build.prop",
        "/system_ext/etc/build.prop"
    )

    fun patch(io: ImageIo, options: Options, pkcs8Key: ByteArray?, progress: Progress = Silent): Report {
        ImageFormat.requireRaw(io)
        progress.stage("Reading AVB footer")
        val avb = Avb(io)
        require(avb.dataBlockSize == avb.hashBlockSize) {
            "differing data/hash block sizes are not supported"
        }

        progress.stage("Opening filesystem")
        val fs = Ext4(io)

        // Collect every build.prop present, not the first match: see the note
        // on BUILD_PROP_PATHS. Missing ones are normal and skipped silently.
        val found = LinkedHashMap<String, Long>()
        for (candidate in BUILD_PROP_PATHS) {
            // lookup throws rather than returning null when a path component is
            // something it cannot walk -- a symlink, or an old block-mapped
            // inode. GSIs routinely symlink /product -> /system/product, so the
            // alternate spellings in this list hit that. Such a path is not a
            // prop file we can patch in place, and the canonical spelling is
            // also in the list, so treat it as absent rather than failing the
            // whole patch.
            val ino = try {
                fs.lookup(candidate)
            } catch (e: Exception) {
                null
            } ?: continue
            found[candidate] = ino
        }
        if (found.isEmpty()) throw IllegalStateException(
            "build.prop not found (looked in " + BUILD_PROP_PATHS.joinToString(", ") + ")"
        )

        var totalReplacements = 0
        val patchedPaths = ArrayList<String>()
        val allChanges = ArrayList<String>()
        for ((p, ino) in found) {
            progress.stage("Patching " + p)
            val original = fs.readFile(ino)
            val result = BuildProp.patch(original, options.targetRelease, options.targetPatch)
            if (result.replacements == 0) continue   // this file defines none of them
            fs.writeFileInPlace(ino, result.bytes)
            totalReplacements += result.replacements
            patchedPaths.add(p)
            result.changes.forEach { allChanges.add(p + " " + it) }
        }
        if (totalReplacements == 0) {
            throw IllegalStateException(
                "no version properties needed changing: this image already reports release " +
                    options.targetRelease
            )
        }

        progress.stage("Recomputing dm-verity hashtree")
        val (newRoot, tree) = HashTree.generate(
            io, avb.imageSize, avb.dataBlockSize, avb.salt
        ) { done, total -> progress.hashing(done, total) }

        progress.stage("Writing hashtree and re-signing vbmeta")
        val oldRoot = avb.rootDigest.hex()
        avb.writeBack(tree, newRoot, options.dropFec, pkcs8Key)

        // Verify against the finished artifact rather than trusting that the
        // steps above returned without throwing.
        progress.stage("Verifying")
        val verifyFs = Ext4(io)
        // Re-read every file that was patched. A stale value left in any one of
        // them is enough to make the whole exercise pointless, since whichever
        // init reads first wins.
        var sawPatch = false
        for (p in found.keys) {
            val vIno = verifyFs.lookup(p)
                ?: throw IllegalStateException("verification: " + p + " vanished")
            val text = String(verifyFs.readFile(vIno), Charsets.UTF_8)
            for (line in text.lineSequence()) {
                val eq = line.indexOf('=')
                if (eq <= 0) continue
                val key = line.substring(0, eq)
                val value = line.substring(eq + 1)
                if ((key.endsWith(".build.version.release") ||
                        key.endsWith(".build.version.release_or_codename")) &&
                    value != options.targetRelease
                ) {
                    throw IllegalStateException(
                        "verification failed: " + p + " still has " + key + "=" + value
                    )
                }
                if (key.endsWith(".build.version.security_patch")) {
                    if (value != options.targetPatch) {
                        throw IllegalStateException(
                            "verification failed: " + p + " still has " + key + "=" + value
                        )
                    }
                    sawPatch = true
                }
            }
        }
        if (!sawPatch) {
            throw IllegalStateException("verification failed: security_patch was not applied")
        }
        val reread = Avb(io)
        if (reread.rootDigest.hex() != newRoot.hex()) {
            throw IllegalStateException("verification failed: root digest did not stick")
        }

        // Re-hash the written image and compare, the way avbtool verify_image
        // does. Checking only the digest stored in vbmeta would pass even if
        // the hashtree itself had been written short -- which on the device
        // shows up as dm-verity refusing to mount, with no clue why.
        progress.stage("Re-hashing to confirm the image verifies")
        val (checkRoot, _) = HashTree.generate(
            io, reread.imageSize, reread.dataBlockSize, reread.salt
        ) { done, total -> progress.hashing(done, total) }
        if (!checkRoot.contentEquals(reread.rootDigest)) {
            throw IllegalStateException(
                "verification failed: the image does not hash to its own root digest " +
                    "(expected " + reread.rootDigest.hex() + ", got " + checkRoot.hex() + ")"
            )
        }

        return Report(
            partitionName = avb.partitionName,
            imageSize = avb.imageSize,
            buildPropPaths = patchedPaths,
            changes = allChanges,
            oldRootDigest = oldRoot,
            newRootDigest = newRoot.hex(),
            algorithm = avb.algorithmName(),
            fecDropped = options.dropFec && avb.fecSize != 0L,
            signingKeyReplaced = avb.signingKeyReplaced
        )
    }
}
