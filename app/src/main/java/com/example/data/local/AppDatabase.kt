package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [DownloadTaskEntity::class],
    version = 2,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun downloadTaskDao(): DownloadTaskDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Safely inspect existing columns to ensure idempotency
                val existingColumns = mutableSetOf<String>()
                db.query("PRAGMA table_info(download_tasks)").use { cursor ->
                    val nameIndex = cursor.getColumnIndex("name")
                    while (cursor.moveToNext()) {
                        if (nameIndex != -1) {
                            existingColumns.add(cursor.getString(nameIndex))
                        }
                    }
                }

                // Add downloadStage (stage) with safe default 'QUEUED'
                if (!existingColumns.contains("stage")) {
                    db.execSQL("ALTER TABLE download_tasks ADD COLUMN stage TEXT NOT NULL DEFAULT 'QUEUED'")
                }

                // Add runId with safe default 0
                if (!existingColumns.contains("runId")) {
                    db.execSQL("ALTER TABLE download_tasks ADD COLUMN runId INTEGER NOT NULL DEFAULT 0")
                }

                // Migrate existing records: map download status to appropriate initial download stage
                db.execSQL("UPDATE download_tasks SET stage = 'COMPLETED' WHERE status = 'COMPLETED'")
                db.execSQL("UPDATE download_tasks SET stage = 'DOWNLOADING' WHERE status = 'DOWNLOADING'")
                db.execSQL("UPDATE download_tasks SET stage = 'PREPARING' WHERE status IN ('PREPARING', 'ANALYZING')")
                db.execSQL("UPDATE download_tasks SET stage = 'MERGING' WHERE status = 'PROCESSING_FFMPEG'")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "download_videos_db"
                )
                .addMigrations(MIGRATION_1_2)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
