package dev.g7tabpro.gsipatch.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import dev.g7tabpro.gsipatch.Compatibility
import dev.g7tabpro.gsipatch.Compression
import dev.g7tabpro.gsipatch.GsiPatcher
import dev.g7tabpro.gsipatch.ImageIo
import dev.g7tabpro.gsipatch.InitSwap
import dev.g7tabpro.gsipatch.Ingest
import dev.g7tabpro.gsipatch.DeviceFacts
import dev.g7tabpro.gsipatch.Preflight
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale

/**
 * Pick a GSI, pick where to write it, patch. No root, no shell, no PC.
 *
 * The image is copied to the destination first and then patched in place, so
 * only one extra copy of the image is ever on disk.
 */
class MainActivity : Activity() {

    private companion object {
        const val REQ_INPUT = 1
        const val REQ_OUTPUT = 2
        const val REQ_DONOR = 3
    }

    private var inputUri: Uri? = null
    private var outputUri: Uri? = null
    private var donorUri: Uri? = null
    private var working = false

    private lateinit var inputBtn: Button
    private lateinit var outputBtn: Button
    private lateinit var patchBtn: Button
    private lateinit var checkBtn: Button
    private lateinit var adbBox: CheckBox
    private lateinit var fixInitBox: CheckBox
    private lateinit var shareBtn: Button
    private lateinit var donorBtn: Button
    private lateinit var device: DeviceFacts
    private lateinit var releaseField: EditText
    private lateinit var patchField: EditText
    private lateinit var progress: ProgressBar
    private lateinit var log: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        device = DeviceProbe.read()

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "Rewrites a GSI's reported Android version so a TrustKernel " +
                "TEE provisioned under an older release will still configure KeyMint, " +
                "then rebuilds and re-signs the dm-verity hashtree."
            setPadding(0, 0, 0, pad)
        })

        inputBtn = Button(this).apply {
            text = "1. Choose GSI (.img, .img.gz or .img.xz)"
            setOnClickListener { pickInput() }
        }
        root.addView(inputBtn)

        outputBtn = Button(this).apply {
            text = "2. Choose where to save"
            isEnabled = false
            setOnClickListener { pickOutput() }
        }
        root.addView(outputBtn)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(TextView(this).apply { text = "Report release " })
        releaseField = EditText(this).apply {
            // ro.keymaster.*.release is the version the bootloader handed the
            // TEE, which is exactly what KeyMint compares against. Preferring it
            // over Build.VERSION.RELEASE also stays correct when the app is run
            // from inside a booted GSI.
            setText(Compatibility.recommendedTargetRelease(device) ?: "13")
            minWidth = (64 * resources.displayMetrics.density).toInt()
        }
        row.addView(releaseField)
        row.addView(TextView(this).apply { text = "  patch " })
        patchField = EditText(this).apply { setText("2025-09-05") }
        row.addView(patchField)
        root.addView(row)

        checkBtn = Button(this).apply {
            text = "Check an image (no changes)"
            isEnabled = false
            setOnClickListener { startCheck() }
        }
        root.addView(checkBtn)

        adbBox = CheckBox(this).apply {
            text = "Keep adb usable if it fails to boot (turns off adb auth)"
            isChecked = false
        }
        root.addView(adbBox)

        // Default ON. It is a no-op on images whose init has no spoof table
        // (reported, not an error), and on images that do it is the fix that
        // was confirmed on hardware -- three bytes, keeping the ROM's own
        // Play Integrity spoofing. See docs/INIT_SWAP_FIX.md.
        fixInitBox = CheckBox(this).apply {
            text = "Fix init verified-boot spoofing (recommended)"
            isChecked = true
        }
        root.addView(fixInitBox)

        // The older, blunter form of the same fix, kept for the case where the
        // three-byte patch does not apply: replaces init wholesale, which also
        // discards the ROM's remaining spoofing. Outside the numbered flow
        // because most images never need it.
        donorBtn = Button(this).apply {
            text = "Optional: replace init (donor GSI or init file)"
            setOnClickListener { pickDonor() }
        }
        root.addView(donorBtn)

        patchBtn = Button(this).apply {
            text = "3. Patch"
            isEnabled = false
            setOnClickListener { startPatch() }
        }
        root.addView(patchBtn)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.INVISIBLE
        }
        root.addView(progress, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        log = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            // Resolved from the theme, not hardcoded: this is the only colour
            // the UI sets itself, and a fixed dark grey is unreadable against
            // the dark theme's background. Every other widget is stock and
            // follows the platform theme on its own.
            setTextColor(themeColor(android.R.attr.textColorSecondary, Color.DKGRAY))
            setPadding(0, pad, 0, 0)
        }
        root.addView(log)

        // The log holds the evidence that matters -- which spoof entries were
        // patched at which addresses, the old and new root digests, what
        // verification concluded. Until now it was trapped in a TextView.
        shareBtn = Button(this).apply {
            text = "Share log"
            setOnClickListener { shareLog() }
        }
        root.addView(shareBtn)

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)

        appendLog("device: TEE expects Android " + (device.teeRelease ?: "unknown") +
            ", vendor API " + (device.vendorApiLevel?.toString() ?: "?") +
            ", KeyMint " + (device.keymintAidlVersion?.let { "V" + it } ?: "version unreadable"))
    }

    /**
     * A colour from the active theme, so the UI follows light/dark without the
     * app deciding which is in force. [fallback] covers a theme that does not
     * define the attribute at all.
     */
    private fun themeColor(attr: Int, fallback: Int): Int {
        val tv = android.util.TypedValue()
        if (!theme.resolveAttribute(attr, tv, true)) return fallback
        return if (tv.resourceId != 0) resources.getColor(tv.resourceId, theme) else tv.data
    }

    // ---------------------------------------------------------------- pickers

    private fun pickInput() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(i, REQ_INPUT)
    }

    private fun pickOutput() {
        val base = displayName(inputUri!!)
            .removeSuffix(".gz")
            .removeSuffix(".xz")
            .removeSuffix(".img")
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, base + "-osver" + releaseField.text.toString() + ".img")
        }
        startActivityForResult(i, REQ_OUTPUT)
    }

    private fun pickDonor() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(i, REQ_DONOR)
    }

    /**
     * Accepts either a bare `init` binary or a whole (raw, uncompressed) GSI to
     * take one out of, deciding by magic bytes rather than asking the user which
     * they picked. A GSI's own init is exactly what a donor is, so requiring
     * them to extract it first would be busywork -- and the maintainers who
     * hand these out distribute both shapes.
     */
    private fun resolveDonor(uri: Uri): ByteArray {
        val head = contentResolver.openInputStream(uri)?.use { readHeadBytes(it, 8) }
            ?: throw IllegalStateException("cannot read the donor file")
        val isElf = head.size >= 4 && head[0].toInt() == 0x7F &&
            head[1].toInt() == 'E'.code && head[2].toInt() == 'L'.code && head[3].toInt() == 'F'.code
        if (isElf) {
            return contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalStateException("cannot read the donor file")
        }
        // Not an ELF, so treat it as an image and go looking inside. Needs
        // random access, which a compressed source cannot give without being
        // decompressed first -- say so plainly rather than failing deep in ext4.
        val pfd = contentResolver.openFileDescriptor(uri, "r")
            ?: throw IllegalStateException(
                "that donor is not an init binary, and this location cannot be read directly " +
                    "to search it as an image"
            )
        return pfd.use {
            ImageIo(FileInputStream(it.fileDescriptor).channel).use { io -> InitSwap.extractFrom(io) }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        val uri = data.data!!
        when (requestCode) {
            REQ_INPUT -> {
                inputUri = uri
                inputBtn.text = "1. GSI: " + displayName(uri)
                outputBtn.isEnabled = true
                checkBtn.isEnabled = true
                appendLog("input: " + displayName(uri) + "  (" + fmt(sizeOf(uri)) + ")")
            }
            REQ_OUTPUT -> {
                outputUri = uri
                outputBtn.text = "2. Save as: " + displayName(uri)
                patchBtn.isEnabled = true
            }
            REQ_DONOR -> {
                // Resolve and validate now, not at patch time: extracting from a
                // multi-gigabyte donor can take a moment, and a wrong file
                // should be rejected while the user is still looking at the
                // picker rather than halfway through a patch.
                donorBtn.isEnabled = false
        fixInitBox.isEnabled = false
                Thread {
                    try {
                        val donor = resolveDonor(uri)
                        InitSwap.validate(donor)
                        donorUri = uri
                        runOnUiThread {
                            donorBtn.text = "Optional: init from " + displayName(uri)
                            donorBtn.isEnabled = true
            fixInitBox.isEnabled = true
                        }
                        appendLog(
                            "donor init: " + donor.size + " bytes from " + displayName(uri)
                        )
                    } catch (t: Throwable) {
                        donorUri = null
                        runOnUiThread {
                            donorBtn.text = "Optional: replace init (donor GSI or init file)"
                            donorBtn.isEnabled = true
                        }
                        appendLog("donor rejected: " + (t.message ?: t.toString()))
                    }
                }.start()
            }
        }
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) return c.getString(idx)
        }
        return uri.lastPathSegment ?: "image.img"
    }

    private fun sizeOf(uri: Uri): Long {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.SIZE)
            if (idx >= 0 && c.moveToFirst() && !c.isNull(idx)) return c.getLong(idx)
        }
        return -1
    }

    private fun fmt(bytes: Long): String =
        if (bytes < 0) "unknown size"
        else String.format(Locale.US, "%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)

    /** Reads up to [n] bytes without requiring the stream to support mark/reset. */
    private fun readHeadBytes(stream: java.io.InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var total = 0
        while (total < n) {
            val r = stream.read(buf, total, n - total)
            if (r < 0) break
            total += r
        }
        return if (total < n) buf.copyOf(total) else buf
    }

    // ----------------------------------------------------------------- worker

    private fun startPatch() {
        if (working) return
        working = true
        patchBtn.isEnabled = false
        checkBtn.isEnabled = false
        inputBtn.isEnabled = false
        outputBtn.isEnabled = false
        donorBtn.isEnabled = false
        progress.visibility = View.VISIBLE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Foreground priority for the whole run: KEEP_SCREEN_ON only protects
        // this while the activity is visible, and a process killed midway
        // leaves a truncated image that still looks like a complete file.
        PatchService.start(this, "Patching " + (inputUri?.let { displayName(it) } ?: "image"))

        val release = releaseField.text.toString().trim()
        val patch = patchField.text.toString().trim()
        if (!Regex("^[0-9]{1,3}$").matches(release)) {
            appendLog("Release must be a plain version number such as 13 -- got \"" + release + "\"")
            resetControls()
            return
        }
        if (!Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$").matches(patch)) {
            appendLog("Security patch must look like 2025-09-05 -- got \"" + patch + "\"")
            resetControls()
            return
        }
        val inUri = inputUri!!
        val outUri = outputUri!!

        Thread {
            try {
                runPatch(inUri, outUri, release, patch)
            } catch (t: Throwable) {
                appendLog("")
                appendLog("FAILED: " + (t.message ?: t.toString()))
                appendLog("The output file is incomplete and must not be flashed.")
            } finally {
                resetControls()
            }
        }.start()
    }

    /** Preflight an image without touching it. Answers "will this boot?" for free. */
    private fun startCheck() {
        if (working) return
        val inUri = inputUri ?: return
        val release = releaseField.text.toString().trim()
        val patch = patchField.text.toString().trim()
        working = true
        checkBtn.isEnabled = false
        patchBtn.isEnabled = false
        progress.visibility = View.VISIBLE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        PatchService.start(this, "Checking " + displayName(inUri))

        Thread {
            try {
                // Preflight needs random access, so a compressed file -- or a
                // container needing extraction first -- cannot be checked
                // where it sits. Say so rather than failing later with a
                // misleading "no AVB footer" (Compression's own detection
                // doesn't know about zip/7z/payload.bin at all, so without
                // this a container would misdetect as RAW and fail confusingly).
                val head = contentResolver.openInputStream(inUri)?.use { readHeadBytes(it, 8) } ?: ByteArray(0)
                if (Ingest.looksLikeContainer(head)) {
                    throw IllegalArgumentException(
                        "this is a zip/7z/payload.bin container, not a plain image; checking " +
                            "reads the image directly, so patch it first and the finished image " +
                            "is checked automatically"
                    )
                }
                val kind = contentResolver.openInputStream(inUri).use { raw ->
                    requireNotNull(raw) { "cannot open the selected image" }
                    Compression.open(raw).kind
                }
                if (kind != Compression.Kind.RAW) {
                    throw IllegalArgumentException(
                        "this file is " + kind.label + "-compressed; checking reads the image " +
                            "directly, so select an uncompressed .img (or patch it and the " +
                            "finished image is checked automatically)"
                    )
                }
                appendLog("")
                appendLog("== pre-flight on " + displayName(inUri))
                val pfd = contentResolver.openFileDescriptor(inUri, "r")
                    ?: throw IllegalStateException("cannot read that location directly")
                pfd.use {
                    val ch = FileInputStream(it.fileDescriptor).channel
                    ImageIo(ch).use { io ->
                        appendLog(
                            Preflight.check(
                                io, release, patch,
                                { d, t -> updateProgress(if (t == 0L) 100 else ((d * 100) / t).toInt()) },
                                device, displayName(inUri)
                            ).toString()
                        )
                    }
                }
            } catch (t: Throwable) {
                appendLog("FAILED: " + (t.message ?: t.toString()))
            } finally {
                resetControls()
            }
        }.start()
    }

    /**
     * Refuses the run if the destination cannot hold the finished image.
     *
     * The output is a SAF document with no file path, so `StatFs` cannot be
     * used -- `fstatvfs` on the open descriptor reports the filesystem the
     * document actually lives on.
     *
     * [compressedSize] is what was selected; a `.gz`/`.xz` GSI expands to
     * several times that, so [expandFactor] is applied unless the input is
     * already raw. Erring high is deliberate: the cost of a false warning is
     * one dismissed message, the cost of running out is several minutes spent
     * producing a truncated image.
     */
    private fun checkFreeSpace(outUri: Uri, compressedSize: Long, expandFactor: Int) {
        if (compressedSize <= 0) return          // unknown size -- cannot judge
        val needed = compressedSize * expandFactor
        val free = try {
            contentResolver.openFileDescriptor(outUri, "r")?.use { pfd ->
                val st = android.system.Os.fstatvfs(pfd.fileDescriptor)
                st.f_bavail * st.f_frsize
            } ?: return
        } catch (e: Exception) {
            return                               // cannot measure -- do not block
        }
        if (free in 1 until needed) {
            throw IllegalStateException(
                "not enough free space where the output is being written: about " +
                    (needed / (1L shl 20)) + " MB needed, " + (free / (1L shl 20)) +
                    " MB available. Patching writes a full copy of the image, and " +
                    "running out midway leaves a truncated file that still looks " +
                    "complete -- so this stops now rather than after several minutes."
            )
        }
    }

    private fun runPatch(inUri: Uri, outUri: Uri, release: String, patch: String) {
        appendLog("")
        var total = sizeOf(inUri)
        // Checked before any work: a compressed GSI expands roughly 2.5-3x,
        // and the finished image is written in full.
        val rawInput = displayName(inUri).endsWith(".img")
        checkFreeSpace(outUri, total, if (rawInput) 1 else 3)
        var written = 0L
        var lastPct = -1

        // An OTA zip, a bare payload.bin, or a 7z all need reducing to a
        // plain system image before anything below (which only ever
        // understood raw/gz/xz) can run. Peek a few bytes rather than always
        // copying first: most inputs are not containers, and a multi-gigabyte
        // GSI is expensive to copy twice on a phone's limited storage for
        // nothing.
        val head = contentResolver.openInputStream(inUri)?.use { readHeadBytes(it, 8) } ?: ByteArray(0)
        var containerTemp: java.io.File? = null
        var unwrapped: java.io.File? = null
        if (Ingest.looksLikeContainer(head)) {
            appendLog("== extracting from container (" + displayName(inUri) + ")")
            val tmp = java.io.File(cacheDir, "ingest-input.tmp")
            contentResolver.openInputStream(inUri).use { rawIn ->
                requireNotNull(rawIn) { "cannot open the selected file for reading" }
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 20)
                    var copied = 0L
                    while (true) {
                        val n = rawIn.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        copied += n
                        if (total > 0) updateProgress(((copied * 100) / total).toInt().coerceAtMost(100))
                    }
                }
            }
            containerTemp = tmp
            unwrapped = Ingest.unwrap(tmp, cacheDir, "system") { done, tot ->
                updateProgress(if (tot == 0L) 100 else ((done * 100) / tot).toInt())
            }
            appendLog("   extracted: " + unwrapped.name)
            total = unwrapped.length()
        }

        try {
            val open: () -> java.io.InputStream = unwrapped?.let { f -> ({ f.inputStream() }) }
                ?: { contentResolver.openInputStream(inUri) ?: throw IllegalStateException("cannot open the selected GSI for reading") }

            open().use { rawIn ->
                // Detected from the header, not the filename: a 7z container
                // (one this app didn't already unwrap above -- e.g. one that
                // doesn't actually contain a GSI) throws here with a clear
                // message rather than failing later as bad ext4.
                val src = Compression.open(rawIn)
                appendLog(
                    if (src.kind == Compression.Kind.RAW) "== copying to destination"
                    else "== decompressing (" + src.kind.label + ") to destination"
                )
                contentResolver.openOutputStream(outUri, "wt").use { out ->
                    requireNotNull(out) { "cannot open the destination for writing" }
                    val buf = ByteArray(1 shl 20)
                    while (true) {
                        val n = src.stream.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        written += n
                        // Progress tracks the compressed side, which is the only
                        // total known up front, so it works for gzip and xz too.
                        if (total > 0) {
                            val pct = ((src.compressedBytesRead * 100) / total)
                                .toInt().coerceAtMost(100)
                            if (pct != lastPct) {
                                lastPct = pct
                                updateProgress(pct)
                            }
                        }
                    }
                    out.flush()
                }
            }
        } finally {
            // These can be multi-gigabyte -- an unwrapped system image is
            // comparable in size to the source GSI -- so clean them up
            // whether the extraction above succeeded or the patch below
            // failed, rather than silently eating the phone's storage.
            containerTemp?.delete()
            if (unwrapped != null && unwrapped != containerTemp) unwrapped.delete()
        }
        appendLog("   wrote " + fmt(written))

        appendLog("")
        appendLog("== patching in place")
        val key = resources.openRawResource(R.raw.testkey_rsa2048).use { it.readBytes() }

        val pfd = contentResolver.openFileDescriptor(outUri, "rw")
            ?: throw IllegalStateException(
                "the destination does not support random access; choose a location on " +
                    "internal storage or the SD card rather than a cloud provider"
            )
        pfd.use {
            // Each stream gets its own dup'd descriptor. Wrapping the same fd
            // twice meant ImageIo.close() closed it once, then again, and
            // pfd.close() a third time -- and a double close can take out an
            // unrelated file if the descriptor number has been reused.
            val readPfd = ParcelFileDescriptor.dup(it.fileDescriptor)
            val writePfd = ParcelFileDescriptor.dup(it.fileDescriptor)
            val readCh = FileInputStream(readPfd.fileDescriptor).channel
            val writeCh = FileOutputStream(writePfd.fileDescriptor).channel
            ImageIo(readCh, writeCh).use { io ->
                val report = GsiPatcher.patch(
                    io,
                    GsiPatcher.Options(
                    release, patch, dropFec = true, enableAdb = adbBox.isChecked,
                    donorInit = donorUri?.let { resolveDonor(it) },
                    // A donor replaces the whole init, so patching it first
                    // would be overwritten -- let the explicit choice win.
                    fixInitSpoof = fixInitBox.isChecked && donorUri == null
                ),
                    key,
                    object : GsiPatcher.Progress {
                        override fun stage(message: String) {
                            appendLog("   " + message)
                        }

                        override fun hashing(done: Long, total: Long) {
                            val pct = if (total == 0L) 100 else ((done * 100) / total).toInt()
                            updateProgress(pct)
                        }
                    }
                )
                appendLog("")
                appendLog(report.toString())
                appendLog("")
                appendLog("")
                appendLog("== pre-flight on the finished image")
                appendLog(
                    Preflight.check(
                        io, release, patch,
                        { d, t -> updateProgress(if (t == 0L) 100 else ((d * 100) / t).toInt()) },
                        device, displayName(outUri)
                    ).toString()
                )
                appendLog("")
                appendLog("Install with DSU Sideloader, then check:")
                appendLog("  getprop sys.boot_completed   -> 1")
                appendLog("  logcat | grep generateKey    -> -67, not -64")
            }
            readPfd.close()
            writePfd.close()
        }
    }

    // -------------------------------------------------------------------- ui

    private fun resetControls() {
        PatchService.stop(this)
        runOnUiThread {
            working = false
            patchBtn.isEnabled = outputUri != null
            checkBtn.isEnabled = inputUri != null
            inputBtn.isEnabled = true
            outputBtn.isEnabled = true
            donorBtn.isEnabled = true
            progress.visibility = View.INVISIBLE
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /**
     * Hands the log to any app that takes text. Deliberately ACTION_SEND with
     * EXTRA_TEXT rather than a file: these logs are tens of kilobytes, and a
     * plain share sheet reaches chat, mail and notes without needing a
     * FileProvider and its manifest surface.
     */
    private fun shareLog() {
        val text = log.text.toString()
        if (text.isBlank()) {
            appendLog("nothing to share yet")
            return
        }
        val header = "GsiKeyMintPatcher " + appVersion() + " on " +
            android.os.Build.MODEL + " (" + android.os.Build.DEVICE + ")\n\n"
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "GSI patch log")
            putExtra(Intent.EXTRA_TEXT, header + text)
        }
        startActivity(Intent.createChooser(i, "Share log"))
    }

    private fun appVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }

    /** Progress chatter stays on the bar; flooding the log would bury the report. */
    private fun updateProgress(pct: Int) {
        runOnUiThread { progress.progress = pct }
    }

    private fun appendLog(line: String) {
        runOnUiThread { log.append(line + "\n") }
    }
}
