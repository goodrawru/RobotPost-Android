package ru.robotpost.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Local history metadata only. Media stays in the user-selected archive. */
object DownloadHistory {
    private const val KEY = "downloadHistory"
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
