package dev.g7tabpro.gsipatch

import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipFile
import org.apache.commons.compress.archivers.sevenz.SevenZFile

/**
 * Reduces the containers GSIs and full ROM builds actually get distributed
 * in -- an OTA zip (`payload.bin` inside), an image zip (`system.img` inside,
 * as Google's ci.android.com GSIs ship), a bare `payload.bin`, a 7z archive,
 * or an Android sparse image -- down to a plain system image, so the existing raw/gz/xz pipeline
 * ([Compression], [GsiPatcher]) can pick up unchanged from there without
 * knowing any of this happened.
 *
 * [unwrap] is the only thing callers need: it returns [input] itself when
 * there is nothing to unwrap, or a new file in [workDir] otherwise. That new
 * file is deliberately *not* guaranteed to be final -- a 7z entry can itself
 * be `.img.xz`, and the caller's own [Compression] handling already knows how
 * to deal with that, so this does not duplicate it.
 */
object Ingest {

    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    private val SEVENZIP_MAGIC = byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C)
    private val PAYLOAD_MAGIC = "CrAU".toByteArray(Charsets.US_ASCII)
    /** Android sparse image magic 0xED26FF3A, little-endian. */
    private val SPARSE_MAGIC = byteArrayOf(0x3A, 0xFF.toByte(), 0x26, 0xED.toByte())

    private fun peek(file: File, n: Int): ByteArray {
        RandomAccessFile(file, "r").use { raf ->
            val buf = ByteArray(n)
            val read = raf.read(buf).coerceAtLeast(0)
            return if (read < n) buf.copyOf(read) else buf
        }
    }

    private fun ByteArray.startsWith(magic: ByteArray) =
        size >= magic.size && magic.indices.all { this[it] == magic[it] }

    /**
     * True if [head] (the first ~8 bytes of a file) looks like something
     * [unwrap] would need to extract from, rather than a plain system image.
     * Exposed separately so a caller reading from a stream it can't easily
     * turn into a [java.io.File] (Android's `content://` Uris, notably) can
     * decide whether a local copy is even worth making before committing to
     * one -- most inputs are not containers, and a multi-gigabyte GSI is
     * expensive to copy twice on a phone's limited storage for nothing.
     */
    fun looksLikeContainer(head: ByteArray): Boolean =
        head.startsWith(PAYLOAD_MAGIC) || head.startsWith(ZIP_MAGIC) ||
            head.startsWith(SEVENZIP_MAGIC) || head.startsWith(SPARSE_MAGIC)

    fun unwrap(
        input: File,
        workDir: File,
        partitionName: String = "system",
        progress: ((Long, Long) -> Unit)? = null
    ): File {
        val head = peek(input, 8)
        return when {
            head.startsWith(PAYLOAD_MAGIC) -> {
                val out = File(workDir, "extracted-$partitionName.img")
                extractPayload(input, out, partitionName, progress)
                out
            }
            head.startsWith(ZIP_MAGIC) -> unwrapZip(input, workDir, partitionName, progress)
            head.startsWith(SEVENZIP_MAGIC) -> extractSevenZipFirstFile(input, workDir)
            head.startsWith(SPARSE_MAGIC) -> {
                val out = File(workDir, "extracted-$partitionName.img")
                input.inputStream().buffered(1 shl 20).use { Sparse.expand(it, out) }
                out
            }
            else -> input
        }
    }

    /** [payloadFile] must already be a plain, seekable `payload.bin` -- not still zipped. */
    private fun extractPayload(
        payloadFile: File,
        outFile: File,
        partitionName: String,
        progress: ((Long, Long) -> Unit)?
    ) {
        RandomAccessFile(payloadFile, "r").use { raf ->
            ImageIo(raf.channel).use { io ->
                val header = Payload.readHeader(io)
                val part = header.partitions.firstOrNull { it.name == partitionName }
                    ?: throw IllegalArgumentException(
                        "this payload has no \"$partitionName\" partition (it has: " +
                            header.partitions.joinToString { it.name } + ")"
                    )
                RandomAccessFile(outFile, "rw").use { outRaf ->
                    outRaf.setLength(part.newSize)
                    ImageIo(outRaf.channel).use { outIo ->
                        Payload.extractPartition(io, header, partitionName, outIo, progress)
                    }
                }
            }
        }
    }

    /**
     * Two zip layouts are in the wild. OTA packages carry `payload.bin`; image
     * zips -- Google's own GSIs from ci.android.com (`aosp_arm64-...zip`),
     * `fastboot update` packages -- carry `system.img` directly, often in
     * Android sparse format. payload.bin wins if both are present.
     */
    private fun unwrapZip(
        zipFile: File,
        workDir: File,
        partitionName: String,
        progress: ((Long, Long) -> Unit)?
    ): File {
        ZipFile(zipFile).use { zip ->
            val entries = zip.entries().asSequence().filter { !it.isDirectory }.toList()
            val payload = entries.firstOrNull { File(it.name).name.equals("payload.bin", true) }
            if (payload != null) {
                val payloadTmp = File(workDir, "payload.bin.tmp")
                try {
                    zip.getInputStream(payload).use { inp -> copy(inp, payloadTmp) }
                    val out = File(workDir, "extracted-$partitionName.img")
                    extractPayload(payloadTmp, out, partitionName, progress)
                    return out
                } finally {
                    payloadTmp.delete()
                }
            }
            val image = entries.firstOrNull { File(it.name).name.equals("$partitionName.img", true) }
                ?: throw IllegalArgumentException(
                    "this zip has neither payload.bin nor $partitionName.img inside it (it has: " +
                        entries.joinToString { File(it.name).name }.ifEmpty { "nothing" } + ")"
                )
            val out = File(workDir, "extracted-$partitionName.img")
            zip.getInputStream(image).buffered(1 shl 20).use { inp ->
                inp.mark(4)
                val magic = ByteArray(4).also { b -> var n = 0; while (n < 4) { val r = inp.read(b, n, 4 - n); if (r < 0) break; n += r } }
                inp.reset()
                if (magic.startsWith(SPARSE_MAGIC)) Sparse.expand(inp, out) else copy(inp, out)
            }
            return out
        }
    }

    /**
     * Most 7z-distributed GSIs are a single compressed `.img`/`.img.xz` and
     * nothing else. Extracting the first non-directory entry covers that
     * case; a 7z containing something more elaborate (an OTA zip, say) is
     * out of scope here -- it would need recursing into [unwrap] again, and
     * nobody actually ships GSIs that way.
     */
    private fun extractSevenZipFirstFile(sevenZFile: File, workDir: File): File {
        SevenZFile.builder().setFile(sevenZFile).get().use { archive ->
            var entry = archive.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val out = File(workDir, "extracted-" + File(entry.name).name)
                    out.outputStream().use { dst ->
                        val buf = ByteArray(1 shl 20)
                        while (true) {
                            val n = archive.read(buf)
                            if (n < 0) break
                            dst.write(buf, 0, n)
                        }
                    }
                    return out
                }
                entry = archive.nextEntry
            }
        }
        throw IllegalArgumentException("this 7z archive has no files in it")
    }

    private fun copy(inp: java.io.InputStream, out: File) {
        out.outputStream().use { dst ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = inp.read(buf)
                if (n < 0) break
                dst.write(buf, 0, n)
            }
        }
    }
}
