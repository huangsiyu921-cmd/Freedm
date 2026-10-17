package app.freedm.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Plain SQLiteOpenHelper (no Room) to keep the dependency surface minimal —
 * a privacy-hardened build should carry as few third-party libraries as possible.
 */
class Db(context: Context) : SQLiteOpenHelper(context.applicationContext, NAME, null, VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE download (
              id            INTEGER PRIMARY KEY AUTOINCREMENT,
              url           TEXT NOT NULL,
              final_url     TEXT,
              file_name     TEXT NOT NULL,
              dir_path      TEXT NOT NULL,
              total_bytes   INTEGER NOT NULL DEFAULT -1,
              downloaded    INTEGER NOT NULL DEFAULT 0,
              state         TEXT NOT NULL,
              etag          TEXT,
              last_modified TEXT,
              mime          TEXT,
              segmented     INTEGER NOT NULL DEFAULT 0,
              thread_count  INTEGER NOT NULL DEFAULT 8,
              wifi_only     INTEGER NOT NULL DEFAULT 0,
              error         TEXT,
              created_at    INTEGER NOT NULL,
              updated_at    INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE segment (
              id          INTEGER PRIMARY KEY AUTOINCREMENT,
              download_id INTEGER NOT NULL,
              idx         INTEGER NOT NULL,
              start_byte  INTEGER NOT NULL,
              end_byte    INTEGER NOT NULL,
              downloaded  INTEGER NOT NULL DEFAULT 0,
              state       TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_segment_download ON segment(download_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Pre-release schema: drop and recreate.
        db.execSQL("DROP TABLE IF EXISTS segment")
        db.execSQL("DROP TABLE IF EXISTS download")
        onCreate(db)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    companion object {
        const val NAME = "downloads.db"
        const val VERSION = 1
    }
}

/** Row-mapping helpers kept free of Android UI types so they are unit-testable. */
object DbMap {

    fun task(c: android.database.Cursor): DownloadTask = DownloadTask(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        url = c.getString(c.getColumnIndexOrThrow("url")),
        finalUrl = c.getString(c.getColumnIndexOrThrow("final_url")),
        fileName = c.getString(c.getColumnIndexOrThrow("file_name")),
        dirPath = c.getString(c.getColumnIndexOrThrow("dir_path")),
        totalBytes = c.getLong(c.getColumnIndexOrThrow("total_bytes")),
        downloaded = c.getLong(c.getColumnIndexOrThrow("downloaded")),
        state = runCatching { TaskState.valueOf(c.getString(c.getColumnIndexOrThrow("state"))) }
            .getOrDefault(TaskState.FAILED),
        etag = c.getString(c.getColumnIndexOrThrow("etag")),
        lastModified = c.getString(c.getColumnIndexOrThrow("last_modified")),
        mime = c.getString(c.getColumnIndexOrThrow("mime")),
        segmented = c.getInt(c.getColumnIndexOrThrow("segmented")) != 0,
        threadCount = c.getInt(c.getColumnIndexOrThrow("thread_count")),
        wifiOnly = c.getInt(c.getColumnIndexOrThrow("wifi_only")) != 0,
        error = c.getString(c.getColumnIndexOrThrow("error")),
        createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
        updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at")),
    )

    fun segment(c: android.database.Cursor): Segment = Segment(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        downloadId = c.getLong(c.getColumnIndexOrThrow("download_id")),
        index = c.getInt(c.getColumnIndexOrThrow("idx")),
        startByte = c.getLong(c.getColumnIndexOrThrow("start_byte")),
        endByte = c.getLong(c.getColumnIndexOrThrow("end_byte")),
        downloaded = c.getLong(c.getColumnIndexOrThrow("downloaded")),
        state = runCatching { TaskState.valueOf(c.getString(c.getColumnIndexOrThrow("state"))) }
            .getOrDefault(TaskState.QUEUED),
    )

    fun values(t: DownloadTask): ContentValues = ContentValues().apply {
        put("url", t.url)
        put("final_url", t.finalUrl)
        put("file_name", t.fileName)
        put("dir_path", t.dirPath)
        put("total_bytes", t.totalBytes)
        put("downloaded", t.downloaded)
        put("state", t.state.name)
        put("etag", t.etag)
        put("last_modified", t.lastModified)
        put("mime", t.mime)
        put("segmented", if (t.segmented) 1 else 0)
        put("thread_count", t.threadCount)
        put("wifi_only", if (t.wifiOnly) 1 else 0)
        put("error", t.error)
        put("created_at", t.createdAt)
        put("updated_at", t.updatedAt)
    }
}
