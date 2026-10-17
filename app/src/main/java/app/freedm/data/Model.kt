package app.freedm.data

/** Lifecycle of a download task. Stored as TEXT in SQLite. */
enum class TaskState {
    QUEUED, RUNNING, PAUSED, COMPLETED, FAILED;

    val isActive: Boolean get() = this == QUEUED || this == RUNNING
    val isFinished: Boolean get() = this == COMPLETED || this == FAILED
}

data class DownloadTask(
    val id: Long,
    val url: String,
    val finalUrl: String?,
    val fileName: String,
    val dirPath: String,
    val totalBytes: Long,
    val downloaded: Long,
    val state: TaskState,
    val etag: String?,
    val lastModified: String?,
    val mime: String?,
    val segmented: Boolean,
    val threadCount: Int,
    val wifiOnly: Boolean,
    val error: String?,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val progress: Float
        get() = if (totalBytes > 0) (downloaded.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f

    val filePath: String get() = "$dirPath/$fileName"
}

data class Segment(
    val id: Long,
    val downloadId: Long,
    val index: Int,
    val startByte: Long,
    val endByte: Long,
    val downloaded: Long,
    val state: TaskState,
) {
    val length: Long get() = endByte - startByte + 1
}

/** Result of probing a URL before starting the transfer. */
data class ProbeResult(
    val finalUrl: String,
    val totalBytes: Long,
    val acceptRanges: Boolean,
    val etag: String?,
    val lastModified: String?,
    val mime: String?,
    val suggestedName: String?,
)
