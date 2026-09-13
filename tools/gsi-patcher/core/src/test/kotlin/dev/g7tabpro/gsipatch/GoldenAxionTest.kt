package dev.g7tabpro.gsipatch

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.io.RandomAccessFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The strongest test this project has: patch stock AxionOS 2.8 VANILLA
 * 2026-09-04 with this device's vendor policy and require the exact root
 * digest of the image that was confirmed booting on the G7 Tab Pro on
 * 2026-09-12.
 *
 * A digest match means every byte the hashtree covers is identical, so any
 * change to ext4 writing, build.prop patching, the init fix, the policy fix or
 * re-signing that alters output is caught here instead of on a device.
 *
 * Needs GOLDEN_AXION_XZ (the stock .img.xz), GOLDEN_VENDOR_SELINUX (a directory
 * of the device's vendor .cil files) and GOLDEN_KEY (the PKCS#8 test key).
 */
class GoldenAxionTest {

    private val confirmedBooting = "dc3de36a433114510e577c9776861be2d000ab2351f84f81b141c6a822b4d1ff"

    @Test
    fun `patching stock Axion reproduces the image that booted on hardware`() {
        val xz = System.getenv("GOLDEN_AXION_XZ")
        val vendorDir = System.getenv("GOLDEN_VENDOR_SELINUX")
        val key = System.getenv("GOLDEN_KEY")
        assumeTrue(
            xz != null && vendorDir != null && key != null,
            "set GOLDEN_AXION_XZ, GOLDEN_VENDOR_SELINUX and GOLDEN_KEY to run this"
        )
        val vendorRules = File(vendorDir!!).listFiles { f -> f.name.endsWith(".cil") }!!
            .flatMap { Sepolicy.parse(it.readText(Charsets.ISO_8859_1), "vendor/" + it.name) }

        val img = File.createTempFile("golden-axion", ".img")
        try {
            File(xz!!).inputStream().buffered().use { raw ->
                Compression.open(raw).stream.use { src ->
                    img.outputStream().use { src.copyTo(it, 1 shl 20) }
                }
            }
            RandomAccessFile(img, "rw").use { raf ->
                ImageIo(raf.channel).use { io ->
                    val report = GsiPatcher.patch(
                        io,
                        GsiPatcher.Options(
                            fixInitSpoof = true,
                            vendorGenfscon = vendorRules,
                            vendorSepolicyVersion = "31.0"
                        ),
                        File(key!!).readBytes()
                    )
                    assertEquals(confirmedBooting, report.newRootDigest, report.toString())
                    assertTrue(report.sepolicyNote.startsWith("neutralised 3"), report.sepolicyNote)

                    val pre = Preflight.check(
                        io, "13", "2025-09-05",
                        vendorGenfscon = vendorRules, vendorSepolicyVersion = "31.0"
                    )
                    assertEquals(0, pre.blockers, pre.toString())
                }
            }
        } finally {
            img.delete()
        }
    }
}
