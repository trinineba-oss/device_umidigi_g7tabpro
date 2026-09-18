package dev.g7tabpro.gsipatch.app

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.MediaStore

/**
 * Installs a patched image as a Dynamic System Update and boots it, using root,
 * so patch, install, boot and diagnose all happen on the tablet.
 *
 * ## Four things learned the hard way
 *
 * 1. `gsi_tool install` reboots into the GSI on its own. When the write
 *    finishes it sets `sys.powerctl` to `reboot,adb` unless given
 *    `--no-reboot`. During the AxionOS work that looked like the tablet
 *    randomly restarting after every install. Here it always gets
 *    `--no-reboot`, so the app decides when the reboot happens.
 *
 * 2. gsid cannot read an ordinary file. gsi_tool hands it the image as a file
 *    descriptor, and SELinux lets gsid read only `gsi_data_file`. An image in
 *    Downloads is refused, and gsi_tool fails late with an empty "Could not
 *    commit live image data". The file is relabelled for the install and
 *    restored afterwards, whether the install worked or not.
 *
 * 3. A previous DSU install can make the next one fail while closing its
 *    userdata partition. Wiping first avoids that.
 *
 * 4. `gsi_tool install` **arms** the DSU by itself. `--no-reboot` suppresses
 *    the reboot, not the enable: a finished install leaves `one_shot_boot=1`
 *    and `gsi_tool status` reporting `enabled`, so the next reboot -- whenever
 *    it happens, for whatever reason -- starts the GSI instead of the user's
 *    own system. Measured on the G7 Tab Pro, where an install that was never
 *    rebooted into still showed `installed / enabled`. So choosing not to boot
 *    it has to call [disable] explicitly rather than assume.
 */
object DsuInstaller {

    class Result(val ok: Boolean, val log: String)

    /** What `gsi_tool status` reports about the DSU slot. */
    enum class State { NORMAL, INSTALLED, RUNNING, UNREADABLE }

    /**
     * [armed] is the one that bites: an installed DSU that is also enabled
     * takes over the next reboot. [bytes] is what it occupies under /data,
     * which is the whole reason to want it gone.
     */
    class Status(val state: State, val armed: Boolean, val bytes: Long) {
        val installed: Boolean get() = state == State.INSTALLED || state == State.RUNNING
    }

    /** Where gsid keeps the installed images. */
    private const val IMAGE_DIR = "/data/gsi/dsu"

    /** Matches what DSU Sideloader used on this device, which booted fine. */
    const val USERDATA_BYTES = 2L shl 30

    /** Terminal colour codes gsi_tool wraps its progress bar in. */
    private val ANSI = Regex("""\x1B\[[0-9;]*m""")
    private val PCT = Regex("""(\d{1,3})%""")

    /**
     * The path root can use for a document, or null when none can be derived.
     *
     * Only primary internal storage works. A removable card is vfat or exfat,
     * which cannot carry an SELinux label, so the relabel in [install] has
     * nothing to act on. The `/data/media/0` form is used rather than
     * `/storage/emulated/0` because the latter is a FUSE view, and chcon
     * does not work through it.
     */
    fun rootPathFor(ctx: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.path?.let(::toDataMedia)
        val id = try {
            DocumentsContract.getDocumentId(uri)
        } catch (e: Exception) {
            return null
        }
        return when (uri.authority) {
            "com.android.externalstorage.documents" -> {
                val colon = id.indexOf(':')
                if (colon > 0 && id.substring(0, colon) == "primary")
                    "/data/media/0/" + id.substring(colon + 1)
                else null
            }
            "com.android.providers.downloads.documents" -> when {
                id.startsWith("raw:") -> toDataMedia(id.removePrefix("raw:"))
                id.startsWith("msf:") -> mediaStorePath(ctx, id.removePrefix("msf:"))?.let(::toDataMedia)
                else -> null
            }
            else -> null
        }
    }

    /** Free space on /data, where gsid writes the installed image. */
    fun freeBytesOnData(ctx: Context): Long = StatFs(ctx.filesDir.path).availableBytes

