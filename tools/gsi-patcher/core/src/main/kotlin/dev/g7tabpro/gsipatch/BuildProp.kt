package dev.g7tabpro.gsipatch

/**
 * Length-preserving rewrite of the three version properties.
 *
 * The whole design depends on this staying byte-neutral: every real
 * substitution is digit-for-digit ("15" -> "13") or date-for-date
 * ("2025-12-01" -> "2025-09-05"), so the file never changes size and the
 * surrounding filesystem never has to be resized, unshared or remounted.
 *
 * Note the properties legitimately appear more than once -- a LineageOS 22.2
 * build.prop carries ro.build.version.release at line 39 and again at line 187
 * in a second block. Every occurrence is rewritten, matching the shell script's
 * sed behaviour.
 */
object BuildProp {

    val KEYS_RELEASE = listOf(
        "ro.build.version.release",
        "ro.build.version.release_or_codename"
    )
    const val KEY_PATCH = "ro.build.version.security_patch"

    /**
     * The same property exists in partition-scoped forms -- ro.system.*,
     * ro.product.*, ro.system_ext.* -- and they matter, because ro. properties
     * are write-once and whichever file init loads first wins. An Android 16
     * GSI carries `ro.build.version.release` in BOTH /system/build.prop and
     * /system/product/etc/build.prop; patching only the former leaves the
     * runtime reporting the unpatched value and KeyMint still returns
     * KEYMINT_NOT_CONFIGURED.
     *
     * Matching on the suffix catches every scoped variant, including
     * `ro.system.build.version.release` which an exactly-anchored pattern
     * misses even inside the file it is already editing.
     */
    private fun isReleaseKey(key: String) =
        key.endsWith(".build.version.release") ||
            key.endsWith(".build.version.release_or_codename")

    private fun isPatchKey(key: String) =
        key.endsWith(".build.version.security_patch")

    class Result(
        val bytes: ByteArray,
        val changes: List<String>,
        val replacements: Int,
        /** adb-related keys present in this file, whatever their value. */
        val adbKeysPresent: Set<String> = emptySet()
    )

    /**
     * @param targetRelease e.g. "13"
     * @param targetPatch   e.g. "2025-09-05"
     */
    /**
     * Properties that decide whether you get adb if the image hangs before
     * /data mounts. Both edits are single characters, so the file length is
     * untouched -- which is what lets this happen in place like everything else.
     */
    private const val KEY_ADB_SECURE = "ro.adb.secure"
    private const val KEY_DEBUGGABLE = "ro.debuggable"

    fun patch(
        original: ByteArray,
        targetRelease: String,
        targetPatch: String,
        enableAdb: Boolean = false
    ): Result {
        val text = String(original, Charsets.UTF_8)
        // Keep the exact line terminators: split on '\n' and rejoin the same way.
        val lines = text.split("\n").toMutableList()
        val changes = ArrayList<String>()
        val adbKeysPresent = LinkedHashSet<String>()
        var n = 0

        for (idx in lines.indices) {
            val line = lines[idx]
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq)
            val value = line.substring(eq + 1)
            if (key == KEY_ADB_SECURE || key == KEY_DEBUGGABLE) adbKeysPresent.add(key)
            val newValue = when {
                isReleaseKey(key) -> targetRelease
                isPatchKey(key) -> targetPatch
                // adbd authorises against /data/misc/adb/adb_keys, and /data is
                // exactly what does not mount when this goes wrong. Turning the
                // check off is the difference between a diagnosable failure and
                // a black box.
                enableAdb && key == KEY_ADB_SECURE -> "0"
                enableAdb && key == KEY_DEBUGGABLE -> "1"
                else -> null
            } ?: continue
            if (value == newValue) continue
            lines[idx] = "$key=$newValue"
            changes.add("line ${idx + 1}: $key=$value -> $newValue")
            n++
        }

        var out = lines.joinToString("\n").toByteArray(Charsets.UTF_8)

        // Absorb any size delta so the write stays in place. In practice the
        // delta is always zero; these are belt-and-braces for odd builds (a
        // codename in release_or_codename, say).
        if (out.size < original.size) {
            // Trailing newlines are ignored by the property parser.
            out = out.copyOf(original.size).also {
                java.util.Arrays.fill(it, out.size, original.size, '\n'.code.toByte())
            }
        } else if (out.size > original.size) {
            val trimmed = shrinkComments(lines, out.size - original.size)
                ?: throw IllegalStateException(
                    "patched build.prop is ${out.size - original.size} bytes longer than the " +
                        "original and there is no comment padding to reclaim; in-place patching " +
                        "is not possible for this image"
                )
            out = trimmed.toByteArray(Charsets.UTF_8)
            check(out.size == original.size) { "comment reclaim produced the wrong size" }
        }
        return Result(out, changes, n, adbKeysPresent)
    }

    /** Reclaim [delta] bytes by shortening '#' comment lines. */
    private fun shrinkComments(lines: List<String>, delta: Int): String? {
        val copy = lines.toMutableList()
        var need = delta
        for (i in copy.indices) {
            if (need == 0) break
            val l = copy[i]
            if (!l.startsWith("#") || l.length <= 1) continue
            val canTake = minOf(need, l.length - 1)
            copy[i] = l.substring(0, l.length - canTake)
            need -= canTake
        }
        return if (need == 0) copy.joinToString("\n") else null
    }
}
