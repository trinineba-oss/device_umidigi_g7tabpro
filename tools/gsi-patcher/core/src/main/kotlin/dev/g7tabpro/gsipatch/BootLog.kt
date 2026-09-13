package dev.g7tabpro.gsipatch

import java.io.InputStream

/**
 * Reads why a GSI failed to boot out of a MediaTek `expdb` dump.
 *
 * ## Why expdb, and not pstore
 *
 * When a boot crashes, init's last words are gone from logcat by the time the
 * device is back on its own system. The usual place to look afterwards is
 * pstore, `/sys/fs/pstore/console-ramoops-0`. On the G7 Tab Pro that record is
 * a trap: it re-serves one old crash forever, byte-identical down to the
 * microsecond timestamps, even after being deleted. expdb carries its own
 * `SYS_PSTORE_RAW` region, which is the likely reason -- the bootloader keeps
 * restoring that copy. A large part of the AxionOS investigation was spent
 * reading a verity table that belonged to no image on the machine.
 *
 * expdb itself is honest. MediaTek's exception handling writes the kernel log
 * into it whenever a boot crashes, and one dump held thirty such records.
 *
 * ## Why records are looked up by root digest
 *
 * The records are not stored in chronological order, so "the latest failure"
 * is not a question a scan can answer. It does not need to. Every failed GSI
 * boot logs the dm-verity table it built for `/system`, that table carries the
 * image's root digest, and the patcher already reports that digest. Keying on
 * it ties a crash to one specific image with no guessing about order.
 *
 * It also makes an absent digest informative. expdb only records crashes, so an
 * image with no record either booted, or died before dm-verity was set up.
 *
 * ## What is trusted, and what is not
 *
 * The dump interleaves several ring buffers, so raw lines arrive with
 * fragments of other text spliced onto their ends, and digits in particular get
 * extended: a real capture shows `system_ext_sepolicy.cil:29` as `:290000`. So
 * this never quotes a raw line back. It matches known phrases, reports those,
 * and names the policy *files* in a conflict but not their line numbers. The
 * patcher's own policy check reads the files themselves for the exact rules.
 */
object BootLog {

    /** Where MediaTek devices expose the partition. Absent elsewhere. */
    const val EXPDB_PATH = "/dev/block/by-name/expdb"

    enum class Cause(val headline: String, val advice: String) {
        SELINUX_GENFSCON(
            "SELinux policy did not compile: genfscon rules conflict with the vendor",
            "The GSI labels a sysfs or proc path that this vendor's policy already labels " +
                "differently. secilc refuses the combined policy, and init reboots in second " +
                "stage, before any boot animation. Re-patch with the root option enabled: the " +
                "patcher reads this device's policy and comments out exactly the conflicting rules."
        ),
        SELINUX_MAPPING(
            "SELinux policy did not compile: the version mapping for this vendor is missing",
            "The GSI does not ship the sepolicy mapping for the version this vendor was built " +
                "against. That cannot be patched in. Use an older GSI that still ships it."
        ),
        SELINUX_POLICY(
            "SELinux policy did not compile",
            "secilc rejected the combined policy for a reason other than a genfscon conflict. " +
                "The evidence is what it reported."
        ),
        SYSTEM_MOUNT(
            "/system never mounted",
            "dm-verity produced a filesystem init could not read. That usually means the " +
                "installed image does not match its own hashtree, from a truncated download or " +
                "an interrupted install. Re-download, re-patch and install again."
        ),
        INIT_FATAL(
            "init aborted",
            "init hit a fatal error. The evidence is what it logged before rebooting."
        ),
        KERNEL_PANIC(
            "the kernel panicked",
            "The kernel panicked during boot. The panic message is below."
        ),
        UNKNOWN(
            "a crash was recorded, but its cause is not recognised",
            "A crash dump mentions this image, but none of the known failure signatures " +
                "appear near it."
        )
    }

