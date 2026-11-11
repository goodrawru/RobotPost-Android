package ru.robotpost.app

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** Local history metadata only. Media stays in the user-selected archive. */
object DownloadHistory {
    private const val KEY = "downloadHistory"
    fun sourceKey(source: String): String {
        val uri = runCatching { Uri.parse(source.trim()) }.getOrNull()
        val host = uri?.host.orEmpty().lowercase()
        val isYoutube = host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com")
        if (isYoutube && uri != null) {
            val path = uri.pathSegments
            val markerIndex = path.indexOfFirst { it in setOf("shorts", "embed", "live", "v") }
            val pathId = when {
                host == "youtu.be" -> path.firstOrNull()
                markerIndex >= 0 -> path.getOrNull(markerIndex + 1)
                else -> null
            }
            val id = uri.getQueryParameter("v")?.takeIf { it.isNotBlank() } ?: pathId
            if (!id.isNullOrBlank()) return "youtube:$id"
        }
        return "url:" + source.trim().substringBefore("#")
    }

    fun downloadedSourceKeys(context: Context): Set<String> =
        list(context).mapNotNull { item ->
            item.optString("source").takeIf { it.isNotBlank() }?.let(::sourceKey)
        }.toSet()

    fun add(context: Context, name: String, source: String, playlist: String = "") {
        val prefs = context.getSharedPreferences("robotpost", Context.MODE_PRIVATE)
        val previous = runCatching { JSONArray(prefs.getString(KEY, "[]")) }.getOrDefault(JSONArray())
        val next = JSONArray()
        next.put(JSONObject().put("name", name).put("source", source).put("playlist", playlist).put("savedAt", System.currentTimeMillis()))
        for (i in 0 until minOf(previous.length(), 99)) next.put(previous.get(i))
        prefs.edit().putString(KEY, next.toString()).apply()
    }
    fun list(context: Context): List<JSONObject> {
        val prefs = context.getSharedPreferences("robotpost", Context.MODE_PRIVATE)
        val data = runCatching { JSONArray(prefs.getString(KEY, "[]")) }.getOrDefault(JSONArray())
        return (0 until data.length()).mapNotNull { data.optJSONObject(it) }
    }
    fun clear(context: Context) {
        context.getSharedPreferences("robotpost", Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}
