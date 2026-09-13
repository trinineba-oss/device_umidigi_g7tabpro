package dev.g7tabpro.gsipatch

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImageFormatTest {

    private fun headWithValueAt1024(value: Long) = ByteArray(1028).also {
        for (i in 0 until 4) it[1024 + i] = ((value shr (8 * i)) and 0xFF).toByte()
    }

    @Test
    fun `an EROFS superblock is recognised from the head alone`() {
        assertTrue(ImageFormat.looksLikeErofs(headWithValueAt1024(ImageFormat.EROFS_MAGIC)))
    }

    @Test
    fun `an ext4 head is not mistaken for EROFS`() {
        // ext4 keeps its 16-bit magic at 1024 + 0x38; byte 1024 is the inode count.
        assertFalse(ImageFormat.looksLikeErofs(headWithValueAt1024(0x00002400L)))
    }

    @Test
    fun `a head too short to hold a superblock is not EROFS`() {
        assertFalse(ImageFormat.looksLikeErofs(ByteArray(1024)))
    }
}
