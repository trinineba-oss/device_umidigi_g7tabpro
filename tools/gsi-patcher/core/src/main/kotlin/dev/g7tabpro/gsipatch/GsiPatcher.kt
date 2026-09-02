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
        val dropFec: Boolean = true,
        /**
         * Turn off adb authorisation in the image. adbd checks against
         * /data/misc/adb/adb_keys, and /data is exactly what fails to mount
         * when this goes wrong, so without it a failed boot gives no shell and
         * no logcat. Opt-in: it means any USB host can connect unauthorised.
         */
        val enableAdb: Boolean = false,
        /**
         * `/system/bin/init` taken from a GSI that boots on the target device.
         * When set, it replaces the image's own init -- a second, independent
         * boot blocker from the version properties. See [InitSwap] and
         * docs/INIT_SWAP_FIX.md. Null leaves init alone.
         */
        val donorInit: ByteArray? = null,
        /**
         * Neutralise the verified-boot spoof entries in the image's **own**
         * init, instead of replacing the file. See [InitSpoof] and
         * docs/INIT_SWAP_FIX.md.
         *
         * Preferred over [donorInit] where it applies: it needs no donor, it
         * changes three bytes rather than the whole binary, and it leaves the
         * ROM's remaining ~34 spoof entries working, so Play Integrity and
         * friends survive. Images whose init has no spoof table are reported
         * as not applicable rather than failing.
         */
        val fixInitSpoof: Boolean = false
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
        val signingKeyReplaced: Boolean,
        val adbNote: String = "unchanged",
        val initNote: String = "unchanged"
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
            appendLine("fec          : " + if (fecDropped) "dropped (descriptor zeroed)" else "unchanged")
            appendLine("adb          : " + adbNote)
            append("init         : " + initNote)
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
        "/system_ext/etc/build.prop",
        // Less common homes for the same properties. Absent ones cost nothing
        // (a missing path is skipped), and a single stale copy anywhere is
        // enough to lose the write-once race.
        "/system/etc/prop.default",
        "/system/product/build.prop",
        "/system/system_ext/build.prop",
        "/system/etc/build.prop",
        "/system/odm/etc/build.prop",
        "/system/odm/build.prop",
        "/system/vendor/build.prop"
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
        val adbKeysSeen = LinkedHashSet<String>()
        for ((p, ino) in found) {
            progress.stage("Patching " + p)
            val original = fs.readFile(ino)
            val result = BuildProp.patch(
                original, options.targetRelease, options.targetPatch, options.enableAdb
            )
            adbKeysSeen.addAll(result.adbKeysPresent)
            if (result.replacements == 0) continue   // this file defines none of them
            fs.writeFileInPlace(ino, result.bytes)
            totalReplacements += result.replacements
            patchedPaths.add(p)
            result.changes.forEach { allChanges.add(p + " " + it) }
        }
        // Swap init before the "nothing changed" guard below, so an image that
        // is already version-patched can still be given a known-good init --
        // the two fixes are independent and an image may need only this one.
        var initNote = "unchanged"
        if (options.donorInit != null) {
            progress.stage("Replacing " + InitSwap.INIT_PATH)
            val swap = InitSwap.apply(fs, options.donorInit)
            initNote = swap?.toString()
                ?: (InitSwap.INIT_PATH + " not present in this image -- nothing to replace")
        } else if (options.fixInitSpoof) {
            progress.stage("Checking " + InitSpoof.INIT_PATH + " for verified-boot spoofing")
            initNote = try {
                InitSpoof.apply(fs)?.toString()
                    ?: (InitSpoof.INIT_PATH + " not present in this image -- nothing to check")
            } catch (e: InitSpoof.NotApplicable) {
                // Not a failure: most images have no spoof table, and those
                // need nothing done to them. Say so and carry on.
                "no init patch needed -- " + e.message
            }
        }

        if (totalReplacements == 0 && options.donorInit == null && !options.fixInitSpoof) {
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
            signingKeyReplaced = avb.signingKeyReplaced,
            // Say what actually happened. Length-preserving editing cannot add
            // a line, so an image that never defined ro.adb.secure keeps
            // whatever adbd defaults to, and claiming otherwise would be a lie
            // the user only discovers when the thing hangs with no shell.
            adbNote = when {
                !options.enableAdb -> "unchanged"
                allChanges.any { it.contains("ro.adb.secure") || it.contains("ro.debuggable") } ->
                    "authorisation disabled for debugging"
                // Present but already permissive -- a userdebug GSI typically
                // ships ro.adb.secure=0 and ro.debuggable=1 already. Nothing to
                // change is a success, not the failure case below.
                adbKeysSeen.isNotEmpty() ->
                    "already permissive in this image (" + adbKeysSeen.joinToString(", ") + ")"
                else -> "requested, but this image defines neither ro.adb.secure nor " +
                    "ro.debuggable, and a line cannot be added in place -- adb may still " +
                    "be unavailable if it fails to boot"
            },
            initNote = initNote
        )
    }
}
