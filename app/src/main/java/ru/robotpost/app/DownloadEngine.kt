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
    private fun python(context: Context): Python {
        if (!Python.isStarted()) Python.start(AndroidPlatform(context.applicationContext))
        return Python.getInstance()
    }

    fun inspect(context: Context, url: String): JSONObject =
        JSONObject(python(context).getModule("downloader").callAttr("inspect_video", url).toString())

    fun download(context: Context, url: String, root: Uri, formatId: String): String {
        val temp = File(context.cacheDir, "robotpost_" + UUID.randomUUID().toString()).apply { mkdirs() }
        try {
            val json = python(context).getModule("downloader")
                .callAttr("download_video", url, temp.absolutePath, formatId).toString()
            val info = JSONObject(json)
            val videoPath = info.optString("video")
            val audioPath = info.optString("audio")
            var video = videoPath.takeIf { it.isNotBlank() }?.let(::File)
            val audio = audioPath.takeIf { it.isNotBlank() }?.let(::File)
            if (info.optBoolean("merge")) {
                require(video != null && audio != null) { "Не хватает потоков для объединения" }
                video = Mp4Muxer.combine(video, audio, File(temp, "merged.mp4"))
            }
            val stamp = info.optString("date")
            val date = if (Regex("[0-9]{8}").matches(stamp))
                "${stamp.substring(6, 8)}.${stamp.substring(4, 6)}.${stamp.substring(2, 4)}"
            else SimpleDateFormat("dd.MM.yy", Locale.ROOT).format(Date())
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
