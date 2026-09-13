package dev.g7tabpro.gsipatch

/**
 * Removes `genfscon` rules from a GSI's SELinux policy that conflict with the
 * device's own vendor policy.
 *
 * ## Why a GSI reboots with no boot animation
 *
 * A `genfscon` rule assigns a security context to a path inside a pseudo
 * filesystem (`sysfs`, `proc`, ...). CIL permits exactly one context per
 * (filesystem, path). When the GSI's policy and the vendor's policy both name
 * the same path with **different** types, `secilc` refuses to compile the
 * combined policy at all:
 *
 * ```
 * secilc: Found conflicting genfscon rules
 *   at /system_ext/etc/selinux/system_ext_sepolicy.cil:29
 *   at /vendor/etc/selinux/vendor_sepolicy.cil:557
 * secilc: Failed to compile cildb: -1
 * init: Unable to open SELinux policy
 * init: InitFatalReboot: signal 6
 * ```
 *
 * This happens in **second stage**, after `/system` has mounted and init has
 * switched root, so the device reboots without ever painting a boot animation.
 * It looks identical to a first-stage mount failure from the outside, which is
 * how it went undiagnosed on the G7 Tab Pro for weeks: AxionOS 2.8 labels the
 * three transparent-hugepage knobs under `/sys/kernel/mm/transparent_hugepage/`
 * as `sysfs_transparent_hugepage`, while this MediaTek vendor policy calls them
 * `sysfs_thp_enabled`, `sysfs_thp_defrag` and `sysfs_thp_khp_defrag`. Three
 * lines out of 377 GSI rules, and nothing else in the image mattered.
 *
 * ## Why the GSI's rule is the one that loses
 *
 * The vendor policy is on a partition we are not patching, and it describes
 * hardware this GSI knows nothing about. Dropping the GSI's rule lets the
 * vendor's label stand, which is the correct label for that device. Anything in
 * the GSI that wanted the generic type gets an SELinux denial on those paths --
 * a log line, not a boot failure. Dropping the vendor's rule instead would mean
 * relabelling the device's own sysfs, which is both out of scope and worse.
 *
 * ## Why commenting rather than deleting
 *
 * Replacing the rule's opening `(` with `;`, CIL's line comment, removes it
 * while leaving the file **exactly the same length**. That keeps the write
 * in-place: no block allocation, no relocation, no touching group descriptors.
 * Deleting the line would shorten the file and drag the whole image through the
 * allocator for no benefit.
 */
object Sepolicy {

    /**
     * One context-assignment statement, located precisely enough to edit it.
     *
     * [kind] is the CIL statement name. [selector] is everything that names
     * *what* is being labelled, normalised: for `genfscon` that is the
     * filesystem and path, for `fs_use_xattr` just the filesystem, for
     * `portcon` the protocol and port range. Two statements collide when their
     * kind and selector match but their [context] differs.
     */
    data class Rule(
        val source: String,
        val line: Int,
        val kind: String,
        val selector: String,
        val context: String
    ) {
        val key: Pair<String, String> get() = kind to selector
        override fun toString(): String = kind + " " + selector
    }

    data class Conflict(val gsi: Rule, val vendor: Rule) {
        /**
         * Only `genfscon` is safe to remove automatically. It labels one path.
         * `fs_use_*` governs how an entire filesystem is labelled, and
         * `portcon`/`nodecon` cover whole ranges -- dropping one of those to
         * make a policy compile trades a diagnosable build failure for an
         * undiagnosable runtime one, so those are reported and left alone.
         */
        val autoFixable: Boolean get() = gsi.kind == "genfscon"

        override fun toString(): String =
            gsi.source + ":" + gsi.line + "  " + gsi.kind + " " + gsi.selector +
                "  (gsi " + gsi.context + " vs vendor " + vendor.context + ")"
    }

    class Result(
        val conflicts: List<Conflict>,
        val neutralised: Int,
        val skipped: List<String>,
        val filesChanged: List<String>
    ) {
        override fun toString(): String = when {
            conflicts.isEmpty() -> "no genfscon conflicts with this vendor policy"
            else -> "neutralised " + neutralised + " conflicting genfscon rule(s) in " +
                filesChanged.joinToString(", ") +
                (if (skipped.isEmpty()) "" else "; left alone: " + skipped.joinToString("; "))
        }
    }

    /**
     * Policy files a GSI can carry, in every spelling seen in the wild.
     *
     * Images symlink `/product` and `/system_ext` to their `/system/...`
     * counterparts, so both forms are listed and a path that resolves to the
     * same inode twice is handled by the caller deduplicating on inode.
     */
    val GSI_POLICY_PATHS: List<String> = listOf(
        "/system/etc/selinux/plat_sepolicy.cil",
        "/system/system_ext/etc/selinux/system_ext_sepolicy.cil",
        "/system/product/etc/selinux/product_sepolicy.cil",
        "/system_ext/etc/selinux/system_ext_sepolicy.cil",
        "/product/etc/selinux/product_sepolicy.cil"
    )

