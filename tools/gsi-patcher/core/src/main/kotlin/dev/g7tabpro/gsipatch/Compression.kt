package dev.g7tabpro.gsipatch

import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream
import org.tukaani.xz.XZInputStream

/**
 * Counts bytes pulled from the underlying stream.
 *
 * Wrapped *below* the decompressor so it counts compressed bytes, which is what
 * makes a percentage possible: the only size known up front for a compressed
 * GSI is the compressed one.
 */
class CountingInputStream(wrapped: InputStream) : FilterInputStream(wrapped) {

    @Volatile
    var count: Long = 0L
        private set

    override fun read(): Int {
        val b = super.read()
        if (b >= 0) count++
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) count += n
        return n
    }

    override fun skip(n: Long): Long {
        val s = super.skip(n)
        count += s
        return s
    }

    override fun markSupported(): Boolean = false
}

/**
 * Transparent decompression for the shapes GSIs are actually distributed in.
 *
 * Detection is by magic bytes rather than file extension: renaming a download
 * is common, and a wrong guess here surfaces as a confusing ext4 or AVB error
 * much later instead of a clear message now.
 */
object Compression {

    enum class Kind(val label: String) {
        RAW("raw image"),
        GZIP("gzip"),
        XZ("xz"),
        SEVENZIP("7z")
    }

    /**
     * Ceiling on the LZMA2 dictionary, in KiB. `xz -9` uses a 64 MiB
     * dictionary, so 256 MiB leaves generous headroom while still failing with
     * a clear message rather than an OOM kill on a phone.
     */
    const val XZ_MEMORY_LIMIT_KIB = 256 * 1024

    private val GZIP_MAGIC = byteArrayOf(0x1F, 0x8B.toByte())
    private val XZ_MAGIC =
        byteArrayOf(0xFD.toByte(), 0x37, 0x7A, 0x58, 0x5A, 0x00)
    private val SEVENZIP_MAGIC =
        byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C)

    class Source(
        val kind: Kind,
        val stream: InputStream,
        private val counter: CountingInputStream
    ) {
        /** Compressed bytes consumed so far, for progress against the file size. */
        val compressedBytesRead: Long get() = counter.count
    }

    private fun ByteArray.startsWith(magic: ByteArray): Boolean {
        if (size < magic.size) return false
        for (i in magic.indices) if (this[i] != magic[i]) return false
        return true
    }

    fun detect(head: ByteArray): Kind = when {
        head.startsWith(XZ_MAGIC) -> Kind.XZ
        head.startsWith(GZIP_MAGIC) -> Kind.GZIP
        head.startsWith(SEVENZIP_MAGIC) -> Kind.SEVENZIP
        else -> Kind.RAW
    }

    fun wrap(input: InputStream, kind: Kind): InputStream = when (kind) {
        Kind.RAW -> input
        Kind.GZIP -> GZIPInputStream(input, 1 shl 16)
        Kind.XZ -> XZInputStream(input, XZ_MEMORY_LIMIT_KIB)
        Kind.SEVENZIP -> throw IllegalArgumentException(
            "this is a 7z archive, which is a container rather than a compressed " +
                "stream: extract the .img out of it first, then patch that"
        )
    }

    /** Sniff the header and return a decompressing stream over [raw]. */
    fun open(raw: InputStream): Source {
        val counting = CountingInputStream(raw)
        val buffered = BufferedInputStream(counting, 1 shl 16)
        buffered.mark(16)
        val head = ByteArray(16)
        var n = 0
        while (n < head.size) {
            val r = buffered.read(head, n, head.size - n)
            if (r < 0) break
            n += r
        }
        buffered.reset()
        val kind = detect(head)
        return Source(kind, wrap(buffered, kind), counting)
    }
}
