package dev.g7tabpro.gsipatch.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Keeps the process alive at foreground priority while a patch is running.
 *
 * The work itself stays on the worker thread in [MainActivity] -- this service
 * deliberately does none of it. Its only job is process priority.
 *
 * ## Why this exists
 *
 * Patching a GSI is a multi-minute operation that rewrites several gigabytes.
 * `FLAG_KEEP_SCREEN_ON` protects it only while the activity is visible; the
 * moment the user switches apps the process becomes an ordinary background
 * process and is eligible to be killed under memory pressure. Being killed
 * midway leaves a **truncated output image that looks complete** -- the worst
 * possible failure for this tool, because a short write still produces a file
 * and the damage is only discovered at flash time.
 *
 * A foreground service raises the process to the same priority as a visible
 * app, so switching away no longer risks the write. The worker thread is not
 * tied to the activity lifecycle, so it keeps running regardless.
 *
 * The notification is a required side effect of that priority, not the point
 * -- though it does mean progress is visible from the shade.
 */
class PatchService : Service() {

    companion object {
        private const val CHANNEL_ID = "patch"
        private const val NOTIFICATION_ID = 1
        private const val EXTRA_TEXT = "text"

        /** Starts, or updates the text of, the foreground notification. */
        fun start(ctx: Context, text: String) {
            val i = Intent(ctx, PatchService::class.java).putExtra(EXTRA_TEXT, text)
            ctx.startForegroundService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, PatchService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "Patching..."
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Patching",
                    // LOW: this is a progress indicator, not something to
                    // interrupt the user with -- no sound, no heads-up.
                    NotificationManager.IMPORTANCE_LOW
                ).apply { setShowBadge(false) }
            )
        }

        val tapBack = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_IMMUTABLE
        )

        val n: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("GSI patch in progress")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(tapBack)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        // API 34 requires a declared type. dataSync is the closest fit: a
        // user-initiated bulk transform of a file the user chose.
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }

        // Not sticky: if the process dies the patch is dead with it, and
        // silently restarting an empty service would imply otherwise.
        return START_NOT_STICKY
    }
}
