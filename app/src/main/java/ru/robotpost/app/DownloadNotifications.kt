package ru.robotpost.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Lightweight queue notifications. This does not keep downloads alive in background:
 * a foreground service is required for that.
 */
object DownloadNotifications {
    private const val CHANNEL = "robotpost_downloads"
    private const val ID = 1002

    fun show(context: Context, title: String, detail: String, ongoing: Boolean = true) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Загрузки RobotPost", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val builder = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(detail.take(160))
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
        manager.notify(ID, builder.build())
    }

    fun finish(context: Context, detail: String) {
        show(context, "RobotPost · очередь", detail, ongoing = false)
    }
}
