package dev.g7tabpro.gsipatch.app

/**
 * Optional root access, used only to read things an app domain is denied.
 *
 * ## Why this is opt-in, and probed early
 *
 * Nothing this app does *needs* root. Root only widens what it can read about
 * the device: `/vendor/etc/selinux` for [VendorPolicy] and the VINTF manifest
 * for [DeviceProbe], both of which are `vendor_configs_file` and denied to app
 * domains regardless of the user's permissions.
 *
 * Asking for it silently, in the middle of a patch, is the wrong shape. The
 * superuser prompt would appear minutes into a multi-gigabyte write, on top of
 * a progress screen, and a user who misses or denies it gets a first call that
 * blocks and then a degraded result with no obvious cause. So the toggle probes
 * once, up front, and the answer is cached for the process: by the time a patch
 * starts, whether root is usable is already settled.
 *
 * Every call degrades to null. An absent `su` throws, a denied one exits
 * non-zero, and neither deserves to fail a patch that root was never required
 * for.
 */
object Root {

    /** Null until probed. Cached because each probe can cost a user prompt. */
    private var granted: Boolean? = null

    /**
     * Whether `su` works, asking for it if that has not been established.
     *
     * Call from a worker thread: on a first grant this blocks on the user
     * answering the superuser dialog.
     */
    fun available(): Boolean {
        granted?.let { return it }
        val ok = run(listOf("id")) != null
        granted = ok
        return ok
    }

    /** What [available] last decided, without triggering a prompt. */
    fun knownAvailable(): Boolean = granted == true

    /** Forgets the cached answer, so the next [available] probes again. */
    fun forget() { granted = null }

    /**
     * Runs one command as root and returns its stdout, or null if it could not
     * run or exited non-zero.
     *
     * stderr is deliberately not merged: `su` implementations write notices
     * there that would otherwise be parsed as file content.
     */
    /**
     * Starts one root command and hands back the process, for output too large
     * or too slow to collect into a String: a 128 MB partition read, or an
     * install that runs for minutes and reports progress as it goes. Null if su
     * cannot be started at all.
     *
     * The caller owns the process and must drain its stream, then waitFor().
     * Merge stderr only for text: dd writes its summary there, which would
     * otherwise land in the middle of binary output.
     */
    fun exec(script: String, mergeStderr: Boolean = false): Process? = try {
        ProcessBuilder(listOf("su", "-c", script))
            .redirectErrorStream(mergeStderr)
            .start()
    } catch (e: Exception) {
        null
    } catch (e: Error) {
        null
    }

    fun run(cmd: List<String>): String? = try {
        val proc = ProcessBuilder(listOf("su", "-c", cmd.joinToString(" ")))
            .redirectErrorStream(false)
            .start()
        val text = proc.inputStream.bufferedReader(Charsets.ISO_8859_1).use { it.readText() }
        if (proc.waitFor() == 0) text else null
    } catch (e: Exception) {
        null
    } catch (e: Error) {
        null
    }
}
