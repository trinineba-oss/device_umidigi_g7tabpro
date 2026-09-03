package dev.g7tabpro.gsipatch

/**
 * Neutralises the three verified-boot properties a GSI's own `init` fabricates,
 * without replacing the file.
 *
 * Background: [docs/INIT_SWAP_FIX.md]. Some GSI inits carry a hardcoded ~37
 * entry property table -- a Play Integrity spoof -- that runs inside init's
 * property-loading routine on every boot. Three of its entries are decisive on
 * a device whose bootloader publishes no verified-boot state:
 *
 * ```
 * ro.boot.vbmeta.device_state = locked
 * ro.boot.verifiedbootstate   = green
 * ro.boot.vbmeta.digest       = <synthesised>
 * ```
 *
 * The vendor KeyMint **service binary** reads exactly those three to build its
 * root of trust. Handed values the device never had, the TA rejects and never
 * loads; keystore2 then finds no `IKeyMintDevice`, caches an emulated fallback,
 * `generateKey` returns `-64 KEYMINT_NOT_CONFIGURED`, vold cannot create the
 * FBE key, `/data` never mounts, and the GSI hangs at its own splash.
 *
 * Confirmed on hardware 2026-09-01: neutralising these three entries -- **three
 * bytes** -- boots a previously non-booting Infinity-X image.
 *
 * ## Why this is preferable to [InitSwap]
 *
 * The donor swap works, but replaces the ROM's entire init and so discards
 * *all* of its spoofing along with the harmful part. This leaves the other ~34
 * entries running, needs no donor file at all, and cannot suffer a donor/ROM
 * version mismatch.
 *
 * ## How an entry is neutralised
 *
 * Each property name is materialised by an `adrp`/`add` pair. Nudging the `add`
 * immediate forward by 3 makes the pointer skip the `"ro."` prefix, so the
 * entry writes to an inert, unread name (`"boot.verifiedbootstate"`) and the
 * real property is left **absent** -- which is exactly the state a known-good
 * init such as Project CiRCLE's produces.
 *
 * Deliberately *not* "write better values": absent is the configuration proven
 * to boot, and inventing a plausible-looking digest would be a guess.
 *
 * ## Why it is safe to do this without a disassembler
 *
 * A full disassembly is not needed -- only three instruction forms, decoded
 * directly:
 *
 * - `ADRP Rd, page`   -- `0x9F000000 & w == 0x90000000`
 * - `ADD  Rd, Rn, #i` -- `0xFFC00000 & w == 0x91000000` (64-bit, no shift)
 * - `BL   target`     -- `0xFC000000 & w == 0x94000000`
 *
 * The scan walks the executable segment once, tracking the most recent `ADRP`
 * per register, and resolves every `ADD` that consumes one. That is the same
 * `adrp`/`add` cross-reference the PC-side tool performs via llvm-objdump, and
 * it produces byte-identical output on the images tested.
 *
 * ## The safety rule that matters
 *
 * `ro.boot.verifiedbootstate` also appears in *legitimate* AOSP code, in both
 * booting and failing inits, at the same address. Patching by string would
 * break it. So a reference is only ever touched when it lies **inside the spoof
 * table function**, identified structurally as the one function that also
 * materialises the literals `"locked"` and `"green"`. Everything outside is
 * reported and left alone, and an init with no such function is refused --
 * it has no spoof table and needs nothing done to it.
 */
object InitSpoof {

    const val INIT_PATH = InitSwap.INIT_PATH

    /** What the vendor KeyMint service binary reads for its root of trust. */
    val ROOT_OF_TRUST = listOf(
        "ro.boot.vbmeta.device_state",
        "ro.boot.vbmeta.digest",
        "ro.boot.verifiedbootstate"
    )

    /** Literals that mark the spoof table; both must be in the same function. */
    private val FINGERPRINT = listOf("locked", "green")

