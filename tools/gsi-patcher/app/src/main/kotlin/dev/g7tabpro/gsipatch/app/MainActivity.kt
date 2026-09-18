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
import android.view.Gravity
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
import dev.g7tabpro.gsipatch.Avb
import dev.g7tabpro.gsipatch.BootLog
import dev.g7tabpro.gsipatch.Compatibility
import dev.g7tabpro.gsipatch.Compression
import dev.g7tabpro.gsipatch.GsiPatcher
import dev.g7tabpro.gsipatch.ImageFormat
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
        /** Installing an image this session did not patch -- see [startDsuInstall]. */
        const val REQ_DSU_IMAGE = 4
        const val PREFS = "gsipatch"
        const val PREF_DIGEST = "last_digest"
        const val PREF_NAME = "last_name"
        const val PREF_URI = "last_uri"
        /** Only for the progress bar: the partition is 128 MiB on the G7 Tab Pro. */
        const val EXPDB_BYTES = 128L shl 20
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
    private lateinit var rootBox: CheckBox
    private lateinit var shareBtn: Button
    private lateinit var clearBtn: Button
    private lateinit var donorBtn: Button
    private lateinit var dsuBtn: Button
    private lateinit var bootLogBtn: Button
    /**
     * Where the last successful patch was written, for the DSU install.
     *
     * Persisted, not just held in memory: patching a multi-gigabyte image and
     * installing it are separate sittings, and the app being closed in between
     * is the normal case rather than the exception.
     */
    private var lastOutputUri: Uri? = null
    /** Cached so the button label costs no resolver query on the UI thread. */
    private var lastOutputName: String? = null
    private lateinit var device: DeviceFacts
    private lateinit var releaseField: EditText
    private lateinit var patchField: EditText
    private lateinit var progress: ProgressBar
    private lateinit var progressLabel: TextView
    private lateinit var progressBox: LinearLayout
    private lateinit var statusLine: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var log: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        device = DeviceProbe.read(Root.knownAvailable())

        val pad = dp(16)

        // Three bands, not one long scroll. The whole screen used to be a
        // single ScrollView, so a few hundred lines of log pushed every button
        // off the bottom -- and the buttons that matter most, the DSU install
        // and the crash reader, are exactly the ones you reach for *after* a
        // wall of output. Controls scroll in their own band, the progress bar
        // is pinned where it stays visible, and the log keeps its own space.
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, dp(8))
            // Takes first focus itself so the release EditText does not, which
            // otherwise opened the keyboard over the controls on every launch.
            isFocusableInTouchMode = true
            descendantFocusability = android.view.ViewGroup.FOCUS_BEFORE_DESCENDANTS
        }

        column.addView(TextView(this).apply {
            text = "Rewrites a GSI's reported Android version so a TrustKernel " +
                "TEE provisioned under an older release will still configure KeyMint, " +
                "then rebuilds and re-signs the dm-verity hashtree."
            setPadding(0, 0, 0, dp(12))
        })

        // What the TEE expects, kept on screen rather than scrolled away in
        // the log: the release typed into the field below is a guess unless
        // the number this device actually asks for is visible next to it.
        statusLine = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(themeColor(android.R.attr.textColorSecondary, Color.GRAY))
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setBackgroundColor(tint(0x14))
        }
        column.addView(statusLine)
        refreshStatusLine()

        column.addView(sectionHeader("Patch"))

        inputBtn = button("1. Choose GSI (.img, .img.gz or .img.xz)") { pickInput() }
        column.addView(inputBtn)

        outputBtn = button("2. Choose where to save") { pickOutput() }
        outputBtn.isEnabled = false
        column.addView(outputBtn)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        row.addView(TextView(this).apply { text = "Report release " })
        releaseField = EditText(this).apply {
            // ro.keymaster.*.release is the version the bootloader handed the
            // TEE, which is exactly what KeyMint compares against. Preferring it
            // over Build.VERSION.RELEASE also stays correct when the app is run
            // from inside a booted GSI.
            setText(Compatibility.recommendedTargetRelease(device) ?: "13")
            minWidth = dp(64)
        }
        row.addView(releaseField)
        row.addView(TextView(this).apply { text = "  patch " })
        patchField = EditText(this).apply { setText("2025-09-05") }
        row.addView(patchField)
        column.addView(row)

        checkBtn = button("Check an image (no changes)") { startCheck() }
        checkBtn.isEnabled = false
        column.addView(checkBtn)

        patchBtn = button("3. Patch") { startPatch() }
        patchBtn.isEnabled = false
        column.addView(patchBtn)

        column.addView(sectionHeader("Options"))

        adbBox = CheckBox(this).apply {
            text = "Keep adb usable if it fails to boot (turns off adb auth)"
            isChecked = false
        }
        column.addView(adbBox)

        // Default ON. It is a no-op on images whose init has no spoof table
        // (reported, not an error), and on images that do it is the fix that
        // was confirmed on hardware -- three bytes, keeping the ROM's own
        // Play Integrity spoofing. See docs/INIT_SWAP_FIX.md.
        fixInitBox = CheckBox(this).apply {
            text = "Fix init verified-boot spoofing (recommended)"
            isChecked = true
        }
        column.addView(fixInitBox)

        // Off by default: nothing here requires root, and an unexpected
        // superuser prompt is worse than a check that politely says it could
        // not run. Ticking it probes immediately -- see Root -- so the prompt
        // appears now rather than minutes into a multi-gigabyte write.
        rootBox = CheckBox(this).apply {
            text = "Use root to check SELinux policy conflicts"
            isChecked = false
            setOnCheckedChangeListener { _, checked ->
                if (!checked) return@setOnCheckedChangeListener
                isEnabled = false
                text = "Checking for root..."
                Thread {
                    val ok = Root.available()
                    val rules = if (ok) VendorPolicy.read(true) else null
                    runOnUiThread {
                        isEnabled = true
                        if (ok) {
                            text = "Use root to check SELinux policy conflicts"
                            appendLog("   root: " + (rules?.note ?: "granted"))
                        } else {
                            isChecked = false
                            text = "Use root to check SELinux policy conflicts"
                            appendLog("   root: not available -- policy conflicts cannot be checked")
                        }
                        refreshStatusLine()
                    }
                }.start()
            }
        }
        column.addView(rootBox)

        // The older, blunter form of the same fix, kept for the case where the
        // three-byte patch does not apply: replaces init wholesale, which also
        // discards the ROM's remaining spoofing. Outside the numbered flow
        // because most images never need it.
        donorBtn = button("Optional: replace init (donor GSI or init file)") {
            if (donorUri == null) pickDonor() else offerDonorChange()
        }
        column.addView(donorBtn)

        column.addView(sectionHeader("Install and diagnose"))
        column.addView(hint(
            "Both need root. Without it, install the patched image with DSU Sideloader instead."
        ))

        // Root only. Closes the loop the rest of the app cannot: install what
        // was just patched and boot it, with no DSU Sideloader and no PC.
        dsuBtn = button("4. Install with DSU and boot it (root)") { startDsuInstall() }
        column.addView(dsuBtn)

        // Root only. Reads why a boot crashed out of the MediaTek expdb
        // partition, looked up by the last patched image's root digest.
        bootLogBtn = button("Why did it fail to boot? (root)") { startBootLog() }
        column.addView(bootLogBtn)

        val controls = ScrollView(this).apply { isFillViewport = true }
        controls.addView(column)

        // Pinned between the two scrolling bands: during a patch this is the
        // only thing on screen that changes, so it must not be somewhere the
        // user can scroll away from.
        progressLabel = TextView(this).apply {
            textSize = 12f
            setTextColor(themeColor(android.R.attr.textColorSecondary, Color.GRAY))
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
        }
        progressBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(6), pad, dp(6))
            visibility = View.GONE
            addView(progressLabel)
            addView(progress, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }

        val logHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, 0, dp(8), 0)
        }
        logHeader.addView(sectionHeader("Log").apply {
            setPadding(0, dp(6), 0, dp(6))
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        })
        clearBtn = flatButton("Clear") { clearLog() }
        logHeader.addView(clearBtn)
        // The log holds the evidence that matters -- which spoof entries were
        // patched at which addresses, the old and new root digests, what
        // verification concluded. Until now it was trapped in a TextView.
        shareBtn = flatButton("Share") { shareLog() }
        logHeader.addView(shareBtn)

        log = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            // Resolved from the theme, not hardcoded: this is the only colour
            // the UI sets itself, and a fixed dark grey is unreadable against
            // the dark theme's background. Every other widget is stock and
            // follows the platform theme on its own.
            setTextColor(themeColor(android.R.attr.textColorSecondary, Color.DKGRAY))
            setPadding(pad, dp(6), pad, pad)
            // Copying one line out of a report beats sharing the whole thing
            // when the destination is a bug tracker.
            setTextIsSelectable(true)
        }
        logScroll = object : ScrollView(this) {
            override fun onMeasure(widthSpec: Int, heightSpec: Int) {
                super.onMeasure(
                    widthSpec,
                    MeasureSpec.makeMeasureSpec(dp(300), MeasureSpec.AT_MOST)
                )
            }
        }.apply {
            setBackgroundColor(tint(0x0C))
            addView(log)
        }

        val screen = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        screen.addView(controls, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        screen.addView(progressBox, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        screen.addView(divider())
        screen.addView(logHeader, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        screen.addView(logScroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        setContentView(screen)

        appendLog("device: TEE expects Android " + (device.teeRelease ?: "unknown") +
            ", vendor API " + (device.vendorApiLevel?.toString() ?: "?") +
            ", KeyMint " + (device.keymintAidlVersion?.let { "V" + it } ?: "version unreadable"))

        restoreLastOutput()
    }

    // ------------------------------------------------------------ ui plumbing

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /**
     * A translucent wash of the foreground colour, for panel backgrounds that
     * have to read correctly in both themes. Deriving it from the text colour
     * rather than naming a grey is what keeps it subtle on a light background
     * and still visible on a dark one.
     */
    private fun tint(alpha: Int): Int =
        (themeColor(android.R.attr.textColorPrimary, Color.GRAY) and 0xFFFFFF) or (alpha shl 24)

    private fun button(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        // These labels are sentences, not verbs. Shouting them made the long
        // ones wrap to three lines.
        setAllCaps(false)
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = dp(2)
            bottomMargin = dp(2)
        }
        setOnClickListener { onClick() }
    }

    private fun flatButton(label: String, onClick: () -> Unit): Button =
        Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = label
            setAllCaps(false)
            textSize = 13f
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener { onClick() }
        }

    private fun sectionHeader(label: String): TextView = TextView(this).apply {
        text = label.uppercase(Locale.US)
        textSize = 12f
        setTypeface(typeface, Typeface.BOLD)
        letterSpacing = 0.08f
        setTextColor(themeColor(android.R.attr.textColorSecondary, Color.GRAY))
        setPadding(0, dp(18), 0, dp(4))
    }

    private fun hint(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(themeColor(android.R.attr.textColorSecondary, Color.GRAY))
        setPadding(0, 0, 0, dp(6))
    }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(tint(0x30))
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(1))
    }

    /** The one-line summary of what the device wants and what the app can see. */
    private fun refreshStatusLine() {
        val root = if (Root.knownAvailable()) "root: yes" else "root: not granted"
        statusLine.text = Build.MODEL + " (" + Build.DEVICE + ")   TEE wants Android " +
            (device.teeRelease ?: "?") + "   vendor API " +
            (device.vendorApiLevel?.toString() ?: "?") + "   KeyMint " +
            (device.keymintAidlVersion?.let { "V" + it } ?: "?") + "   " + root
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
            // Without this the grant dies with the process, and the DSU button
            // is dead the next time the app opens even though the image it
            // would install is still sitting on disk.
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
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
     * Lets a chosen donor be swapped or dropped.
     *
     * Choosing one used to be one-way: the button only ever opened the picker,
     * so a donor selected by mistake could be changed but never removed, and
     * the recommended three-byte fix stayed suppressed until the app was
     * restarted.
     */
    private fun offerDonorChange() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Donor init")
            .setMessage("Using init from " + (donorUri?.let { displayName(it) } ?: "?"))
            .setPositiveButton("Choose another") { _, _ -> pickDonor() }
            .setNegativeButton("Remove") { _, _ ->
                donorUri = null
                donorBtn.text = "Optional: replace init (donor GSI or init file)"
                fixInitBox.text = "Fix init verified-boot spoofing (recommended)"
                fixInitBox.isEnabled = true
                appendLog("donor removed -- the three-byte init fix applies again")
            }
            .setNeutralButton("Keep", null)
            .show()
    }

    /**
     * Picks an image to install that this session did not patch.
     *
     * The DSU button used to install only what the app had just produced, so
     * an image patched yesterday -- or one that needs no patch at all, like
     * the andyyan or PeterCai builds -- could not be installed from here even
     * though every other part of the flow would have worked.
     */
    private fun pickDsuImage() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
        startActivityForResult(i, REQ_DSU_IMAGE)
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
                persist(uri, data.flags)
                outputUri = uri
                outputBtn.text = "2. Save as: " + displayName(uri)
                patchBtn.isEnabled = true
                // Said now, while it is still one tap to choose somewhere else.
                // Finding out after a fifteen-minute patch that the image landed
                // on an SD card gsid cannot be pointed at is the worst moment
                // for this to surface.
                if (DsuInstaller.rootPathFor(this, uri) == null) {
                    appendLog(
                        "note: the DSU button cannot install from there (only primary internal " +
                            "storage, e.g. Download). Patching works; installing would need " +
                            "DSU Sideloader."
                    )
                }
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
                        appendLog(
                            "donor init: " + donor.size + " bytes from " + displayName(uri)
                        )
                        appendLog(
                            "   a donor replaces init wholesale, so the three-byte spoof fix is " +
                                "not applied on top of it"
                        )
                        runOnUiThread {
                            donorBtn.text = "Optional: init from " + displayName(uri)
                            donorBtn.isEnabled = true
                            // Left off, not just greyed: with a donor selected the
                            // checkbox has no effect (see runPatch), and a control
                            // that silently does nothing is worse than one that
                            // plainly says so.
                            fixInitBox.isEnabled = false
                            fixInitBox.text = "Fix init verified-boot spoofing (replaced by the donor)"
                        }
                    } catch (t: Throwable) {
                        donorUri = null
                        appendLog("donor rejected: " + (t.message ?: t.toString()))
                        runOnUiThread {
                            donorBtn.text = "Optional: replace init (donor GSI or init file)"
                            donorBtn.isEnabled = true
                            // Restored on this path too. It used to be left
                            // disabled forever, so one rejected donor silently
                            // turned the recommended fix off for good.
                            fixInitBox.isEnabled = true
                        }
                    }
                }.start()
            }
            REQ_DSU_IMAGE -> {
                persist(uri, data.flags)
                // The digest is read inside the install thread: it opens the
                // image, and file I/O belongs nowhere near onActivityResult.
                startDsuInstall(uri)
            }
        }
    }

    /**
     * Keeps a picker's grant alive across restarts, where the provider offers
     * one. Best effort by design: a provider that refuses is not a reason to
     * fail the pick, it only means the button will not survive a restart.
     */
    private fun persist(uri: Uri, flags: Int) {
        val keep = flags and
            (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if (keep == 0) return
        trimPersistedGrants()
        try {
            contentResolver.takePersistableUriPermission(uri, keep)
        } catch (e: SecurityException) {
            // Not persistable. The in-memory reference still works this session.
        }
    }

    /**
     * Drops the oldest persisted grants so taking a new one keeps working.
     *
     * The platform caps how many an app may hold, and every output location
     * ever chosen takes one. Without this the app would quietly stop being
     * able to remember new images after enough runs -- a failure that would
     * only show up for the people who use it most.
     */
    private fun trimPersistedGrants(keepNewest: Int = 8) {
        val held = try {
            contentResolver.persistedUriPermissions
        } catch (e: Exception) {
            return
        }
        if (held.size < keepNewest) return
        held.sortedBy { it.persistedTime }
            .take(held.size - keepNewest + 1)
            .forEach {
                val mode =
                    (if (it.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                        (if (it.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
                try {
                    contentResolver.releasePersistableUriPermission(it.uri, mode)
                } catch (e: Exception) {
                    // Already gone; nothing to release.
                }
            }
    }

    /**
     * The AVB root digest of an image, or null if it has no readable footer.
     *
     * Cheap on purpose: this reads the footer and the vbmeta block, not the
     * hashtree, so it costs a couple of reads even on a four-gigabyte image.
     * It is what lets [startBootLog] answer about the image that was actually
     * installed rather than about whichever one was patched last.
     */
    private fun digestOf(uri: Uri): String? = try {
        contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            ImageIo(FileInputStream(pfd.fileDescriptor).channel).use { io ->
                Avb(io).rootDigest.joinToString("") { b -> "%02x".format(b) }
            }
        }
    } catch (t: Throwable) {
        null
    }

    /**
     * Records which image the DSU button and the crash reader are about.
     *
     * Call off the UI thread: [displayName] queries the content provider.
     */
    private fun rememberOutput(uri: Uri, digest: String?) {
        val name = displayName(uri)
        lastOutputUri = uri
        lastOutputName = name
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(PREF_DIGEST, digest)
            .putString(PREF_NAME, name)
            .putString(PREF_URI, uri.toString())
            .apply()
    }

    /**
     * Brings back the image a previous run patched, so the DSU button works in
     * a fresh session. Verified rather than trusted: the file may have been
     * moved, deleted or flashed and removed since, and offering to install a
     * URI that no longer resolves is worse than offering nothing.
     */
    private fun restoreLastOutput() {
        val saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_URI, null) ?: return
        Thread {
            val uri = try { Uri.parse(saved) } catch (e: Exception) { null }
            val size = if (uri == null) -1L else try { sizeOf(uri) } catch (e: Exception) { -1L }
            val name = if (uri != null && size > 0) displayName(uri) else null
            runOnUiThread {
                if (uri != null && name != null) {
                    lastOutputUri = uri
                    lastOutputName = name
                    appendLog("ready to install: " + name + "  (" + fmt(size) + ")")
                } else {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(PREF_URI).apply()
                }
                refreshDsuButton()
            }
        }.start()
    }

    /** The DSU button always works; the label says which image it will use. */
    private fun refreshDsuButton() {
        val name = if (lastOutputUri == null) null else lastOutputName
        dsuBtn.text = if (name == null) "4. Install an image with DSU and boot it (root)"
            else "4. Install " + name + " with DSU (root)"
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
        markBusy("Patching " + (inputUri?.let { displayName(it) } ?: "image"))
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
                // Leaving a half-written image on disk under a normal-looking
                // name is the most dangerous thing this app could do: it is
                // indistinguishable from a good one until someone flashes it.
                // Truncating makes it unmistakably unusable, which matches the
                // rule the rest of the tool follows -- refuse rather than ship
                // something that looks fine and fails at boot.
                appendLog(truncateFailedOutput(outUri))
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
        markBusy("Checking " + displayName(inUri))
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
                val vendorPolicy = VendorPolicy.read(rootBox.isChecked)
                appendLog("   " + vendorPolicy.note)
                val pfd = contentResolver.openFileDescriptor(inUri, "r")
                    ?: throw IllegalStateException("cannot read that location directly")
                pfd.use {
                    val ch = FileInputStream(it.fileDescriptor).channel
                    ImageIo(ch).use { io ->
                        appendLog(
                            Preflight.check(
                                io, release, patch,
                                { d, t -> updateProgress(if (t == 0L) 100 else ((d * 100) / t).toInt()) },
                                device, displayName(inUri),
                                vendorPolicy.rules, vendorPolicy.sepolicyVersion
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

            // Refuse EROFS before the copy rather than after it. The filesystem
            // is only opened once the whole image is on the destination, so an
            // EROFS GSI would otherwise cost minutes and gigabytes of writes.
            open().use { probeIn ->
                if (ImageFormat.looksLikeErofs(readHeadBytes(Compression.open(probeIn).stream, 1028))) {
                    throw IllegalArgumentException(ImageFormat.EROFS_MESSAGE)
                }
            }

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
                // Read this device's own SELinux rules so the image can be
                // checked for genfscon conflicts. Needs root; without it the
                // patcher reports the check as not performed, never as clean.
                val vendorPolicy = VendorPolicy.read(rootBox.isChecked)
                appendLog("   " + vendorPolicy.note)
                val report = GsiPatcher.patch(
                    io,
                    GsiPatcher.Options(
                    release, patch, dropFec = true, enableAdb = adbBox.isChecked,
                    donorInit = donorUri?.let { resolveDonor(it) },
                    // A donor replaces the whole init, so patching it first
                    // would be overwritten -- let the explicit choice win.
                    fixInitSpoof = fixInitBox.isChecked && donorUri == null,
                    vendorGenfscon = vendorPolicy.rules,
                    vendorSepolicyVersion = vendorPolicy.sepolicyVersion
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
                // Remembered so the crash-log reader can answer about THIS image
                // by its root digest, and so the DSU button still knows what to
                // install after the app has been closed and reopened.
                rememberOutput(outUri, report.newRootDigest)
                runOnUiThread { refreshDsuButton() }
                appendLog("")
                appendLog("")
                appendLog("== pre-flight on the finished image")
                appendLog(
                    Preflight.check(
                        io, release, patch,
                        { d, t -> updateProgress(if (t == 0L) 100 else ((d * 100) / t).toInt()) },
                        device, displayName(outUri),
                        vendorPolicy.rules, vendorPolicy.sepolicyVersion
                    ).toString()
                )
                appendLog("")
                appendLog(
                    "Next: install it with the DSU button below (root) or DSU Sideloader. If it " +
                        "reboots without a boot animation, come back and tap \"Why did it fail to boot?\"."
                )
            }
            readPfd.close()
            writePfd.close()
        }
    }

    /** Disables every action while one runs. Call on the UI thread. */
    private fun markBusy(what: String) {
        patchBtn.isEnabled = false
        checkBtn.isEnabled = false
        inputBtn.isEnabled = false
        outputBtn.isEnabled = false
        donorBtn.isEnabled = false
        dsuBtn.isEnabled = false
        bootLogBtn.isEnabled = false
        progressLabel.text = what
        progress.progress = 0
        progressBox.visibility = View.VISIBLE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /**
     * Installs the image the last patch produced, then asks before rebooting.
     *
     * Root is checked here rather than assumed: this is the one flow in the app
     * with no unprivileged fallback.
     */
    private fun startDsuInstall(image: Uri? = null) {
        if (working) return
        // No image on record is not a dead end -- it is a question. The button
        // used to do nothing at all in this state, which read as broken.
        val outUri = image ?: lastOutputUri ?: run { pickDsuImage(); return }
        working = true
        markBusy("Installing " + displayName(outUri) + " as a DSU")
        PatchService.start(this, "Installing " + displayName(outUri) + " as a DSU")
        Thread {
            try {
                if (!Root.available()) throw IllegalStateException(
                    "root is not available, so the app cannot install a DSU itself -- use DSU Sideloader"
                )
                val path = DsuInstaller.rootPathFor(this, outUri) ?: throw IllegalStateException(
                    "cannot turn where the image was saved into a path root can use. Save it on " +
                        "internal storage, for example in Download, rather than an SD card or cloud folder."
                )
                val size = sizeOf(outUri)
                if (size <= 0) throw IllegalStateException("cannot determine the image size")
                // Recorded before the install, not after: if this one crashes
                // the tablet, the crash reader has to already know which digest
                // to look for in expdb.
                rememberOutput(outUri, digestOf(outUri))
                val need = size + DsuInstaller.USERDATA_BYTES
                val free = DsuInstaller.freeBytesOnData(this)
                if (free in 1 until need) throw IllegalStateException(
                    "not enough free space on /data: about " + (need shr 20) + " MB needed, " +
                        (free shr 20) + " MB free"
                )
                appendLog("")
                appendLog("== installing " + displayName(outUri) + " as a DSU (" + fmt(size) + ")")
                val result = DsuInstaller.install(path, size, { updateProgress(it) }, { appendLog("   " + it) })
                if (!result.ok) throw IllegalStateException("the install did not complete; gsi_tool's output is above")
                appendLog("   installed")
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    android.app.AlertDialog.Builder(this)
                        .setTitle("Installed")
                        .setMessage(
                            "Reboot into " + displayName(outUri) + " now? If it fails to boot, the " +
                                "tablet falls back to this system on its own."
                        )
                        .setPositiveButton("Reboot now") { _, _ -> Thread { DsuInstaller.bootIntoGsi() }.start() }
                        .setNegativeButton("Not now") { _, _ ->
                            appendLog("   left installed but not armed. When ready: gsi_tool enable --single-boot, then reboot")
                        }
                        .show()
                }
            } catch (t: Throwable) {
                appendLog("FAILED: " + (t.message ?: t.toString()))
            } finally {
                resetControls()
            }
        }.start()
    }

    /**
     * Reads why a GSI boot crashed, out of the MediaTek expdb partition.
     *
     * Looks the last patched image up by its root digest, so the answer is
     * about that image rather than whichever crash is stored first. See
     * [BootLog] for why expdb and not pstore.
     */
    private fun startBootLog() {
        if (working) return
        working = true
        markBusy("Reading the crash log from expdb")
        PatchService.start(this, "Reading the crash log")
        Thread {
            try {
                if (!Root.available()) throw IllegalStateException(
                    "reading the crash log needs root: the partition is not readable by apps"
                )
                val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
                val digest = prefs.getString("last_digest", null)
                val name = prefs.getString("last_name", null)
                appendLog("")
                appendLog("== why did it fail to boot?" + (name?.let { " (" + it + ")" } ?: ""))
                val proc = Root.exec("dd if=" + BootLog.EXPDB_PATH + " bs=1M 2>/dev/null")
                    ?: throw IllegalStateException("could not start su")
                val attempts = proc.inputStream.buffered().use { stream ->
                    BootLog.scan(stream) { read ->
                        updateProgress(((read * 100) / EXPDB_BYTES).toInt().coerceAtMost(100))
                    }
                }
                proc.waitFor()
                if (attempts.isEmpty() && digest == null) {
                    appendLog("   no GSI crash records found. expdb is MediaTek-specific, so other devices will not have one.")
                } else {
                    if (digest == null) appendLog("   no patched image on record yet, so listing every recorded crash")
                    BootLog.report(attempts, digest).lineSequence().forEach { appendLog("   " + it) }
                }
            } catch (t: Throwable) {
                appendLog("FAILED: " + (t.message ?: t.toString()))
            } finally {
                resetControls()
            }
        }.start()
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
            // These two were the bug: markBusy() disabled them at the start of
            // every run and nothing here put them back, so the DSU button was
            // permanently greyed out from the moment the first patch finished
            // -- precisely when it becomes useful.
            dsuBtn.isEnabled = true
            bootLogBtn.isEnabled = true
            // Only meaningful without a donor; the donor branch owns it otherwise.
            fixInitBox.isEnabled = donorUri == null
            refreshDsuButton()
            progressBox.visibility = View.GONE
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

    /**
     * Empties a failed run's output file so it cannot be mistaken for a good
     * image, and reports what happened. Never throws -- this runs on the
     * failure path, where a second exception would bury the first.
     */
    private fun truncateFailedOutput(outUri: Uri): String = try {
        // "rwt" truncates on open; a zero-byte file cannot be flashed and
        // cannot be confused for a finished image.
        contentResolver.openFileDescriptor(outUri, "rwt")?.close()
        "The output was incomplete, so it has been emptied -- it cannot be flashed. " +
            "Delete it and start again."
    } catch (e: Throwable) {
        "The output file is incomplete and MUST NOT be flashed -- delete it manually " +
            "(could not empty it automatically: " + (e.message ?: e.toString()) + ")"
    }

    /** Progress chatter stays on the bar; flooding the log would bury the report. */
    private fun updateProgress(pct: Int) {
        runOnUiThread { progress.progress = pct }
    }

    /**
     * Empties the log. Worth a button because a second run's report is hard to
     * find under a first run's, and these reports are hundreds of lines.
     */
    private fun clearLog() {
        log.text = ""
        appendLog("device: TEE expects Android " + (device.teeRelease ?: "unknown") +
            ", vendor API " + (device.vendorApiLevel?.toString() ?: "?") +
            ", KeyMint " + (device.keymintAidlVersion?.let { "V" + it } ?: "version unreadable"))
    }

    private fun appendLog(line: String) {
        runOnUiThread {
            log.append(line + "\n")
            // Posted, not called directly: the ScrollView has not laid the new
            // line out yet at this point, so scrolling now stops one line short
            // every time.
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }
}