    class Attempt(
        val rootDigest: String,
        val dataBlocks: Long,
        val records: Int,
        val cause: Cause,
        val reachedSecondStage: Boolean,
        /** Pairs of (GSI-side file, vendor-side file). Line numbers deliberately omitted. */
        val conflictingFiles: List<Pair<String, String>>,
        val panic: String?,
        val evidence: List<String>
    ) {
        override fun toString(): String = buildString {
            appendLine(cause.headline)
            appendLine(
                "  image root digest " + rootDigest.take(16) + "..., seen in " + records +
                    " crash record" + (if (records == 1) "" else "s")
            )
            appendLine(
                "  " + if (reachedSecondStage)
                    "reached second stage: /system mounted and init switched root"
                else "never reached second stage: it died before init switched root to /system"
            )
            if (evidence.isNotEmpty()) {
                appendLine("  evidence:")
                evidence.forEach { appendLine("    - " + it) }
            }
            if (conflictingFiles.isNotEmpty()) {
                appendLine("  conflicting policy files:")
                conflictingFiles.forEach { appendLine("    - " + it.first + " against " + it.second) }
            }
            panic?.let { appendLine("  kernel panic: " + it) }
            append("  what to do: " + cause.advice)
        }
    }

    private const val CHUNK = 4 shl 20
    /** How far past a verity table its boot's evidence is looked for. */
    private const val WINDOW = 600_000
    /** How far past a table the mount target that identifies it is looked for. */
    private const val TARGET_LOOKAHEAD = 6_000
    /** Kept past an end marker, so the panic message itself is included. */
    private const val END_TAIL = 200

    private val TABLE = Regex(
        """Built verity table: '1 /dev/block/dm-\d+ /dev/block/dm-\d+ \d+ \d+ (\d+) \d+ sha256 ([0-9a-f]{64}) """
    )
    private val TARGET = Regex("""target=(/[a-z_]+)""")
    private val CONFLICT = Regex(
        """Found conflicting genfscon rules.{0,400}?at (/[a-z_]+/etc/selinux/[a-z_]+\.cil):\d+.{0,300}?at (/[a-z_]+/etc/selinux/[a-z_]+\.cil):\d+""",
        RegexOption.DOT_MATCHES_ALL
    )
    private val PANIC = Regex("""Kernel panic - not syncing: ([ -~]{0,80})""")
    /** Best effort: the exact wording of a missing-mapping failure is not yet captured. */
    private val MISSING_MAPPING = Regex(
        """(?:No such file|not found|[Ff]ailed)[ -~]{0,80}mapping/[0-9.]+\.cil|mapping/[0-9.]+\.cil[ -~]{0,40}(?:No such file|not found)"""
    )
    private const val SECOND_STAGE = "Switching root to '/system'"
    private val END_MARKERS = listOf(
        "Kernel panic - not syncing", "InitFatalReboot", "Reboot ending, jumping to kernel"
    )

    private class Signal(val phrase: String, val evidence: String)

    private val SIGNALS = listOf(
        Signal("Found conflicting genfscon rules", "secilc: found conflicting genfscon rules"),
        Signal("Failed to compile cildb", "secilc: failed to compile the policy"),
        Signal("Unable to open SELinux policy", "init: unable to open the SELinux policy"),
        Signal("Invalid ext4 superblock", "libfs_mgr: invalid ext4 superblock on the verity device"),
        Signal("Failed to mount /system", "init: failed to mount /system"),
        Signal("Failed to mount required partitions early", "init: failed to mount required partitions"),
        Signal("InitFatalReboot", "init: fatal reboot")
    )

    private class Acc(val digest: String, val dataBlocks: Long) {
        var records = 0
        var secondStage = false
        var missingMapping = false
        var panic: String? = null
        val seen = LinkedHashSet<String>()
        val conflicts = LinkedHashSet<Pair<String, String>>()
    }

    /**
     * Every GSI boot crash recorded in [input], keyed by the image's root digest.
     *
     * Streams in chunks, so a 128 MB partition never has to be held in memory.
     * [onProgress] receives the bytes read so far.
     */
    fun scan(input: InputStream, onProgress: (Long) -> Unit = {}): Map<String, Attempt> {
        val acc = LinkedHashMap<String, Acc>()
        val buf = ByteArray(CHUNK)
        var carry = ""
        var total = 0L
        while (true) {
            val n = readFully(input, buf)
            val eof = n < buf.size
            total += n
            val text = carry + String(buf, 0, n, Charsets.ISO_8859_1)
            // A table needs WINDOW characters after it to be judged. One closer
            // to the end than that waits for the next chunk, rather than being
            // read with its evidence cut off -- and a table split across the
            // boundary simply does not match until it is whole.
            val limit = if (eof) text.length else maxOf(0, text.length - WINDOW)
            for (m in TABLE.findAll(text)) {
                if (m.range.first >= limit) break
                record(acc, text, m)
            }
            onProgress(total)
            if (eof) break
            carry = text.substring(limit)
        }
        return acc.mapValues { toAttempt(it.value) }
    }

