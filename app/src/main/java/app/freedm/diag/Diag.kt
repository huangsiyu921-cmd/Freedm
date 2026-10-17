package app.freedm.diag

import android.content.Context
import app.freedm.data.Repo
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Local-only diagnostic log, on in release builds too.
 *
 * Location: `Android/data/<package>/files/logs/diag.log` — reachable over USB or
 * from a file manager, and never uploaded anywhere.
 *
 * Privacy rules (must not be relaxed):
 *  - record event names, hosts, counts, error codes — **never** query strings,
 *    cookies, response bodies or any device identifier;
 *  - one 512 KiB file plus one rotated copy, so it cannot grow without bound;
 *  - writes go through a single background thread: WebView callbacks never block
 *    on the file system.
 */
object Diag {

    private const val MAX_BYTES = 512 * 1024L
    private const val FILE_NAME = "diag.log"
    private const val ROTATED = "diag.1.log"
    private const val MAX_DETAIL = 240

    @Volatile
    private var file: File? = null

    @Volatile
    private var enabled = true

    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "freedm-diag").apply { isDaemon = true }
    }

    private val stamp = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    }

    /** Call once after `Repo.init`; the directory is created lazily on first write. */
    fun init(context: Context) {
        val base = context.applicationContext.getExternalFilesDir(null)
            ?: context.applicationContext.filesDir
        file = File(File(base, "logs"), FILE_NAME)
        enabled = runCatching { Repo.prefs().getBoolean(PREF_ENABLED, true) }.getOrDefault(true)
    }

    fun isEnabled(): Boolean = enabled

    fun setEnabled(on: Boolean) {
        enabled = on
        runCatching { Repo.prefs().edit().putBoolean(PREF_ENABLED, on).apply() }
    }

    /** Absolute path shown in settings; null before [init]. */
    fun location(): String? = file?.absolutePath

    fun sizeBytes(): Long = runCatching { file?.takeIf { it.isFile }?.length() ?: 0L }.getOrDefault(0L)

    /**
     * Lower-cased host of [url], for log lines.
     *
     * Duplicated from `AdBlocker` on purpose: the engine should not have to depend
     * on the browser package just to print a host name.
     */
    fun hostOf(url: String?): String? {
        if (url.isNullOrBlank()) return null
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

    /**
     * Appends one line. [detail] must already be free of anything sensitive —
     * callers pass hosts, counts and error codes only.
     */
    fun log(event: String, detail: String = "") {
        if (!enabled) return
        val line = buildString {
            append(stamp.get()?.format(Date()) ?: "")
            append("  ")
            append(event)
            if (detail.isNotEmpty()) {
                append("  ")
                append(if (detail.length > MAX_DETAIL) detail.take(MAX_DETAIL) else detail)
            }
            append('\n')
        }
        runCatching { writer.execute { append(line) } }
    }

    /** Deletes both the live file and its rotated copy. */
    fun clear() {
        runCatching {
            writer.execute {
                val f = file ?: return@execute
                runCatching { f.delete() }
                runCatching { File(f.parentFile, ROTATED).delete() }
            }
        }
    }

    private fun append(line: String) {
        val f = file ?: return
        runCatching {
            f.parentFile?.mkdirs()
            if (f.length() > MAX_BYTES) {
                val old = File(f.parentFile, ROTATED)
                runCatching { old.delete() }
                runCatching { f.renameTo(old) }
            }
            f.appendText(line)
        }
    }

    const val PREF_ENABLED = "diag_log"
}
