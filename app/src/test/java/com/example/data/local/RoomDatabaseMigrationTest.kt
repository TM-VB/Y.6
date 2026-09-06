package com.example.data.local

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.domain.model.DownloadStage
import com.example.domain.model.DownloadStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Migration Test for Room Database: Version 1 -> Version 2.
 *
 * Verifies:
 * 1. Safe addition of 'stage' (downloadStage) and 'runId' columns.
 * 2. Proper default values for legacy data without data loss.
 * 3. Preservation of all previous download rows, metadata, URLs, file paths, and progress.
 * 4. Room DAO operations and full schema compatibility on upgraded database.
 * 5. Complete absence of destructive migration.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomDatabaseMigrationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var testDbFile: File
    private val dbName = "test_migration_download_db.db"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        testDbFile = File(context.applicationInfo.dataDir, "databases/$dbName")
        testDbFile.parentFile?.mkdirs()
        testDbFile.delete()
    }

    @After
    fun tearDown() {
        try {
            testDbFile.delete()
        } catch (_: Throwable) {}
    }

    /**
     * Helper to create a legacy Version 1 database with realistic records.
     */
    private fun createVersion1Database(db: SupportSQLiteDatabase) {
        // Exact table structure of download_tasks in Version 1 (no 'stage', no 'runId')
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS download_tasks (
                id TEXT NOT NULL PRIMARY KEY,
                url TEXT NOT NULL,
                title TEXT NOT NULL,
                thumbnailUrl TEXT,
                formatId TEXT NOT NULL DEFAULT 'best',
                formatDescription TEXT NOT NULL DEFAULT 'Best Quality',
                startTime TEXT,
                endTime TEXT,
                cutMode TEXT NOT NULL DEFAULT 'none',
                status TEXT NOT NULL DEFAULT 'QUEUED',
                progress REAL NOT NULL DEFAULT 0.0,
                downloadSpeed TEXT NOT NULL DEFAULT '',
                downloadedSize TEXT NOT NULL DEFAULT '',
                totalSize TEXT NOT NULL DEFAULT '',
                eta TEXT NOT NULL DEFAULT '',
                filePath TEXT,
                contentUri TEXT,
                errorMessage TEXT,
                createdAt INTEGER NOT NULL,
                completedAt INTEGER,
                retryCount INTEGER NOT NULL DEFAULT 0,
                maxRetries INTEGER NOT NULL DEFAULT 3,
                isAudioOnly INTEGER NOT NULL DEFAULT 0,
                isVideoOnly INTEGER NOT NULL DEFAULT 0,
                downloadSubtitles INTEGER NOT NULL DEFAULT 0,
                subtitleLanguage TEXT,
                queueOrder INTEGER NOT NULL
            )
        """.trimIndent())

        db.execSQL("CREATE INDEX IF NOT EXISTS index_download_tasks_status ON download_tasks (status)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_download_tasks_url ON download_tasks (url)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_download_tasks_queueOrder ON download_tasks (queueOrder)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_download_tasks_createdAt ON download_tasks (createdAt)")

        // 1. Completed legacy download
        db.execSQL("""
            INSERT INTO download_tasks (
                id, url, title, thumbnailUrl, formatId, formatDescription, status, progress, 
                downloadedSize, totalSize, filePath, contentUri, createdAt, completedAt, queueOrder
            ) VALUES (
                'task_v1_completed', 'https://youtube.com/watch?v=comp1', 'Legacy Completed Video', 
                'https://thumb.com/1.jpg', '137+140', '1080p + Audio', 'COMPLETED', 100.0, 
                '45 MB', '45 MB', '/storage/emulated/0/Download/video1.mp4', 'content://media/1', 1700000000000, 1700000060000, 1
            )
        """.trimIndent())

        // 2. Downloading legacy task
        db.execSQL("""
            INSERT INTO download_tasks (
                id, url, title, formatId, status, progress, downloadSpeed, downloadedSize, totalSize, eta, createdAt, queueOrder
            ) VALUES (
                'task_v1_downloading', 'https://youtube.com/watch?v=down2', 'Legacy Downloading Video', 
                'best', 'DOWNLOADING', 55.5, '2.1 MB/s', '20 MB', '36 MB', '00:08', 1700000100000, 2
            )
        """.trimIndent())

        // 3. Processing / Muxing legacy task
        db.execSQL("""
            INSERT INTO download_tasks (
                id, url, title, status, progress, createdAt, queueOrder
            ) VALUES (
                'task_v1_processing', 'https://youtube.com/watch?v=proc3', 'Legacy Processing Video', 
                'PROCESSING_FFMPEG', 95.0, 1700000200000, 3
            )
        """.trimIndent())

        // 4. Preparing legacy task
        db.execSQL("""
            INSERT INTO download_tasks (
                id, url, title, status, progress, createdAt, queueOrder
            ) VALUES (
                'task_v1_preparing', 'https://youtube.com/watch?v=prep4', 'Legacy Preparing Video', 
                'PREPARING', 0.0, 1700000300000, 4
            )
        """.trimIndent())

        // 5. Queued legacy task
        db.execSQL("""
            INSERT INTO download_tasks (
                id, url, title, status, progress, createdAt, queueOrder
            ) VALUES (
                'task_v1_queued', 'https://youtube.com/watch?v=queue5', 'Legacy Queued Video', 
                'QUEUED', 0.0, 1700000400000, 5
            )
        """.trimIndent())

        // 6. Failed legacy task
        db.execSQL("""
            INSERT INTO download_tasks (
                id, url, title, status, progress, errorMessage, createdAt, queueOrder
            ) VALUES (
                'task_v1_failed', 'https://youtube.com/watch?v=fail6', 'Legacy Failed Video', 
                'FAILED', 30.0, 'Network connection reset by peer', 1700000500000, 6
            )
        """.trimIndent())
    }

    @Test
    fun `migration from 1 to 2 adds stage and runId columns with appropriate defaults without data loss`() {
        val helperConfig = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    createVersion1Database(db)
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()

        val helper = FrameworkSQLiteOpenHelperFactory().create(helperConfig)
        val v1Db = helper.writableDatabase

        // Verify version 1 data count
        v1Db.query("SELECT count(*) FROM download_tasks").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(6, cursor.getInt(0))
        }

        // Apply MIGRATION_1_2
        AppDatabase.MIGRATION_1_2.migrate(v1Db)
        v1Db.version = 2

        // Verify columns are added
        val columns = mutableMapOf<String, String>()
        v1Db.query("PRAGMA table_info(download_tasks)").use { cursor ->
            val nameIdx = cursor.getColumnIndex("name")
            val typeIdx = cursor.getColumnIndex("type")
            while (cursor.moveToNext()) {
                columns[cursor.getString(nameIdx)] = cursor.getString(typeIdx)
            }
        }

        assertTrue("Column 'stage' (downloadStage) must be present in table", columns.containsKey("stage"))
        assertTrue("Column 'runId' must be present in table", columns.containsKey("runId"))

        // Verify all 6 legacy download records are preserved
        v1Db.query("SELECT count(*) FROM download_tasks").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("All 6 legacy tasks must be preserved without data loss", 6, cursor.getInt(0))
        }

        // Check defaults and status mapping for legacy records
        v1Db.query("SELECT id, status, stage, runId, title, filePath, progress FROM download_tasks").use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow("id")
            val statusIdx = cursor.getColumnIndexOrThrow("status")
            val stageIdx = cursor.getColumnIndexOrThrow("stage")
            val runIdIdx = cursor.getColumnIndexOrThrow("runId")
            val titleIdx = cursor.getColumnIndexOrThrow("title")
            val filePathIdx = cursor.getColumnIndexOrThrow("filePath")
            val progressIdx = cursor.getColumnIndexOrThrow("progress")

            val records = mutableMapOf<String, Map<String, Any?>>()
            while (cursor.moveToNext()) {
                val id = cursor.getString(idIdx)
                records[id] = mapOf(
                    "status" to cursor.getString(statusIdx),
                    "stage" to cursor.getString(stageIdx),
                    "runId" to cursor.getLong(runIdIdx),
                    "title" to cursor.getString(titleIdx),
                    "filePath" to cursor.getString(filePathIdx),
                    "progress" to cursor.getFloat(progressIdx)
                )
            }

            // Task 1: COMPLETED -> stage should be COMPLETED, runId = 0
            val comp = records["task_v1_completed"]!!
            assertEquals("COMPLETED", comp["status"])
            assertEquals("COMPLETED", comp["stage"])
            assertEquals(0L, comp["runId"])
            assertEquals("Legacy Completed Video", comp["title"])
            assertEquals("/storage/emulated/0/Download/video1.mp4", comp["filePath"])
            assertEquals(100.0f, comp["progress"])

            // Task 2: DOWNLOADING -> stage should be DOWNLOADING, runId = 0
            val down = records["task_v1_downloading"]!!
            assertEquals("DOWNLOADING", down["status"])
            assertEquals("DOWNLOADING", down["stage"])
            assertEquals(0L, down["runId"])
            assertEquals("Legacy Downloading Video", down["title"])
            assertEquals(55.5f, down["progress"])

            // Task 3: PROCESSING_FFMPEG -> stage should be MERGING, runId = 0
            val proc = records["task_v1_processing"]!!
            assertEquals("PROCESSING_FFMPEG", proc["status"])
            assertEquals("MERGING", proc["stage"])
            assertEquals(0L, proc["runId"])

            // Task 4: PREPARING -> stage should be PREPARING, runId = 0
            val prep = records["task_v1_preparing"]!!
            assertEquals("PREPARING", prep["status"])
            assertEquals("PREPARING", prep["stage"])
            assertEquals(0L, prep["runId"])

            // Task 5: QUEUED -> stage should be default QUEUED, runId = 0
            val que = records["task_v1_queued"]!!
            assertEquals("QUEUED", que["status"])
            assertEquals("QUEUED", que["stage"])
            assertEquals(0L, que["runId"])

            // Task 6: FAILED -> stage should be default QUEUED, runId = 0
            val fail = records["task_v1_failed"]!!
            assertEquals("FAILED", fail["status"])
            assertEquals("QUEUED", fail["stage"])
            assertEquals(0L, fail["runId"])
        }

        v1Db.close()
        helper.close()
    }

    @Test
    fun `migrated database operates seamlessly with Room Database and DownloadTaskDao`() = runBlocking {
        // Step 1: Initialize raw database with version 1
        val helperConfig = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    createVersion1Database(db)
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()

        val helper = FrameworkSQLiteOpenHelperFactory().create(helperConfig)
        val rawDb = helper.writableDatabase
        rawDb.close()
        helper.close()

        // Step 2: Open database with Room at Version 2 using MIGRATION_1_2
        val roomDb = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .build()

        val dao = roomDb.downloadTaskDao()

        // Step 3: Query legacy records through DAO
        val completedTask = dao.getTaskByIdSync("task_v1_completed")
        assertNotNull("Legacy completed task must be loaded by DAO", completedTask)
        assertEquals("task_v1_completed", completedTask!!.id)
        assertEquals(DownloadStatus.COMPLETED, completedTask.status)
        assertEquals(DownloadStage.COMPLETED, completedTask.stage)
        assertEquals(0L, completedTask.runId)
        assertEquals("Legacy Completed Video", completedTask.title)
        assertEquals("/storage/emulated/0/Download/video1.mp4", completedTask.filePath)

        val downloadingTask = dao.getTaskByIdSync("task_v1_downloading")
        assertNotNull("Legacy downloading task must be loaded by DAO", downloadingTask)
        assertEquals(DownloadStatus.DOWNLOADING, downloadingTask!!.status)
        assertEquals(DownloadStage.DOWNLOADING, downloadingTask.stage)
        assertEquals(0L, downloadingTask.runId)
        assertEquals("Legacy Downloading Video", downloadingTask.title)
        assertEquals("2.1 MB/s", downloadingTask.downloadSpeed)

        // Step 4: Insert new Version 2 task with custom stage and runId
        val newV2Task = DownloadTaskEntity(
            id = "task_v2_new",
            url = "https://youtube.com/watch?v=v2new",
            title = "New V2 Download Video",
            status = DownloadStatus.DOWNLOADING,
            stage = DownloadStage.MERGING,
            runId = 1002L,
            progress = 75.0f
        )
        dao.insertTask(newV2Task)

        val retrievedV2 = dao.getTaskByIdSync("task_v2_new")
        assertNotNull("Newly inserted V2 task must be readable", retrievedV2)
        assertEquals(DownloadStage.MERGING, retrievedV2!!.stage)
        assertEquals(1002L, retrievedV2.runId)
        assertEquals(75.0f, retrievedV2.progress)

        // Verify total completed tasks query
        val completedList = dao.getAllCompletedTasksSync()
        assertTrue(completedList.any { it.id == "task_v1_completed" })

        roomDb.close()
    }

    @Test
    fun `migration is idempotent and safe against multiple executions`() {
        val helperConfig = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    createVersion1Database(db)
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()

        val helper = FrameworkSQLiteOpenHelperFactory().create(helperConfig)
        val db = helper.writableDatabase

        // Execute migration 1st time
        AppDatabase.MIGRATION_1_2.migrate(db)

        // Execute migration 2nd time (should safely no-op without throwing column duplicate error)
        AppDatabase.MIGRATION_1_2.migrate(db)

        // Verify data count remains intact
        db.query("SELECT count(*) FROM download_tasks").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(6, cursor.getInt(0))
        }

        db.close()
        helper.close()
    }
}
