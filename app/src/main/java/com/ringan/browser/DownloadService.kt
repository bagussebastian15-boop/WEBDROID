package com.ringan.browser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat

/** Menjaga download tetap jalan saat aplikasi di latar belakang, dengan notifikasi progres. */
class DownloadService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var tracker: DownloadTracker

    private val tick = object : Runnable {
        override fun run() {
            val active = tracker.query().filter { it.active }
            if (active.isEmpty()) {
                stopSelf()
                return
            }
            getSystemService(NotificationManager::class.java)
                .notify(DownloadNotifier.ONGOING_ID, DownloadNotifier.ongoing(this@DownloadService, tracker, active))
            handler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        tracker = DownloadTracker(this)
        DownloadNotifier.ensureChannels(this)
        val active = tracker.query().filter { it.active }
        ServiceCompat.startForeground(
            this,
            DownloadNotifier.ONGOING_ID,
            DownloadNotifier.ongoing(this, tracker, active),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        if (wakeLock == null) {
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ringan:download")
                .apply {
                    setReferenceCounted(false)
                    acquire(6 * 60 * 60 * 1000L)
                }
        }
        handler.removeCallbacks(tick)
        handler.post(tick)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        try { wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
        super.onDestroy()
    }
}

object DownloadNotifier {
    const val CH_PROGRESS = "download_progress"
    const val CH_DONE = "download_done"
    const val ONGOING_ID = 7001

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_PROGRESS, "Progres download", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_DONE, "Download selesai", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun openApp(ctx: Context): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_DOWNLOADS, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(ctx, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun ongoing(ctx: Context, tracker: DownloadTracker, active: List<DownloadTracker.Info>): Notification {
        val first = active.firstOrNull()
        val b = NotificationCompat.Builder(ctx, CH_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(ctx))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (first == null) {
            b.setContentTitle("Download").setContentText("Mempersiapkan…")
        } else {
            val title = if (active.size == 1) first.name else "Mengunduh ${active.size} file"
            b.setContentTitle(title).setContentText(tracker.summary(first, withName = active.size > 1))
            if (first.percent >= 0) b.setProgress(100, first.percent, false) else b.setProgress(0, 0, true)
        }
        return b.build()
    }

    fun done(ctx: Context, tracker: DownloadTracker, t: Task) {
        val info = tracker.find(t.id) ?: return
        val ok = info.status == Status.SUCCESSFUL
        ensureChannels(ctx)
        val b = NotificationCompat.Builder(ctx, CH_DONE)
            .setSmallIcon(if (ok) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setContentTitle(info.name)
            .setContentText(if (ok) "Download selesai · ${tracker.sizeText(info.downloaded)}" else "Gagal: ${info.message}")
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx))
        try {
            NotificationManagerCompat.from(ctx).notify((1000 + info.id).toInt(), b.build())
        } catch (_: SecurityException) {}
    }
}
