package dev.g7tabpro.gsipatch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SepolicyTest {

    private val gsi = """
        (genfscon proc "/ax_boost" (u object_r proc_ax_boost ((s0) (s0))))
        (genfscon sysfs "/kernel/mm/transparent_hugepage/enabled" (u object_r sysfs_transparent_hugepage ((s0) (s0))))
        (genfscon sysfs "/kernel/mm/transparent_hugepage/defrag" (u object_r sysfs_transparent_hugepage ((s0) (s0))))
        (fs_use_xattr ext4 (u object_r labeledfs ((s0) (s0))))
    """.trimIndent()

    // Bare paths, as this MTK vendor writes them, and one agreeing duplicate.
    private val vendor = """
        (genfscon sysfs /kernel/mm/transparent_hugepage/enabled (u object_r sysfs_thp_enabled ((s0) (s0))))
        (genfscon sysfs /kernel/mm/transparent_hugepage/defrag (u object_r sysfs_transparent_hugepage ((s0) (s0))))
        (fs_use_xattr ext4 (u object_r vendor_labeledfs ((s0) (s0))))
        (allow ccci_mdinit nvram_device (blk_file (read open)))
    """.trimIndent()

    @Test
    fun `quoted and bare paths parse to the same selector`() {
        val g = Sepolicy.parse(gsi, "gsi").first { it.selector.endsWith("/enabled") }
        val v = Sepolicy.parse(vendor, "vendor").first { it.selector.endsWith("/enabled") }
        assertEquals(g.key, v.key)
        assertEquals("sysfs /kernel/mm/transparent_hugepage/enabled", g.selector)
    }

    @Test
    fun `statements that assign no context are ignored`() {
        assertTrue(Sepolicy.parse(vendor, "vendor").none { it.kind == "allow" })
    }

    @Test
    fun `a differing context conflicts and an agreeing one does not`() {
        val c = Sepolicy.conflicts(Sepolicy.parse(gsi, "gsi"), Sepolicy.parse(vendor, "vendor"))
        val selectors = c.map { it.gsi.selector }.toSet()
        assertTrue("sysfs /kernel/mm/transparent_hugepage/enabled" in selectors)
        assertFalse("sysfs /kernel/mm/transparent_hugepage/defrag" in selectors)
    }

    @Test
    fun `only genfscon conflicts are auto-fixable`() {
        val c = Sepolicy.conflicts(Sepolicy.parse(gsi, "gsi"), Sepolicy.parse(vendor, "vendor"))
        assertTrue(c.first { it.gsi.kind == "genfscon" }.autoFixable)
        assertFalse(c.first { it.gsi.kind == "fs_use_xattr" }.autoFixable)
    }

    @Test
    fun `neutralise comments the rule out without changing the length`() {
        val bytes = gsi.toByteArray(Charsets.ISO_8859_1)
        val (out, skipped) = Sepolicy.neutralise(bytes, setOf(2))
        assertEquals(bytes.size, out.size)
        assertTrue(skipped.isEmpty())
        val before = gsi.split('\n')
        val after = String(out, Charsets.ISO_8859_1).split('\n')
        assertTrue(after[1].startsWith(";genfscon"))
        assertEquals(before[0], after[0])
        assertEquals(before[2], after[2])
    }

    @Test
    fun `a rule sharing its line with another is refused`() {
        val shared = "(genfscon proc \"/a\" (u object_r a ((s0) (s0)))) " +
            "(genfscon proc \"/b\" (u object_r b ((s0) (s0))))"
        val (out, skipped) = Sepolicy.neutralise(shared.toByteArray(Charsets.ISO_8859_1), setOf(1))
        assertEquals(shared, String(out, Charsets.ISO_8859_1))
        assertEquals(1, skipped.size)
    }

    @Test
    fun `a rule continuing onto the next line is refused`() {
        val split = "(genfscon proc \"/a\"\n  (u object_r a ((s0) (s0))))"
        val (_, skipped) = Sepolicy.neutralise(split.toByteArray(Charsets.ISO_8859_1), setOf(1))
        assertEquals(1, skipped.size)
    }
}