    /** Bytes skipped so the name loses its `ro.` prefix and becomes inert. */
    private const val PREFIX_SKIP = 3

    class Change(
        val property: String,
        /** Virtual address of the patched `add`. */
        val site: Long,
        val oldImm: Int,
        val newName: String
    ) {
        override fun toString(): String =
            property + " at 0x" + java.lang.Long.toHexString(site) +
                " (#" + oldImm + " -> #" + (oldImm + PREFIX_SKIP) + ") now writes \"" + newName + "\""
    }

    class Result(
        val changes: List<Change>,
        /** Legitimate references deliberately left alone, as virtual addresses. */
        val untouched: List<Long>,
        val bytesChanged: Int
    ) {
        override fun toString(): String = buildString {
            append("neutralised ").append(changes.size)
            append(" verified-boot spoof entr").append(if (changes.size == 1) "y" else "ies")
            append(" (").append(bytesChanged).append(" byte(s) changed)")
            for (c in changes) append("\n    ").append(c)
            if (untouched.isNotEmpty()) {
                append("\n    left alone (legitimate): ")
                append(untouched.joinToString(", ") { "0x" + java.lang.Long.toHexString(it) })
            }
        }
    }

    /** Raised when the image needs no patching, as distinct from a failure. */
    class NotApplicable(message: String) : Exception(message)

    // ---------------------------------------------------------------- ELF

    private class Segment(val offset: Long, val vaddr: Long, val size: Long, val exec: Boolean) {
        fun containsVaddr(v: Long) = v >= vaddr && v < vaddr + size
        fun toOffset(v: Long) = v - vaddr + offset
        fun toVaddr(o: Long) = o - offset + vaddr
        fun containsOffset(o: Long) = o >= offset && o < offset + size
    }

    private fun le16(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, o: Int): Long {
        var v = 0L
        for (i in 3 downTo 0) v = (v shl 8) or (b[o + i].toLong() and 0xFF)
        return v
    }

    private fun le64(b: ByteArray, o: Int): Long {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (b[o + i].toLong() and 0xFF)
        return v
    }

    private fun loadSegments(elf: ByteArray): List<Segment> {
        InitSwap.validate(elf)                       // magic, ELFCLASS64, AArch64
        val phoff = le64(elf, 0x20)
        val phentsize = le16(elf, 0x36)
        val phnum = le16(elf, 0x38)
        require(phoff > 0 && phentsize >= 56 && phnum > 0) {
            "init has no usable program headers -- refusing to patch it"
        }
        val out = ArrayList<Segment>()
        for (i in 0 until phnum) {
            val p = (phoff + i.toLong() * phentsize).toInt()
            if (p + 56 > elf.size) break
            if (le32(elf, p) != 1L) continue         // PT_LOAD
            val flags = le32(elf, p + 4)
            out.add(
                Segment(
                    offset = le64(elf, p + 8),
                    vaddr = le64(elf, p + 16),
                    size = le64(elf, p + 32),        // p_filesz
                    exec = (flags and 1L) != 0L      // PF_X
                )
            )
        }
        require(out.isNotEmpty()) { "init has no PT_LOAD segments -- refusing to patch it" }
        return out
    }

    // -------------------------------------------------------- instructions

    private fun word(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or
            ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24)

    private fun isAdrp(w: Long) = (w and 0x9F000000L) == 0x90000000L

    /** 64-bit `ADD Rd, Rn, #imm12` with no shift -- the form the table uses. */
    private fun isAddImm(w: Long) = (w and 0xFFC00000L) == 0x91000000L

    private fun isBl(w: Long) = (w and 0xFC000000L) == 0x94000000L

    private fun adrpTarget(w: Long, pc: Long): Long {
        val immLo = (w ushr 29) and 0x3
        val immHi = (w ushr 5) and 0x7FFFF
        var imm = (immHi shl 2) or immLo                 // 21 bits
        if ((imm and 0x100000L) != 0L) imm -= 0x200000L  // sign-extend
        return (pc and 0xFFFL.inv()) + (imm shl 12)
    }

