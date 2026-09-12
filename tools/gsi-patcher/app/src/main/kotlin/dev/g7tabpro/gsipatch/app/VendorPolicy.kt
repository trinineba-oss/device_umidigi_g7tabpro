package dev.g7tabpro.gsipatch.app

import dev.g7tabpro.gsipatch.Sepolicy
import java.io.File

/**
 * Reads this device's own SELinux `genfscon` rules, so a GSI's conflicting
 * rules can be found before the image is flashed rather than after it
 * fatal-reboots.
 *
 * ## The permission problem, and why root is tried
 *
 * `/vendor/etc/selinux` is `vendor_configs_file`, and app domains are denied
 * it -- the same wall [DeviceProbe] already documents for the VINTF manifest.
 * There is no public API that returns a path's security context either, so an
 * unprivileged app genuinely cannot see these rules.
 *
 * So: read directly if the labelling on this device happens to permit it, and
 * otherwise ask `su`. Root is optional. Without it [read] returns an empty list
 * and the patcher reports the check as "not performed" rather than claiming the
 * image is clean, because a false all-clear here is exactly the kind of quiet
 * wrong answer that costs a flash cycle.
 */
object VendorPolicy {

    class Result(val rules: List<Sepolicy.Rule>, val note: String)

    fun read(): Result {
        val direct = readAll { f -> if (f.canRead()) f.readText(Charsets.ISO_8859_1) else null }
        if (direct.isNotEmpty()) {
            return Result(direct, "read " + direct.size + " vendor genfscon rules directly")
        }
        val rooted = readAll { f -> suCat(f.path) }
        if (rooted.isNotEmpty()) {
            return Result(rooted, "read " + rooted.size + " vendor genfscon rules via su")
        }
        return Result(
            emptyList(),
            "could not read this device's SELinux policy (/vendor/etc/selinux is denied to " +
                "apps and su is unavailable), so GSI policy conflicts cannot be checked"
        )
    }

    private fun readAll(load: (File) -> String?): List<Sepolicy.Rule> {
        val out = ArrayList<Sepolicy.Rule>()
        for (dir in Sepolicy.VENDOR_POLICY_DIRS) {
            val files = listCil(dir)
            for (f in files) {
                val text = try { load(f) } catch (e: Exception) { null } ?: continue
                out.addAll(Sepolicy.parse(text, "vendor/" + f.name))
            }
        }
        return out
    }

    /**
     * The `.cil` files in [dir].
     *
     * `listFiles` returns null when the directory itself is unreadable, which
     * is the normal case here, so fall back to asking `su` to list it.
     */
    private fun listCil(dir: String): List<File> {
        File(dir).listFiles()?.let { entries ->
            return entries.filter { it.isFile && it.name.endsWith(".cil") }
        }
        val listing = suRun(listOf("ls", "-1", dir)) ?: return emptyList()
        return listing.lineSequence()
            .map { it.trim() }
            .filter { it.endsWith(".cil") }
            .map { File(dir, it) }
            .toList()
    }

    private fun suCat(path: String): String? = suRun(listOf("cat", path))

    /**
     * Runs one command as root, or returns null if that is not possible.
     *
     * Deliberately quiet: an absent `su` throws IOException, a denied one exits
     * non-zero, and neither is an error worth surfacing -- it just means the
     * check cannot be done on this device.
     */
    private fun suRun(cmd: List<String>): String? = try {
        val proc = ProcessBuilder(listOf("su", "-c", cmd.joinToString(" ")))
            .redirectErrorStream(false)
            .start()
        val text = proc.inputStream.bufferedReader(Charsets.ISO_8859_1).use { it.readText() }
        if (proc.waitFor() == 0 && text.isNotEmpty()) text else null
    } catch (e: Exception) {
        null
    } catch (e: Error) {
        null
    }
}
