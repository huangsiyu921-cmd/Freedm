package app.freedm.ui

import java.util.Locale

/** Human-readable byte size, e.g. "1.2 GB". */
fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "—"
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var i = 0
    while (value >= 1024 && i < units.lastIndex) {
        value /= 1024
        i++
    }
    return if (value >= 100) {
        String.format(Locale.US, "%.0f %s", value, units[i])
    } else {
        String.format(Locale.US, "%.1f %s", value, units[i])
    }
}

/** Human-readable remaining time, e.g. "1 分 20 秒". */
fun formatEta(remainingBytes: Long, bytesPerSec: Long): String {
    if (bytesPerSec <= 0 || remainingBytes <= 0) return ""
    val secs = remainingBytes / bytesPerSec
    return when {
        secs < 60 -> "$secs 秒"
        secs < 3600 -> "${secs / 60} 分 ${secs % 60} 秒"
        else -> "${secs / 3600} 时 ${(secs % 3600) / 60} 分"
    }
}

/** Extracts the first http(s) URL found in free text, or null. */
fun firstUrl(text: String?): String? {
    if (text.isNullOrBlank()) return null
    val r = Regex("""https?://[^\s"'<>]+""")
    return r.find(text)?.value?.trimEnd('.', ',', ')', ']', '"', '\'')
}
