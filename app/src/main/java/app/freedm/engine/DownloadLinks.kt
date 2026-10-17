package app.freedm.engine

/**
 * Extensions that can only be meant as a file.
 *
 * Used in two places: the browser deciding whether a tapped link should become a
 * download task instead of a page load, and the app entry point deciding where an
 * external link goes (browser for pages, download queue for files).
 */
private val DOWNLOAD_EXT = setOf(
    "apk", "zip", "7z", "rar", "tar", "gz", "bz2", "xz", "iso", "img", "exe", "msi",
    "dmg", "pkg", "deb", "rpm", "torrent",
    "mp4", "mkv", "avi", "mov", "webm", "flv", "mp3", "flac", "wav", "m4a", "ogg",
    "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "epub",
)

fun looksLikeDownload(url: String): Boolean {
    val name = url.substringBefore('?').substringBefore('#').substringAfterLast('/')
    val ext = name.substringAfterLast('.', "").lowercase()
    return ext.isNotEmpty() && ext in DOWNLOAD_EXT
}
