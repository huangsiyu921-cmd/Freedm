package app.freedm.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import app.freedm.MainActivity
import app.freedm.R
import java.io.File

/**
 * Terminal-state notifications (completed / failed).
 *
 * The progress channel is handled by [DownloadService]; this one fires once per
 * task when it leaves the active set.
 */
object Notifier {

    fun completed(context: Context, taskId: Long, fileName: String, path: String) {
        val requestCode = taskId.toInt()
        val open = runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(path))
            PendingIntent.getActivity(
                context,
                requestCode,
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, guessMime(fileName))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }.getOrNull()

        val n = Notification.Builder(context, DownloadService.CH_DONE)
            .setContentTitle("下载完成")
            .setContentText(fileName)
            .setSmallIcon(R.drawable.ic_launcher)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        // Keyed by task id so two files with the same name never overwrite each other.
        post(context, requestCode, n)
    }

    fun failed(context: Context, taskId: Long, fileName: String, error: String?) {
        val requestCode = taskId.toInt()
        val retry = PendingIntent.getActivity(
            context,
            requestCode,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(context, DownloadService.CH_DONE)
            .setContentTitle("下载失败")
            .setContentText("$fileName${if (error != null) " · $error" else ""}")
            .setSmallIcon(R.drawable.ic_launcher)
            .setAutoCancel(true)
            .setContentIntent(retry)
            .build()
        post(context, requestCode, n)
    }

    private fun post(context: Context, id: Int, n: Notification) {
        runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(id, n)
        }
    }

    private fun guessMime(name: String): String =
        when (name.substringAfterLast('.', "").lowercase()) {
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            "apk" -> "application/vnd.android.package-archive"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            else -> "application/octet-stream"
        }

    /** Exposes a private file to the system viewer. */
    fun providerUri(context: Context, file: File): Uri? = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.getOrNull()
}
