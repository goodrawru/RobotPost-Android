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

object DownloadEngine {
    fun download(context: Context, url: String, root: Uri): String {
        if (!Python.isStarted()) Python.start(AndroidPlatform(context))
        val temp = File(context.cacheDir, "robotpost_downloads").apply { mkdirs() }
        val json = Python.getInstance().getModule("downloader")
            .callAttr("download_video", url, temp.absolutePath).toString()
        val info = JSONObject(json)
        val file = File(info.getString("path"))
        val stamp = info.optString("date")
        val date = if (Regex("[0-9]{8}").matches(stamp))
            "${stamp.substring(6, 8)}.${stamp.substring(4, 6)}.${stamp.substring(2, 4)}"
        else SimpleDateFormat("dd.MM.yy", Locale.ROOT).format(Date())
        val result = ArchiveExporter.export(
            context, root, info.optString("channel", "YouTube"),
            info.optString("title", "Видео"), date,
            cleanDownloadDescription(info.optString("description", "")),
            Uri.fromFile(file), null
        )
        file.delete()
        return result
    }

    private fun cleanDownloadDescription(value: String): String =
        value.lines().filterNot {
            Regex("(?i)(реклама|спонсор|промокод|партн[её]рская ссылка|advertisement|sponsored|promo code|affiliate link)")
                .containsMatchIn(it)
        }.joinToString("\n").trim()
}
