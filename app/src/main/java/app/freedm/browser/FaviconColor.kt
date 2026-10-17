package app.freedm.browser

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max
import kotlin.math.min

/**
 * Picks a representative colour out of a site's own icon, so shortcut cards are
 * tinted by the site rather than by a hand-written palette.
 *
 * Deliberately dependency-free: a 24×24 rescale plus a hue histogram is enough
 * for a card background, and it keeps the APK free of the Palette library.
 */
object FaviconColor {

    /** Returns an ARGB colour for [host], or null when no icon could be read. */
    fun fetch(host: String, userAgent: String): Int? {
        val candidates = listOf(
            "https://$host/favicon.ico",
            "https://$host/apple-touch-icon.png",
            "https://$host/apple-touch-icon-precomposed.png",
        )
        for (url in candidates) {
            val bmp = download(url, userAgent) ?: continue
            dominant(bmp)?.let { return it }
        }
        return null
    }

    private fun download(url: String, userAgent: String): Bitmap? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6000
            readTimeout = 6000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", userAgent)
        }
        try {
            val bytes = conn.inputStream.use { it.readBytes() }
            if (bytes.size > 512 * 1024) return@runCatching null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } finally {
            runCatching { conn.disconnect() }
        }
    }.getOrNull()

    private fun dominant(src: Bitmap): Int? {
        val scaled = Bitmap.createScaledBitmap(src, 24, 24, true)
        val buckets = Array(12) { IntArray(4) } // r, g, b, count
        val hsv = FloatArray(3)
        for (y in 0 until scaled.height) {
            for (x in 0 until scaled.width) {
                val c = scaled.getPixel(x, y)
                if (Color.alpha(c) < 200) continue
                val r = Color.red(c)
                val g = Color.green(c)
                val b = Color.blue(c)
                val mx = max(r, max(g, b))
                val mn = min(r, min(g, b))
                if (mx < 40) continue
                val sat = if (mx == 0) 0f else (mx - mn).toFloat() / mx
                if (sat < 0.15f) continue // skip near-black / near-grey pixels
                Color.RGBToHSV(r, g, b, hsv)
                val idx = (hsv[0] / 30f).toInt().coerceIn(0, 11)
                buckets[idx][0] += r
                buckets[idx][1] += g
                buckets[idx][2] += b
                buckets[idx][3] += 1
            }
        }
        val best = buckets.filter { it[3] > 0 }.maxByOrNull { it[3] } ?: return null
        val n = best[3]
        Color.RGBToHSV(best[0] / n, best[1] / n, best[2] / n, hsv)
        // Lift saturation/value so the colour survives as a dark-theme card.
        hsv[1] = hsv[1].coerceIn(0.5f, 1f)
        hsv[2] = hsv[2].coerceIn(0.5f, 0.92f)
        return Color.HSVToColor(hsv)
    }

    /** Black or white, whichever reads better on [argb]. */
    fun onColor(argb: Int): Int {
        val r = Color.red(argb) / 255f
        val g = Color.green(argb) / 255f
        val b = Color.blue(argb) / 255f
        val luminance = 0.2126f * r + 0.7152f * g + 0.0722f * b
        return if (luminance > 0.55f) 0xFF14161B.toInt() else 0xFFFFFFFF.toInt()
    }

    /**
     * Keep the site's own colour. Only clamp the extremes so a near-black logo
     * still works on a dark theme and a near-white one on a light theme — the
     * previous version pinned chroma/tone, which flattened 知乎蓝 and 豆瓣绿 into
     * the same slate blue.
     */
    fun containerColor(argb: Int, dark: Boolean): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(argb, hsv)
        if (dark) {
            hsv[1] = hsv[1].coerceIn(0.45f, 0.95f)
            hsv[2] = hsv[2].coerceIn(0.42f, 0.68f)
        } else {
            hsv[1] = hsv[1].coerceIn(0.35f, 0.85f)
            hsv[2] = hsv[2].coerceIn(0.72f, 0.95f)
        }
        return Color.HSVToColor(hsv)
    }

    /** Text colour that actually contrasts with [containerColor]. */
    fun onContainerColor(argb: Int, dark: Boolean): Int = onColor(containerColor(argb, dark))

    /**
     * Fallback tint for sites whose icon cannot be read. Deliberately neutral:
     * a hashed hue made 知乎 and GitHub cards come out green/teal, which reads as
     * "wrong brand colour" instead of "no data".
     */
    fun fromString(seed: String): Int {
        val hsv = floatArrayOf((seed.hashCode() and 0x7FFFFFFF) % 40f + 200f, 0.22f, 0.55f)
        return Color.HSVToColor(hsv)
    }

    /**
     * Brand colours for sites whose icon is monochrome, so no colour can be
     * sampled from it — better than showing a hashed hue.
     */
    fun brand(host: String): Int? = when {
        host.endsWith("github.com") -> 0xFF24292F.toInt()
        host.contains("zhihu.com") -> 0xFF0084FF.toInt()
        host.contains("bilibili.com") -> 0xFFFB7299.toInt()
        host.contains("douban.com") -> 0xFF2E963D.toInt()
        host.contains("baidu.com") -> 0xFF2932E1.toInt()
        host.contains("bing.com") -> 0xFF008373.toInt()
        host.contains("google.com") -> 0xFF4285F4.toInt()
        host.contains("youtube.com") -> 0xFFFF0000.toInt()
        host.contains("weibo.com") -> 0xFFE6162D.toInt()
        host.contains("taobao.com") || host.contains("tmall.com") -> 0xFFFF5000.toInt()
        host.contains("jd.com") -> 0xFFE1251B.toInt()
        host.contains("qq.com") -> 0xFF12B7F5.toInt()
        else -> null
    }
}