    /** Where a device keeps the policy the GSI has to coexist with. */
    val VENDOR_POLICY_DIRS: List<String> = listOf(
        "/vendor/etc/selinux",
        "/odm/etc/selinux"
    )

    /**
     * The CIL statements that bind a context to something, and that therefore
     * cannot be declared twice with different contexts.
     */
    private val KINDS = setOf(
        "genfscon", "fs_use_xattr", "fs_use_task", "fs_use_trans",
        "portcon", "netifcon", "nodecon"
    )

    // (genfscon sysfs "/some/path" (u object_r some_type ((s0) (s0))))
    // (fs_use_xattr ext4 (u object_r labeledfs ((s0) (s0))))
    // Everything between the statement name and the context s-expression is the
    // selector; paths are quoted in some policies and bare in others.
    private val RULE = Regex(
        """^\s*\(([a-z_]+)\s+([^()]*?)\s*\(\s*\S+\s+\S+\s+([A-Za-z0-9_]+)"""
    )

    /**
     * Every single-line context-assignment statement in [text].
     *
     * Rules split across lines are deliberately not returned. They are rare,
     * and this object's only edit is a single-character line comment, which
     * cannot safely neutralise a rule that continues onto the next line.
     */
    fun parse(text: String, source: String): List<Rule> {
        val out = ArrayList<Rule>()
        text.split('\n').forEachIndexed { i, raw ->
            val m = RULE.find(raw) ?: return@forEachIndexed
            val kind = m.groupValues[1]
            if (kind !in KINDS) return@forEachIndexed
            if (!isSelfContained(raw)) return@forEachIndexed
            val selector = m.groupValues[2].replace("\"", "").trim().replace(Regex("\\s+"), " ")
            if (selector.isEmpty()) return@forEachIndexed
            out.add(Rule(source, i + 1, kind, selector, m.groupValues[3]))
        }
        return out
    }

    /**
     * True when the line holds exactly one complete, balanced s-expression and
     * nothing else of substance.
     *
     * Both halves matter. Unbalanced means the rule continues on the next line,
     * so a `;` would comment out only part of it and leave a syntax error.
     * Trailing content means a second rule shares the line, and a `;` would
     * silently remove that one too.
     */
    private fun isSelfContained(line: String): Boolean {
        var depth = 0
        var closedAt = -1
        var inString = false
        for ((i, c) in line.withIndex()) {
            when {
                c == '"' -> inString = !inString
                inString -> {}
                c == ';' -> return closedAt >= 0 && depth == 0   // trailing comment is fine
                c == '(' -> { if (closedAt >= 0) return false; depth++ }
                c == ')' -> { depth--; if (depth == 0) closedAt = i; if (depth < 0) return false }
            }
        }
        if (depth != 0 || closedAt < 0) return false
        return line.substring(closedAt + 1).isBlank()
    }

    /**
     * GSI rules whose (filesystem, path) the vendor also claims, with a
     * different context.
     *
     * An identical context is not a conflict: CIL tolerates a duplicate that
     * agrees, and rewriting it would be a change with no reason behind it.
     */
    fun conflicts(gsi: List<Rule>, vendor: List<Rule>): List<Conflict> {
        val byKey = HashMap<Pair<String, String>, Rule>()
        for (r in vendor) byKey.putIfAbsent(r.key, r)
        return gsi.mapNotNull { g ->
            val v = byKey[g.key] ?: return@mapNotNull null
            if (v.context == g.context) null else Conflict(g, v)
        }
    }

    /**
     * Comments out the given 1-based [lines], preserving the byte length.
     *
     * Returns the new bytes and a description of anything it refused to touch.
     * Refusing is the safe outcome: a policy file that still has a conflict
     * fails to compile loudly, whereas one this mangles could fail in ways that
     * are much harder to trace.
     */
    fun neutralise(bytes: ByteArray, lines: Set<Int>): Pair<ByteArray, List<String>> {
        val text = String(bytes, Charsets.ISO_8859_1)
        val split = text.split('\n').toMutableList()
        val skipped = ArrayList<String>()
        for (n in lines.sorted()) {
            val idx = n - 1
            if (idx !in split.indices) { skipped.add("line $n is past the end of the file"); continue }
            val line = split[idx]
            val open = line.indexOf('(')
            val head = if (open < 0) "" else line.substring(open)
            if (KINDS.none { head.startsWith("(" + it + " ") }) {
                skipped.add("line $n no longer looks like a context-assignment rule")
                continue
            }
            if (!isSelfContained(line)) {
                skipped.add("line $n is not a single self-contained rule")
                continue
            }
            split[idx] = line.substring(0, open) + ";" + line.substring(open + 1)
        }
        val out = split.joinToString("\n").toByteArray(Charsets.ISO_8859_1)
        check(out.size == bytes.size) {
            "the policy edit changed the file length (" + bytes.size + " -> " + out.size +
                "); refusing, because that would force a relocation this path does not do"
        }
        return out to skipped
    }

