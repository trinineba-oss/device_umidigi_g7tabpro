package dev.g7tabpro.gsipatch

import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile

/**
 * Expands an Android sparse image (libsparse format, what `img2simg` writes
 * and what fastboot packages usually carry) to a raw image -- `simg2img` in a
 * stream, so it works straight out of a zip entry with no intermediate copy.
 *
 * Format: a 28-byte file header (magic 0xED26FF3A, version 1.x, block size,
 * total blocks, chunk count) then chunks, each a 12-byte header (type, size in
 * blocks, total bytes) followed by its payload:
 *   RAW        0xCAC1  blocks of data, written as-is
 *   FILL       0xCAC2  a 4-byte pattern repeated over the chunk
 *   DONT_CARE  0xCAC3  no payload; left as zeroes
 *   CRC32      0xCAC4  4-byte checksum; skipped
 * Header sizes are read from the file rather than assumed, as libsparse does.
 *
 * Every count is checked against the header, so a truncated or corrupt file
 * fails loudly instead of yielding an image that is merely wrong.
 */
object Sparse {
    private const val MAGIC = 0xED26FF3AL
    private const val RAW = 0xCAC1
    private const val FILL = 0xCAC2
    private const val DONT_CARE = 0xCAC3
    private const val CRC32 = 0xCAC4

    fun expand(input: InputStream, out: File) {
        val hdr = readFully(input, 28)
        require(hdr.le32(0) == MAGIC) { "not an Android sparse image" }
        val major = hdr.le16(4)
        require(major == 1) { "unsupported sparse image version $major" }
        val fileHdrSz = hdr.le16(8)
        val chunkHdrSz = hdr.le16(10)
        val blkSz = hdr.le32(12)
        val totalBlks = hdr.le32(16)
        val totalChunks = hdr.le32(20)
        require(fileHdrSz >= 28 && chunkHdrSz >= 12) { "sparse header sizes are too small" }
        require(blkSz > 0 && blkSz % 4 == 0L) { "sparse block size $blkSz is invalid" }
        skipFully(input, (fileHdrSz - 28).toLong())

        RandomAccessFile(out, "rw").use { raf ->
            raf.setLength(0)
            raf.setLength(blkSz * totalBlks)  // DONT_CARE and zero FILL stay holes
            var blk = 0L
            val buf = ByteArray(1 shl 20)
            for (c in 0 until totalChunks) {
                val ch = readFully(input, 12)
                skipFully(input, (chunkHdrSz - 12).toLong())
                val type = ch.le16(0)
                val chunkBlks = ch.le32(4)
                val dataSz = ch.le32(8) - chunkHdrSz
                require(blk + chunkBlks <= totalBlks || type == CRC32) {
                    "sparse chunk $c runs past the image end"
                }
                when (type) {
                    RAW -> {
                        require(dataSz == chunkBlks * blkSz) { "sparse RAW chunk $c has a bad size" }
                        raf.seek(blk * blkSz)
                        var left = dataSz
                        while (left > 0) {
                            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                            require(n > 0) { "sparse image is truncated in chunk $c" }
                            raf.write(buf, 0, n)
                            left -= n
                        }
                    }
                    FILL -> {
                        require(dataSz == 4L) { "sparse FILL chunk $c has a bad size" }
                        val pat = readFully(input, 4)
                        if (pat.any { it != 0.toByte() }) {
                            val block = ByteArray(blkSz.toInt())
                            for (i in block.indices) block[i] = pat[i % 4]
                            raf.seek(blk * blkSz)
                            for (i in 0 until chunkBlks) raf.write(block)
                        }
                    }
                    DONT_CARE -> require(dataSz == 0L) { "sparse DONT_CARE chunk $c has data" }
                    CRC32 -> skipFully(input, dataSz)
                    else -> throw IllegalArgumentException(
                        "unknown sparse chunk type 0x" + type.toString(16) + " at chunk $c"
                    )
                }
                if (type != CRC32) blk += chunkBlks
            }
            require(blk == totalBlks) {
                "sparse image covers $blk of $totalBlks blocks -- truncated or corrupt"
            }
        }
    }

    private fun readFully(input: InputStream, n: Int): ByteArray {
        val b = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(b, off, n - off)
            require(r > 0) { "sparse image is truncated" }
            off += r
        }
        return b
    }

    private fun skipFully(input: InputStream, n: Long) {
        var left = n
        while (left > 0) {
            val s = input.skip(left)
            if (s > 0) { left -= s; continue }
            require(input.read() >= 0) { "sparse image is truncated" }
            left--
        }
    }

    private fun ByteArray.le16(o: Int) =
        (this[o].toInt() and 0xFF) or ((this[o + 1].toInt() and 0xFF) shl 8)

    private fun ByteArray.le32(o: Int) =
        (this[o].toLong() and 0xFF) or ((this[o + 1].toLong() and 0xFF) shl 8) or
            ((this[o + 2].toLong() and 0xFF) shl 16) or ((this[o + 3].toLong() and 0xFF) shl 24)
}