    private fun blTarget(w: Long, pc: Long): Long {
        var imm = w and 0x3FFFFFF
        if ((imm and 0x2000000L) != 0L) imm -= 0x4000000L
        return pc + (imm shl 2)
    }

    private fun encodeAddImm(imm: Int, rn: Int, rd: Int): Long {
        require(imm in 0..4095) { "immediate $imm does not fit a single add" }
        return 0x91000000L or (imm.toLong() shl 10) or (rn.toLong() shl 5) or rd.toLong()
    }

    // ------------------------------------------------------------- strings

    /** Virtual addresses of NUL-terminated strings exactly equal to [s]. */
    private fun findString(elf: ByteArray, segs: List<Segment>, s: String): List<Long> {
        val needle = (s + "\u0000").toByteArray(Charsets.US_ASCII)
        val out = ArrayList<Long>()
        var i = 0
        outer@ while (i <= elf.size - needle.size) {
            for (j in needle.indices) {
                if (elf[i + j] != needle[j]) {
                    i++
                    continue@outer
                }
            }
            // A real string starts at a segment boundary or just after a NUL.
            if (i == 0 || elf[i - 1].toInt() == 0) {
                val off = i.toLong()
                segs.firstOrNull { it.containsOffset(off) }?.let { out.add(it.toVaddr(off)) }
            }
            i++
        }
        return out
    }

    private fun stringAt(elf: ByteArray, segs: List<Segment>, vaddr: Long): String {
        val seg = segs.first { it.containsVaddr(vaddr) }
        var o = seg.toOffset(vaddr).toInt()
        val sb = StringBuilder()
        while (o < elf.size && elf[o].toInt() != 0) {
            sb.append((elf[o].toInt() and 0xFF).toChar()); o++
        }
        return sb.toString()
    }

    // -------------------------------------------------------------- scan

    private class Ref(val site: Long, val imm: Int, val rn: Int, val rd: Int)

