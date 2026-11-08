package ru.robotpost.app

import android.content.Context
import android.net.Uri
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

object DownloadEngine {
    /** Translate common transport failures into actionable Russian messages. */
    fun readableError(error: Throwable): String {
        val raw = generateSequence(error) { it.cause }.mapNotNull { it.message }
            .joinToString(" | ")
        val lower = raw.lowercase(Locale.ROOT)
        return when {
            "no address associated with hostname" in lower ||
                "name or service not known" in lower ||
                "temporary failure in name resolution" in lower ||
                "nodename nor servname provided" in lower ->
                "DNS не может определить адрес YouTube. Проверьте интернет, VPN и частный DNS Android. " +
                    "Переподключите VPN и повторите загрузку."
            "timed out" in lower || "timeout" in lower ->
                "Превышено время ожидания YouTube. Проверьте VPN и соединение, затем повторите."
            "network is unreachable" in lower || "connection refused" in lower ->
                "Нет соединения с сервером YouTube. Проверьте сеть и VPN."
            else -> error.localizedMessage ?: "Неизвестная ошибка загрузки"
        }
    }
    private fun python(context: Context): Python {
        if (!Python.isStarted()) Python.start(AndroidPlatform(context.applicationContext))
        return Python.getInstance()
    }

    fun latest(context: Context, channelUrl: String): List<Pair<String, String>> {
        val json = org.json.JSONArray(python(context).getModule("downloader")
            .callAttr("latest_channel_videos", channelUrl).toString())
        return (0 until json.length()).map { i ->
            val item = json.getJSONObject(i)
            item.optString("title", "Видео") to item.getString("url")
        }
    }

    fun playlist(context: Context, url: String, limit: Int = 100): JSONObject =
        JSONObject(python(context).getModule("downloader").callAttr("inspect_playlist", url, limit).toString())

    fun inspect(context: Context, url: String): JSONObject =
        JSONObject(python(context).getModule("downloader").callAttr("inspect_video", url).toString())

    fun download(context: Context, url: String, root: Uri, formatId: String, onTemp: (File) -> Unit = {}, onProgress: (String) -> Unit = {}): String {
        val temp = File(context.cacheDir, "robotpost_" + UUID.randomUUID().toString()).apply { mkdirs() }
        onTemp(temp)
        try {
            val watcher = Thread {
                val status = File(temp, "progress.json")
                while (!Thread.currentThread().isInterrupted) {
                    try {
                        if (status.isFile) {
                            val data = JSONObject(status.readText())
                            val stage = if (data.optString("stage") == "audio") "Аудио" else "Видео"
                            val percent = data.optDouble("percent", -1.0)
                            val speed = data.optDouble("speed", 0.0) / 1048576.0
                            val message = if (percent >= 0) "$stage: ${percent.toInt()}% · ${"%.1f".format(Locale.ROOT, speed)} МБ/с" else "$stage: скачивание..."
                            onProgress(message)
                        }
                        Thread.sleep(500)
                    } catch (_: InterruptedException) {
                        break
                    } catch (_: Exception) { }
                }
            }.apply { isDaemon = true; start() }
            val json = try {
                python(context).getModule("downloader")
                    .callAttr("download_video", url, temp.absolutePath, formatId).toString()
            } finally { watcher.interrupt() }
            if (File(temp, "cancel.flag").exists()) error("Загрузка отменена пользователем")
            val info = JSONObject(json)
            val videoPath = info.optString("video")
            val audioPath = info.optString("audio")
            var video = videoPath.takeIf { it.isNotBlank() }?.let(::File)
            var audio = audioPath.takeIf { it.isNotBlank() }?.let(::File)
            if (info.optBoolean("merge")) {
                require(video != null && audio != null) { "Не хватает потоков для объединения" }
                video = Mp4Muxer.combine(video, audio, File(temp, "merged.mp4"))
            }
            if (video != null && audio == null) {
                audio = AudioExtractor.extract(video, File(temp, "audio.m4a"))
            }
            val stamp = info.optString("date")
            val date = if (Regex("[0-9]{8}").matches(stamp))
                "${stamp.substring(6, 8)}.${stamp.substring(4, 6)}.${stamp.substring(2, 4)}"
            else SimpleDateFormat("dd.MM.yy", Locale.ROOT).format(Date())
            if (File(temp, "cancel.flag").exists()) error("Загрузка отменена пользователем")
            return ArchiveExporter.export(
                context, root,
                info.optString("channel", "YouTube"), info.optString("title", "Видео"),
                date, cleanDescription(info.optString("description", "")),
                video?.let { Uri.fromFile(it) },
                audio?.let { Uri.fromFile(it) },
                "audio.m4a"
            )
        } finally {
            temp.listFiles()?.forEach { it.delete() }
            temp.delete()
        }
    }

    private fun cleanDescription(value: String): String =
        value.lines().filterNot {
            Regex("(?i)(реклама|спонсор|промокод|партн[её]рская ссылка|advertisement|sponsored|promo code|affiliate link)")
                .containsMatchIn(it)
        }.joinToString("\n").trim()
}
