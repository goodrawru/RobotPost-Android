package ru.robotpost.app

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Portable settings-only backup. Media files are never included. */
object BackupStore {
    private const val VERSION = 1
    private const val MAX_IMPORT = 2_000_000

    fun export(prefs: SharedPreferences): String {
        val channels = prefs.getString("channels", "").orEmpty().lines().filter { it.isNotBlank() }
        val links = prefs.getString("savedLinks", "").orEmpty().lines().filter { it.isNotBlank() }
        return JSONObject().apply {
            put("format", "robotpost-android")
            put("version", VERSION)
            put("channels", JSONArray(channels))
            put("savedLinks", JSONArray(links))
            put("description", prefs.getString("description", "").orEmpty())
            put("quality", prefs.getString("quality", "Лучшее доступное").orEmpty())
        }.toString(2)
    }

    fun import(prefs: SharedPreferences, json: String): Pair<Int, Int> {
        require(json.length <= MAX_IMPORT) { "Файл слишком большой" }
        val data = JSONObject(json)
        require(data.optString("format") == "robotpost-android") { "Неверный формат архива" }
        require(data.optInt("version") == VERSION) { "Неподдерживаемая версия архива" }
        val channelsArray = data.getJSONArray("channels")
        val linksArray = data.getJSONArray("savedLinks")
        require(channelsArray.length() <= 500 && linksArray.length() <= 5000) { "Слишком много записей" }
        val channels = (0 until channelsArray.length()).map { channelsArray.getString(it) }
            .filter { it.startsWith("https://") && (it.contains("youtube.com/") || it.contains("youtu.be/")) }
            .distinct()
        val links = (0 until linksArray.length()).map { linksArray.getString(it) }
            .filter { it.startsWith("https://") && (it.contains("youtube.com/") || it.contains("youtu.be/")) }
            .distinct()
        val description = data.optString("description", "")
        require(description.length <= 200_000) { "Описание слишком длинное" }
        val quality = data.optString("quality", "Лучшее доступное")
        require(quality in listOf("Лучшее доступное", "1080p", "720p", "480p")) { "Некорректное качество" }
        check(prefs.edit()
            .putString("channels", channels.joinToString("\n"))
            .putString("savedLinks", links.joinToString("\n"))
            .putString("description", description)
            .putString("quality", quality)
            .commit()) { "Не удалось записать настройки" }
        return channels.size to links.size
    }
}
