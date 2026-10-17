package app.freedm.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import app.freedm.data.DownloadTask
import app.freedm.data.ProbeResult
import app.freedm.data.Repo
import app.freedm.data.Segment
import app.freedm.data.TaskState
import app.freedm.service.Notifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class TaskProgress(val downloaded: Long, val total: Long, val bytesPerSec: Long)

/**
 * Segmented HTTP downloader.
 *
 * Design notes:
 *  - One [RandomAccessFile] per task; segments seek and write at absolute offsets
 *    under a lock, so no per-segment scratch files are needed.
 *  - Each segment owns an [AtomicLong] holding *bytes durably reachable via seek*.
 *    That value is the resume anchor and is flushed to SQLite about once a second,
 *    which is therefore also the crash-recovery granularity.
 *  - Concurrency is task-level (queue depth); inside a task every segment runs in
 *    parallel up to the task's thread count.
 */
object Engine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val progressData = ConcurrentHashMap<Long, TaskProgress>()

    private val _progress = MutableStateFlow<Map<Long, TaskProgress>>(emptyMap())
    val progress: StateFlow<Map<Long, TaskProgress>> = _progress.asStateFlow()

    fun isRunning(id: Long): Boolean = jobs.containsKey(id)

    fun maxConcurrent(): Int = Repo.prefs().getInt("max_concurrent", 3).coerceIn(1, 8)

    fun defaultThreads(): Int = Repo.prefs().getInt("threads", 8).coerceIn(1, 16)

    // ---------------------------------------------------------------- lifecycle

    fun start(id: Long) {
        if (jobs.containsKey(id)) return
        RateLimiter.setRate(Repo.prefs().getLong("speed_limit", 0L))

        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                runTask(id)
            } catch (e: CancellationException) {
                Repo.setState(id, TaskState.PAUSED)
                throw e
            } catch (t: Throwable) {
                val msg = t.message ?: t::class.java.simpleName
                Repo.setState(id, TaskState.FAILED, msg)
                Notifier.failed(Repo.context, id, Repo.task(id)?.fileName ?: "下载任务", msg)
            } finally {
                progressData.remove(id)
                publish()
                jobs.remove(id)
                pump()
            }
        }
        jobs[id] = job
        job.start()
    }

    fun pause(id: Long) {
        jobs[id]?.let { scope.launch { it.cancelAndJoin() } }
        if (!isRunning(id)) Repo.setState(id, TaskState.PAUSED)
    }

    fun pauseAll() {
        jobs.keys.toList().forEach { pause(it) }
    }

    /** Starts queued tasks until the concurrency limit is reached. */
    fun pump() {
        val limit = maxConcurrent()
        if (jobs.size >= limit) return
        Repo.tasks.value
            .filter { it.state == TaskState.QUEUED && !jobs.containsKey(it.id) }
            .sortedBy { it.createdAt }
            .take(limit - jobs.size)
            .forEach { start(it.id) }
    }

    fun resumeAll() {
        Repo.tasks.value.filter { it.state == TaskState.PAUSED }.forEach {
            Repo.setState(it.id, TaskState.QUEUED)
        }
        pump()
    }

    // ---------------------------------------------------------------- internals

    private fun publish() {
        _progress.value = HashMap(progressData)
    }

    private suspend fun runTask(id: Long) {
        var task = Repo.task(id) ?: return
        Repo.setState(id, TaskState.RUNNING)

        val dir = File(task.dirPath).apply { mkdirs() }
        val file = File(dir, task.fileName)

        // 1) Probe when size or validator is unknown.
        var probe: ProbeResult? = null
        if (task.totalBytes <= 0 || task.etag == null) {
            probe = withContext(Dispatchers.IO) { Prober.probe(task.finalUrl ?: task.url) }
            task = task.copy(
                finalUrl = probe.finalUrl,
                totalBytes = probe.totalBytes,
                segmented = probe.acceptRanges,
                etag = probe.etag,
                lastModified = probe.lastModified,
                mime = probe.mime,
            )
            Repo.update(task)
        }

        // 2) A changed validator invalidates everything already on disk.
        val existing = Repo.loadSegments(id)
        if (existing.isNotEmpty() && probe != null) {
            val etagChanged = probe.etag != null && task.etag != null && probe.etag != task.etag
            val sizeChanged = probe.totalBytes > 0 && task.totalBytes != probe.totalBytes
            if (etagChanged || sizeChanged) {
                Repo.planSegments(id, Repo.buildPlan(probe.totalBytes, task.threadCount))
                runCatching { file.delete() }
            }
        }

        // 3) Build the plan on first run; re-read so every Segment carries its row id.
        if (Repo.loadSegments(id).isEmpty()) {
            val total = task.totalBytes
            val fresh = if (task.segmented && total > 0) {
                Repo.buildPlan(total, task.threadCount)
            } else {
                listOf(
                    Segment(
                        0, id, 0, 0,
                        if (total > 0) total - 1 else Long.MAX_VALUE,
                        0, TaskState.QUEUED,
                    ),
                )
            }
            Repo.planSegments(id, fresh)
        }
        val plan = Repo.loadSegments(id)

        if (task.wifiOnly && !onWifi()) {
            Repo.setState(id, TaskState.PAUSED, WIFI_WAIT)
            return
        }

        // 4) Reserve the output file up front so all segments can seek freely.
        val raf = RandomAccessFile(file, "rw")
        try {
            if (task.totalBytes > 0 && raf.length() < task.totalBytes) raf.setLength(task.totalBytes)

            // One mutable counter per segment: the resume anchor.
            val segProgress = plan.associateWith { AtomicLong(it.downloaded) }
            val counter = AtomicLong(plan.sumOf { it.downloaded })
            val total = if (task.totalBytes > 0) task.totalBytes else -1L
            val startedAt = System.currentTimeMillis()
            var lastFlush = startedAt
            var lastBytes = counter.get()
            var lastSegFlush = 0L
            var lastSegBytes = counter.get()

            val ticker = scope.launch {
                while (isActive) {
                    delay(500)
                    val now = System.currentTimeMillis()
                    val bytes = counter.get()
                    val bps = if (now > lastFlush) (bytes - lastBytes) * 1000 / (now - lastFlush) else 0
                    progressData[id] = TaskProgress(bytes, total, bps)
                    publish()
                    Repo.fastProgress(id, bytes, total)
                    // Flush per-segment offsets on whichever comes first: one second
                    // elapsed, or another MiB landed. This bounds both the write rate
                    // and the worst-case re-download after a crash.
                    if (now - lastSegFlush >= 1000 || bytes - lastSegBytes >= ONE_MIB) {
                        plan.forEach { seg ->
                            Repo.markSegment(
                                id, seg.index, segProgress.getValue(seg).get(), TaskState.RUNNING,
                            )
                        }
                        lastSegFlush = now
                        lastSegBytes = bytes
                    }
                    lastFlush = now
                    lastBytes = bytes
                }
            }

            try {
                coroutineScope {
                    plan.forEach { seg ->
                        launch(Dispatchers.IO) {
                            val acc = segProgress.getValue(seg)
                            val ok = fetchSegment(id, task, seg, raf, acc) { delta ->
                                counter.addAndGet(delta)
                            }
                            if (!ok) error("分段 ${seg.index} 下载失败")
                            Repo.markSegment(id, seg.index, seg.length, TaskState.COMPLETED)
                        }
                    }
                }
            } finally {
                ticker.cancel()
            }

            val finalBytes = counter.get()
            progressData[id] = TaskProgress(finalBytes, total, 0)
            publish()
            Repo.fastProgress(id, finalBytes, total)

            // 5) Size check — a short file means the transfer was incomplete.
            if (total > 0 && file.length() < total) {
                error("文件大小不符：${file.length()} / $total")
            }
            Repo.setState(id, TaskState.COMPLETED)
            Notifier.completed(Repo.context, id, task.fileName, file.absolutePath)
            Exporter.exportIfEnabled(Repo.context, file, task.fileName)
        } finally {
            runCatching { raf.close() }
        }
    }

    /**
     * Downloads one segment, resuming from whatever [written] already holds.
     *
     * `written` is shared with the ticker *and* kept across retry attempts, so:
     *  - a dropped connection continues at the exact byte reached;
     *  - a crash resumes from the last flushed offset;
     *  - only genuinely new bytes reach [onBytes], so the task counter cannot
     *    double-count a retried region (the bug that made progress exceed 100%).
     *
     * Returns true when the whole range is on disk.
     */
    private fun fetchSegment(
        taskId: Long,
        task: DownloadTask,
        seg: Segment,
        raf: RandomAccessFile,
        written: AtomicLong,
        onBytes: (Long) -> Unit,
    ): Boolean {
        val url = task.finalUrl ?: task.url
        val expected = if (seg.endByte == Long.MAX_VALUE) -1L else seg.length
        var attempt = 0

        while (attempt < MAX_RETRY) {
            attempt++
            try {
                if (expected >= 0 && written.get() >= expected) return true

                val from = seg.startByte + written.get()
                val rangedRequest = seg.endByte != Long.MAX_VALUE
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", Prober.UA)
                    if (rangedRequest) setRequestProperty("Range", "bytes=$from-${seg.endByte}")
                    if (task.etag != null) setRequestProperty("If-Range", task.etag)
                }
                try {
                    val code = conn.responseCode
                    if (code !in 200..299) throw IOException("HTTP $code")

                    // A server that ignores Range replies 200 with the full body.
                    // Writing that at a non-zero offset would silently corrupt the
                    // file, so a resumed request must be answered with 206.
                    if (rangedRequest && from > seg.startByte && code != 206) {
                        throw IOException("服务器未按 Range 返回（HTTP $code）")
                    }

                    conn.inputStream.use { input ->
                        val buf = ByteArray(BUF_SIZE)
                        var pos = from
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            synchronized(raf) {
                                raf.seek(pos)
                                raf.write(buf, 0, n)
                            }
                            pos += n
                            written.addAndGet(n.toLong())
                            onBytes(n.toLong())
                            RateLimiter.acquire(n)
                        }
                    }

                    // A stream that just stops early (no exception) is a truncated
                    // transfer — treat it as a failure so the retry loop resumes.
                    if (expected >= 0 && written.get() < expected) {
                        throw IOException("连接提前结束（${written.get()}/$expected）")
                    }
                    return true
                } finally {
                    runCatching { conn.disconnect() }
                }
            } catch (e: Exception) {
                // Persist the offset reached so even a process death resumes here.
                Repo.markSegment(taskId, seg.index, written.get(), TaskState.QUEUED)
                if (attempt >= MAX_RETRY) return false
                // Exponential backoff: 1s, 2s, 4s.
                Thread.sleep(1000L shl (attempt - 1))
            }
        }
        return false
    }

    private fun onWifi(): Boolean {
        val cm = Repo.context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private const val MAX_RETRY = 3
    private const val BUF_SIZE = 64 * 1024
    const val ONE_MIB = 1024L * 1024L

    /** Marker stored in `download.error` when a task paused itself for Wi-Fi. */
    const val WIFI_WAIT = "等待 Wi-Fi"
}
