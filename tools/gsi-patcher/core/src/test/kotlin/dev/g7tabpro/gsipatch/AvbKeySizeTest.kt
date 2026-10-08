package dev.g7tabpro.gsipatch

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * GSIs are signed with 2048- or 4096-bit keys. The re-signed vbmeta keeps its
 * size, so the patcher must pick a key of the same size -- this used to fail on
 * every 4096-bit GSI with "the patcher holds a 2048-bit one".
 *
 * Each case builds a real AVB image with avbtool, signed with a freshly
 * generated key (so the embedded key genuinely has to be replaced), changes a
 * data byte, re-signs through [Avb.writeBack], and requires `avbtool
 * verify_image` -- not our own check -- to accept the result.
 *
 * Needs AVBTOOL (path to avbtool or avbtool.py) and AVB_TESTKEYS (a directory
 * holding testkey_rsa{2048,4096}.pkcs8.der, e.g. this module's parent after
 * running make-key.sh); skipped without them. openssl must be on PATH.
 */
class AvbKeySizeTest {

    private val avbtool = System.getenv("AVBTOOL")
    private val keysDir = System.getenv("AVB_TESTKEYS")

    private fun requireTools() = assumeTrue(
        avbtool != null && keysDir != null,
        "set AVBTOOL and AVB_TESTKEYS to run this"
    )

    private fun key(bits: Int) = File(keysDir, "testkey_rsa$bits.pkcs8.der").readBytes()

    private fun run(vararg cmd: String): String {
        val full = if (cmd[0] == "avbtool") {
            (if (avbtool!!.endsWith(".py")) listOf("python3", avbtool) else listOf(avbtool)) +
                cmd.drop(1)
        } else cmd.toList()
        val p = ProcessBuilder(full).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { full.joinToString(" ") + " failed:\n" + out }
        return out
    }

    /** Signed fixture: 4 MiB of data, hashtree footer, no FEC. */
    private fun fixture(dir: File, algorithm: String, bits: Int): File {
        val signer = File(dir, "signer.pem")
        run("openssl", "genrsa", "-out", signer.path, bits.toString())
        // avbtool verify_image re-opens the image as <partition name><ext>
        val img = File(dir, "system.img")
        img.writeBytes(Random(42).nextBytes(4 shl 20))
        run(
            "avbtool", "add_hashtree_footer", "--image", img.path,
            "--partition_name", "system", "--partition_size", (8 shl 20).toString(),
            "--algorithm", algorithm, "--key", signer.path,
            "--hash_algorithm", "sha256", "--do_not_generate_fec"
        )
        // change one byte inside the hashed data, so the tree must be rebuilt
        RandomAccessFile(img, "rw").use { it.seek(12345); it.write(0x5A) }
        return img
    }

    private fun resign(img: File, keys: List<ByteArray>): Avb =
        RandomAccessFile(img, "rw").use { raf ->
            ImageIo(raf.channel).use { io ->
                val avb = Avb(io)
                val (root, tree) = HashTree.generate(io, avb.imageSize, avb.dataBlockSize, avb.salt) { _, _ -> }
                avb.writeBack(tree, root, true, keys)
                avb
            }
        }

    private fun roundTrip(algorithm: String, bits: Int) {
        requireTools()
        val dir = Files.createTempDirectory("avbkeysize").toFile()
        try {
            val img = fixture(dir, algorithm, bits)
            val avb = resign(img, listOf(key(2048), key(4096)))
            assertTrue(avb.signingKeyReplaced, "fixture key should have been replaced")
            val info = run("avbtool", "info_image", "--image", img.path)
            assertTrue(info.contains("Algorithm:                $algorithm"), info)
            run("avbtool", "verify_image", "--image", img.path)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `4096-bit SHA256 image is re-signed with the 4096-bit key`() =
        roundTrip("SHA256_RSA4096", 4096)

    @Test
    fun `4096-bit SHA512 image is re-signed with SHA-512`() =
        roundTrip("SHA512_RSA4096", 4096)

    @Test
    fun `2048-bit image still works when both keys are supplied`() =
        roundTrip("SHA256_RSA2048", 2048)

    @Test
    fun `4096-bit image with only a 2048-bit key fails clearly`() {
        requireTools()
        val dir = Files.createTempDirectory("avbkeysize").toFile()
        try {
            val img = fixture(dir, "SHA256_RSA4096", 4096)
            val e = assertFailsWith<IllegalArgumentException> { resign(img, listOf(key(2048))) }
            assertEquals(
                "this image is signed with a 4096-bit key but the patcher holds only " +
                    "2048-bit key(s), so it cannot be re-signed",
                e.message
            )
        } finally {
            dir.deleteRecursively()
        }
    }
}
