package dev.g7tabpro.gsipatch

/**
 * Read-only checks on a finished image, answering "is this actually going to
 * work?" before it costs a flash cycle and a reboot.
 *
 * The useful one is [scanForStaleVersions]. Patching deliberately never scans
 * the raw image -- the same byte string occurs outside build.prop and blindly
 * rewriting it would corrupt unrelated data, which is why [Ext4] resolves an
 * inode and stays inside its extents. But *reading* the whole image is safe,
 * and it is the only check that can catch a version property living somewhere
 * nobody has thought of yet.
 *
 * That is not hypothetical. Android 16 GSIs put the generic
 * `ro.build.version.release` in /system/product/etc/build.prop as well as
 * /system/build.prop. Patching only the latter produced an image that looked
 * correct in every file the patcher knew about, verified its own hashtree, and
 * then hung on the device reporting the unpatched version -- because ro.
 * properties are write-once and the product partition won. A whole-image scan
 * would have shown the leftover immediately.
 *
 * So this is a net cast wider than the patcher's own knowledge: if a future
 * Android release adds a seventh location, the scan still finds it even though
 * BUILD_PROP_PATHS does not list it.
 */
object Preflight {

    class Finding(val severity: Severity, val message: String) {
        override fun toString() = severity.tag + " " + message
    }

    enum class Severity(val tag: String) {
        BLOCKER("[BLOCKER]"),   // will not boot; do not flash
        WARNING("[warn]   "),   // may boot, but you should know
        INFO("[info]   ")
    }

    class Result(val findings: List<Finding>) {
        val blockers get() = findings.count { it.severity == Severity.BLOCKER }
        val willLikelyBoot get() = blockers == 0
        override fun toString() = buildString {
            findings.forEach { appendLine(it.toString()) }
            append(
                if (willLikelyBoot) "No blockers found."
                else "$blockers blocker(s) -- this image is not expected to boot."
            )
        }
    }

    private const val CHUNK = 4 shl 20          // 4 MiB
    private const val KEY_RELEASE = ".build.version.release="
    private const val KEY_ADB = "ro.adb.secure="

    /**
     * Streams the whole image looking for version properties that still carry a
     * value other than [targetRelease].
     *
     * Chunks overlap by the length of the longest pattern plus its value, so a
     * match straddling a chunk boundary is not missed.
     */
    fun scanForStaleVersions(
        io: ImageIo,
        imageSize: Long,
        targetRelease: String,
        progress: (Long, Long) -> Unit = { _, _ -> }
    ): List<String> {
        val stale = LinkedHashSet<String>()
        val overlap = 64
        var pos = 0L
        var carry = ByteArray(0)
        while (pos < imageSize) {
            val len = minOf(CHUNK.toLong(), imageSize - pos).toInt()
            val buf = io.read(pos, len)
            val text = String(carry + buf, Charsets.ISO_8859_1)
            var i = text.indexOf(KEY_RELEASE)
            while (i >= 0) {
                // read the key back to the start of the line, and the value forward
                var s = i
                while (s > 0 && text[s - 1] != '\n' && text[s - 1] != '\u0000') s--
                var e = i + KEY_RELEASE.length
                while (e < text.length && text[e] != '\n' && text[e] != '\u0000' && text[e] != '\r') e++
                if (e < text.length) {          // only judge a complete line
                    val key = text.substring(s, i + KEY_RELEASE.length - 1)
                    val value = text.substring(i + KEY_RELEASE.length, e)
                    // release_or_codename shares the prefix; both must match the target
                    if (value.isNotEmpty() && value != targetRelease &&
                        key.all { it.isLetterOrDigit() || it == '.' || it == '_' }
                    ) {
                        stale.add("$key=$value")
                    }
                }
                i = text.indexOf(KEY_RELEASE, i + 1)
            }
            carry = if (buf.size > overlap) buf.copyOfRange(buf.size - overlap, buf.size) else buf
            pos += len
            progress(pos, imageSize)
        }
        return stale.toList()
    }

