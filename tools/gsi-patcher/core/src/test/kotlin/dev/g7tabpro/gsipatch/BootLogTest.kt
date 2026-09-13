package dev.g7tabpro.gsipatch

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BootLogTest {

    // Real digests from the G7 Tab Pro: the patched Axion that died on its
    // SELinux policy, and the stale record whose /system never mounted.
    private val selinuxImage = "c686b7dd6805bbf4deda068a4a8c25beb30fddad5f57bd047006477b01f1aad4"
    private val mountImage = "d8f882cea7e0812f2faf48747b955211d9b1b150145d981adbfac1d1039a6ae5"
    private val salt = "7c45eda901ed71ad3b338ccf55d5da231b6077e7304e19303d2b838f0fcdc54e"

    /** Lines as they appear in a real capture, in their real order. */
    private fun genfsconCrash(digest: String) = listOf(
        "init: Switching root to '/first_stage_ramdisk'",
        "init: [libfs_mgr]Created logical partition system_gsi on device /dev/block/dm-7",
        "init: [libfs_avb]Found unknown public key used to sign /system",
        "init: [libfs_avb]Built verity table: '1 /dev/block/dm-7 /dev/block/dm-7 4096 4096 829268 829268 sha256 $digest $salt 0'",
        "EXT4-fs (dm-8): mounted filesystem without journal. Opts: ",
        "init: [libfs_mgr]__mount(source=/dev/block/dm-8,target=/system,type=ext4)=0: Success",
        "init: Switching root to '/system'",
        "init: [libfs_avb]Built verity table: '1 /dev/block/dm-4 /dev/block/dm-4 4096 4096 241744 241744 sha256 505d92a5f0b13862300c13675fc71a5ef7369ff773cfe342cc2270f4d6b865ff $salt 0'",
        "init: [libfs_mgr]__mount(source=/dev/block/dm-9,target=/vendor,type=ext4)=0: Success",
        "init: Second Stage Init",
        "init: /system/bin/secilc: Found conflicting genfscon rules",
        "init: /system/bin/secilc:   at /system_ext/etc/selinux/system_ext_sepolicy.cil:29",
        "init: /system/bin/secilc:   at /vendor/etc/selinux/vendor_sepolicy.cil:557",
        "init: /system/bin/secilc: Found conflicting genfscon rules",
        "init: /system/bin/secilc:   at /vendor/etc/selinux/vendor_sepolicy.cil:4661",
        "init: /system/bin/secilc:   at /system_ext/etc/selinux/system_ext_sepolicy.cil:26",
        "init: /system/bin/secilc: Failed to compile cildb: -1",
        "init: Unable to open SELinux policy",
        "init: InitFatalReboot: signal 6",
        "init: Reboot ending, jumping to kernel"
    ).joinToString("\n", postfix = "\n")

    private fun mountCrash(digest: String) = listOf(
        "init: [libfs_mgr]Created logical partition system_gsi on device /dev/block/dm-7",
        "init: [libfs_avb]Built verity table: '1 /dev/block/dm-7 /dev/block/dm-7 4096 4096 360857 360857 sha256 $digest $salt 10 use_fec_from_device /dev/block/dm-7 fec_roots 2 fec_blocks 363701 fec_start 363701 restart_on_corruption ignore_zero_blocks'",
        "init: [libfs_mgr]Invalid ext4 superblock on '/dev/block/dm-8'",
        "EXT4-fs (dm-8): VFS: Can't find ext4 filesystem",
        "init: [libfs_mgr]__mount(source=/dev/block/dm-8,target=/system,type=ext4)=-1: Invalid argument",
        "init: Failed to mount /system: Invalid argument",
        "init: Failed to mount required partitions early ...",
        "Kernel panic - not syncing: Attempted to kill init! exitcode=0x00007f00["
    ).joinToString("\n", postfix = "\n")

    private fun noise(n: Int, seed: Long) = ByteArray(n).also { Random(seed).nextBytes(it) }

    /**
     * A synthetic dump shaped like the real one: binary noise, crash records,
     * a duplicate copy, and a verity table deliberately split across the
     * reader's 4 MiB chunk boundary.
     */
    private fun dump(): ByteArray {
        val chunk = 4 shl 20
        val first = genfsconCrash(selinuxImage)
        val tableAt = first.indexOf("init: [libfs_avb]Built verity")
        return ByteArrayOutputStream().apply {
            write(noise(chunk - tableAt - 60, 1))
            write(first.toByteArray(Charsets.ISO_8859_1))
            write(noise(1_500_000, 2))
            write(mountCrash(mountImage).toByteArray(Charsets.ISO_8859_1))
            write(noise(900_000, 3))
            write(genfsconCrash(selinuxImage).toByteArray(Charsets.ISO_8859_1))
            write(noise(2_000_000, 4))
        }.toByteArray()
    }

    @Test
    fun `each crash is tied to its image and its cause`() {
        val attempts = BootLog.scan(dump().inputStream())
        // The vendor table inside the first record must not create an attempt.
        assertEquals(setOf(selinuxImage, mountImage), attempts.keys)

        val selinux = attempts.getValue(selinuxImage)
        assertEquals(BootLog.Cause.SELINUX_GENFSCON, selinux.cause)
        assertTrue(selinux.reachedSecondStage)
        assertEquals(2, selinux.records)
        assertEquals(
            listOf("/system_ext/etc/selinux/system_ext_sepolicy.cil" to "/vendor/etc/selinux/vendor_sepolicy.cil"),
            selinux.conflictingFiles
        )

        val mount = attempts.getValue(mountImage)
        assertEquals(BootLog.Cause.SYSTEM_MOUNT, mount.cause)
        assertFalse(mount.reachedSecondStage)
        assertEquals("Attempted to kill init! exitcode=0x00007f00", mount.panic)
    }

    @Test
    fun `an image with no crash record says so rather than guessing`() {
        val text = BootLog.report(BootLog.scan(dump().inputStream()), "0".repeat(64))
        assertTrue(text.startsWith("No crash record in expdb mentions this image"))
        assertTrue(text.contains("other images with crashes recorded"))
    }

    @Test
    fun `the real G7 Tab Pro expdb reads correctly`() {
        val path = System.getenv("GOLDEN_EXPDB")
        assumeTrue(path != null, "set GOLDEN_EXPDB to a dumped expdb partition to run this")
        val attempts = File(path!!).inputStream().buffered().use { BootLog.scan(it) }
        assertEquals(BootLog.Cause.SELINUX_GENFSCON, attempts.getValue(selinuxImage).cause)
        assertEquals(
            BootLog.Cause.SELINUX_GENFSCON,
            attempts.getValue("3adeb792b9a3291e6d0d7048f004a464efaff1d502c8ebf708eb6a3fbc996f2a").cause
        )
        assertEquals(BootLog.Cause.SYSTEM_MOUNT, attempts.getValue(mountImage).cause)
    }
}
