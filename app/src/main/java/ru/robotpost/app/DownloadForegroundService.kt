package ru.robotpost.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** Runs the persisted queue independently from the activity lifecycle. */
class DownloadForegroundService : Service() {
    companion object {
        const val ACTION_START = "ru.robotpost.app.DOWNLOAD_START"
        const val ACTION_STOP = "ru.robotpost.app.DOWNLOAD_STOP"
        private const val CHANNEL = "robotpost_foreground"
        private const val ID = 1003
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var worker: Job? = null
    @Volatile private var stopping = false
    @Volatile private var activeTemp: File? = null

    override fun onBind(intent: Intent?): IBinder? = null

    private fun status(message: String) {
        getSharedPreferences("robotpost", Context.MODE_PRIVATE).edit()
            .putString("queueServiceStatus", message).apply()
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("RobotPost · очередь")
            .setContentText(message.take(110))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
        manager.notify(ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopping = true
            activeTemp?.let { File(it, "cancel.flag").writeText("cancel") }
            status("Останавливаю очередь...")
            if (worker == null) stopSelf()
            return START_NOT_STICKY
        }
        if (worker?.isActive == true) return START_NOT_STICKY
        stopping = false
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Фоновая загрузка RobotPost", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("RobotPost · очередь")
            .setContentText("Подготовка загрузки")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(ID, notification)
        worker = scope.launch { processQueue() }
        return START_NOT_STICKY
    }

    private suspend fun processQueue() {
        val prefs = getSharedPreferences("robotpost", Context.MODE_PRIVATE)
        var completed = 0
        var failed = 0
        prefs.edit().putBoolean("queueWasRunning", true).apply()
        try {
            while (!stopping) {
                val pending = prefs.getString("pendingQueue", "").orEmpty().lines().filter { it.isNotBlank() }
                if (pending.isEmpty()) break
                val url = pending.first()
                try {
                    status("Проверяю: " + url)
                    val quality = prefs.getString("queueQuality", "720") ?: "720"
                    val root = prefs.getString("archiveRoot", "").orEmpty()
                    require(root.isNotBlank()) { "Не выбрана папка архива" }
                    val info = withContext(Dispatchers.IO) { DownloadEngine.inspect(this@DownloadForegroundService, url) }
                    if (stopping) break
                    val formats = info.optJSONArray("formats") ?: error("Нет форматов")
                    val selected = (0 until formats.length()).map { formats.getJSONObject(it) }
                        .firstOrNull {
                            if (quality == "audio") it.optString("kind") == "audio"
                            else it.optString("kind") != "audio" && it.optInt("height", 0) in 1..quality.toInt()
                        } ?: error("Нет подходящего формата")
                    status("Скачиваю: " + info.optString("title", url))
                    val saved = withContext(Dispatchers.IO) {
                        DownloadEngine.download(this@DownloadForegroundService, url, Uri.parse(root), selected.getString("id"),
                            onTemp = { activeTemp = it },
                            onProgress = { progress -> status(progress) })
                    }
                    DownloadHistory.add(this@DownloadForegroundService, saved, url)
                    completed++
                } catch (e: Exception) {
                    if (stopping) break
                    failed++
                    val failedLinks = prefs.getString("failedQueue", "").orEmpty().lines().filter { it.isNotBlank() }
                    val reasons = runCatching { JSONObject(prefs.getString("failureReasons", "{}")) }.getOrDefault(JSONObject())
                    reasons.put(url, e.localizedMessage ?: "Ошибка загрузки")
                    prefs.edit()
                        .putString("failedQueue", (failedLinks + url).distinct().joinToString("\n"))
                        .putString("failureReasons", reasons.toString()).apply()
                } finally {
                    activeTemp = null
                }
                // Remove only after successful export or recorded failure.
                prefs.edit().putString("pendingQueue", pending.drop(1).joinToString("\n")).apply()
            }
        } finally {
            val message = if (stopping) "Остановлено · готово: $completed · ошибок: $failed"
                else "Завершено · готово: $completed · ошибок: $failed"
            prefs.edit().putBoolean("queueWasRunning", false)
                .putString("queueServiceStatus", message).apply()
            stopForeground(STOP_FOREGROUND_REMOVE)
            DownloadNotifications.finish(this, message)
            worker = null
            stopSelf()
        }
    }

    override fun onDestroy() {
        stopping = true
        activeTemp?.let { runCatching { File(it, "cancel.flag").writeText("cancel") } }
        scope.cancel()
        super.onDestroy()
    }
}
