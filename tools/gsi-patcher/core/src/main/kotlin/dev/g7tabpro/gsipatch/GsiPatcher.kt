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
        val buildPropPath: String,
        val changes: List<String>,
        val oldRootDigest: String,
        val newRootDigest: String,
        val algorithm: String,
        val fecDropped: Boolean
    ) {
        override fun toString(): String = buildString {
            appendLine("partition    : " + partitionName)
            appendLine("image size   : " + imageSize)
            appendLine("build.prop   : " + buildPropPath)
            changes.forEach { appendLine("  changed    : " + it) }
            appendLine("root digest  : " + oldRootDigest)
            appendLine("          -> : " + newRootDigest)
            appendLine("re-signed    : " + algorithm)
            append("fec          : " + if (fecDropped) "dropped (descriptor zeroed)" else "unchanged")
        }
    }

    private val BUILD_PROP_PATHS = listOf("/system/build.prop", "/build.prop")

    fun patch(io: ImageIo, options: Options, pkcs8Key: ByteArray?, progress: Progress = Silent): Report {
        progress.stage("Reading AVB footer")
        val avb = Avb(io)
        require(avb.dataBlockSize == avb.hashBlockSize) {
            "differing data/hash block sizes are not supported"
        }

        progress.stage("Opening filesystem")
        val fs = Ext4(io)

        var path: String? = null
        var ino: Long? = null
        for (candidate in BUILD_PROP_PATHS) {
            val found = fs.lookup(candidate)
            if (found != null) {
                path = candidate
                ino = found
                break
            }
        }
        if (ino == null) throw IllegalStateException(
            "build.prop not found (looked in " + BUILD_PROP_PATHS.joinToString(", ") + ")"
        )

        progress.stage("Patching " + path)
        val original = fs.readFile(ino)
        val result = BuildProp.patch(original, options.targetRelease, options.targetPatch)
        if (result.replacements == 0) {
            throw IllegalStateException(
                "no version properties needed changing: this image already reports release " +
                    options.targetRelease
            )
        }
        fs.writeFileInPlace(ino, result.bytes)

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
        val vIno = verifyFs.lookup(path!!)
            ?: throw IllegalStateException("verification: build.prop vanished")
        val text = String(verifyFs.readFile(vIno), Charsets.UTF_8)
        for (key in BuildProp.KEYS_RELEASE) {
            val expected = key + "=" + options.targetRelease
            if (text.lineSequence().none { it == expected } && text.contains(key + "=")) {
                throw IllegalStateException("verification failed: " + key + " is not " + options.targetRelease)
            }
        }
        val expectedPatch = BuildProp.KEY_PATCH + "=" + options.targetPatch
        if (text.lineSequence().none { it == expectedPatch }) {
            throw IllegalStateException("verification failed: security_patch was not applied")
        }
        val reread = Avb(io)
        if (reread.rootDigest.hex() != newRoot.hex()) {
            throw IllegalStateException("verification failed: root digest did not stick")
        }

        return Report(
            partitionName = avb.partitionName,
            imageSize = avb.imageSize,
            buildPropPath = path,
            changes = result.changes,
            oldRootDigest = oldRoot,
            newRootDigest = newRoot.hex(),
            algorithm = avb.algorithmName(),
            fecDropped = options.dropFec && avb.fecSize != 0L
        )
    }
}
