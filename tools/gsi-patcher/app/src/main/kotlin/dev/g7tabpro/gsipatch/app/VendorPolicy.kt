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

    class Result(
        val rules: List<Sepolicy.Rule>,
        val note: String,
        /**
         * The sepolicy version this vendor is built against, from
         * `/vendor/etc/selinux/plat_sepolicy_vers.txt`. The image must ship the
         * matching `mapping/<version>.cil` or its policy cannot compile here.
         */
        val sepolicyVersion: String? = null
    )

    fun read(useRoot: Boolean): Result {
        val version = readVersion(useRoot)
        val direct = readAll(useRoot) { f ->
            if (f.canRead()) f.readText(Charsets.ISO_8859_1) else null
        }
        if (direct.isNotEmpty()) {
            return Result(direct, "read " + direct.size + " vendor genfscon rules directly", version)
        }
        if (useRoot && Root.available()) {
            val rooted = readAll(true) { f -> Root.run(listOf("cat", f.path)) }
            if (rooted.isNotEmpty()) {
                return Result(
                    rooted, "read " + rooted.size + " vendor genfscon rules using root", version
                )
            }
        }
        return Result(
            emptyList(),
            if (useRoot)
                "could not read this device's SELinux policy even with root, so GSI policy " +
                    "conflicts cannot be checked"
            else
                "this device's SELinux policy is not readable without root " +
                    "(/vendor/etc/selinux is denied to apps), so GSI policy conflicts were " +
                    "NOT checked -- enable the root option to check them",
            version
        )
    }

    /**
     * The vendor's sepolicy version, or null if it cannot be read.
     *
     * Small enough to be worth a separate read: it decides whether the image
     * can compile a policy here at all, independently of any rule conflict.
     */
    private fun readVersion(useRoot: Boolean): String? {
        val f = File("/vendor/etc/selinux/plat_sepolicy_vers.txt")
        val text = (try { if (f.canRead()) f.readText() else null } catch (e: Exception) { null })
            ?: if (useRoot && Root.available()) Root.run(listOf("cat", f.path)) else null
        return text?.trim()?.ifBlank { null }
    }

    private fun readAll(useRoot: Boolean, load: (File) -> String?): List<Sepolicy.Rule> {
        val out = ArrayList<Sepolicy.Rule>()
        for (dir in Sepolicy.VENDOR_POLICY_DIRS) {
            val files = listCil(dir, useRoot)
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
    private fun listCil(dir: String, useRoot: Boolean): List<File> {
        File(dir).listFiles()?.let { entries ->
            return entries.filter { it.isFile && it.name.endsWith(".cil") }
        }
        if (!useRoot) return emptyList()
        val listing = Root.run(listOf("ls", "-1", dir)) ?: return emptyList()
        return listing.lineSequence()
            .map { it.trim() }
            .filter { it.endsWith(".cil") }
            .map { File(dir, it) }
            .toList()
    }

}
