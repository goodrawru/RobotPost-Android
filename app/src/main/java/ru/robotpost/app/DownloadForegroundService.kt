package ru.robotpost.app

import android.app.Notification
import android.app.PendingIntent
import android.Manifest
import android.content.pm.PackageManager
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

    private fun queueNotification(message: String): Notification {
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val openActivity = PendingIntent.getActivity(
            this,
            ID,
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            ),
            pendingFlags
        )
        val stopQueue = PendingIntent.getService(
            this,
            ID + 1,
            Intent(this, DownloadForegroundService::class.java).setAction(ACTION_STOP),
            pendingFlags
        )
        val percent = Regex("""(\d{1,3})%""")
            .find(message)?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 100)
        val active = percent != null || message.startsWith("Проверяю:") ||
            message.startsWith("Скачиваю:") || message.startsWith("Объединяю") ||
            message.startsWith("Сохраняю")
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("RobotPost · очередь")
            .setContentText(message.take(110))
            .setContentIntent(openActivity)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Остановить очередь", stopQueue)
        if (active) builder.setProgress(100, percent ?: 0, percent == null)
        return builder.build()
    }

    private fun status(message: String) {
        getSharedPreferences("robotpost", Context.MODE_PRIVATE).edit()
            .putString("queueServiceStatus", message).apply()
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
            manager.notify(ID, queueNotification(message))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopping = true
            activeTemp?.let { runCatching { File(it, "cancel.flag").writeText("cancel") } }
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
        val notification = queueNotification("Подготовка загрузки")
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(ID, notification)
        getSharedPreferences("robotpost", Context.MODE_PRIVATE).edit()
            .putBoolean("queueWasRunning", true)
            .putString("queueServiceStatus", "Подготовка очереди...").commit()
        worker = scope.launch { processQueue() }
        return START_NOT_STICKY
    }

    private suspend fun processQueue() {
        val prefs = getSharedPreferences("robotpost", Context.MODE_PRIVATE)
        var completed = 0
        var failed = 0
        val initialTotal = prefs.getString("pendingQueue", "").orEmpty().lines().count { it.isNotBlank() }
        prefs.edit().putInt("queueTotal", initialTotal).putInt("queueProcessed", 0)
            .putInt("queueItemPercent", 0).putString("queueItemTitle", "").apply()
        try {
            while (!stopping) {
                val pending = prefs.getString("pendingQueue", "").orEmpty().lines().filter { it.isNotBlank() }
                if (pending.isEmpty()) break
                val url = pending.first()
                try {
                    status("Проверяю: " + url)
                    val job = runCatching { JSONObject(prefs.getString("singleJobMap", "{}") ?: "{}").optJSONObject(url) }.getOrNull()
                    val playlistMode = job == null
                    val playlistMeta = runCatching {
                        JSONObject(prefs.getString("playlistVideoMap", "{}") ?: "{}").optJSONObject(url)
                    }.getOrNull()
                    val saveMode = job?.optString("mode")
                        ?: playlistMeta?.optString("mode")?.takeIf { it.isNotBlank() }
                        ?: prefs.getString("playlistSaveMode", "full").orEmpty()
                    val quality = playlistMeta?.optString("quality")?.takeIf { it.isNotBlank() }
                        ?: when (prefs.getString("quality", "Лучшее доступное")) {
                            "1080p" -> "1080"
                            "720p" -> "720"
                            "480p" -> "480"
                            else -> "best"
                        }
                    val root = job?.optString("root")?.takeIf { it.isNotBlank() }
                        ?: playlistMeta?.optString("root")?.takeIf { it.isNotBlank() }
                        ?: prefs.getString("archiveRoot", "").orEmpty()
                    require(root.isNotBlank()) { "Не выбрана папка архива" }
                    val info = withContext(Dispatchers.IO) { DownloadEngine.inspect(this@DownloadForegroundService, url) }
                    if (stopping) break
                    val formats = info.optJSONArray("formats") ?: error("Нет форматов")
                    val candidates = (0 until formats.length()).map { formats.getJSONObject(it) }
                        .filter {
                            it.optString("kind") != "audio" && it.optInt("height", 0) > 0
                        }
                    val selected = if (job != null) null else if (quality == "best") candidates.maxWithOrNull(
                            compareBy<JSONObject> { it.optInt("height", 0) }
                                .thenBy { if (it.optString("kind") == "single") 1 else 0 }
                        )
                        else {
                            val cap = quality.toInt()
                            val withinCap = candidates.filter { it.optInt("height", 0) <= cap }
                            (withinCap.ifEmpty {
                                candidates.minByOrNull { it.optInt("height", Int.MAX_VALUE) }?.let(::listOf) ?: emptyList()
                            }).maxWithOrNull(
                                compareBy<JSONObject> { it.optInt("height", 0) }
                                    .thenBy { if (it.optString("kind") == "single") 1 else 0 }
                            )
                        }

                    require(job != null || selected != null) { "Нет доступных совместимых форматов" }
                    if (job == null && quality != "best" && selected!!.optInt("height", 0) != quality.toInt())
                        status("Доступно ${selected!!.optInt("height", 0)}p вместо ${quality}p")
                    val itemTitle = info.optString("title", url)
                    prefs.edit().putString("queueItemTitle", itemTitle).putInt("queueItemPercent", 0).apply()
                    status("Скачиваю: " + itemTitle)
                    val saved = withContext(Dispatchers.IO) {
                        DownloadEngine.download(this@DownloadForegroundService, url, Uri.parse(root), job?.getString("format") ?: selected!!.getString("id"),
                            onTemp = { activeTemp = it },
                            onProgress = { progress ->
                                Regex("""(\d{1,3})%""").find(progress)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let {
                                    prefs.edit().putInt("queueItemPercent", it.coerceIn(0, 100)).apply()
                                }
                                status(progress)
                            },
                            includeDescription = job?.optBoolean("description") ?: prefs.getBoolean("includeDescription", true),
                            videoOnly = saveMode == "video",
                            playlistName = if (playlistMeta != null && saveMode == "video") playlistMeta.optString("title") else "",
                            playlistIndex = if (playlistMode) playlistMeta?.optInt("index", 0) ?: 0 else 0,
                            saveMode = saveMode)
                    }
                    DownloadHistory.add(this@DownloadForegroundService, saved, url,
                        if (playlistMode) playlistMeta?.optString("title").orEmpty() else "")
                    // A successful retry must disappear from the failure list and its error map.
                    val previousFailures = prefs.getString("failedQueue", "").orEmpty()
                        .lines().filter { it.isNotBlank() && it != url }
                    val previousReasons = runCatching {
                        JSONObject(prefs.getString("failureReasons", "{}") ?: "{}")
                    }.getOrDefault(JSONObject())
                    previousReasons.remove(url)
                    prefs.edit()
                        .putString("failedQueue", previousFailures.joinToString("\n"))
                        .putString("failureReasons", previousReasons.toString()).commit()
                    if (playlistMeta != null) {
                        val meta = JSONObject(prefs.getString("playlistVideoMap", "{}") ?: "{}")
                        meta.remove(url)
                        prefs.edit().putString("playlistVideoMap", meta.toString()).commit()
                    }
                    if (job != null) {
                        val jobs = JSONObject(prefs.getString("singleJobMap", "{}") ?: "{}")
                        jobs.remove(url)
                        check(prefs.edit().putString("singleJobMap", jobs.toString()).commit()) {
                            "Не удалось обновить параметры заданий"
                        }
                    }
                    completed++
                } catch (e: Exception) {
                    if (stopping) break
                    failed++
                    status("Ошибка: " + DownloadEngine.readableError(e))
                    val failedLinks = prefs.getString("failedQueue", "").orEmpty().lines().filter { it.isNotBlank() }
                    val reasons = runCatching { JSONObject(prefs.getString("failureReasons", "{}")) }.getOrDefault(JSONObject())
                    reasons.put(url, DownloadEngine.readableError(e))
                    check(prefs.edit()
                        .putString("failedQueue", (failedLinks + url).distinct().joinToString("\n"))
                        .putString("failureReasons", reasons.toString()).commit()) {
                        "Не удалось сохранить ошибку загрузки; ссылка останется в очереди"
                    }
                } finally {
                    activeTemp = null
                }
                if (stopping) break // Keep interrupted URL in pendingQueue for an explicit retry.
                // Remove the processed URL from the latest persisted queue, not a stale snapshot.
                // This preserves links added by the user while the service was running.
                val current = prefs.getString("pendingQueue", "").orEmpty()
                    .lines().filter { it.isNotBlank() }.toMutableList()
                current.remove(url)
                prefs.edit().putInt("queueProcessed", completed + failed).putInt("queueItemPercent", 0).apply()
                check(prefs.edit().putString("pendingQueue", current.joinToString("\n")).commit()) {
                    "Не удалось сохранить обновлённую очередь"
                }
            }
        } finally {
            val message = if (stopping) "Остановлено · готово: $completed · ошибок: $failed"
                else "Завершено · готово: $completed · ошибок: $failed"
            prefs.edit().putBoolean("queueWasRunning", false)
                .putString("queueServiceStatus", message).commit()
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
