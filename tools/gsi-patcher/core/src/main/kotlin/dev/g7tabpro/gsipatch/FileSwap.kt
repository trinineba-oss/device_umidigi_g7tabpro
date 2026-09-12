package dev.g7tabpro.gsipatch

/**
 * Replaces an arbitrary file inside the image with one taken from elsewhere,
 * using the same length-preserving, inode-preserving write [InitSwap] uses.
 *
 * ## Why this exists
 *
 * [InitSwap] hardcodes `/system/bin/init`, because that was the only file the
 * investigation needed to replace. It is not the only file that can stop a GSI
 * booting.
 *
 * A report on the ROM maintainer's own tracker (issues_general#16) describes
 * Doze-off GSIs failing to boot under DSU on two unrelated Unisoc devices
 * (kernel 4.14 and 5.4) while booting fine when flashed directly -- and the
 * reporter fixed it by transplanting `snapuserd`, `gsid`, `init`, `ueventd`
 * and the phh scripts from a GSI that did boot. `snapuserd` is the userspace
 * daemon that serves DSU's dm-snapshot, and it runs in **first stage, before
 * init** -- which is exactly where an "instant revert" with no splash would
 * come from, and exactly why replacing init never helped.
 *
 * ## Preserving the inode is not optional
 *
 * Executables under `/system/bin` carry SELinux labels in extended attributes
 * (`snapuserd` is `u:object_r:snapuserd_exec:s0`). Creating a *new* file loses
 * the xattr, and an unlabelled first-stage daemon is worse than the original
 * problem -- it will not run at all. Writing through the existing inode leaves
 * the label untouched, which is the same reason [InitSwap] works.
 *
 * ## Length
 *
 * The donor is zero-padded to the destination's exact length. That is safe for
 * an ELF -- the loader maps what the program headers describe and never reads
 * past the last section -- and it keeps the write length-preserving, so no
 * block allocation is needed. A donor *larger* than the destination is
 * refused rather than half-attempted.
 */
object FileSwap {

    class Result(
        val path: String,
        val donorSize: Int,
        val destSize: Int,
        val relocated: Boolean
    ) {
        override fun toString(): String =
            path + ": replaced with " + donorSize + " bytes, padded to " + destSize +
                (if (relocated) " -- relocated to fresh blocks" else "")
    }

    /** Raised when the image has no such file, as distinct from a failure. */
    class NotPresent(message: String) : Exception(message)

    /**
     * Reads [path] out of [donorImage] -- a raw, already decompressed GSI.
     */
    fun extractFrom(donorImage: ImageIo, path: String): ByteArray {
        val fs = Ext4(donorImage)
        val ino = (try {
            fs.lookup(path)
        } catch (e: Exception) {
            null
        }) ?: throw IllegalArgumentException("the donor image has no $path")
        return fs.readFile(ino)
    }

    /**
     * Writes [donor] over [path] in [fs], preserving the inode and its
     * extended attributes.
     */
    fun apply(fs: Ext4, path: String, donor: ByteArray): Result {
        val ino = (try {
            fs.lookup(path)
        } catch (e: Exception) {
            null
        }) ?: throw NotPresent("$path is not present in this image")

        val destSize = fs.readFile(ino).size
        require(donor.size <= destSize) {
            "the donor $path is " + (donor.size - destSize) + " byte(s) larger than the " +
                "image's own (" + donor.size + " vs " + destSize + "). Growing a file needs " +
                "block allocation, which this path deliberately does not do."
        }

        val padded = if (donor.size == destSize) donor else donor.copyOf(destSize)
        var relocated = false
        try {
            fs.writeFileInPlace(ino, padded)
        } catch (e: IllegalArgumentException) {
            // The sparse-hole case, same as InitSwap: the destination has holes
            // where the donor has real bytes, so there is nowhere in the
            // existing blocks to put them.
            fs.writeFileRelocated(ino, padded)
            relocated = true
        }

        // Never trust the write. A dropped byte in a first-stage daemon
        // produces an image that verifies perfectly and then fails to boot,
        // with no diagnostic at all.
        val readBack = fs.readFile(ino)
        require(readBack.size == padded.size && readBack.contentEquals(padded)) {
            "$path did not read back as written -- refusing to continue; the image is not safe"
        }
        return Result(path, donor.size, destSize, relocated)
    }
}
