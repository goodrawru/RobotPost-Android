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

data class LatestVideo(val title: String, val url: String, val publishedAt: String)

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

    fun latest(context: Context, channelUrl: String): List<LatestVideo> {
        val json = org.json.JSONArray(python(context).getModule("downloader")
            .callAttr("latest_channel_videos", channelUrl).toString())
        return (0 until json.length()).map { i ->
            val item = json.getJSONObject(i)
            LatestVideo(
                title = item.optString("title", "Видео"),
                url = item.getString("url"),
                publishedAt = item.optString("published_at", "")
            )
        }
    }

    fun playlist(context: Context, url: String, limit: Int = 100): JSONObject =
        JSONObject(python(context).getModule("downloader").callAttr("inspect_playlist", url, limit).toString())

    fun inspect(context: Context, url: String): JSONObject =
        JSONObject(python(context).getModule("downloader").callAttr("inspect_video", url).toString())

    fun download(context: Context, url: String, root: Uri, formatId: String, onTemp: (File) -> Unit = {}, onProgress: (String) -> Unit = {}, includeDescription: Boolean = true, videoOnly: Boolean = false, playlistName: String = "", playlistIndex: Int = 0, saveMode: String = "full"): String {
        require(saveMode in setOf("full", "video", "video_description", "audio")) {
            "Неизвестный режим сохранения"
        }
        if (saveMode in setOf("video", "video_description") && formatId.substringAfter("|").startsWith("audio:"))
            error("Для выбранного режима нужен видеоформат. Выберите MP4 в списке качества.")
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
            if (!videoOnly && playlistName.isBlank() && saveMode != "video_description" && saveMode != "video" && video != null && audio == null) {
                audio = AudioExtractor.extract(video, File(temp, "audio.m4a"))
            }
            val stamp = info.optString("date")
            val date = if (Regex("[0-9]{8}").matches(stamp))
                "${stamp.substring(6, 8)}.${stamp.substring(4, 6)}.${stamp.substring(2, 4)}"
            else SimpleDateFormat("dd.MM.yy", Locale.ROOT).format(Date())
            if (File(temp, "cancel.flag").exists()) error("Загрузка отменена пользователем")
            if (playlistName.isNotBlank()) {
                require(video != null) { "Для режима только видео нужен видеопоток" }
                return ArchiveExporter.exportPlaylistVideo(
                    context, root, playlistName, info.optString("title", "Видео"),
                    playlistIndex, Uri.fromFile(video)
                )
            }
            val exportVideo = if (saveMode == "audio") null else video
            val exportAudio = if (saveMode == "video" || saveMode == "video_description") null else audio
            require(exportVideo != null || exportAudio != null) { "Выбранный формат не содержит нужного медиапотока" }
            return ArchiveExporter.export(
                context, root,
                info.optString("channel", "YouTube"), info.optString("title", "Видео"),
                date, buildTxtDescription(info),
                exportVideo?.let { Uri.fromFile(it) },
                exportAudio?.let { Uri.fromFile(it) },
                "audio.m4a",
                includeDescription && saveMode != "video" && saveMode != "audio",
                videoOnly || saveMode == "video"
            )
        } finally {
            temp.listFiles()?.forEach { it.delete() }
            temp.delete()
        }
    }

    /** Strict TXT: title, @minaevcast, and only source-provided timestamps. */
    private fun buildTxtDescription(info: JSONObject): String {
        val title = info.optString("title", "Видео").trim().ifBlank { "Видео" }
        val chapters = info.optJSONArray("chapters")
        val timestamps = mutableListOf<String>()
        if (chapters != null) {
            for (i in 0 until chapters.length()) {
                val chapter = chapters.optJSONObject(i) ?: continue
                val seconds = chapter.optDouble("start_time", -1.0)
                val name = chapter.optString("title").trim()
                if (!seconds.isFinite() || seconds < 0 || name.isBlank()) continue
                val total = seconds.toLong()
                val stamp = if (total >= 3600)
                    "%02d:%02d:%02d".format(Locale.ROOT, total / 3600, (total / 60) % 60, total % 60)
                else "%02d:%02d".format(Locale.ROOT, total / 60, total % 60)
                timestamps.add("$stamp — $name")
            }
        }
        if (timestamps.isEmpty()) {
            val pattern = Regex("""^\s*((?:\d{1,2}:)?\d{1,2}:\d{2})\s*[-–—.:)]?\s*(\S.*)$""")
            for (line in info.optString("description", "").lines()) {
                val match = pattern.find(line) ?: continue
                timestamps.add(match.groupValues[1] + " — " + match.groupValues[2].trim())
            }
        }
        return listOf(title, "@minaevcast").joinToString("\n\n") +
            if (timestamps.isEmpty()) "" else "\n\n" + timestamps.distinct().joinToString("\n")
    }
}
