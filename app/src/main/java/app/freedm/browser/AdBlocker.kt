package app.freedm.browser

import android.content.Context
import android.webkit.WebResourceResponse
import app.freedm.data.Repo
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * Host-based request blocker for the built-in browser.
 *
 * Design constraints:
 *  - The rule list ships inside the APK (`assets/adblock_hosts.txt`) and is never
 *    fetched at runtime — the app makes no request the user did not ask for.
 *  - Matching is plain host matching with parent-domain fallback: a rule for
 *    `example.com` also covers `ads.example.com`, and nothing else. No regexes,
 *    no path patterns, so a rule can never silently eat a whole site.
 *  - The document itself is never blocked (see [shouldBlock]); only subresources.
 *  - Per-site opt-out lives in the `adblock_allow` set, so a site that breaks can
 *    be repaired from the browser menu without editing the list.
 */
object AdBlocker {

    private const val ASSET = "adblock_hosts.txt"
    const val PREF_ENABLED = "adblock"
    const val PREF_ALLOW = "adblock_allow"

    private val rules = HashSet<String>(1024)
    private val blocked = AtomicLong(0)
    private val emptyBody = ByteArray(0)

    @Volatile
    private var loaded = false

    @Volatile
    private var allowCache: Set<String>? = null

    /** Reads the bundled rule list; safe to call from any thread, does work once. */
    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            runCatching {
                context.applicationContext.assets.open(ASSET).bufferedReader().useLines { lines ->
                    lines.forEach { raw ->
                        val line = raw.substringBefore('#').trim().lowercase()
                        if (line.isNotEmpty()) rules.add(line)
                    }
                }
            }
            loaded = true
        }
    }

    fun ruleCount(): Int = rules.size

    fun isEnabled(): Boolean = Repo.prefs().getBoolean(PREF_ENABLED, true)

    fun setEnabled(enabled: Boolean) {
        Repo.prefs().edit().putBoolean(PREF_ENABLED, enabled).apply()
    }

    fun blockedCount(): Long = blocked.get()

    fun resetCount() {
        blocked.set(0)
    }

    /** True when the browser menu has whitelisted this site. */
    fun isSiteAllowed(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val allow = allowSet()
        if (allow.isEmpty()) return false
        var h = host.lowercase()
        while (true) {
            if (h in allow) return true
            val dot = h.indexOf('.')
            if (dot < 0) return false
            h = h.substring(dot + 1)
        }
    }

    fun setSiteAllowed(host: String, allowed: Boolean) {
        val key = host.lowercase().trim()
        if (key.isEmpty()) return
        val prefs = Repo.prefs()
        val set = (prefs.getStringSet(PREF_ALLOW, null) ?: emptySet()).toMutableSet()
        if (allowed) set.add(key) else set.remove(key)
        prefs.edit().putStringSet(PREF_ALLOW, set).apply()
        allowCache = set
    }

    /**
     * Decides whether [url] should be answered with an empty body.
     *
     * [pageHost] is the page the request belongs to: a whitelisted page suspends
     * blocking for everything it loads, which is the only way to keep third-party
     * widgets of a site the user cares about working.
     */
    fun shouldBlock(url: String, pageHost: String?): Boolean {
        if (!isEnabled()) return false
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        val host = hostOf(url) ?: return false
        if (isSiteAllowed(pageHost ?: host)) return false
        if (!matches(host)) return false
        blocked.incrementAndGet()
        return true
    }

    /** Empty 200-equivalent response: the request is fulfilled with nothing. */
    fun emptyResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(emptyBody))

    /** Lower-cased host of [url], or null when it has none. */
    fun hostOf(url: String): String? {
        val start = url.indexOf("://")
        if (start <= 0) return null
        var rest = url.substring(start + 3)
        val end = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        if (end >= 0) rest = rest.substring(0, end)
        val at = rest.lastIndexOf('@')
        if (at >= 0) rest = rest.substring(at + 1)
        val colon = rest.indexOf(':')
        if (colon >= 0) rest = rest.substring(0, colon)
        return rest.lowercase().takeIf { it.isNotEmpty() }
    }

    private fun matches(host: String): Boolean {
        if (!loaded) return false
        var h = host
        while (true) {
            if (h in rules) return true
            val dot = h.indexOf('.')
            if (dot < 0) return false
            h = h.substring(dot + 1)
        }
    }

    private fun allowSet(): Set<String> {
        allowCache?.let { return it }
        val set = Repo.prefs().getStringSet(PREF_ALLOW, null)?.toHashSet() ?: emptySet()
        allowCache = set
        return set
    }
}
