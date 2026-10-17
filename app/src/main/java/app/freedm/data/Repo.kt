package app.freedm.data

import android.annotation.SuppressLint
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID

/**
 * Single in-process source of truth over SQLite.
 *
 * Deliberately dependency-free (no Room, no Flow-from-DB): the task list is
 * small, and an explicit refresh keeps behaviour obvious and easy to audit.
 */
object Repo {

    private lateinit var helper: Db
    private lateinit var appContext: Context
    private val lock = Any()

    /** Random, app-local identifier. Never derived from any device property. */
    val installId: String by lazy {
        val prefs = appContext.getSharedPreferences("local", Context.MODE_PRIVATE)
        prefs.getString("install_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("install_id", it).apply()
        }
    }

    /** Application context, valid after [init]. */
    val context: Context get() = appContext

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    private val _segments = MutableStateFlow<Map<Long, List<Segment>>>(emptyMap())
    val segments: StateFlow<Map<Long, List<Segment>>> = _segments.asStateFlow()

    val downloadsDir: File
        get() = File(appContext.filesDir, "downloads").apply { mkdirs() }

    fun init(context: Context) {
        appContext = context.applicationContext
        helper = Db(appContext)
        downloadsDir.mkdirs()
        // A task that was RUNNING when the process died should continue; one the
        // user explicitly PAUSED must stay paused. Mapping RUNNING->QUEUED here is
        // what keeps those two cases distinguishable across restarts.
        synchronized(lock) {
            helper.writableDatabase.execSQL(
                "UPDATE download SET state=? WHERE state=?",
                arrayOf(TaskState.QUEUED.name, TaskState.RUNNING.name),
            )
        }
        refresh()
    }

    fun refresh() {
        val list = synchronized(lock) {
            val out = ArrayList<DownloadTask>()
            helper.readableDatabase.rawQuery(
                "SELECT * FROM download ORDER BY created_at DESC", null,
            ).use { c -> while (c.moveToNext()) out.add(DbMap.task(c)) }
            out
        }
        _tasks.value = list
    }

    fun loadSegments(id: Long): List<Segment> = synchronized(lock) {
        val out = ArrayList<Segment>()
        helper.readableDatabase.rawQuery(
            "SELECT * FROM segment WHERE download_id=? ORDER BY idx", arrayOf(id.toString()),
        ).use { c -> while (c.moveToNext()) out.add(DbMap.segment(c)) }
        out
    }

    fun task(id: Long): DownloadTask? = synchronized(lock) {
        helper.readableDatabase.rawQuery("SELECT * FROM download WHERE id=?", arrayOf(id.toString()))
            .use { c -> if (c.moveToFirst()) DbMap.task(c) else null }
    }

    fun create(
        url: String,
        fileName: String,
        dirPath: String,
        threadCount: Int,
        wifiOnly: Boolean,
        probe: ProbeResult?,
    ): Long {
        val now = System.currentTimeMillis()
        val t = DownloadTask(
            id = 0,
            url = url,
            finalUrl = probe?.finalUrl,
            fileName = fileName,
            dirPath = dirPath,
            totalBytes = probe?.totalBytes ?: -1,
            downloaded = 0,
            state = TaskState.QUEUED,
            etag = probe?.etag,
            lastModified = probe?.lastModified,
            mime = probe?.mime,
            segmented = probe?.acceptRanges == true,
            threadCount = threadCount,
            wifiOnly = wifiOnly,
            error = null,
            createdAt = now,
            updatedAt = now,
        )
        val id = synchronized(lock) {
            helper.writableDatabase.insert("download", null, DbMap.values(t))
        }
        refresh()
        return id
    }

    fun update(t: DownloadTask) {
        synchronized(lock) {
            helper.writableDatabase.update(
                "download", DbMap.values(t.copy(updatedAt = System.currentTimeMillis())),
                "id=?", arrayOf(t.id.toString()),
            )
        }
    }

    fun fastProgress(id: Long, downloaded: Long, totalBytes: Long) {
        synchronized(lock) {
            helper.writableDatabase.execSQL(
                "UPDATE download SET downloaded=?, total_bytes=?, updated_at=? WHERE id=?",
                arrayOf(downloaded, totalBytes, System.currentTimeMillis(), id),
            )
        }
    }

    fun setState(id: Long, state: TaskState, error: String? = null) {
        synchronized(lock) {
            helper.writableDatabase.execSQL(
                "UPDATE download SET state=?, error=?, updated_at=? WHERE id=?",
                arrayOf(state.name, error, System.currentTimeMillis(), id),
            )
        }
        refresh()
    }

    fun delete(id: Long, deleteFiles: Boolean) {
        val t = task(id)
        synchronized(lock) {
            helper.writableDatabase.execSQL("DELETE FROM segment WHERE download_id=?", arrayOf<Any>(id))
            helper.writableDatabase.execSQL("DELETE FROM download WHERE id=?", arrayOf<Any>(id))
        }
        if (deleteFiles && t != null) {
            runCatching { File(t.filePath).delete() }
        }
        refresh()
    }

    /** Replaces the segment plan for a task (called before the first byte). */
    fun planSegments(id: Long, plan: List<Segment>) {
        synchronized(lock) {
            helper.writableDatabase.execSQL("DELETE FROM segment WHERE download_id=?", arrayOf<Any>(id))
            plan.forEach { s ->
                helper.writableDatabase.execSQL(
                    "INSERT INTO segment(download_id, idx, start_byte, end_byte, downloaded, state) " +
                        "VALUES(?,?,?,?,0,?)",
                    arrayOf(id, s.index, s.startByte, s.endByte, TaskState.QUEUED.name),
                )
            }
        }
        publishSegments(id)
    }

    fun markSegment(id: Long, idx: Int, downloaded: Long, state: TaskState) {
        synchronized(lock) {
            helper.writableDatabase.execSQL(
                "UPDATE segment SET downloaded=?, state=? WHERE download_id=? AND idx=?",
                arrayOf(downloaded, state.name, id, idx),
            )
        }
    }

    fun publishSegments(id: Long) {
        _segments.value = _segments.value + (id to loadSegments(id))
    }

    /** Splits [total] into at most [maxSegments] byte ranges. */
    fun buildPlan(total: Long, threads: Int): List<Segment> {
        val n = threads.coerceIn(1, 16)
        if (total <= 0) return emptyList()
        val granule = 8L * 1024 * 1024
        if (total < granule * 2) {
            return listOf(Segment(0, 0, 0, 0, total - 1, 0, TaskState.QUEUED))
        }
        val count = minOf(n, maxOf(1, (total / granule).toInt().coerceAtMost(64)))
        val chunk = (total + count - 1) / count
        val out = ArrayList<Segment>(count)
        var start = 0L
        var idx = 0
        while (start < total) {
            val end = minOf(start + chunk - 1, total - 1)
            out.add(Segment(0, 0, idx, start, end, 0, TaskState.QUEUED))
            start = end + 1
            idx++
        }
        return out
    }

    @SuppressLint("ApplySharedPref")
    fun prefs() = appContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
}
