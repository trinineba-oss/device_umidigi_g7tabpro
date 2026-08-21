package dev.g7tabpro.gsipatch.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import dev.g7tabpro.gsipatch.Compression
import dev.g7tabpro.gsipatch.GsiPatcher
import dev.g7tabpro.gsipatch.ImageIo
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
    }

    private var inputUri: Uri? = null
    private var outputUri: Uri? = null
    private var working = false

    private lateinit var inputBtn: Button
    private lateinit var outputBtn: Button
    private lateinit var patchBtn: Button
    private lateinit var releaseField: EditText
    private lateinit var patchField: EditText
    private lateinit var progress: ProgressBar
    private lateinit var log: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
            setText("13")
            minWidth = (64 * resources.displayMetrics.density).toInt()
        }
        row.addView(releaseField)
        row.addView(TextView(this).apply { text = "  patch " })
        patchField = EditText(this).apply { setText("2025-09-05") }
        row.addView(patchField)
        root.addView(row)

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
            setTextColor(Color.DKGRAY)
            setPadding(0, pad, 0, 0)
        }
        root.addView(log)

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        val uri = data.data!!
        when (requestCode) {
            REQ_INPUT -> {
                inputUri = uri
                inputBtn.text = "1. GSI: " + displayName(uri)
                outputBtn.isEnabled = true
                appendLog("input: " + displayName(uri) + "  (" + fmt(sizeOf(uri)) + ")")
            }
            REQ_OUTPUT -> {
                outputUri = uri
                outputBtn.text = "2. Save as: " + displayName(uri)
                patchBtn.isEnabled = true
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

    // ----------------------------------------------------------------- worker

    private fun startPatch() {
        if (working) return
        working = true
        patchBtn.isEnabled = false
        inputBtn.isEnabled = false
        outputBtn.isEnabled = false
        progress.visibility = View.VISIBLE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

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

    private fun runPatch(inUri: Uri, outUri: Uri, release: String, patch: String) {
        appendLog("")
        val total = sizeOf(inUri)
        var written = 0L
        var lastPct = -1

        contentResolver.openInputStream(inUri).use { rawIn ->
            requireNotNull(rawIn) { "cannot open the selected GSI for reading" }
            // Detected from the header, not the filename: a 7z container throws
            // here with a clear message rather than failing later as bad ext4.
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
                    GsiPatcher.Options(release, patch, dropFec = true),
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
                appendLog("DONE. Install with DSU Sideloader, then check:")
                appendLog("  getprop sys.boot_completed   -> 1")
                appendLog("  logcat | grep generateKey    -> -67, not -64")
            }
            readPfd.close()
            writePfd.close()
        }
    }

    // -------------------------------------------------------------------- ui

    private fun resetControls() {
        runOnUiThread {
            working = false
            patchBtn.isEnabled = true
            inputBtn.isEnabled = true
            outputBtn.isEnabled = true
            progress.visibility = View.INVISIBLE
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /** Progress chatter stays on the bar; flooding the log would bury the report. */
    private fun updateProgress(pct: Int) {
        runOnUiThread { progress.progress = pct }
    }

    private fun appendLog(line: String) {
        runOnUiThread { log.append(line + "\n") }
    }
}
