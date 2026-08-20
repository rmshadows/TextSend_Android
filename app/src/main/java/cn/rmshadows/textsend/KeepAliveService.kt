package cn.rmshadows.textsend

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class KeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
        return START_STICKY
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.keepalive_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        ch.setShowBadge(false)
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val launch = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            this,
            0,
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_textsend)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.keepalive_notification))
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "textsend_keepalive"
        private const val NOTIF_ID = 1
    }
}
