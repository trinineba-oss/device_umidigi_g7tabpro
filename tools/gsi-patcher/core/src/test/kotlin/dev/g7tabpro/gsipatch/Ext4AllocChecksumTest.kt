package dev.g7tabpro.gsipatch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class Ext4AllocChecksumTest {

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    // Group 25 of an AxionOS image the old code produced, read straight off
    // disk: the filesystem UUID and the 32-byte descriptor as it was written.
    // e2fsck -fn on that image said: "Group descriptor 25 checksum is 0x8a3a,
    // should be 0xe7a4".
    private val uuid = hex("428d493c91fd5f06aff06e95e75ae09d")
    private val descriptor = hex("02800c0003800c0004800c007e1e210107000000000000000000000021013a8a")

    @Test
    fun `matches what e2fsck expects for a real descriptor`() {
        assertEquals(0xe7a4, Ext4Alloc.groupDescCsum(uuid, 25, descriptor))
    }

    @Test
    fun `does not reproduce the value the old code stored`() {
        assertNotEquals(0x8a3a, Ext4Alloc.groupDescCsum(uuid, 25, descriptor))
    }

    @Test
    fun `ignores whatever the checksum field currently holds`() {
        val changed = descriptor.copyOf().also { it[0x1E] = 0x11; it[0x1F] = 0x22 }
        assertEquals(
            Ext4Alloc.groupDescCsum(uuid, 25, descriptor),
            Ext4Alloc.groupDescCsum(uuid, 25, changed)
        )
    }
}
