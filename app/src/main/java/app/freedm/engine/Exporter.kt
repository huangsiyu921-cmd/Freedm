package app.freedm.engine

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import app.freedm.data.Repo
import java.io.File

/**
 * Optional post-download export into the shared Downloads collection.
 *
 * Segmented transfers need random access, which MediaStore cannot provide, so the
 * data always lands in the app-private directory first and is *copied out* once
 * complete. The private copy is removed only after a successful export, which
 * keeps the download resumable if the export fails.
 */
object Exporter {

    fun exportIfEnabled(context: Context, file: File, displayName: String) {
        if (!Repo.prefs().getBoolean("export_public", false)) return
        if (!file.exists() || file.length() == 0L) return

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                exportViaMediaStore(context, file, displayName)
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                dir.mkdirs()
                val target = File(dir, displayName)
                file.copyTo(target, overwrite = true)
                file.delete()
            }
        }
    }

    private fun exportViaMediaStore(context: Context, file: File, displayName: String) {
        val resolver = context.contentResolver
        val pending = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, pending)
        if (uri == null) {
            // Already-exported name collisions land here; keep the private copy.
            return
        }
        resolver.openOutputStream(uri)?.use { out ->
            file.inputStream().use { input -> input.copyTo(out) }
        } ?: return

        val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
        resolver.update(uri, done, null, null)
        file.delete()
    }
}
