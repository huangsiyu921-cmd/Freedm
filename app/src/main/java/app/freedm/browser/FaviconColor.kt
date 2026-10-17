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
        hsv[1] = hsv[1].coerceAtLeast(0.45f)
        hsv[2] = hsv[2].coerceIn(0.55f, 0.85f)
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
     * Material tonal treatment of a brand colour: keep the hue, fix chroma and
     * tone. That is what makes cards from wildly different logos sit together.
     */
    fun containerColor(argb: Int, dark: Boolean): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(argb, hsv)
        // Keep the logo's hue and most of its chroma; only pin the tone so a
        // white logo and a black logo both end up usable. Tuned bright enough
        // that a card still reads as "that site's colour".
        hsv[1] = if (dark) 0.55f else 0.42f
        hsv[2] = if (dark) 0.62f else 0.90f
        return Color.HSVToColor(hsv)
    }

    /** On-colour paired with [containerColor]. */
    fun onContainerColor(argb: Int, dark: Boolean): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(argb, hsv)
        hsv[1] = if (dark) 0.12f else 0.62f
        hsv[2] = if (dark) 0.97f else 0.16f
        return Color.HSVToColor(hsv)
    }

    /** Fallback tint for sites whose icon cannot be read. */
    fun fromString(seed: String): Int {
        val hsv = floatArrayOf((seed.hashCode() and 0x7FFFFFFF) % 360f, 0.55f, 0.72f)
        return Color.HSVToColor(hsv)
    }
}
