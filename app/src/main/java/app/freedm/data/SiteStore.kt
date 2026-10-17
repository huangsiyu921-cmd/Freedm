package app.freedm.data

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** One shortcut on the browser start page. */
data class QuickSite(
    val label: String,
    val url: String,
    /** ARGB card tint, usually taken from the site's own icon. */
    val color: Int,
)

/**
 * User-owned shortcut list. Stored as JSON in SharedPreferences so the start
 * page can be edited at runtime instead of shipping a fixed list.
 */
object SiteStore {

    private const val KEY = "quick_sites"

    /** Seeds the list on first run only; afterwards the user owns it. */
    val defaults = listOf(
        QuickSite("百度", "https://www.baidu.com", 0xFF2A3DE0.toInt()),
        QuickSite("Bing", "https://cn.bing.com", 0xFF008373.toInt()),
        QuickSite("哔哩哔哩", "https://m.bilibili.com", 0xFFFB7299.toInt()),
        QuickSite("知乎", "https://www.zhihu.com", 0xFF0084FF.toInt()),
        QuickSite("GitHub", "https://github.com", 0xFF24292F.toInt()),
        QuickSite("豆瓣", "https://m.douban.com", 0xFF2E963D.toInt()),
    )

    fun load(prefs: SharedPreferences): List<QuickSite> {
        val raw = prefs.getString(KEY, null) ?: return defaults
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                QuickSite(
                    label = o.optString("label"),
                    url = o.optString("url"),
                    color = o.optInt("color", 0xFF2F6FEB.toInt()),
                )
            }.filter { it.url.isNotBlank() }
        }.getOrDefault(defaults)
    }

    fun save(prefs: SharedPreferences, sites: List<QuickSite>) {
        val arr = JSONArray()
        sites.forEach { s ->
            arr.put(
                JSONObject().apply {
                    put("label", s.label)
                    put("url", s.url)
                    put("color", s.color)
                },
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }
}
