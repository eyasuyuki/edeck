package org.javaopen.edeck

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri

object VideoCacheStore {
    private const val DATABASE_NAME = "video_cache.db"
    private const val DATABASE_VERSION = 1

    fun save(context: Context, sourceKey: String, videos: List<RemoteVideo>) {
        val database = Database(context).writableDatabase
        database.beginTransaction()
        try {
            // Replace a source's rows atomically: if the app is interrupted mid-refresh,
            // startup must see either the previous full list or the new full list.
            database.delete("videos", "source_key = ?", arrayOf(sourceKey))
            val insert = database.compileStatement(
                "INSERT INTO videos(source_key, uri, title, last_modified) VALUES (?, ?, ?, ?)"
            )
            videos.forEach { video ->
                insert.clearBindings()
                insert.bindString(1, sourceKey)
                insert.bindString(2, video.uri.toString())
                insert.bindString(3, video.title)
                insert.bindLong(4, video.lastModified)
                insert.executeInsert()
            }
            val state = database.compileStatement(
                "INSERT OR REPLACE INTO video_lists(source_key, updated_at) VALUES (?, ?)"
            )
            // Keep a separate list marker so a successfully cached empty folder/list is
            // different from a source that has never been scanned.
            state.bindString(1, sourceKey)
            state.bindLong(2, System.currentTimeMillis())
            state.executeInsert()
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    fun load(context: Context, sourceKey: String): List<RemoteVideo>? {
        val database = Database(context).readableDatabase
        val hasCache = database.rawQuery(
            "SELECT 1 FROM video_lists WHERE source_key = ?",
            arrayOf(sourceKey)
        ).use { it.moveToFirst() }
        // null means there is no saved snapshot; an empty list means the last scan
        // completed successfully and found no videos.
        if (!hasCache) return null
        return database.rawQuery(
            "SELECT title, uri, last_modified FROM videos WHERE source_key = ? ORDER BY last_modified DESC, title COLLATE NOCASE",
            arrayOf(sourceKey)
        ).use { cursor ->
            buildList(cursor.count) {
                while (cursor.moveToNext()) {
                    add(RemoteVideo(cursor.getString(0), Uri.parse(cursor.getString(1)), cursor.getLong(2)))
                }
            }
        }
    }

    private class Database(context: Context) : SQLiteOpenHelper(
        context.applicationContext,
        DATABASE_NAME,
        null,
        DATABASE_VERSION
    ) {
        override fun onCreate(db: SQLiteDatabase) {
            // Keep list-existence separate from video rows: an empty scan is a valid cached
            // result, while no marker means startup has never had a snapshot to show.
            db.execSQL("CREATE TABLE video_lists (source_key TEXT PRIMARY KEY NOT NULL, updated_at INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE videos (source_key TEXT NOT NULL, uri TEXT NOT NULL, title TEXT NOT NULL, last_modified INTEGER NOT NULL, PRIMARY KEY(source_key, uri))")
            db.execSQL("CREATE INDEX videos_by_source_date ON videos(source_key, last_modified DESC)")
        }

        // Version 1 is the first schema; once it changes, a migration must preserve cached
        // rows because they are the fast path for startup on a slow home network.
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
}
