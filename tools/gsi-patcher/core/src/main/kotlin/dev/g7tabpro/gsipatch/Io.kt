package dev.g7tabpro.gsipatch

import java.io.Closeable
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Random-access view over the image being patched.
 *
 * Deliberately positional (pread/pwrite style) rather than stream based: the
 * patch touches three widely separated regions (build.prop inside the
 * filesystem, the hashtree, the vbmeta blob) and never rewrites the file
 * linearly.
 */
class ImageIo(
    private val readCh: FileChannel,
    private val writeCh: FileChannel = readCh
) : Closeable {

    val size: Long get() = readCh.size()

    fun read(offset: Long, len: Int): ByteArray {
        val buf = ByteBuffer.allocate(len)
        readInto(offset, buf)
        return buf.array()
    }

    fun readInto(offset: Long, buf: ByteBuffer) {
        var pos = offset
        while (buf.hasRemaining()) {
            val n = readCh.read(buf, pos)
            if (n < 0) throw EOFException("unexpected EOF at $pos")
            pos += n
        }
    }

    fun write(offset: Long, data: ByteArray, dataOff: Int = 0, len: Int = data.size - dataOff) {
        val buf = ByteBuffer.wrap(data, dataOff, len)
        var pos = offset
        while (buf.hasRemaining()) pos += writeCh.write(buf, pos)
    }

    fun force() = writeCh.force(true)

    /**
     * On the JVM both channels are the same RandomAccessFile channel. Under SAF
     * there is no read-write FileChannel, so the caller passes a FileInputStream
     * channel and a FileOutputStream channel over one ParcelFileDescriptor;
     * both address the same open file, so positional I/O stays coherent.
     */
    override fun close() {
        try {
            writeCh.close()
        } finally {
            if (readCh !== writeCh) readCh.close()
        }
    }
}

// ---- endian helpers -------------------------------------------------------
// ext4 is little-endian; AVB is big-endian. Keeping both explicit avoids the
// classic bug of reading one structure with the other's accessor.

internal fun ByteArray.le16(o: Int): Int =
    (this[o].toInt() and 0xFF) or ((this[o + 1].toInt() and 0xFF) shl 8)

internal fun ByteArray.le32(o: Int): Long =
    (this[o].toLong() and 0xFF) or
        ((this[o + 1].toLong() and 0xFF) shl 8) or
        ((this[o + 2].toLong() and 0xFF) shl 16) or
        ((this[o + 3].toLong() and 0xFF) shl 24)

internal fun ByteArray.be32(o: Int): Long =
    ((this[o].toLong() and 0xFF) shl 24) or
        ((this[o + 1].toLong() and 0xFF) shl 16) or
        ((this[o + 2].toLong() and 0xFF) shl 8) or
        (this[o + 3].toLong() and 0xFF)

internal fun ByteArray.be64(o: Int): Long {
    var v = 0L
    for (i in 0 until 8) v = (v shl 8) or (this[o + i].toLong() and 0xFF)
    return v
}

internal fun ByteArray.putBe32(o: Int, v: Long) {
    this[o] = ((v shr 24) and 0xFF).toByte()
    this[o + 1] = ((v shr 16) and 0xFF).toByte()
    this[o + 2] = ((v shr 8) and 0xFF).toByte()
    this[o + 3] = (v and 0xFF).toByte()
}

internal fun ByteArray.putBe64(o: Int, v: Long) {
    for (i in 0 until 8) this[o + i] = ((v shr (56 - 8 * i)) and 0xFF).toByte()
}

internal fun roundUp(value: Long, multiple: Long): Long =
    if (value % multiple == 0L) value else value + multiple - (value % multiple)

internal fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
