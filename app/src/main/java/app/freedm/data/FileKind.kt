package app.freedm.data

import java.util.Locale

/** File category used for the list icon and optional auto-filing. */
enum class FileKind(val folder: String) {
    SUBTITLE("Subtitles"),
    COMPRESSED("Compressed"),
    DOCUMENT("Documents"),
    MUSIC("Music"),
    VIDEO("Video"),
    PHOTO("Pictures"),
    PROGRAM("Programs"),
    TORRENT("Torrents"),
    OTHER("Other"),
}

/**
 * Extension-set based classification, with an MIME fallback.
 *
 * Decision order matters: subtitle first (`.ass` collides with other families),
 * audio before video (`.m4a`/`.mp4` share lineage), then the rest.
 */
object FileKinds {

    private val SUBTITLE = setOf("srt", "vtt", "sub", "ttml", "sbv", "ssa", "ass")

    private val COMPRESSED = setOf(
        "xz", "cab", "7z", "ace", "alz", "at3", "arc", "arj", "bzip2", "deb", "gho",
        "gzip", "gz", "lha", "lz", "lzma", "lzo", "lzx", "rar", "rpm", "tar", "tgz", "tb", "bz2", "zst", "lz4",
        "uha", "uue", "zoo", "zip", "obb", "pak", "pk3", "pk4", "par", "isz", "z",
    )

    private val DOCUMENT = setOf(
        "csv", "doc", "docm", "docx", "dot", "dotx", "epub", "gdoc", "hwp", "md",
        "mobi", "odm", "odt", "ott", "pdf", "rtf", "tex", "txt", "wpd", "wps", "wpt",
        "xml", "xps", "ods", "ots", "slk", "xls", "xlsb", "xlsm", "xlsx", "xlt",
        "xltm", "pptx", "ppt", "odp", "otp", "key", "pages", "numbers", "gsheet",
    )

    private val MUSIC = setOf(
        "aac", "aax", "aiff", "amr", "ape", "awb", "flac", "gsm", "m4a", "m4b", "m4p",
        "mmf", "mp3", "mpc", "msv", "ogg", "oga", "opus", "ra", "tta", "vox", "wav",
        "wma", "mid", "midi", "dsf", "dff",
    )

    private val VIDEO = setOf(
        "divx", "webm", "mkv", "flv", "vob", "ogv", "avi", "mov", "qt", "wmv", "rm",
        "rmvb", "asf", "amv", "mp4", "mpg", "mp2", "mpeg", "mpe", "mpv", "m2v", "m4v",
        "svi", "3gp", "3g2", "mxf", "nsv", "f4v", "ts", "m3u8", "mpd",
    )

    private val PHOTO = setOf(
        "bmp", "gif", "jpeg", "jfif", "jpg", "png", "webp", "svg", "tiff", "tif",
        "heic", "heif", "avif", "ico", "psd", "cr2", "dng", "raw", "jng", "jp2",
    )

    private val PROGRAM = setOf(
        "apk", "apks", "apkm", "xapk", "aab", "ipa", "exe", "msi", "jar", "so", "elf",
        "bin", "class", "dex", "py", "pyc", "sh", "bat", "cmd", "ps1", "js", "lua",
        "rb", "vbs", "war", "ear", "deb", "rpm", "dmg", "pkg", "app", "aout",
    )

    private val TORRENT = setOf("torrent")

    private val DISK_IMAGE = setOf("iso", "img", "dmg", "vhd", "vhdx")

    fun of(fileName: String, mime: String? = null): FileKind {
        val ext = extensionOf(fileName)
        if (ext != null) {
            byExtension(ext)?.let { return it }
            if (ext in DISK_IMAGE) return FileKind.OTHER
        }
        // Fallback: MIME major type.
        val m = mime?.lowercase(Locale.US)?.substringBefore(';')?.trim()
        return when {
            m == null -> FileKind.OTHER
            m == "application/vnd.apple.mpegurl" || m == "application/dash+xml" -> FileKind.VIDEO
            m.startsWith("audio/") -> FileKind.MUSIC
            m.startsWith("video/") -> FileKind.VIDEO
            m.startsWith("image/") -> FileKind.PHOTO
            m.startsWith("text/") -> FileKind.DOCUMENT
            m == "application/pdf" -> FileKind.DOCUMENT
            m == "application/zip" || m == "application/x-tar" ||
                m == "application/gzip" || m == "application/x-7z-compressed" -> FileKind.COMPRESSED
            m == "application/vnd.android.package-archive" -> FileKind.PROGRAM
            m == "application/x-bittorrent" -> FileKind.TORRENT
            else -> FileKind.OTHER
        }
    }

    private fun byExtension(ext: String): FileKind? = when {
        ext in SUBTITLE -> FileKind.SUBTITLE
        ext in COMPRESSED -> FileKind.COMPRESSED
        ext in DOCUMENT -> FileKind.DOCUMENT
        ext in MUSIC -> FileKind.MUSIC
        ext in VIDEO -> FileKind.VIDEO
        ext in PHOTO -> FileKind.PHOTO
        ext in PROGRAM -> FileKind.PROGRAM
        ext in TORRENT -> FileKind.TORRENT
        else -> null
    }

    private fun extensionOf(name: String): String? {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return null
        return name.substring(dot + 1).lowercase(Locale.US)
    }
}
