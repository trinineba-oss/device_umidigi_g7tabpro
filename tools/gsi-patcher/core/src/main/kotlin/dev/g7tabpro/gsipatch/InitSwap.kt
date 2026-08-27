package dev.g7tabpro.gsipatch

/**
 * Replaces `/system/bin/init` with a copy taken from a GSI that is known to
 * boot on the target device.
 *
 * Background: [docs/INIT_SWAP_FIX.md]. On a device whose vendor KeyMint HAL
 * races keystore2 at boot, an `init` that does more early work than the
 * baseline can lose that race for it -- keystore2 then caches a fallback to an
 * emulated KeyMint, the real trustlet is never loaded, /data never mounts, and
 * the GSI hangs at its own splash even with a provably-correct version patch.
 * Confirmed on hardware: swapping this one file, changing nothing else, turns
 * a reliably-hanging image into a booting one.
 *
 * ## Why this fits the in-place design
 *
 * The rest of this tool never changes a file's length, which is what lets it
 * work without root, without a loop mount, and without touching block
 * allocation. A donor `init` is a different size, so at first glance it breaks
 * that invariant.
 *
 * It does not, because **an ELF can be zero-padded at the end**. The loader
 * maps what the program headers describe; trailing bytes past the last section
 * are never read. Verified empirically against the real donor: padded to the
 * destination's exact length, the file still parses as the same ELF (identical
 * build id, entry point, all four LOAD segments) and is byte-identical over
 * its original extent. So the swap becomes a length-preserving write like every
 * other, and [Ext4.writeFileInPlace] is used unmodified.
 *
 * Keeping the destination inode is not just convenient, it is required:
 * `/system/bin/init` carries the SELinux label `u:object_r:init_exec:s0` in an
 * extended attribute, and an unlabelled init is an unbootable image. Writing
 * through the existing inode leaves the xattr untouched. (The shell equivalent
 * has the same trap: a plain `cp` preserves the label, `cp --remove-destination`
 * or `rm`+`cp` destroys it.)
 *
 * ## The one case this cannot handle
 *
 * A donor **larger** than the destination would need extra blocks allocated,
 * which means rewriting the block bitmap, group descriptors and free counts --
 * exactly the complexity this tool exists to avoid. That is refused with a
 * clear message rather than half-attempted; the loop-mount recipe in the doc
 * covers it.
 */
object InitSwap {

    const val INIT_PATH = "/system/bin/init"

    /** ELF magic, plus the fields worth sanity-checking before writing. */
    private val ELF_MAGIC = byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())
    private const val ELFCLASS64 = 2
    private const val EM_AARCH64 = 183

    class Result(val donorSize: Int, val destSize: Int, val padding: Int) {
        override fun toString(): String =
            "replaced (donor " + donorSize + " bytes, padded with " + padding +
                " zero byte(s) to the destination's " + destSize + ")"
    }

    /**
     * Reads `/system/bin/init` out of [donorImage] -- a raw, already
     * decompressed GSI that boots on the target device.
     */
    fun extractFrom(donorImage: ImageIo): ByteArray {
        val fs = Ext4(donorImage)
        val ino = (try {
            fs.lookup(INIT_PATH)
        } catch (e: Exception) {
            null
        }) ?: throw IllegalArgumentException(
            "the donor image has no $INIT_PATH -- is it a GSI system image?"
        )
        return fs.readFile(ino)
    }

    /**
     * Checks [donor] looks like an init binary this device could actually run.
     * A wrong file here produces an image that fails at boot with no
     * diagnostic, so the cheap checks are worth doing up front.
     */
    fun validate(donor: ByteArray) {
        require(donor.size >= 64) { "the donor init is too small to be an ELF binary" }
        for (i in ELF_MAGIC.indices) {
            require(donor[i] == ELF_MAGIC[i]) {
                "the donor init is not an ELF binary (bad magic) -- wrong file?"
            }
        }
        require(donor[4].toInt() == ELFCLASS64) {
            "the donor init is a 32-bit ELF; this expects a 64-bit one"
        }
        // e_machine is a 16-bit little-endian field at offset 18.
        val machine = (donor[18].toInt() and 0xFF) or ((donor[19].toInt() and 0xFF) shl 8)
        require(machine == EM_AARCH64) {
            "the donor init targets machine type $machine, not AArch64 ($EM_AARCH64)"
        }
    }

    /**
     * Writes [donor] over `/system/bin/init` in [fs], zero-padding it to the
     * destination's exact length. Returns null when the image has no init at
     * that path (nothing to do), throws when the donor cannot fit.
     */
    fun apply(fs: Ext4, donor: ByteArray): Result? {
        validate(donor)
        val ino = (try {
            fs.lookup(INIT_PATH)
        } catch (e: Exception) {
            null
        }) ?: return null

        val destSize = fs.readFile(ino).size
        require(donor.size <= destSize) {
            "the donor init is " + (donor.size - destSize) + " byte(s) larger than the " +
                "image's own (" + donor.size + " vs " + destSize + "). Growing a file needs " +
                "block allocation, which this tool deliberately does not do -- use the " +
                "loop-mount recipe in docs/INIT_SWAP_FIX.md for that direction."
        }

        val padded = if (donor.size == destSize) donor else donor.copyOf(destSize)
        try {
            fs.writeFileInPlace(ino, padded)
        } catch (e: IllegalArgumentException) {
            // Overwhelmingly the sparse-hole case, and worth translating: the
            // low-level message is accurate but does not tell the user what to
            // do about it. Measured on a real pair (Project CiRCLE's init into
            // Infinity-X 3.12): the destination has holes at blocks 111,
            // 637-639 and 647, and the donor carries 7,514 non-zero bytes in
            // them, so an in-place swap is genuinely impossible here.
            throw IllegalArgumentException(
                "cannot swap init in place: " + e.message + "\n" +
                    "The image's own init is a sparse file and the donor has real data where " +
                    "the holes are. Use the loop-mount recipe in docs/INIT_SWAP_FIX.md, which " +
                    "lets the kernel allocate the missing blocks.",
                e
            )
        }

        // Never trust the write: read it back. A silently-dropped byte here
        // produces an image that verifies perfectly and then fails to boot,
        // which is the most expensive kind of bug this tool can ship.
        val readBack = fs.readFile(ino)
        require(readBack.size == padded.size && readBack.contentEquals(padded)) {
            "init did not read back as written -- refusing to continue; the image is not safe to use"
        }
        return Result(donor.size, destSize, destSize - donor.size)
    }
}
