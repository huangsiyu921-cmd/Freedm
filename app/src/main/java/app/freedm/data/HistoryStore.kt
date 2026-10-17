package app.freedm.data

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pages the browser has finished loading, newest first.
 *
 * Separate from the per-tab history stacks in `BrowseScreen`: those drive the
 * back/forward buttons of one tab and die with it, this is the cross-tab,
 * cross-session list the history page shows. Stored in prefs as JSON — small,
 * and already where the browser keeps its tabs.
 */
object HistoryStore {

    private const val KEY = "browse_history"

    /** Newest first. Hard cap so the list cannot grow without bound. */
    private const val MAX = 300

    data class Entry(val url: String, val title: String, val at: Long)

    fun load(prefs: SharedPreferences): List<Entry> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Entry(
                    url = o.optString("url"),
                    title = o.optString("title"),
                    at = o.optLong("at", 0L),
                )
            }.filter { it.url.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    /** Records a visit. Re-visiting a page moves it to the top instead of duplicating. */
    fun add(prefs: SharedPreferences, url: String, title: String) {
        if (url.isBlank()) return
        val rows = load(prefs)
            .filterNot { it.url == url }
            .toMutableList()
        rows.add(0, Entry(url, title.ifBlank { url }, System.currentTimeMillis()))
        save(prefs, rows.take(MAX))
    }

    fun remove(prefs: SharedPreferences, url: String) {
        save(prefs, load(prefs).filterNot { it.url == url })
    }

    fun clear(prefs: SharedPreferences) {
        prefs.edit().remove(KEY).apply()
    }

    private fun save(prefs: SharedPreferences, rows: List<Entry>) {
        val arr = JSONArray()
        rows.forEach { e ->
            arr.put(
                JSONObject().apply {
                    put("url", e.url)
                    put("title", e.title)
                    put("at", e.at)
                },
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }
}