    /**
     * A readable answer for the image with [digest], followed by a short list of
     * any other images that crashed. A null digest lists everything.
     */
    fun report(attempts: Map<String, Attempt>, digest: String?): String = buildString {
        val wanted = digest?.lowercase()
        if (wanted != null) appendLine(attempts[wanted]?.toString() ?: missing(wanted))
        val others = attempts.values.filter { it.rootDigest != wanted }
        if (others.isNotEmpty()) {
            appendLine()
            appendLine(
                if (wanted == null) "GSI boot crashes recorded in expdb:"
                else "other images with crashes recorded in expdb:"
            )
            others.forEach {
                appendLine(
                    "  " + it.rootDigest.take(16) + "...  " + it.cause.headline +
                        " (" + it.records + "x)"
                )
            }
        } else if (wanted == null) {
            appendLine("expdb holds no GSI boot crashes.")
        }
    }.trimEnd()

    fun missing(digest: String): String =
        "No crash record in expdb mentions this image (root digest " + digest.take(16) + "...). " +
            "expdb only records boots that crash, so it either booted, or it died before " +
            "dm-verity was set up for /system -- first stage, where an instant reboot looks " +
            "the same from the outside."

    private fun record(acc: MutableMap<String, Acc>, text: String, m: MatchResult) {
        val start = m.range.first
        val ahead = text.substring(start, minOf(text.length, start + TARGET_LOOKAHEAD))
        val target = TARGET.find(ahead)?.groupValues?.get(1)
            ?: if (ahead.contains("Failed to mount /system")) "/system" else null
        // Vendor, odm and dlkm partitions log tables too. Only /system is the GSI.
        if (target != "/system") return

        var window = text.substring(start, minOf(text.length, start + WINDOW))
        val end = END_MARKERS.mapNotNull { e -> window.indexOf(e).takeIf { it >= 0 } }.minOrNull()
        if (end != null) window = window.substring(0, minOf(window.length, end + END_TAIL))

        val digest = m.groupValues[2]
        val a = acc.getOrPut(digest) { Acc(digest, m.groupValues[1].toLong()) }
        a.records++
        for (s in SIGNALS) if (window.contains(s.phrase)) a.seen.add(s.phrase)
        if (window.contains(SECOND_STAGE)) a.secondStage = true
        for (c in CONFLICT.findAll(window)) a.conflicts.add(gsiFirst(c.groupValues[1], c.groupValues[2]))
        if (a.panic == null) {
            PANIC.find(window)?.let { p ->
                a.panic = p.groupValues[1].trim().trimEnd { !it.isLetterOrDigit() }
            }
        }
        if (MISSING_MAPPING.containsMatchIn(window)) a.missingMapping = true
    }

    private fun toAttempt(a: Acc): Attempt {
        val cause = when {
            "Found conflicting genfscon rules" in a.seen -> Cause.SELINUX_GENFSCON
            a.missingMapping -> Cause.SELINUX_MAPPING
            "Unable to open SELinux policy" in a.seen || "Failed to compile cildb" in a.seen ->
                Cause.SELINUX_POLICY
            "Failed to mount /system" in a.seen || "Invalid ext4 superblock" in a.seen ->
                Cause.SYSTEM_MOUNT
            "InitFatalReboot" in a.seen -> Cause.INIT_FATAL
            a.panic != null -> Cause.KERNEL_PANIC
            else -> Cause.UNKNOWN
        }
        return Attempt(
            rootDigest = a.digest,
            dataBlocks = a.dataBlocks,
            records = a.records,
            cause = cause,
            reachedSecondStage = a.secondStage,
            conflictingFiles = a.conflicts.toList(),
            panic = a.panic,
            evidence = SIGNALS.filter { it.phrase in a.seen }.map { it.evidence }
        )
    }

    private fun gsiFirst(a: String, b: String): Pair<String, String> =
        if (isVendorSide(a) && !isVendorSide(b)) b to a else a to b

    private fun isVendorSide(path: String) =
        path.startsWith("/vendor/") || path.startsWith("/odm/")

    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) break
            off += n
        }
        return off
    }
}