    /**
     * Wipes any previous DSU install, then installs [path] without rebooting.
     *
     * [onProgress] gets gsi_tool's percentage and [onLine] every other line
     * it prints. Blocks for the length of the install, so call it off the UI
     * thread.
     */
    fun install(path: String, imageBytes: Long, onProgress: (Int) -> Unit, onLine: (String) -> Unit): Result {
        val q = shellQuote(path)
        val script = listOf(
            "gsi_tool wipe >/dev/null 2>&1",
            "chcon u:object_r:gsi_data_file:s0 $q || { echo 'could not relabel the image for gsid'; exit 3; }",
            "gsi_tool install --no-reboot --gsi-size $imageBytes --userdata-size $USERDATA_BYTES < $q",
            "RC=\$?",
            "restorecon $q >/dev/null 2>&1 || chcon u:object_r:media_rw_data_file:s0 $q",
            "gsi_tool status",
            "exit \$RC"
        ).joinToString("; ")

        val proc = Root.exec(script, mergeStderr = true)
            ?: return Result(false, "root is not available")
        val log = StringBuilder()
        val line = StringBuilder()
        var lastPct = -1
        proc.inputStream.bufferedReader(Charsets.ISO_8859_1).use { reader ->
            // gsi_tool redraws its progress bar with carriage returns, so a
            // line ends at either.
            while (true) {
                val c = reader.read()
                if (c >= 0 && c != '\r'.code && c != '\n'.code) {
                    line.append(c.toChar())
                    continue
                }
                val text = line.toString().replace(ANSI, "").trim()
                line.setLength(0)
                if (text.isNotEmpty()) {
                    val pct = PCT.find(text)?.groupValues?.get(1)?.toIntOrNull()
                    if (pct != null) {
                        if (pct != lastPct) {
                            lastPct = pct
                            onProgress(pct)
                        }
                    } else {
                        log.appendLine(text)
                        onLine(text)
                    }
                }
                if (c < 0) break
            }
        }
        val rc = proc.waitFor()
        val failed = log.contains("Could not") || log.contains("could not relabel")
        return Result(rc == 0 && !failed, log.toString())
    }

    /**
     * Arms the installed image for one boot and reboots into it. A failed boot
     * falls back to the normal system, and so does the next reboot.
     */
    fun bootIntoGsi(): Boolean =
        Root.exec("gsi_tool enable --single-boot && reboot", mergeStderr = true) != null

    /**
     * What is installed, whether it will take the next reboot, and how much
     * room it is using. [State.UNREADABLE] when root is unavailable -- never
     * guessed at, because reporting "nothing installed" when the truth is
     * unknown would hide five gigabytes.
     *
     * The size is measured *before* gsi_tool runs, deliberately: on this
     * device a `du` issued after `gsi_tool` in the same root shell fails with
     * EACCES, while the same `du` on its own succeeds.
     */
    fun status(): Status {
        val out = Root.run(listOf(
            "du -sb $IMAGE_DIR 2>/dev/null | cut -f1; echo '---'; gsi_tool status 2>&1"
        )) ?: return Status(State.UNREADABLE, false, -1)

        val parts = out.split("---")
        val bytes = parts.getOrNull(0)?.trim()?.lines()?.firstOrNull()?.trim()?.toLongOrNull() ?: -1L
        val lines = (parts.getOrNull(1) ?: "").lines().map { it.trim() }.filter { it.isNotEmpty() }
        val state = when (lines.firstOrNull()) {
            "running" -> State.RUNNING
            "installed" -> State.INSTALLED
            "normal" -> State.NORMAL
            else -> State.UNREADABLE
        }
        return Status(state, lines.any { it == "enabled" }, bytes)
    }

    /**
     * Removes the installed DSU and its userdata, freeing the space.
     *
     * The host system is untouched; this only deletes what lives under
     * /data/gsi. gsid refuses while the DSU is the running system, which is
     * why the caller checks [status] first rather than letting it fail with a
     * message about live images.
     */
    fun wipe(): Result = rootStep("gsi_tool wipe")

    /**
     * Leaves the DSU installed but takes it out of the boot path.
     *
     * The counterpart to the install arming it: this is what makes "not now"
     * mean what it says.
     */
    fun disable(): Result = rootStep("gsi_tool disable")

    private fun rootStep(cmd: String): Result {
        val proc = Root.exec(cmd, mergeStderr = true) ?: return Result(false, "root is not available")
        val text = proc.inputStream.bufferedReader(Charsets.ISO_8859_1).use { it.readText() }.trim()
        val rc = proc.waitFor()
        return Result(rc == 0, if (text.isEmpty()) "exit code $rc" else text)
    }

    private fun toDataMedia(p: String): String? = when {
        p.startsWith("/storage/emulated/0/") -> "/data/media/0/" + p.removePrefix("/storage/emulated/0/")
        p.startsWith("/sdcard/") -> "/data/media/0/" + p.removePrefix("/sdcard/")
        p.startsWith("/data/media/0/") -> p
        else -> null
    }

    private fun mediaStorePath(ctx: Context, id: String): String? = try {
        ctx.contentResolver.query(
            MediaStore.Files.getContentUri("external"),
            arrayOf(MediaStore.MediaColumns.DATA),
            MediaStore.MediaColumns._ID + "=?",
            arrayOf(id),
            null
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (e: Exception) {
        null
    }

    private fun shellQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"
}
