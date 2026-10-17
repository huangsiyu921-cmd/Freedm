package app.freedm.engine

import app.freedm.data.ProbeResult
import java.io.UnsupportedEncodingException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

/**
 * Pre-transfer inspection: does the server support ranges, how big is the file,
 * what is a sensible file name, and what cache validator must be re-checked
 * before resuming.
 */
object Prober {

    private const val TIMEOUT = 20_000

    fun probe(rawUrl: String): ProbeResult {
        var conn = open(rawUrl, "HEAD", null)
        try {
            var code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                // Plenty of CDNs reject HEAD; fall back to a 1-byte ranged GET.
                conn = open(rawUrl, "GET", "bytes=0-0")
                code = conn.responseCode
            }
            if (code !in 200..399) error("HTTP $code")

            val contentRange = conn.getHeaderField("Content-Range")
            val acceptRanges = conn.getHeaderField("Accept-Ranges")?.contains("bytes", true) == true ||
                contentRange != null || code == 206

            val total = when {
                contentRange != null -> {
                    // e.g. "bytes 0-0/12345"
                    contentRange.substringAfterLast('/', "-").trim().toLongOrNull() ?: -1L
                }
                else -> conn.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
            }

            val mime = conn.contentType?.substringBefore(';')?.trim()
            return ProbeResult(
                finalUrl = conn.url.toString(),
                totalBytes = total,
                acceptRanges = acceptRanges && total > 0,
                etag = conn.getHeaderField("ETag"),
                lastModified = conn.getHeaderField("Last-Modified"),
                mime = mime,
                suggestedName = FileNamer.fromHeaders(
                    disposition = conn.getHeaderField("Content-Disposition"),
                    url = conn.url.toString(),
                    mime = mime,
                ),
            )
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private fun open(url: String, method: String, range: String?): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.instanceFollowRedirects = true
        c.connectTimeout = TIMEOUT
        c.readTimeout = TIMEOUT
        c.setRequestProperty("User-Agent", UA)
        if (range != null) c.setRequestProperty("Range", range)
        return c
    }

    const val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0 Mobile Safari/537.36 FreeDM/0.1"

    /** Used when the user asks the built-in browser to render as desktop. */
    const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0 Safari/537.36"
}

/** Derives a usable file name; never trusts the network blindly. */
object FileNamer {

    private val CT_EXT = mapOf(
        "video/mp4" to "mp4", "video/webm" to "webm", "video/x-matroska" to "mkv",
        "audio/mpeg" to "mp3", "audio/mp4" to "m4a", "audio/ogg" to "ogg",
        "application/pdf" to "pdf", "application/zip" to "zip",
        "application/vnd.apple.mpegurl" to "m3u8", "application/dash+xml" to "mpd",
        "application/octet-stream" to "bin", "text/plain" to "txt",
        "image/jpeg" to "jpg", "image/png" to "png", "image/webp" to "webp",
        "application/vnd.android.package-archive" to "apk",
    )

    fun fromHeaders(disposition: String?, url: String, mime: String?): String? {
        disposition?.let { d ->
            parseFilenameStar(d)?.let { return sanitize(it) }
            parseFilenameQuoted(d)?.let { return sanitize(it) }
        }
        val base = urlBaseName(url)?.let { sanitize(it) }
        if (base != null && base.contains('.') && base.length > 1) return base
        val ext = mime?.let { CT_EXT[it.substringBefore(';').trim()] }
        if (base != null) return if (ext != null && !base.endsWith(".$ext")) "$base.$ext" else base
        if (ext != null) return "download.$ext"
        return null
    }

    /** RFC 5987: filename*=UTF-8''percent-encoded */
    private fun parseFilenameStar(d: String): String? {
        val marker = "filename*="
        val i = d.indexOf(marker, ignoreCase = true)
        if (i < 0) return null
        var value = d.substring(i + marker.length).trimStart()
        value = value.substringBefore(';').trim().trim('"')
        val parts = value.split("'", limit = 3)
        if (parts.size == 3) {
            return try {
                URLDecoder.decode(parts[2], "UTF-8")
            } catch (_: UnsupportedEncodingException) {
                parts[2]
            }
        }
        return value
    }

    private fun parseFilenameQuoted(d: String): String? {
        val marker = "filename="
        val i = d.indexOf(marker, ignoreCase = true)
        if (i < 0) return null
        var value = d.substring(i + marker.length).trimStart()
        value = value.substringBefore(';').trim()
        return if (value.startsWith("\"")) value.trim('"') else value
    }

    private fun urlBaseName(url: String): String? {
        val path = url.substringBefore('#').substringBefore('?')
        val seg = path.substringAfterLast('/')
        return seg.ifBlank { null }
    }

    private val ILLEGAL = Regex("""[\\/:*?"<>|\u0000-\u001f]""")

    fun sanitize(name: String): String {
        var n = ILLEGAL.replace(name, "_").trim().trim('.')
        if (n.isEmpty()) n = "download"
        if (n.length > 120) {
            val dot = n.lastIndexOf('.')
            n = if (dot > 0) n.substring(0, 120 - (n.length - dot)) + n.substring(dot) else n.take(120)
        }
        return n
    }

    /** Appends " (1)", " (2)" … until the name is free inside [dir]. */
    fun uniqueIn(dir: java.io.File, name: String): String {
        if (!java.io.File(dir, name).exists()) return name
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (i < 1000) {
            val candidate = "$stem ($i)$ext"
            if (!java.io.File(dir, candidate).exists()) return candidate
            i++
        }
        return "$stem-$i$ext"
    }
}
