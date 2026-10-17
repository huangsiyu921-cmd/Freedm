package app.freedm.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import app.freedm.MainActivity
import app.freedm.R
import app.freedm.data.Repo
import app.freedm.data.TaskState
import app.freedm.engine.Engine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps the process alive while transfers run and mirrors progress into the
 * notification shade. No network access of its own.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null
    private lateinit var nm: NotificationManager

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createChannels()
        startForeground(FOREGROUND_ID, buildSummary())
        observer = scope.launch {
            Engine.progress.collectLatest { updateNotification() }
        }
        scope.launch {
            Repo.tasks.collectLatest { updateNotification() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE_ALL -> {
                Engine.pauseAll()
                stopSelfIfIdle()
            }
            else -> updateNotification()
        }
        return START_STICKY
    }

    private fun stopSelfIfIdle() {
        if (Repo.tasks.value.none { it.state.isActive }) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun updateNotification() {
        val tasks = Repo.tasks.value
        val active = tasks.filter { it.state == TaskState.RUNNING || it.state == TaskState.QUEUED }
        if (active.isEmpty()) {
            stopSelfIfIdle()
            return
        }
        val progress = Engine.progress.value
        val downloaded = active.sumOf { t -> progress[t.id]?.downloaded ?: t.downloaded }
        val total = active.sumOf { t -> progress[t.id]?.total ?: t.totalBytes }.coerceAtLeast(0)
        val bps = progress.values.sumOf { it.bytesPerSec }

        val title = if (active.size == 1) active.first().fileName else "${active.size} 个任务下载中"
        val pct = if (total > 0) (downloaded * 100 / total).toInt() else 0
        val text = buildString {
            if (total > 0) {
                append(fmt(downloaded)).append(" / ").append(fmt(total)).append("  ")
            }
            if (bps > 0) append("· ").append(fmt(bps)).append("/s")
        }.trim()

        val n = Notification.Builder(this, CH_PROGRESS)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent())
            .addAction(
                Notification.Action.Builder(
                    null, "全部暂停",
                    PendingIntent.getService(
                        this, 1,
                        Intent(this, DownloadService::class.java).setAction(ACTION_PAUSE_ALL),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                ).build(),
            )
            .apply { if (total > 0) setProgress(100, pct, false) }
            .build()

        nm.notify(FOREGROUND_ID, n)
    }

    private fun buildSummary(): Notification =
        Notification.Builder(this, CH_PROGRESS)
            .setContentTitle("FreeDM")
            .setContentText("准备下载")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(contentIntent())
            .setOngoing(true)
            .build()

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        nm.createNotificationChannel(
            NotificationChannel(CH_PROGRESS, "下载进度", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_DONE, "下载完成", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    /** Formats a byte count with a unit suffix, e.g. "1.2 GB". */
    private fun fmt(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = listOf("KB", "MB", "GB", "TB")
        var v = bytes.toDouble() / 1024
        var i = 0
        while (v >= 1024 && i < units.lastIndex) {
            v /= 1024
            i++
        }
        return if (v >= 100) "${v.toInt()} ${units[i]}" else String.format("%.1f %s", v, units[i])
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // The scope collects Repo.tasks and Engine.progress forever; a service
        // that is gone must not keep a subscriber (and a Service reference) alive.
        scope.cancel()
        observer?.cancel()
        super.onDestroy()
    }

    companion object {
        const val CH_PROGRESS = "download_progress"
        const val CH_DONE = "download_done"
        const val FOREGROUND_ID = 1001
        const val ACTION_PAUSE_ALL = "app.freedm.PAUSE_ALL"

        fun start(context: Context) {
            val i = Intent(context, DownloadService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DownloadService::class.java))
        }
    }
}