    private class Scan(
        /** target vaddr -> instructions that materialise it */
        val refs: Map<Long, List<Ref>>,
        /** sorted `bl` targets, used as function entry points */
        val entries: LongArray
    ) {
        fun enclosing(pc: Long): Long? {
            var lo = 0
            var hi = entries.size - 1
            var best: Long? = null
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (entries[mid] <= pc) {
                    best = entries[mid]; lo = mid + 1
                } else hi = mid - 1
            }
            return best
        }
    }

    /**
     * Walks the executable segment once, tracking the most recent `ADRP` per
     * register and resolving every `ADD` that consumes one.
     *
     * Register tracking is deliberately simple -- no control-flow analysis. A
     * compiler emits the pair adjacently or near-adjacently, so this resolves
     * the real pairs; a stale entry can only ever produce an address that is
     * not one of [wanted], which is then ignored.
     */
    private fun scan(elf: ByteArray, segs: List<Segment>, wanted: Set<Long>): Scan {
        val refs = HashMap<Long, MutableList<Ref>>()
        val entries = ArrayList<Long>()
        val adrp = arrayOfNulls<Long>(32)
        for (seg in segs.filter { it.exec }) {
            var o = seg.offset
            val end = minOf(seg.offset + seg.size, elf.size.toLong())
            while (o + 4 <= end) {
                val w = word(elf, o.toInt())
                val pc = seg.toVaddr(o)
                when {
                    isAdrp(w) -> adrp[(w and 0x1F).toInt()] = adrpTarget(w, pc)
                    isBl(w) -> entries.add(blTarget(w, pc))
                    isAddImm(w) -> {
                        val rn = ((w ushr 5) and 0x1F).toInt()
                        val base = adrp[rn]
                        if (base != null) {
                            val addr = base + ((w ushr 10) and 0xFFF)
                            if (addr in wanted) {
                                refs.getOrPut(addr) { ArrayList() }.add(
                                    Ref(pc, ((w ushr 10) and 0xFFF).toInt(), rn, (w and 0x1F).toInt())
                                )
                            }
                        }
                    }
                }
                o += 4
            }
        }
        val arr = entries.toLongArray()
        arr.sort()
        return Scan(refs, arr)
    }

    // ------------------------------------------------------------- inspect

    /**
     * Which of [properties] this init's spoof table still writes.
     *
     * Read-only, and the counterpart to [patch]: on an unpatched init it names
     * what would be neutralised, and on a patched one it must come back empty.
     * Throws [NotApplicable] when there is no spoof table at all.
     *
     * References *outside* the spoof table are legitimate AOSP code and are
     * never counted -- the same rule [patch] applies before touching anything.
     */
    fun inspect(elf: ByteArray, properties: List<String> = ROOT_OF_TRUST): List<String> {
        val segs = loadSegments(elf)
        val fingerprintAddrs = FINGERPRINT.associateWith { findString(elf, segs, it) }
        for ((lit, addrs) in fingerprintAddrs) {
            if (addrs.isEmpty()) throw NotApplicable(
                "this image's init contains no \"$lit\" literal, so it has no verified-boot " +
                    "spoof table"
            )
        }
        val propAddrs = properties.associateWith { findString(elf, segs, it) }
        val wanted = HashSet<Long>()
        fingerprintAddrs.values.forEach { wanted.addAll(it) }
        propAddrs.values.forEach { wanted.addAll(it) }
        val sc = scan(elf, segs, wanted)

        fun functionsFor(addrs: List<Long>): Set<Long> =
            addrs.flatMap { a -> sc.refs[a].orEmpty().mapNotNull { sc.enclosing(it.site) } }.toSet()

        val spoofFns = FINGERPRINT
            .map { functionsFor(fingerprintAddrs.getValue(it)) }
            .reduce { a, b -> a intersect b }
        if (spoofFns.isEmpty()) throw NotApplicable(
            "this image's init has the spoof literals but no single function references both, " +
                "so no spoof table could be located"
        )
        return properties.filter { p ->
            propAddrs.getValue(p).any { a ->
                sc.refs[a].orEmpty().any { sc.enclosing(it.site) in spoofFns }
            }
        }
    }

    /**
     * Re-reads `/system/bin/init` out of [fs] and throws unless its spoof table
     * writes none of [properties].
     *
     * Called after patching, against the bytes actually written. The read-back
     * check inside [apply] proves the bytes landed; this proves they *mean*
     * what was intended -- a distinction this project has paid for more than
     * once by verifying the wrong artefact.
     */
    fun verifyPatched(fs: Ext4, properties: List<String> = ROOT_OF_TRUST) {
        val ino = (try { fs.lookup(INIT_PATH) } catch (e: Exception) { null }) ?: return
        val remaining = try {
            inspect(fs.readFile(ino), properties)
        } catch (e: NotApplicable) {
            return          // no table at all -- nothing could have been missed
        }
        check(remaining.isEmpty()) {
            "verification failed: after patching, init's spoof table still writes " +
                remaining.joinToString(", ")
        }
    }

    // -------------------------------------------------------------- patch

    /**
     * Returns [elf] with the root-of-trust spoof entries neutralised, or throws
     * [NotApplicable] when this init has no spoof table (so it needs nothing).
     *
     * The input array is not modified.
     */
    fun patch(elf: ByteArray, properties: List<String> = ROOT_OF_TRUST): Pair<ByteArray, Result> {
        val segs = loadSegments(elf)

        val fingerprintAddrs = FINGERPRINT.associateWith { findString(elf, segs, it) }
        for ((lit, addrs) in fingerprintAddrs) {
            if (addrs.isEmpty()) throw NotApplicable(
                "this image's init contains no \"$lit\" literal, so it has no verified-boot " +
                    "spoof table and needs no init patch"
            )
        }
        val propAddrs = properties.associateWith { findString(elf, segs, it) }

        val wanted = HashSet<Long>()
        fingerprintAddrs.values.forEach { wanted.addAll(it) }
        propAddrs.values.forEach { wanted.addAll(it) }
        val sc = scan(elf, segs, wanted)

        fun functionsFor(addrs: List<Long>): Set<Long> =
            addrs.flatMap { a -> sc.refs[a].orEmpty().mapNotNull { sc.enclosing(it.site) } }.toSet()

        val spoofFns = FINGERPRINT
            .map { functionsFor(fingerprintAddrs.getValue(it)) }
            .reduce { a, b -> a intersect b }
        if (spoofFns.isEmpty()) throw NotApplicable(
            "this image's init has the spoof literals but no single function references both, " +
                "so the spoof table could not be located -- refusing to guess which reference " +
                "to patch"
        )

        val out = elf.copyOf()
        val changes = ArrayList<Change>()
        val untouched = ArrayList<Long>()

        for (p in properties) {
            val addrs = propAddrs.getValue(p)
            if (addrs.isEmpty()) continue
            val inside = ArrayList<Ref>()
            for (a in addrs) for (r in sc.refs[a].orEmpty()) {
                if (sc.enclosing(r.site) in spoofFns) inside.add(r) else untouched.add(r.site)
            }
            if (inside.isEmpty()) continue
            require(inside.size == 1) {
                "$p is referenced ${inside.size} times inside the spoof table -- refusing to " +
                    "patch blind"
            }
            val ref = inside[0]
            val seg = segs.first { it.containsVaddr(ref.site) }
            val off = seg.toOffset(ref.site).toInt()
            val have = word(out, off)
            val want = encodeAddImm(ref.imm, ref.rn, ref.rd)
            require(have == want) {
                "the instruction at 0x" + java.lang.Long.toHexString(ref.site) +
                    " is not the add that was decoded -- refusing to patch"
            }
            val target = addrs[0] + PREFIX_SKIP
            val newName = stringAt(elf, segs, target)
            require(newName.isNotEmpty()) { "redirect target for $p is not a usable string" }

            val enc = encodeAddImm(ref.imm + PREFIX_SKIP, ref.rn, ref.rd)
            for (i in 0 until 4) out[off + i] = ((enc ushr (8 * i)) and 0xFF).toByte()
            changes.add(Change(p, ref.site, ref.imm, newName))
        }

        if (changes.isEmpty()) throw NotApplicable(
            "this image's init has a spoof table, but none of the root-of-trust properties are " +
                "in it -- whatever stops this image booting, it is not this"
        )
        require(out.size == elf.size) { "init changed length -- impossible, refusing" }
        val diff = (elf.indices).count { elf[it] != out[it] }
        return Pair(out, Result(changes, untouched.sorted(), diff))
    }

    /**
     * Reads the image's own `/system/bin/init`, neutralises the spoof entries
     * and writes it back in place. Returns null when the image has no init.
     *
     * Length-preserving by construction, so [Ext4.writeFileInPlace] applies with
     * no relocation and no block allocation.
     */
    fun apply(fs: Ext4, properties: List<String> = ROOT_OF_TRUST): Result? {
        val ino = (try {
            fs.lookup(INIT_PATH)
        } catch (e: Exception) {
            null
        }) ?: return null

        val original = fs.readFile(ino)
        val (patched, result) = patch(original, properties)
        fs.writeFileInPlace(ino, patched)

        // Same rule as InitSwap: never trust the write. A dropped byte here
        // produces an image that verifies perfectly and then fails to boot.
        val readBack = fs.readFile(ino)
        require(readBack.size == patched.size && readBack.contentEquals(patched)) {
            "init did not read back as written -- refusing to continue; the image is not safe to use"
        }
        return result
    }
}
