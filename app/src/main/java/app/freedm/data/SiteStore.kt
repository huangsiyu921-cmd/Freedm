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
    /** One-shot flag: the seed list below was replaced, so stored sites get reset. */
    private const val KEY_SEED_V2 = "quick_sites_seed_v2"

    /**
     * Seeds the list on first run (and once after the seed changed). Order is the
     * user's, and so are the tints: a card whose icon cannot be fetched should
     * still look like the site.
     */
    val defaults = listOf(
        QuickSite("百度", "https://www.baidu.com", 0xFF2A3DE0.toInt()),
        QuickSite("Bing", "https://cn.bing.com", 0xFF008373.toInt()),
        QuickSite("GitHub", "https://github.com", 0xFF24292F.toInt()),
        QuickSite("FreeDM", "https://github.com/huangsiyu921-cmd/Freedm", 0xFF2F6FEB.toInt()),
        QuickSite("bilibili", "https://m.bilibili.com", 0xFFFB7299.toInt()),
        QuickSite("Gmail", "https://mail.google.com", 0xFFEA4335.toInt()),
        QuickSite("Apple Music", "https://music.apple.com", 0xFFFA243C.toInt()),
    )

    /**
     * Replaces whatever is stored with [defaults], once. The seed list is a user
     * decision, so a stale list from an older build is not merged — it is dropped.
     */
    fun migrateSeed(prefs: SharedPreferences) {
        if (prefs.getBoolean(KEY_SEED_V2, false)) return
        prefs.edit().putBoolean(KEY_SEED_V2, true).apply()
        save(prefs, defaults)
    }

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