    /**
     * Every conflict between the image's policy and [vendorRules], changing
     * nothing. Preflight uses this to report before a patch; [apply] finds the
     * same set and then edits.
     */
    fun inspect(fs: Ext4, vendorRules: List<Rule>): List<Conflict> {
        if (vendorRules.isEmpty()) return emptyList()
        val out = ArrayList<Conflict>()
        val seenInodes = HashSet<Long>()
        for (path in GSI_POLICY_PATHS) {
            val ino = (try { fs.lookup(path) } catch (e: Exception) { null }) ?: continue
            if (!seenInodes.add(ino)) continue
            val text = String(fs.readFile(ino), Charsets.ISO_8859_1)
            out.addAll(conflicts(parse(text, path), vendorRules))
        }
        return out
    }

    /**
     * The sepolicy version mapping a device needs, and whether the image has it.
     *
     * A vendor built against sepolicy version *N* requires the GSI to ship
     * `/system/etc/selinux/mapping/N.cil`, which translates the vendor's
     * versioned types onto the platform's current ones. Without it `secilc`
     * fails and init fatal-reboots in second stage -- the same silent,
     * animation-less reboot a genfscon conflict produces, and just as easy to
     * misread as a mount failure.
     *
     * GSIs drop old mappings as releases age, so this is the way a device with
     * an older vendor stops booting *new* GSIs while still booting old ones.
     * Nothing in this patcher can synthesise a missing mapping, so the only
     * useful thing to do is say so plainly, before the flash.
     *
     * [version] comes from the device's `/vendor/etc/selinux/plat_sepolicy_vers.txt`
     * (e.g. `31.0`). Null means it could not be read, which is reported as
     * unchecked rather than as fine.
     */
    fun mappingProblem(fs: Ext4, version: String?): String? {
        if (version.isNullOrBlank()) return null
        val v = version.trim()
        val path = "/system/etc/selinux/mapping/" + v + ".cil"
        val present = (try { fs.lookup(path) } catch (e: Exception) { null }) != null
        if (present) return null
        return "this device's vendor is built against sepolicy version " + v +
            ", but the image does not ship " + path + ". The policy cannot compile " +
            "on this device and init will fatal-reboot in second stage, with no boot " +
            "animation. This image is not usable here; try an older GSI."
    }

    /**
     * Finds and neutralises every conflicting `genfscon` rule in the image.
     *
     * [vendorRules] comes from the target device; with none supplied there is
     * nothing to compare against and this returns an empty result rather than
     * guessing, because guessing here means deleting policy that was correct.
     */
    fun apply(fs: Ext4, vendorRules: List<Rule>): Result {
        if (vendorRules.isEmpty()) return Result(emptyList(), 0, emptyList(), emptyList())

        val allConflicts = ArrayList<Conflict>()
        val skipped = ArrayList<String>()
        val changed = ArrayList<String>()
        val seenInodes = HashSet<Long>()

        for (path in GSI_POLICY_PATHS) {
            val ino = (try { fs.lookup(path) } catch (e: Exception) { null }) ?: continue
            if (!seenInodes.add(ino)) continue          // symlinked alias of one already done
            val bytes = fs.readFile(ino)
            val rules = parse(String(bytes, Charsets.ISO_8859_1), path)
            val found = conflicts(rules, vendorRules)
            if (found.isEmpty()) continue

            val (fixable, manual) = found.partition { it.autoFixable }
            manual.forEach {
                skipped.add(
                    it.toString() + " -- a " + it.gsi.kind + " rule covers more than one path, " +
                        "so removing it automatically would trade a build failure for a " +
                        "runtime one. Resolve this by hand."
                )
            }
            if (fixable.isEmpty()) { allConflicts.addAll(found); continue }

            val (patched, refused) = neutralise(bytes, fixable.map { it.gsi.line }.toSet())
            fs.writeFileInPlace(ino, patched)

            val readBack = fs.readFile(ino)
            require(readBack.size == patched.size && readBack.contentEquals(patched)) {
                path + " did not read back as written -- refusing to continue"
            }
            allConflicts.addAll(found)
            skipped.addAll(refused)
            changed.add(path)
        }
        return Result(allConflicts, allConflicts.size - skipped.size, skipped, changed)
    }
}
