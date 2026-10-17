package app.freedm.browser

import org.json.JSONArray
import org.json.JSONObject

data class SniffedMedia(
    val url: String,
    val kind: Kind,
    val title: String,
    val fromNetwork: Boolean,
    val sizeHint: Long = -1L,
) {
    enum class Kind { VIDEO, AUDIO, HLS, DASH, FILE }

    val key: String get() = normalize(url)

    companion object {
        fun normalize(u: String): String {
            val noFrag = u.substringBefore('#')
            val base = noFrag.substringBefore('?')
            val query = noFrag.substringAfter('?', "")
            return if (query.isEmpty()) base else base + "?" + query.split('&').sorted().joinToString("&")
        }

        fun kindOf(url: String, mime: String?): Kind {
            val path = url.substringBefore('#').substringBefore('?').lowercase()
            val m = mime?.lowercase()?.substringBefore(';')?.trim()
            return when {
                path.endsWith(".m3u8") || m == "application/vnd.apple.mpegurl" ||
                    m == "application/x-mpegurl" -> Kind.HLS
                path.endsWith(".mpd") || m == "application/dash+xml" -> Kind.DASH
                m?.startsWith("video/") == true || VIDEO_EXT.any { path.endsWith(it) } -> Kind.VIDEO
                m?.startsWith("audio/") == true || AUDIO_EXT.any { path.endsWith(it) } -> Kind.AUDIO
                else -> Kind.FILE
            }
        }

        // `.m4s` is what Bilibili/YouTube-style DASH players request per fragment;
        // without it the network layer sees the busiest media traffic as "FILE".
        private val VIDEO_EXT = listOf(".mp4", ".m4s", ".webm", ".mkv", ".mov", ".flv", ".ts")
        private val AUDIO_EXT = listOf(".mp3", ".m4a", ".aac", ".ogg", ".opus", ".wav")
    }
}

/**
 * Collects media candidates from both the DOM and the network layer.
 *
 * The injected script is a compile-time constant — nothing is downloaded from a
 * server and no callback reports anywhere. This is a hard requirement of the
 * privacy baseline (see specs/behavior-spec.md §2.4).
 */
object Sniffer {

    val PROBE_JS = """
        (function () {
          var out = [];
          var push = function (u, t) { if (u) out.push({u: u, t: t || ''}); };
          document.querySelectorAll('video, audio').forEach(function (el) {
            var w = el.clientWidth || 0, h = el.clientHeight || 0;
            if (w > 100 && h > 100 && el.src) push(el.src, el.getAttribute('title') || '');
            el.querySelectorAll('source').forEach(function (s) { push(s.src, ''); });
            if (el.currentSrc) push(el.currentSrc, '');
          });
          document.querySelectorAll('a[href]').forEach(function (a) {
            var href = a.href || '';
            if (/\.(mp4|m4s|webm|mkv|mov|m3u8|mpd|mp3|m4a|aac|ogg|flv|ts)(\?|#|$)/i.test(href)) {
              push(href, (a.textContent || '').trim());
            }
          });
          var seen = {}, uniq = [];
          out.forEach(function (o) { if (!seen[o.u]) { seen[o.u] = 1; uniq.push(o); } });
          return JSON.stringify(uniq.slice(0, 50));
        })();
    """.trimIndent()

    /** Parses the string returned by [PROBE_JS] (WebView double-encodes it). */
    fun parseProbeResult(raw: String?): List<SniffedMedia> {
        if (raw.isNullOrBlank() || raw == "null") return emptyList()
        val json = runCatching {
            // evaluateJavascript returns a JSON-encoded JS string.
            val inner = if (raw.startsWith("\"")) JSONObject("{\"v\":$raw}").getString("v") else raw
            JSONArray(inner)
        }.getOrNull() ?: return emptyList()

        val out = ArrayList<SniffedMedia>(json.length())
        for (i in 0 until json.length()) {
            val o = json.optJSONObject(i) ?: continue
            val u = o.optString("u")
            if (u.isBlank()) continue
            out.add(
                SniffedMedia(
                    url = u,
                    kind = SniffedMedia.kindOf(u, null),
                    title = o.optString("t").ifBlank { u.substringAfterLast('/') },
                    fromNetwork = false,
                ),
            )
        }
        return out
    }
}