    /**
     * Full pre-flight over an already-patched image.
     *
     * [expectedRelease] and [expectedPatch] are what the patch aimed for;
     * anything still disagreeing is reported.
     */
    fun check(
        io: ImageIo,
        expectedRelease: String,
        expectedPatch: String,
        progress: (Long, Long) -> Unit = { _, _ -> }
    ): Result {
        val findings = ArrayList<Finding>()

        ImageFormat.requireRaw(io)
        val avb = Avb(io)
        val fs = Ext4(io)

        // --- what is this image, in its own words -------------------------
        var sdk: String? = null
        var adbSecure: String? = null
        var debuggable: String? = null
        for (p in listOf("/system/build.prop", "/build.prop")) {
            val ino = (try { fs.lookup(p) } catch (e: Exception) { null }) ?: continue
            val text = String(fs.readFile(ino), Charsets.UTF_8)
            for (line in text.lineSequence()) {
                val eq = line.indexOf('='); if (eq <= 0) continue
                val k = line.substring(0, eq); val v = line.substring(eq + 1)
                when (k) {
                    "ro.build.version.sdk" -> if (sdk == null) sdk = v
                    "ro.adb.secure" -> if (adbSecure == null) adbSecure = v
                    "ro.debuggable" -> if (debuggable == null) debuggable = v
                }
            }
            break
        }
        if (sdk != null) {
            findings.add(Finding(Severity.INFO, "image API level: $sdk (framework behaviour unchanged by the patch)"))
        }

        // --- the authoritative check: the prop files init actually reads ---
        //
        // Only a value inside a real build.prop can affect the runtime. Judge
        // these strictly.
        var propFilesSeen = 0
        for (p in GsiPatcher.buildPropPaths()) {
            // see the note in GsiPatcher: an alternate spelling can resolve to a
            // symlink that Ext4 cannot walk; the canonical one is also listed.
            val ino = (try { fs.lookup(p) } catch (e: Exception) { null }) ?: continue
            propFilesSeen++
            val text = String(fs.readFile(ino), Charsets.UTF_8)
            for (line in text.lineSequence()) {
                val eq = line.indexOf('='); if (eq <= 0) continue
                val k = line.substring(0, eq); val v = line.substring(eq + 1)
                if ((k.endsWith(".build.version.release") ||
                        k.endsWith(".build.version.release_or_codename")) && v != expectedRelease
                ) {
                    findings.add(
                        Finding(
                            Severity.BLOCKER,
                            "$p still has $k=$v (expected $expectedRelease). ro. properties are " +
                                "write-once, so whichever file init reads first wins -- this will " +
                                "report the wrong version to KeyMint and /data will fail to mount."
                        )
                    )
                }
                if (k.endsWith(".build.version.security_patch") && v != expectedPatch) {
                    findings.add(Finding(Severity.WARNING, "$p has $k=$v (expected $expectedPatch)"))
                }
            }
        }
        findings.add(Finding(Severity.INFO, "checked $propFilesSeen build.prop file(s)"))

        // --- the wider net: anything the list above does not know about ----
        //
        // Reported as a WARNING, never a blocker. The same byte string occurs
        // legitimately elsewhere in a GSI -- inside an APK, a config blob, or
        // stale bytes in free space -- where it has no effect on runtime
        // properties. A known-good Android 16 image that boots on hardware
        // carries two such inert copies, so treating a raw hit as fatal would
        // condemn a perfectly working image.
        //
        // It still earns its place: if a future Android release moves the
        // properties into a file this tool does not know to patch, the prop
        // files above will all look clean while the device reports the
        // unpatched version. This is the only signal that would show it.
        val stale = scanForStaleVersions(io, avb.imageSize, expectedRelease, progress)
        if (stale.isEmpty()) {
            findings.add(Finding(Severity.INFO, "no leftover version strings anywhere in the image"))
        } else {
            findings.add(
                Finding(
                    Severity.WARNING,
                    "found " + stale.size + " version string(s) elsewhere in the image (" +
                        stale.joinToString(", ") + "). These are usually inert -- embedded in an " +
                        "APK or left in free space -- and a known-good image carries some. But if " +
                        "this image fails to boot reporting the wrong version, check whether one " +
                        "of them lives in a prop file this tool does not yet patch."
                )
            )
        }

        // --- will you be able to see anything if it hangs? -----------------
        if (adbSecure == "1" && debuggable != "1") {
            findings.add(
                Finding(
                    Severity.WARNING,
                    "ro.adb.secure=1 on a non-debuggable build: if this image hangs before " +
                        "/data mounts, adbd cannot authorise and you will get no logs. " +
                        "Patching ro.adb.secure=0 is length-preserving and makes a failure " +
                        "diagnosable."
                )
            )
        }

        // --- does it verify the way the bootloader will? --------------------
        try {
            val (root, _) = HashTree.generate(io, avb.imageSize, avb.dataBlockSize, avb.salt)
            if (root.contentEquals(avb.rootDigest)) {
                findings.add(Finding(Severity.INFO, "dm-verity hashtree matches the vbmeta root digest"))
            } else {
                findings.add(
                    Finding(
                        Severity.BLOCKER,
                        "hashtree does not match the stored root digest -- dm-verity will refuse " +
                            "to mount this image"
                    )
                )
            }
        } catch (e: Exception) {
            findings.add(Finding(Severity.BLOCKER, "could not verify the hashtree: " + e.message))
        }

        return Result(findings)
    }
}
