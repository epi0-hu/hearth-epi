package app.hearth

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * Keeps Hearth alive while a reply is being generated, so switching apps or turning the
 * screen off doesn't freeze the process and drop the connection. Shows a quiet
 * "… is writing" notification for as long as it runs.
 */
class GenerationService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelfNow()
            return START_NOT_STICKY
        }
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Writing a reply…"
        val n = Notifications.progress(this, title)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(Notifications.PROGRESS_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(Notifications.PROGRESS_ID, n)
        }
        if (wakeLock == null) {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Hearth:generation")
                .apply { setReferenceCounted(false); acquire(15 * 60 * 1000L) }
        }
        return START_NOT_STICKY
    }

    private fun stopSelfNow() {
        runCatching { wakeLock?.release() }
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        runCatching { wakeLock?.release() }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_TITLE = "title"
        private const val ACTION_STOP = "app.hearth.STOP_GENERATION"

        fun start(ctx: Context, title: String) {
            val i = Intent(ctx, GenerationService::class.java).putExtra(EXTRA_TITLE, title)
            runCatching {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.startService(Intent(ctx, GenerationService::class.java).setAction(ACTION_STOP)) }
                .onFailure { runCatching { ctx.stopService(Intent(ctx, GenerationService::class.java)) } }
        }
    }
}

object Notifications {
    const val PROGRESS_ID = 41
    private const val REPLY_ID = 42
    private const val CH_PROGRESS = "generation"
    private const val CH_REPLIES = "replies"

    private fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH_PROGRESS) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CH_PROGRESS, "Writing replies", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while a reply is being generated in the background"
                    setShowBadge(false)
                },
            )
        }
        if (nm.getNotificationChannel(CH_REPLIES) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CH_REPLIES, "New replies", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "When a reply finishes while Hearth is in the background"
                },
            )
        }
    }

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    @Suppress("DEPRECATION")
    private fun builder(ctx: Context, channel: String): Notification.Builder =
        if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, channel) else Notification.Builder(ctx)

    fun progress(ctx: Context, title: String): Notification {
        ensureChannels(ctx)
        return builder(ctx, CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText("Hearth keeps writing while you're away")
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(ctx))
            .build()
    }

    /** "Seraphina replied" with a preview, posted only when the app isn't on screen. */
    fun replyReady(ctx: Context, who: String, preview: String) {
        ensureChannels(ctx)
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val text = preview.replace(Regex("<[^>]*>"), "").replace(Regex("\\s+"), " ").trim().take(300)
        val n = builder(ctx, CH_REPLIES)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("$who replied")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx))
            .build()
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(REPLY_ID, n) }
    }

    fun clearReply(ctx: Context) {
        runCatching { ctx.getSystemService(NotificationManager::class.java).cancel(REPLY_ID) }
    }
}
