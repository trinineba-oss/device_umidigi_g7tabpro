package dev.g7tabpro.gsipatch

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Image zips (Google's ci.android.com GSIs: `system.img` inside, usually
 * sparse) used to fail with "this zip has no payload.bin inside it".
 */
class IngestTest {

    private val bs = 4096

    private fun tmpDir() = Files.createTempDirectory("ingest").toFile()

    /** Hand-built sparse image covering every chunk type, plus its raw form. */
    private fun handBuilt(): Pair<ByteArray, ByteArray> {
        val rawData = Random(7).nextBytes(2 * bs)
        val expected = ByteArrayOutputStream().apply {
            write(rawData)                                                   // RAW, 2 blocks
            write(ByteArray(3 * bs) { i -> byteArrayOf(1, 2, 3, 4)[i % 4] }) // FILL 01020304, 3
            write(ByteArray(2 * bs))                                         // FILL zero, 2
            write(ByteArray(4 * bs))                                         // DONT_CARE, 4
        }.toByteArray()
        fun chunk(type: Int, blocks: Int, data: ByteArray) =
            ByteBuffer.allocate(12 + data.size).order(ByteOrder.LITTLE_ENDIAN)
                .putShort(type.toShort()).putShort(0).putInt(blocks).putInt(12 + data.size)
                .put(data).array()
        val chunks = listOf(
            chunk(0xCAC1, 2, rawData),
            chunk(0xCAC2, 3, byteArrayOf(1, 2, 3, 4)),
            chunk(0xCAC2, 2, ByteArray(4)),
            chunk(0xCAC3, 4, ByteArray(0)),
            chunk(0xCAC4, 0, byteArrayOf(9, 9, 9, 9)),
        )
        val hdr = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0xED26FF3A.toInt()).putShort(1).putShort(0).putShort(28).putShort(12)
            .putInt(bs).putInt(11).putInt(chunks.size).putInt(0).array()
        val sparse = ByteArrayOutputStream().apply { write(hdr); chunks.forEach { write(it) } }
        return sparse.toByteArray() to expected
    }

    @Test
    fun `hand-built sparse image expands to the exact raw bytes`() {
        val dir = tmpDir()
        try {
            val (sparse, expected) = handBuilt()
            val f = File(dir, "system.img").apply { writeBytes(sparse) }
            assertTrue(Ingest.looksLikeContainer(sparse.copyOf(8)))
            val out = Ingest.unwrap(f, dir)
            assertContentEquals(expected, out.readBytes())
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun `truncated sparse image fails instead of producing a short image`() {
        val dir = tmpDir()
        try {
            val (sparse, _) = handBuilt()
            val f = File(dir, "system.img").apply { writeBytes(sparse.copyOf(sparse.size - 5000)) }
            assertFailsWith<IllegalArgumentException> { Ingest.unwrap(f, dir) }
        } finally { dir.deleteRecursively() }
    }

    /** A raw image with data, zero runs and a repeated pattern, like a real fs. */
    private fun rawImage(): ByteArray {
        val r = Random(11)
        val img = ByteArray(64 * bs)
        r.nextBytes(img, 0, 10 * bs)
        for (i in 20 * bs until 30 * bs) img[i] = 0x55
        r.nextBytes(img, 40 * bs, 41 * bs + 100)
        return img
    }

    private fun img2simg(raw: File, sparse: File) {
        val p = ProcessBuilder("img2simg", raw.path, sparse.path).redirectErrorStream(true).start()
        val log = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { "img2simg failed: $log" }
    }

    private fun hasImg2simg() = System.getenv("PATH").orEmpty().split(File.pathSeparator)
        .any { File(it, "img2simg").canExecute() }

    @Test
    fun `sparse image from the real img2simg round-trips`() {
        assumeTrue(hasImg2simg(), "img2simg not on PATH")
        val dir = tmpDir()
        try {
            val raw = File(dir, "raw.img").apply { writeBytes(rawImage()) }
            val sparse = File(dir, "system.img")
            img2simg(raw, sparse)
            assertTrue(sparse.length() < raw.length(), "img2simg should have made it smaller")
            assertContentEquals(raw.readBytes(), Ingest.unwrap(sparse, dir).readBytes())
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun `Google-style image zip with sparse system img is unwrapped`() {
        assumeTrue(hasImg2simg(), "img2simg not on PATH")
        val dir = tmpDir()
        try {
            val raw = File(dir, "raw.img").apply { writeBytes(rawImage()) }
            val sparse = File(dir, "sparse.img")
            img2simg(raw, sparse)
            val zip = File(dir, "aosp_arm64-img.zip")
            ZipOutputStream(zip.outputStream()).use { z ->
                z.putNextEntry(ZipEntry("vbmeta.img")); z.write(ByteArray(64)); z.closeEntry()
                z.putNextEntry(ZipEntry("system.img")); z.write(sparse.readBytes()); z.closeEntry()
                z.putNextEntry(ZipEntry("android-info.txt")); z.write("board=".toByteArray()); z.closeEntry()
            }
            val work = File(dir, "work").apply { mkdirs() }
            assertContentEquals(raw.readBytes(), Ingest.unwrap(zip, work).readBytes())
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun `zip with a raw system img is copied as-is`() {
        val dir = tmpDir()
        try {
            val raw = rawImage()
            val zip = File(dir, "gsi.zip")
            ZipOutputStream(zip.outputStream()).use { z ->
                z.putNextEntry(ZipEntry("out/system.img")); z.write(raw); z.closeEntry()
            }
            val work = File(dir, "work").apply { mkdirs() }
            assertContentEquals(raw, Ingest.unwrap(zip, work).readBytes())
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun `zip with neither payload nor system img says what it does contain`() {
        val dir = tmpDir()
        try {
            val zip = File(dir, "x.zip")
            ZipOutputStream(zip.outputStream()).use { z ->
                z.putNextEntry(ZipEntry("boot.img")); z.write(ByteArray(8)); z.closeEntry()
            }
            val e = assertFailsWith<IllegalArgumentException> { Ingest.unwrap(zip, dir) }
            assertTrue(e.message!!.contains("neither payload.bin nor system.img") &&
                e.message!!.contains("boot.img"), e.message)
        } finally { dir.deleteRecursively() }
    }
}
