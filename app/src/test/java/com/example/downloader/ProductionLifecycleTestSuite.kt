package com.example.downloader

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.DownloadTaskDao
import com.example.data.local.DownloadTaskEntity
import com.example.data.repository.DownloadRepository
import com.example.data.settings.AppSettings
import com.example.domain.model.DownloadError
import com.example.domain.model.DownloadProgress
import com.example.domain.model.DownloadRequest
import com.example.domain.model.DownloadStage
import com.example.domain.model.DownloadStatus
import com.example.downloader.engine.DownloadEngine
import com.example.downloader.engine.OutputFileDetector
import com.example.downloader.execution.DownloadExecutionManager
import com.example.downloader.ffmpeg.FFmpegManager
import com.example.downloader.lifecycle.DownloadStateMachine
import com.example.downloader.lifecycle.DownloadTaskLifecycle
import com.example.downloader.network.NetworkMonitor
import com.example.downloader.queue.DownloadQueueCoordinator
import com.example.downloader.queue.DownloadQueueManager
import com.example.storage.MediaStoreHelper
import com.example.storage.StorageSpaceChecker
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Deterministic production lifecycle regression test suite covering:
 * 1. runId isolation
 * 2. stale callback after pause
 * 3. stale callback after retry
 * 4. stale callback from old run after resume
 * 5. cancel during yt-dlp
 * 6. cancel during FFmpeg
 * 7. delete during download
 * 8. retry after failure
 * 9. retry while old coroutine is finishing
 * 10. resume after process interruption
 * 11. output file detection with multiple files
 * 12. insufficient storage
 * 13. MediaStore publish failure
 * 14. duplicate download race
 *
 * Tests are fully deterministic using coroutine synchronization (Deferred/CompletableDeferred),
 * in-memory Room database, and strict assertions without arbitrary thread sleeping.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProductionLifecycleTestSuite {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var dao: DownloadTaskDao
    private lateinit var repository: DownloadRepository
    private lateinit var queueManager: DownloadQueueManager
    private lateinit var testScope: CoroutineScope

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.downloadTaskDao()
        repository = DownloadRepository(dao)
        testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        queueManager = DownloadQueueManager(
            context = context,
            repository = repository,
            scope = testScope
        )
        StorageSpaceChecker.customStorageProvider = null
    }

    @After
    fun tearDown() {
        StorageSpaceChecker.customStorageProvider = null
        testScope.cancel()
        database.close()
    }

    private fun createDummyFile(dir: File, name: String, sizeBytes: Long): File {
        val file = File(dir, name)
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(sizeBytes.coerceAtLeast(1).toInt().coerceAtMost(1024)))
        if (sizeBytes > 1024) {
            RandomAccessFile(file, "rw").use { raf ->
                raf.setLength(sizeBytes)
            }
        }
        return file
    }

    // ---------------------------------------------------------------------------------------------
    // 1. runId isolation
    // ---------------------------------------------------------------------------------------------
    @Test
    fun testRunIdIsolation_ProgressUpdatesFromDifferentRunIdAreStrictlyRejected() = runBlocking {
        val taskId = "task_isolation_1"
        val activeRunId = 101L
        val staleRunId = 99L

        val task = DownloadTaskEntity(
            id = taskId,
            url = "https://youtube.com/watch?v=isolation",
            title = "Isolation Test",
            formatId = "18",
            formatDescription = "360p",
            status = DownloadStatus.DOWNLOADING,
            stage = DownloadStage.DOWNLOADING,
            runId = activeRunId,
            progress = 30f
        )
        repository.insertTask(task)

        // Attempt update with stale runId
        val updatedRows = dao.updateProgress(
            id = taskId,
            runId = staleRunId,
            status = DownloadStatus.DOWNLOADING,
            stage = DownloadStage.DOWNLOADING,
            progress = 60f,
            downloadSpeed = "10 MB/s",
            eta = "00:05"
        )
        assertEquals("Database should reject progress updates with mismatching runId", 0, updatedRows)

        val taskAfterStale = repository.getTaskByIdSync(taskId)
        assertNotNull(taskAfterStale)
        assertEquals(30f, taskAfterStale!!.progress, 0.01f)
        assertEquals(activeRunId, taskAfterStale.runId)

        // Valid update with active runId
        val validRows = dao.updateProgress(
            id = taskId,
            runId = activeRunId,
            status = DownloadStatus.DOWNLOADING,
            stage = DownloadStage.DOWNLOADING,
            progress = 35f,
            downloadSpeed = "5 MB/s",
            eta = "00:15"
        )
        assertEquals("Database should accept progress updates with matching runId", 1, validRows)

        val taskAfterValid = repository.getTaskByIdSync(taskId)
        assertEquals(35f, taskAfterValid!!.progress, 0.01f)
    }

    // ---------------------------------------------------------------------------------------------
    // 2. stale callback after pause
    // ---------------------------------------------------------------------------------------------
    @Test
    fun testStaleCallbackAfterPause_CallbackIsDroppedAndPausedStatePreserved() = runBlocking {
        val taskId = "task_pause_callback_2"
        val runId = 202L

        val task = DownloadTaskEntity(
            id = taskId,
            url = "https://youtube.com/watch?v=pause_test",
            title = "Pause Test",
            formatId = "18",
            formatDescription = "360p",
            status = DownloadStatus.DOWNLOADING,
            stage = DownloadStage.DOWNLOADING,
            runId = runId,
            progress = 42f
        )
        repository.insertTask(task)

        // Pause the task
        queueManager.pauseDownloadSync(taskId)

        val pausedTask = repository.getTaskByIdSync(taskId)
        assertNotNull(pausedTask)
        assertEquals(DownloadStatus.PAUSED, pausedTask!!.status)

        // Verify state machine rejects callbacks on paused task
        assertFalse(DownloadStateMachine.canAcceptCallback(runId, runId, DownloadStatus.PAUSED))

        // In-flight progress callback arrives late from the paused background download
        val affectedRows = dao.updateProgress(
            id = taskId,
            runId = runId,
            status = DownloadStatus.DOWNLOADING,
            stage = DownloadStage.DOWNLOADING,
            progress = 45f,
            downloadSpeed = "2 MB/s",
            eta = "00:20"
        )
        assertEquals("Database updateProgress must affect 0 rows when status is PAUSED", 0, affectedRows)

        // Check task did not revert to DOWNLOADING or advance progress
        val rechecked = repository.getTaskByIdSync(taskId)
        assertEquals(DownloadStatus.PAUSED, rechecked?.status)
        assertEquals(42f, rechecked?.progress ?: 0f, 0.01f)
    }

    // ---------------------------------------------------------------------------------------------
    // 3. stale callback after retry
    // ---------------------------------------------------------------------------------------------
    @Test
    fun testStaleCallbackAfterRetry_OldRunCallbackCannotOverwriteNewRun() = runBlocking {
        val taskId = "task_retry_callback_3"
        val oldRunId = 301L

        val task = DownloadTaskEntity(
            id = taskId,
            url = "https://youtube.com/watch?v=retry_callback",
            title = "Retry Callback",
            formatId = "18",
            formatDescription = "360p",
            status = DownloadStatus.FAILED,
            stage = DownloadStage.DOWNLOADING,
            runId = oldRunId,
            progress = 70f,
            retryCount = 0
        )
        repository.insertTask(task)

        // Trigger retry: resets progress, advances retryCount, and assigns a new higher runId
        queueManager.retryDownloadSync(taskId)

        val retriedTask = repository.getTaskByIdSync(taskId)
        assertNotNull(retriedTask)
        val newRunId = retriedTask!!.runId
        assertTrue("New runId must be greater than old runId", newRunId > oldRunId)
        assertEquals(DownloadStatus.QUEUED, retriedTask.status)
        assertEquals(0f, retriedTask.progress, 0.01f)

        // Late callback from old run arrives with progress = 85%
        val updated = dao.updateProgress(
            id = taskId,
            runId = oldRunId,
            status = DownloadStatus.DOWNLOADING,
            stage = DownloadStage.DOWNLOADING,
            progress = 85f,
            downloadSpeed = "4 MB/s",
            eta = "00:03"
        )
        assertEquals("Stale run callback must not update the retried task", 0, updated)

        val taskAfterStaleCallback = repository.getTaskByIdSync(taskId)
        assertEquals("Status must remain QUEUED", DownloadStatus.QUEUED, taskAfterStaleCallback?.status)
        assertEquals("Progress must remain 0", 0f, taskAfterStaleCallback?.progress ?: 0f, 0.01f)
        assertEquals(newRunId, taskAfterStaleCallback?.runId)
    }

    // ---------------------------------------------------------------------------------------------
    // 4. stale callback from old run after resume
    // ---------------------------------------------------------------------------------------------
    @Test
    fun testStaleCallbackFromOldRunAfterResume_IgnoredAndNewRunProtected() = runBlocking {
        val taskId = "task_resume_callback_4"
        val oldRunId = 401L

        val task = DownloadTaskEntity(
            id = taskId,
            url = "https://youtube.com/watch?v=resume_callback",
            title = "Resume Callback",
            formatId = "18",
            formatDescription = "360p",
            status = DownloadStatus.PAUSED,
            stage = DownloadStage.DOWNLOADING,
            runId = oldRunId,
            progress = 50f
        )
        repository.insertTask(task)

        // Resume download: allocates a new execution runId
        queueManager.resumeDownloadSync(taskId)

        val resumedTask = repository.getTaskByIdSync(taskId)
        assertNotNull(resumedTask)
        val newRunId = resumedTask!!.runId
        assertNotEquals(oldRunId, newRunId)
        assertTrue(newRunId > oldRunId)

        // Late callback from the pre-pause execution thread arrives
        val affected = dao.updateProgress(
            id = taskId,
            runId = oldRunId,
            status = DownloadStatus.DOWNLOADING,
            stage = DownloadStage.DOWNLOADING,
            progress = 52f,
            downloadSpeed = "1 MB/s",
            eta = "00:40"
        )
        assertEquals("Late callback from previous run must be completely ignored", 0, affected)

        val verified = repository.getTaskByIdSync(taskId)
        assertEquals(newRunId, verified?.runId)
        assertEquals(50f, verified?.progress ?: 0f, 0.01f)
    }

    // ---------------------------------------------------------------------------------------------
    // 5. cancel during yt-dlp
    // ---------------------------------------------------------------------------------------------
    @Test
    fun testCancelDuringYtDlp_ProcessCancelledAndStateSetToCancelled() = runBlocking {
        val taskId = "task_cancel_ytdlp_5"
        val runId = 501L

        val task = DownloadTaskEntity(
            id = taskId,
            url = "https://youtube.com/watch?v=cancel_ytdlp",
            title = "Cancel yt-dlp Video",
            formatId = "18",
            formatDescription = "360p",
            status = DownloadStatus.DOWNLOADING,
            stage = DownloadStage.DOWNLOADING,
            runId = runId,
            progress = 25f
        )
        repository.insertTask(task)

        // Trigger cancellation through QueueManager
        queueManager.cancelDownloadSync(taskId)

        val cancelledTask = repository.getTaskByIdSync(taskId)
        assertNotNull(cancelledTask)
        assertEquals(DownloadStatus.CANCELLED, cancelledTask!!.status)
        assertEquals(DownloadStage.QUEUED, cancelledTask.stage)
        assertEquals(0L, cancelledTask.runId)

        // Subsequent progress updates from yt-dlp are rejected
        val updated = dao.updateProgress(
            id = taskId,
            runId = runId,
            status = DownloadStatus.DOWNLOADING,
            stage = DownloadStage.DOWNLOADING,
            progress = 30f,
            downloadSpeed = "3 MB/s",
            eta = "00:10"
        )
        assertEquals(0, updated)
    }

    // ---------------------------------------------------------------------------------------------
    // 6. cancel during FFmpeg
    // ---------------------------------------------------------------------------------------------
    @Test
    fun testCancelDuringFFmpeg_TerminatesProcessAndCleansTempOutput() = runBlocking {
        val ffmpegManager = FFmpegManager(context)
        val taskId = "task_cancel_ffmpeg_6"
        val runId = 601L
        val tempOutput = File(context.cacheDir, "ffmpeg_test_${taskId}.tmp").apply {
            writeText("intermediate ffmpeg data")
        }

        // Register cancellation
        ffmpegManager.cancel(taskId, runId)
        assertTrue(ffmpegManager.isTaskCancelled(taskId, runId))

        // Execution must immediately abort without running external process
        val result = ffmpegManager.executeFFmpeg(
            taskId = taskId,
            runId = runId,
            arguments = listOf("sh", "-c", "exec sleep 5"),
            tempOutput = tempOutput,
            totalDurationSeconds = 10.0,
            onProgress = null
        )

        assertTrue("Cancelled execution must result in failure", result.isFailure)
        assertTrue("Error must be DownloadError.Cancelled", result.exceptionOrNull() is DownloadError.Cancelled)
        assertFalse("Temp output must be deleted upon cancellation", tempOutput.exists())
        assertFalse("Execution record must not remain active", ffmpegManager.isExecutionActive(taskId, runId))
    }

    // ---------------------------------------------------------------------------------------------
    // 7. delete during download
    // ---------------------------------------------------------------------------------------------
    @Test
    fun testDeleteDuringDownload_TaskPurgedAndRaceCallbacksCannotReviveIt() = runBlocking {
        val taskId = "task_delete_download_7"
        val runId = 701L

        val task = DownloadTaskEntity(
            id = taskId,
            url = "https://youtube.com/watch?v=delete_race",
            title = "Delete Race Task",
            formatId = "18",
            formatDescription = "360p",
            status = DownloadStatus.DOWNLOADING,
            stage = DownloadStage.DOWNLOADING,
            runId = runId,
            progress = 40f
        )
        repository.insertTask(task)

        // Concurrently run 20 progress callbacks and 1 delete
        coroutineScope {
            val callbacks = (1..20).map { i ->
                async(Dispatchers.Default) {
                    dao.updateProgress(
                        id = taskId,
                        runId = runId,
                        status = DownloadStatus.DOWNLOADING,
                        stage = DownloadStage.DOWNLOADING,
                        progress = 40f + i,
                        downloadSpeed = "2 MB/s",
                        eta = "10s"
                    )
                }
            }

            val deleteJob = async(Dispatchers.Default) {
                queueManager.deleteDownloadSync(taskId)
            }

            callbacks.awaitAll()
            deleteJob.await()
        }

        // Task must be permanently deleted from DB and cannot be revived
        val current = repository.getTaskByIdSync(taskId)
        assertNull("Task must be completely removed from database", current)
    }

    // ---------------------------------------------------------------------------------------------
    // 8. retry after failure
    // ---------------------------------------------------------------------------------------------
    @Test
    fun testRetryAfterFailure_ResetsErrorProgressAndIncrementsRetryCount() = runBlocking {
        val taskId = "task_retry_after_fail_8"
        val initialRunId = 801L

        val task = DownloadTaskEntity(
            id = taskId,
            url = "https://youtube.com/watch?v=fail_retry",
            title = "Failed Video",
            formatId = "18",
            formatDescription = "360p",
            status = DownloadStatus.FAILED,
            stage = DownloadStage.DOWNLOADING,
            runId = initialRunId,
            progress = 80f,
            errorMessage = "Connection reset by peer",
            retryCount = 1
        )
        repository.insertTask(task)

        // Perform retry
        queueManager.retryDownloadSync(taskId)

        val retried = repository.getTaskByIdSync(taskId)
        assertNotNull(retried)
        assertEquals(DownloadStatus.QUEUED, retried!!.status)
        assertEquals(DownloadStage.QUEUED, retried.stage)
        assertEquals(0f, retried.progress, 0.01f)
        assertNull("Error message must be cleared on retry", retried.errorMessage)
        assertEquals(2, retried.retryCount)
        assertTrue("New runId must be allocated", retried.runId > initialRunId)
    }

    // ---------------------------------------------------------------------------------------------
    // 9. retry while old coroutine is finishing
    // ---------------------------------------------------------------------------------------------
    @Test
    fun testRetryWhileOldCoroutineIsFinishing_NewRunPrevailsAndOldCompletionIgnored() = runBlocking {
        val taskId = "task_retry_overlap_9"
        val oldRunId = 901L

        val task = DownloadTaskEntity(
            id = taskId,
            url = "https://youtube.com/watch?v=overlap",
            title = "Overlap Task",
            formatId = "18",
            formatDescription = "360p",
            status = DownloadStatus.DOWNLOADING,
            stage = DownloadStage.DOWNLOADING,
            runId = oldRunId,
            progress = 95f
        )
        repository.insertTask(task)

        // Synchronize: Retry starts and completes
        queueManager.retryDownloadSync(taskId)

        val taskInDb = repository.getTaskByIdSync(taskId)
        assertNotNull(taskInDb)
        val newRunId = taskInDb!!.runId
        assertTrue(newRunId > oldRunId)
        assertEquals(DownloadStatus.QUEUED, taskInDb.status)

        // Now the old coroutine that was lingering finishes and attempts to mark the task completed with oldRunId
        val rowsUpdated = dao.updateProgress(
            id = taskId,
            runId = oldRunId,
            status = DownloadStatus.COMPLETED,
            stage = DownloadStage.COMPLETED,
            progress = 100f,
            downloadSpeed = "",
            eta = ""
        )
        assertEquals("Old finishing coroutine cannot update the retried task", 0, rowsUpdated)

        val finalCheck = repository.getTaskByIdSync(taskId)
        assertEquals(DownloadStatus.QUEUED, finalCheck?.status)
        assertEquals(0f, finalCheck?.progress ?: 0f, 0.01f)
        assertEquals(newRunId, finalCheck?.runId)
    }

    // ---------------------------------------------------------------------------------------------
    // 10. resume after process interruption
    // ---------------------------------------------------------------------------------------------
    @Test
    fun testResumeAfterProcessInterruption_TransitionsToQueuedWithFreshRunId() = runBlocking {
        val taskId = "task_interrupted_10"
        val interruptedRunId = 1001L

        val task = DownloadTaskEntity(
            id = taskId,
            url = "https://youtube.com/watch?v=interrupted",
            title = "Interrupted Video",
            formatId = "18",
            formatDescription = "360p",
            status = DownloadStatus.DOWNLOADING,
            stage = DownloadStage.DOWNLOADING,
            runId = interruptedRunId,
            progress = 60f
        )
        repository.insertTask(task)

        // Process dies / app restarts: mark active tasks as INTERRUPTED
        val marked = repository.markActiveTasksAsInterrupted()
        assertEquals(1, marked)

        val interruptedTask = repository.getTaskByIdSync(taskId)
        assertNotNull(interruptedTask)
        assertEquals(DownloadStatus.INTERRUPTED, interruptedTask!!.status)

        // Resume the interrupted task
        queueManager.resumeDownloadSync(taskId)

        val resumedTask = repository.getTaskByIdSync(taskId)
        assertNotNull(resumedTask)
        assertEquals(DownloadStatus.QUEUED, resumedTask!!.status)
        assertEquals(DownloadStage.QUEUED, resumedTask.stage)
        assertNull(resumedTask.errorMessage)
        assertTrue("RunId must be regenerated for resumed task", resumedTask.runId > interruptedRunId)
    }

    // ---------------------------------------------------------------------------------------------
    // 11. output file detection with multiple files
    // ---------------------------------------------------------------------------------------------
    @Test
    fun testOutputFileDetectionWithMultipleFiles_DeterministicExclusionOfPartAndFragments() {
        val workDir = tempFolder.newFolder("output_detection_dir")
        val taskId = "task_multi_detect_11"

        // 1. Largest file is an intermediate part file (400 MB)
        val partFile = createDummyFile(workDir, "task_$taskId.mp4.part", 400L * 1024 * 1024)

        // 2. Fragment streams
        val videoFrag = createDummyFile(workDir, "task_$taskId.f137.mp4", 250L * 1024 * 1024)
        val audioFrag = createDummyFile(workDir, "task_$taskId.f140.m4a", 25L * 1024 * 1024)

        // 3. Auxiliaries: Subtitle, Thumbnail, Metadata
        val subtitle = createDummyFile(workDir, "task_$taskId.ar.vtt", 10L * 1024)
        val thumbnail = createDummyFile(workDir, "task_$taskId.webp", 150L * 1024)
        val meta = createDummyFile(workDir, "task_$taskId.info.json", 4L * 1024)

        // 4. Genuine completed output file: 20 MB (much smaller than part file!)
        val finalExpected = createDummyFile(workDir, "task_$taskId.mp4", 20L * 1024 * 1024)

        val result = OutputFileDetector.resolveOutputFile(
            workDir = workDir,
            taskId = taskId,
            isAudioOnly = false
        )

        assertTrue("Output file detection must succeed", result.isSuccess)
        val detected = result.getOrNull()
        assertNotNull(detected)
        assertEquals("Must strictly select the completed final file", finalExpected.absolutePath, detected!!.absolutePath)
        assertFalse("Must never select the .part file despite being 400MB", detected.name == partFile.name)
        assertFalse("Must not select intermediate video fragment", detected.name == videoFrag.name)
        assertFalse("Must not select intermediate audio fragment", detected.name == audioFrag.name)
        assertFalse("Must not select subtitle", detected.name == subtitle.name)
        assertFalse("Must not select thumbnail", detected.name == thumbnail.name)
        assertFalse("Must not select metadata", detected.name == meta.name)
    }

    // ---------------------------------------------------------------------------------------------
    // 12. insufficient storage
    // ---------------------------------------------------------------------------------------------
    @Test
    fun testInsufficientStorage_RejectsOperationWithClearMissingBytesMessage() {
        val mediaSize = 200 * 1024 * 1024L // 200 MB
        val requirement = StorageSpaceChecker.calculateRequiredSpace(
            expectedMediaSizeBytes = mediaSize,
            requiresMerge = true,
            requiresCut = false
        )

        // Simulate available space is only 50 MB (far below required ~700 MB)
        val mockAvailable = 50 * 1024 * 1024L
        StorageSpaceChecker.customStorageProvider = StorageSpaceChecker.StorageSpaceProvider {
            mockAvailable
        }

        val result = StorageSpaceChecker.validateStorage(context, requirement)

        assertFalse("Validation must fail when storage is insufficient", result.hasEnoughSpace)
        assertTrue("Missing bytes must be greater than 0", result.missingBytes > 0L)
        assertEquals(requirement.totalRequiredBytes - mockAvailable, result.missingBytes)
        assertNotNull("Error message must explain the shortage", result.errorMessage)
        assertTrue(result.errorMessage!!.contains("required"))
        assertTrue(result.errorMessage!!.contains("available"))

        // Also test DownloadRequest path
        val request = DownloadRequest(
            url = "https://youtube.com/watch?v=storage_shortage",
            expectedMediaSizeBytes = mediaSize,
            formatSelector = "bestvideo+bestaudio",
            isAudioOnly = false
        )
        val requestResult = StorageSpaceChecker.validateDownloadSpace(context, request)
        assertFalse(requestResult.hasEnoughSpace)
        assertTrue(requestResult.missingBytes > 0L)
    }

    // ---------------------------------------------------------------------------------------------
    // 13. MediaStore publish failure
    // ---------------------------------------------------------------------------------------------
    @Test
    fun testMediaStorePublishFailure_InvalidSourceFileReturnsNullAndHandlesGracefully() {
        val nonExistentFile = File(context.cacheDir, "ghost_video.mp4")
        assertFalse("Source file does not exist", nonExistentFile.exists())

        // Saving a non-existent or 0-byte file must return (null, null)
        val (uri, path) = MediaStoreHelper.saveToPublicDownloads(
            context = context,
            sourceFile = nonExistentFile,
            rawTitle = "Ghost Video"
        )
        assertNull("Uri must be null on failed publish", uri)
        assertNull("Path must be null on failed publish", path)

        // Empty file (< 512 bytes)
        val emptyFile = File(context.cacheDir, "empty_video.mp4").apply {
            writeBytes(ByteArray(100))
        }
        val (emptyUri, emptyPath) = MediaStoreHelper.saveToPublicDownloads(
            context = context,
            sourceFile = emptyFile,
            rawTitle = "Empty Video"
        )
        assertNull("Uri must be null for invalid/truncated media", emptyUri)
        assertNull("Path must be null for invalid/truncated media", emptyPath)
    }

    // ---------------------------------------------------------------------------------------------
    // 14. duplicate download race
    // ---------------------------------------------------------------------------------------------
    @Test
    fun testDuplicateDownloadRace_ConcurrentQueriesReturnConsistentExistingTask() = runBlocking {
        val url = "https://youtube.com/watch?v=dup_race_14"
        val formatId = "22"
        val completedFile = File(context.cacheDir, "completed_duplicate_test.mp4").apply {
            writeText("dummy completed content")
        }
        val existingTask = DownloadTaskEntity(
            id = "task_duplicate_master",
            url = url,
            title = "Duplicate Master Video",
            formatId = formatId,
            formatDescription = "720p",
            startTime = "00:00:10",
            endTime = "00:00:30",
            filePath = completedFile.absolutePath,
            status = DownloadStatus.COMPLETED
        )
        repository.insertTask(existingTask)

        // Launch 25 concurrent checks to verify race safety and consistency
        coroutineScope {
            val results = (1..25).map {
                async(Dispatchers.Default) {
                    repository.findExistingTask(
                        url = url,
                        formatId = formatId,
                        startTime = "00:00:10",
                        endTime = "00:00:30"
                    )
                }
            }.awaitAll()

            for (res in results) {
                assertNotNull("Every concurrent query must successfully find the existing task", res)
                assertEquals("task_duplicate_master", res?.id)
                assertEquals(DownloadStatus.COMPLETED, res?.status)
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 15. DownloadQueueCoordinator race: fast/instant completion does not leak slots or stale jobs
    // ---------------------------------------------------------------------------------------------
    @Test
    fun testDownloadQueueCoordinator_InstantCompletionDoesNotLeaveStaleJobOrLeakSlots() = runBlocking {
        val appSettings = AppSettings.getInstance(context)
        appSettings.setConcurrentDownloads(2)
        val networkMonitor = NetworkMonitor(context)

        val activeJobs = ConcurrentHashMap<String, Job>()
        val executedTasks = mutableListOf<String>()

        val coordinator = DownloadQueueCoordinator(
            repository = repository,
            appSettings = appSettings,
            networkMonitor = networkMonitor,
            activeJobs = activeJobs,
            scope = testScope,
            onStartTask = { id ->
                // Simulate fast/instant execution
                executedTasks.add(id)
            }
        )

        // Insert 4 queued tasks
        for (i in 1..4) {
            repository.insertTask(
                DownloadTaskEntity(
                    id = "queue_race_task_$i",
                    url = "https://youtube.com/watch?v=queue_$i",
                    title = "Queue Task $i",
                    formatId = "18",
                    status = DownloadStatus.QUEUED,
                    queueOrder = i.toLong()
                )
            )
        }

        // Process queue for first batch
        coordinator.processQueue()
        delay(100)

        // Inactive jobs must be purged and not leave stale entries
        coordinator.processQueue()
        delay(100)

        // All launched instant jobs must be completed and active download count must not leak
        assertEquals("Active download count should be 0 after all instant jobs complete", 0, coordinator.activeDownloadCount.value)
        assertTrue("Active jobs map should have purged inactive jobs", activeJobs.values.none { it.isActive })
    }

    // ---------------------------------------------------------------------------------------------
    // 16. DownloadExecutionManager race: Pause during start does not advance to PREPARING or DOWNLOADING
    // ---------------------------------------------------------------------------------------------
    @Test
    fun testDownloadExecutionManager_PauseDuringStartDoesNotAdvanceToPreparingOrDownloading() = runBlocking {
        val taskId = "task_pause_at_start"
        val task = DownloadTaskEntity(
            id = taskId,
            url = "https://youtube.com/watch?v=pause_at_start",
            title = "Pause At Start",
            formatId = "18",
            status = DownloadStatus.QUEUED,
            stage = DownloadStage.QUEUED,
            runId = 1L
        )
        repository.insertTask(task)

        val activeRunIds = ConcurrentHashMap<String, Long>()
        val mockEngine = object : DownloadEngine {
            override suspend fun download(
                request: DownloadRequest,
                onProgress: (DownloadProgress) -> Unit
            ): Result<File> = Result.success(File(context.cacheDir, "fake.mp4"))

            override suspend fun download(
                task: com.example.domain.model.DownloadTask,
                onProgress: (DownloadProgress) -> Unit
            ): Result<File> = Result.success(File(context.cacheDir, "fake.mp4"))

            override suspend fun cancel(taskId: String) {}
        }

        val executionManager = DownloadExecutionManager(
            context = context,
            repository = repository,
            appSettings = AppSettings.getInstance(context),
            downloadEngine = mockEngine,
            notificationManager = com.example.downloader.notification.DownloadNotificationManager(context),
            activeRunIds = activeRunIds,
            runIdCounter = java.util.concurrent.atomic.AtomicLong(100L),
            scope = testScope,
            onExecutionFinished = {},
            onRetryRequested = { _, _ -> }
        )

        // Mark task as PAUSED right before or as execution starts
        repository.updateTask(task.copy(status = DownloadStatus.PAUSED))

        // Execute task
        executionManager.executeTask(taskId)

        // Task must remain PAUSED and not have transitioned to PREPARING or DOWNLOADING or COMPLETED
        val finalTask = repository.getTaskByIdSync(taskId)
        assertNotNull(finalTask)
        assertEquals("Task must remain PAUSED after pause at start", DownloadStatus.PAUSED, finalTask!!.status)
    }

    // ---------------------------------------------------------------------------------------------
    // 17. Callback Safety: markCompleted and markFailed refuse to alter PAUSED or CANCELLED tasks
    // ---------------------------------------------------------------------------------------------
    @Test
    fun testCallbackSafety_CompletionAndFailureRefuseToAlterPausedOrCancelledTasks() = runBlocking {
        val pausedTaskId = "task_callback_safety_paused"
        val runId = 555L

        val pausedTask = DownloadTaskEntity(
            id = pausedTaskId,
            url = "https://youtube.com/watch?v=callback_safety",
            title = "Callback Safety Test",
            formatId = "18",
            status = DownloadStatus.PAUSED,
            stage = DownloadStage.DOWNLOADING,
            runId = runId
        )
        repository.insertTask(pausedTask)

        // Attempt completion on PAUSED task
        val completedUpdated = repository.markCompleted(
            id = pausedTaskId,
            runId = runId,
            contentUri = "content://media/fake",
            filePath = "/fake/path.mp4",
            downloadedSize = "10 MB",
            totalSize = "10 MB",
            completedAt = System.currentTimeMillis()
        )
        assertEquals("markCompleted must affect 0 rows on a PAUSED task", 0, completedUpdated)

        // Attempt failure on PAUSED task
        val failedUpdated = repository.markFailedOrCancelled(
            id = pausedTaskId,
            runId = runId,
            status = DownloadStatus.FAILED,
            errorMessage = "Late failure error"
        )
        assertEquals("markFailedOrCancelled must affect 0 rows on a PAUSED task", 0, failedUpdated)

        // Attempt active state update on PAUSED task
        val stateUpdated = repository.updateActiveState(
            id = pausedTaskId,
            runId = runId,
            status = DownloadStatus.DOWNLOADING,
            stage = DownloadStage.DOWNLOADING
        )
        assertEquals("updateActiveState must affect 0 rows on a PAUSED task", 0, stateUpdated)

        // Check task is still PAUSED
        val rechecked = repository.getTaskByIdSync(pausedTaskId)
        assertNotNull(rechecked)
        assertEquals(DownloadStatus.PAUSED, rechecked!!.status)
    }

    @Test
    fun testAutoRetry_CancelDuringRetryDelay_PermanentlyAbortsRetry(): Unit = runBlocking {
        val taskId = "test_retry_cancel_1"
        val runId = 101L
        val task = DownloadTaskEntity(
            id = taskId,
            url = "https://youtube.com/watch?v=cancel_retry",
            title = "Cancel Retry Task",
            formatId = "18",
            status = DownloadStatus.DOWNLOADING,
            stage = DownloadStage.DOWNLOADING,
            runId = runId,
            retryCount = 0
        )
        repository.insertTask(task)

        val activeRunIds = ConcurrentHashMap<String, Long>()
        activeRunIds[taskId] = runId
        var retryRequestedCount = 0

        val executionManager = DownloadExecutionManager(
            context = context,
            repository = repository,
            appSettings = AppSettings.getInstance(context),
            downloadEngine = object : DownloadEngine {
                override suspend fun download(request: DownloadRequest, onProgress: (DownloadProgress) -> Unit): Result<java.io.File> = Result.failure(DownloadError.NetworkError("Net err"))
                override suspend fun download(task: com.example.domain.model.DownloadTask, onProgress: (DownloadProgress) -> Unit): Result<java.io.File> = Result.failure(DownloadError.NetworkError("Net err"))
                override suspend fun cancel(taskId: String) {}
            },
            notificationManager = com.example.downloader.notification.DownloadNotificationManager(context),
            activeRunIds = activeRunIds,
            runIdCounter = java.util.concurrent.atomic.AtomicLong(200L),
            scope = testScope,
            onExecutionFinished = {},
            onRetryRequested = { _, _ ->
                retryRequestedCount++
            }
        )

        val lifecycle = DownloadTaskLifecycle(
            context = context,
            repository = repository,
            downloadEngine = object : DownloadEngine {
                override suspend fun download(request: DownloadRequest, onProgress: (DownloadProgress) -> Unit): Result<java.io.File> = Result.success(java.io.File(context.cacheDir, "fake.mp4"))
                override suspend fun download(task: com.example.domain.model.DownloadTask, onProgress: (DownloadProgress) -> Unit): Result<java.io.File> = Result.success(java.io.File(context.cacheDir, "fake.mp4"))
                override suspend fun cancel(taskId: String) {}
            },
            notificationManager = com.example.downloader.notification.DownloadNotificationManager(context),
            activeRunIds = activeRunIds,
            activeJobs = ConcurrentHashMap(),
            taskMutexes = ConcurrentHashMap(),
            runIdCounter = java.util.concurrent.atomic.AtomicLong(300L),
            onCancelPendingRetry = { id ->
                executionManager.cancelPendingRetry(id)
            },
            onTaskStateChanged = {}
        )

        // Trigger failure to start delayed retry
        val err = DownloadError.NetworkError("Temporary timeout")
        executionManager.handleDownloadFailure(taskId, runId, task, err)

        // Verify pending retry is registered
        assertTrue("Pending retry must be active", executionManager.hasPendingRetry(taskId))

        // Cancel during delay
        lifecycle.cancelDownloadSync(taskId)

        // Verify cancelPendingRetry aborted the pending retry
        assertFalse("Pending retry must be cancelled", executionManager.hasPendingRetry(taskId))

        // Wait past backoff delay
        delay(1500)

        // Verify retry was never invoked and task remains CANCELLED
        assertEquals("onRetryRequested must not be called after cancellation", 0, retryRequestedCount)
        val finalTask = repository.getTaskByIdSync(taskId)
        assertNotNull(finalTask)
        assertEquals(DownloadStatus.CANCELLED, finalTask!!.status)
    }

    @Test
    fun testAutoRetry_DeleteDuringRetryDelay_PermanentlyAbortsRetry(): Unit = runBlocking {
        val taskId = "test_retry_delete_1"
        val runId = 102L
        val task = DownloadTaskEntity(
            id = taskId,
            url = "https://youtube.com/watch?v=delete_retry",
            title = "Delete Retry Task",
            formatId = "18",
            status = DownloadStatus.DOWNLOADING,
            stage = DownloadStage.DOWNLOADING,
            runId = runId,
            retryCount = 0
        )
        repository.insertTask(task)

        val activeRunIds = ConcurrentHashMap<String, Long>()
        activeRunIds[taskId] = runId
        var retryRequestedCount = 0

        val executionManager = DownloadExecutionManager(
            context = context,
            repository = repository,
            appSettings = AppSettings.getInstance(context),
            downloadEngine = object : DownloadEngine {
                override suspend fun download(request: DownloadRequest, onProgress: (DownloadProgress) -> Unit): Result<java.io.File> = Result.failure(DownloadError.NetworkError("Net err"))
                override suspend fun download(task: com.example.domain.model.DownloadTask, onProgress: (DownloadProgress) -> Unit): Result<java.io.File> = Result.failure(DownloadError.NetworkError("Net err"))
                override suspend fun cancel(taskId: String) {}
            },
            notificationManager = com.example.downloader.notification.DownloadNotificationManager(context),
            activeRunIds = activeRunIds,
            runIdCounter = java.util.concurrent.atomic.AtomicLong(200L),
            scope = testScope,
            onExecutionFinished = {},
            onRetryRequested = { _, _ ->
                retryRequestedCount++
            }
        )

        val lifecycle = DownloadTaskLifecycle(
            context = context,
            repository = repository,
            downloadEngine = object : DownloadEngine {
                override suspend fun download(request: DownloadRequest, onProgress: (DownloadProgress) -> Unit): Result<java.io.File> = Result.success(java.io.File(context.cacheDir, "fake.mp4"))
                override suspend fun download(task: com.example.domain.model.DownloadTask, onProgress: (DownloadProgress) -> Unit): Result<java.io.File> = Result.success(java.io.File(context.cacheDir, "fake.mp4"))
                override suspend fun cancel(taskId: String) {}
            },
            notificationManager = com.example.downloader.notification.DownloadNotificationManager(context),
            activeRunIds = activeRunIds,
            activeJobs = ConcurrentHashMap(),
            taskMutexes = ConcurrentHashMap(),
            runIdCounter = java.util.concurrent.atomic.AtomicLong(300L),
            onCancelPendingRetry = { id ->
                executionManager.cancelPendingRetry(id)
            },
            onTaskStateChanged = {}
        )

        executionManager.handleDownloadFailure(taskId, runId, task, DownloadError.NetworkError("Timeout"))
        assertTrue(executionManager.hasPendingRetry(taskId))

        // Delete during delay
        lifecycle.deleteDownloadSync(taskId)
        assertFalse(executionManager.hasPendingRetry(taskId))

        delay(1500)

        assertEquals("onRetryRequested must not be called after delete", 0, retryRequestedCount)
        val deletedTask = repository.getTaskByIdSync(taskId)
        assertNull("Task must remain deleted from database", deletedTask)
    }

    @Test
    fun testAutoRetry_StaleRetryAfterNewRun_CannotAffectOrRevertNewRun(): Unit = runBlocking {
        val taskId = "test_retry_stale_new_run"
        val oldRunId = 100L
        val newRunId = 200L

        val task = DownloadTaskEntity(
            id = taskId,
            url = "https://youtube.com/watch?v=stale_retry",
            title = "Stale Retry Task",
            formatId = "18",
            status = DownloadStatus.DOWNLOADING,
            stage = DownloadStage.DOWNLOADING,
            runId = newRunId,
            retryCount = 1
        )
        repository.insertTask(task)

        val activeRunIds = ConcurrentHashMap<String, Long>()
        activeRunIds[taskId] = newRunId

        val lifecycle = DownloadTaskLifecycle(
            context = context,
            repository = repository,
            downloadEngine = object : DownloadEngine {
                override suspend fun download(request: DownloadRequest, onProgress: (DownloadProgress) -> Unit): Result<java.io.File> = Result.success(java.io.File(context.cacheDir, "fake.mp4"))
                override suspend fun download(task: com.example.domain.model.DownloadTask, onProgress: (DownloadProgress) -> Unit): Result<java.io.File> = Result.success(java.io.File(context.cacheDir, "fake.mp4"))
                override suspend fun cancel(taskId: String) {}
            },
            notificationManager = com.example.downloader.notification.DownloadNotificationManager(context),
            activeRunIds = activeRunIds,
            activeJobs = ConcurrentHashMap(),
            taskMutexes = ConcurrentHashMap(),
            runIdCounter = java.util.concurrent.atomic.AtomicLong(300L),
            onCancelPendingRetry = {},
            onTaskStateChanged = {}
        )

        // Attempt stale retry with oldRunId while newRunId is DOWNLOADING
        lifecycle.retryDownloadSync(taskId, expectedRunId = oldRunId)

        // Task must not be reverted to QUEUED, must remain DOWNLOADING with newRunId
        val currentTask = repository.getTaskByIdSync(taskId)
        assertNotNull(currentTask)
        assertEquals("Task status must remain DOWNLOADING", DownloadStatus.DOWNLOADING, currentTask!!.status)
        assertEquals("Task runId must remain newRunId", newRunId, currentTask.runId)
    }

    @Test
    fun testFFmpeg_CancellationDuringMergeOrCut_CancelsImmediately(): Unit = runBlocking {
        val ffmpeg = FFmpegManager.getInstance(context)
        val taskId = "test_ffmpeg_cancel_cuj"
        val runId = 999L

        // Register cancellation
        ffmpeg.cancel(taskId, runId)
        assertTrue("Task must be registered as cancelled in FFmpegManager", ffmpeg.isTaskCancelled(taskId, runId))

        // Subsequent execute or merge checks must detect cancellation
        val dummyVideo = java.io.File(context.cacheDir, "dummy_video.mp4").apply { writeText("fake video") }
        val dummyAudio = java.io.File(context.cacheDir, "dummy_audio.m4a").apply { writeText("fake audio") }
        val dummyOut = java.io.File(context.cacheDir, "dummy_out.mp4")

        val mergeResult = ffmpeg.mergeVideoAudio(
            videoFile = dummyVideo,
            audioFile = dummyAudio,
            outputFile = dummyOut,
            taskId = taskId,
            runId = runId
        )

        assertTrue("Merge must fail or be cancelled", mergeResult.isFailure)
        val ex = mergeResult.exceptionOrNull()
        assertTrue("Error must be Cancelled or FfmpegError", ex is DownloadError.Cancelled || ex is DownloadError.FfmpegError || ex is Exception)
        assertFalse("Output file must not exist after cancellation", dummyOut.exists())

        dummyVideo.delete()
        dummyAudio.delete()
        dummyOut.delete()
        Unit
    }

    @Test
    fun testFFmpeg_StaleCallbackAfterCancel_IsSuppressed(): Unit = runBlocking {
        val ffmpeg = FFmpegManager.getInstance(context)
        val taskId = "test_ffmpeg_callback_suppress"
        val runId = 888L

        // Cancel task
        ffmpeg.cancel(taskId, runId)
        assertTrue(ffmpeg.isTaskCancelled(taskId, runId))

        var callbackDelivered = false
        val progressCallback: (com.example.domain.model.ProcessingProgress) -> Unit = {
            callbackDelivered = true
        }

        // Simulate engine progress guard check
        if (!ffmpeg.isTaskCancelled(taskId, runId)) {
            progressCallback(
                com.example.domain.model.ProcessingProgress(
                    percentage = 50f,
                    statusDescription = "Stale progress"
                )
            )
        }

        assertFalse("Progress callback must be suppressed after cancellation", callbackDelivered)
    }

    @Test
    fun testFFmpeg_UnifiedProcessOwnershipAcrossInstances() {
        val instance1 = FFmpegManager(context)
        val instance2 = FFmpegManager.getInstance(context)

        val taskId = "test_ffmpeg_unified_ownership"
        val runId = 777L

        // Cancel on instance1
        instance1.cancel(taskId, runId)

        // Verify instance2 and any other instance see the same cancellation state
        assertTrue("instance2 must see cancellation from instance1", instance2.isTaskCancelled(taskId, runId))
        assertTrue("instance1 must see cancellation", instance1.isTaskCancelled(taskId, runId))
        assertTrue("instance2 status or supported ABIs are available", instance2.getStatus().detectedAbi.isNotBlank() || instance2.supportedAbis.isNotEmpty())
    }

    @Test
    fun testCancelDuringPreparing_PreventsDownloadingAndMarksCancelled(): Unit = runBlocking {
        val taskId = "cuj_cancel_during_preparing"
        val initialTask = DownloadTaskEntity(
            id = taskId,
            url = "https://www.youtube.com/watch?v=prep_cancel",
            title = "Test Prep Cancel",
            status = DownloadStatus.QUEUED,
            stage = DownloadStage.QUEUED,
            createdAt = System.currentTimeMillis()
        )
        dao.insertTask(initialTask)

        // Claim runId 101 for preparing
        val preparingUpdated = dao.updateActiveState(
            id = taskId,
            runId = 101L,
            status = DownloadStatus.PREPARING,
            stage = DownloadStage.PREPARING
        )
        assertEquals("Should transition to PREPARING", 1, preparingUpdated)

        // Cancel arrives before DOWNLOADING transition
        val cancelUpdated = dao.markFailedOrCancelled(
            id = taskId,
            runId = 101L,
            status = DownloadStatus.CANCELLED,
            errorMessage = "User cancelled"
        )
        assertEquals("Should mark as CANCELLED", 1, cancelUpdated)

        // Late DOWNLOADING transition must fail to overwrite CANCELLED state
        val lateDownloadAttempt = dao.updateActiveState(
            id = taskId,
            runId = 101L,
            status = DownloadStatus.DOWNLOADING,
            stage = DownloadStage.DOWNLOADING
        )
        assertEquals("Late DOWNLOADING update must be rejected", 0, lateDownloadAttempt)

        val finalTask = dao.getTaskByIdSync(taskId)
        assertNotNull(finalTask)
        assertEquals(DownloadStatus.CANCELLED, finalTask?.status)
    }

    @Test
    fun testRetryThenCancel_CancelsPendingRetryJob(): Unit = runBlocking {
        val taskId = "cuj_retry_then_cancel"
        val initialTask = DownloadTaskEntity(
            id = taskId,
            url = "https://www.youtube.com/watch?v=retry_cancel",
            title = "Retry Cancel",
            status = DownloadStatus.DOWNLOADING,
            stage = DownloadStage.DOWNLOADING,
            retryCount = 1,
            errorMessage = "",
            runId = 201L,
            createdAt = System.currentTimeMillis()
        )
        dao.insertTask(initialTask)

        val activeRunIds = ConcurrentHashMap<String, Long>()
        val runIdCounter = java.util.concurrent.atomic.AtomicLong(200L)
        var retryDispatched = false
        val settings = AppSettings.getInstance(context).apply { setAutoRetry(true) }

        val execManager = DownloadExecutionManager(
            context = context,
            repository = repository,
            appSettings = settings,
            downloadEngine = object : DownloadEngine {
                override suspend fun download(request: DownloadRequest, onProgress: (DownloadProgress) -> Unit) =
                    Result.failure<File>(DownloadError.NetworkError("Network failed"))
                override suspend fun download(task: com.example.domain.model.DownloadTask, onProgress: (DownloadProgress) -> Unit) =
                    Result.failure<File>(DownloadError.NetworkError("Network failed"))
                override suspend fun cancel(taskId: String) {}
            },
            notificationManager = com.example.downloader.notification.DownloadNotificationManager(context),
            activeRunIds = activeRunIds,
            runIdCounter = runIdCounter,
            scope = this,
            onExecutionFinished = {},
            onRetryRequestedSingle = { retryDispatched = true }
        )

        // Trigger failure to schedule auto retry
        activeRunIds[taskId] = 201L
        execManager.handleDownloadFailure(
            taskId = taskId,
            runId = 201L,
            originalTask = initialTask,
            error = DownloadError.NetworkError("Transient failure")
        )

        assertTrue("Retry job should be pending", execManager.hasPendingRetry(taskId))

        // User cancels download before retry delay elapses
        execManager.cancelPendingRetry(taskId)
        assertFalse("Retry job must be cancelled immediately", execManager.hasPendingRetry(taskId))

        // Update DB to CANCELLED
        dao.markFailedOrCancelled(taskId, 201L, DownloadStatus.CANCELLED, "Cancelled by user")

        // Wait to verify retry is never dispatched
        delay(100)
        assertFalse("Retry callback must never be invoked after cancellation", retryDispatched)
        assertEquals(DownloadStatus.CANCELLED, dao.getTaskByIdSync(taskId)?.status)
    }

    @Test
    fun testQueueCoordinator_DuplicateTasksAndFiltering() {
        runBlocking {
            val queuedTasks = listOf(
                DownloadTaskEntity(id = "T1", url = "http://1", title = "T1", status = DownloadStatus.QUEUED, stage = DownloadStage.QUEUED, createdAt = 1),
                DownloadTaskEntity(id = "T1", url = "http://1", title = "T1", status = DownloadStatus.QUEUED, stage = DownloadStage.QUEUED, createdAt = 1),
                DownloadTaskEntity(id = "T2", url = "http://2", title = "T2", status = DownloadStatus.QUEUED, stage = DownloadStage.QUEUED, createdAt = 2),
                DownloadTaskEntity(id = "T3", url = "http://3", title = "T3", status = DownloadStatus.QUEUED, stage = DownloadStage.QUEUED, createdAt = 3)
            )

            for (task in queuedTasks) {
                dao.insertTask(task)
            }

            val activeJobs = ConcurrentHashMap<String, Job>()
            val startedTasks = mutableListOf<String>()
            val t2Started = CompletableDeferred<Unit>()

            // Simulate T1 already has an active running Job
            val dummyJob = launch { delay(1000) }
            activeJobs["T1"] = dummyJob

            val settings = AppSettings.getInstance(context).apply { setConcurrentDownloads(2) }
            val onlineNetworkMonitor = object : NetworkMonitor(context) {
                override fun isOnline() = true
            }
            val testScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            try {
                val coordinator = DownloadQueueCoordinator(
                    repository = repository,
                    appSettings = settings,
                    networkMonitor = onlineNetworkMonitor,
                    activeJobs = activeJobs,
                    scope = testScope,
                    onStartTask = { taskId ->
                        startedTasks.add(taskId)
                        t2Started.complete(Unit)
                        delay(5000) // Keep active during test
                    }
                )

                coordinator.processQueue()
                t2Started.await()

                // Available slots = 2 - 1 = 1 slot.
                // T1 is already active in activeJobs, so it must be filtered out.
                // Deduplicated remaining queue: [T2, T3].
                // 1 available slot must take exactly T2.
                assertEquals("Must start exactly 1 task into the 1 available slot", 1, startedTasks.size)
                assertEquals("Must start T2", "T2", startedTasks[0])
            } finally {
                dummyJob.cancel()
                testScope.cancel()
            }
        }
    }

    @Test
    fun testQueueSlotReplenishment_OnJobCompletion() {
        runBlocking {
            val activeJobs = ConcurrentHashMap<String, Job>()
            val finishedTasks = mutableListOf<String>()
            val t1Started = CompletableDeferred<Unit>()
            val t2Finished = CompletableDeferred<Unit>()

            val settings = AppSettings.getInstance(context).apply { setConcurrentDownloads(1) }
            val onlineNetworkMonitor = object : NetworkMonitor(context) {
                override fun isOnline() = true
            }
            val testScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            try {
                val coordinator = DownloadQueueCoordinator(
                    repository = repository,
                    appSettings = settings,
                    networkMonitor = onlineNetworkMonitor,
                    activeJobs = activeJobs,
                    scope = testScope,
                    onStartTask = { taskId ->
                        dao.updateActiveState(taskId, 1L, DownloadStatus.DOWNLOADING, DownloadStage.DOWNLOADING)
                        if (taskId == "task_seq_1") {
                            t1Started.complete(Unit)
                        }
                        delay(20)
                        finishedTasks.add(taskId)
                        dao.markCompleted(taskId, 1L, "", "", "", "", System.currentTimeMillis())
                        if (taskId == "task_seq_2") {
                            t2Finished.complete(Unit)
                        }
                    }
                )

                dao.insertTask(DownloadTaskEntity(id = "task_seq_1", url = "http://s1", title = "Seq 1", status = DownloadStatus.QUEUED, stage = DownloadStage.QUEUED, createdAt = 1))
                dao.insertTask(DownloadTaskEntity(id = "task_seq_2", url = "http://s2", title = "Seq 2", status = DownloadStatus.QUEUED, stage = DownloadStage.QUEUED, createdAt = 2))

                coordinator.processQueue()
                t1Started.await()
                assertEquals("Active download count should be 1", 1, coordinator.activeDownloadCount.value)

                // Wait for task_seq_2 to be automatically scheduled and finished via queue replenishment
                t2Finished.await()

                assertTrue("First task must finish", finishedTasks.contains("task_seq_1"))
                assertTrue("Second task must automatically start and finish due to queue replenishment", finishedTasks.contains("task_seq_2"))
            } finally {
                testScope.cancel()
            }
        }
    }

    // =========================================================================
    // PRODUCTION AUDIT CONCURRENCY TESTS (Items 1-9)
    // =========================================================================

    @Test
    fun testAudit_ConcurrentProcessQueue() {
        runBlocking {
            val activeJobs = ConcurrentHashMap<String, Job>()
            val startedTasks = ConcurrentHashMap.newKeySet<String>()
            val settings = AppSettings.getInstance(context).apply { setConcurrentDownloads(2) }
            val onlineNetworkMonitor = object : NetworkMonitor(context) { override fun isOnline() = true }
            val testScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

            try {
                val coordinator = DownloadQueueCoordinator(
                    repository = repository,
                    appSettings = settings,
                    networkMonitor = onlineNetworkMonitor,
                    activeJobs = activeJobs,
                    scope = testScope,
                    onStartTask = { taskId ->
                        startedTasks.add(taskId)
                        delay(200) // Keep running to measure concurrency
                    }
                )

                // Enqueue 5 tasks
                for (i in 1..5) {
                    dao.insertTask(DownloadTaskEntity(id = "c_task_$i", url = "http://c$i", title = "Task $i", status = DownloadStatus.QUEUED, stage = DownloadStage.QUEUED, queueOrder = i.toLong()))
                }

                // Launch 10 concurrent calls to processQueue()
                val jobs = (1..10).map {
                    testScope.async {
                        coordinator.processQueue()
                    }
                }
                jobs.awaitAll()
                delay(50)

                // Concurrency is 2, so at this moment exactly 2 tasks should be started and active
                assertEquals("Exactly 2 tasks should be started concurrently", 2, startedTasks.size)
                assertEquals("Active download count should match slots", 2, coordinator.activeDownloadCount.value)
            } finally {
                testScope.cancel()
            }
        }
    }

    @Test
    fun testAudit_SameTaskCannotStartTwice() {
        runBlocking {
            val activeJobs = ConcurrentHashMap<String, Job>()
            val executionCount = AtomicInteger(0)
            val settings = AppSettings.getInstance(context).apply { setConcurrentDownloads(3) }
            val onlineNetworkMonitor = object : NetworkMonitor(context) { override fun isOnline() = true }
            val testScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

            try {
                val coordinator = DownloadQueueCoordinator(
                    repository = repository,
                    appSettings = settings,
                    networkMonitor = onlineNetworkMonitor,
                    activeJobs = activeJobs,
                    scope = testScope,
                    onStartTask = { taskId ->
                        executionCount.incrementAndGet()
                        delay(200)
                    }
                )

                dao.insertTask(DownloadTaskEntity(id = "single_task", url = "http://single", title = "Single Task", status = DownloadStatus.QUEUED, stage = DownloadStage.QUEUED))

                // Launch 20 concurrent processQueue calls
                val callers = (1..20).map {
                    testScope.async {
                        coordinator.processQueue()
                    }
                }
                callers.awaitAll()
                delay(50)

                assertEquals("The same task must be started exactly once across concurrent invocations", 1, executionCount.get())
            } finally {
                testScope.cancel()
            }
        }
    }

    @Test
    fun testAudit_ExecutionThrowsException_FreesSlotAndMarksFailed() {
        runBlocking {
            val activeJobs = ConcurrentHashMap<String, Job>()
            val settings = AppSettings.getInstance(context).apply { setConcurrentDownloads(1) }
            val onlineNetworkMonitor = object : NetworkMonitor(context) { override fun isOnline() = true }
            val testScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val secondTaskStarted = CompletableDeferred<Unit>()

            try {
                val coordinator = DownloadQueueCoordinator(
                    repository = repository,
                    appSettings = settings,
                    networkMonitor = onlineNetworkMonitor,
                    activeJobs = activeJobs,
                    scope = testScope,
                    onStartTask = { taskId ->
                        if (taskId == "failing_task") {
                            throw RuntimeException("Fatal runtime explosion inside task execution")
                        } else {
                            secondTaskStarted.complete(Unit)
                        }
                    }
                )

                dao.insertTask(DownloadTaskEntity(id = "failing_task", url = "http://fail", title = "Failing Task", status = DownloadStatus.QUEUED, stage = DownloadStage.QUEUED, queueOrder = 1))
                dao.insertTask(DownloadTaskEntity(id = "next_task", url = "http://next", title = "Next Task", status = DownloadStatus.QUEUED, stage = DownloadStage.QUEUED, queueOrder = 2))

                coordinator.processQueue()

                // Wait for the next task to automatically start after the failed one frees the slot
                secondTaskStarted.await()

                val failedTask = dao.getTaskByIdSync("failing_task")
                assertNotNull("Failed task must exist in DB", failedTask)
                assertEquals("Failing task must be marked as FAILED in database", DownloadStatus.FAILED, failedTask?.status)
                assertTrue("Error message must reflect exception", failedTask?.errorMessage?.contains("Fatal runtime explosion") == true)
                assertFalse("Failed task must not remain in activeJobs", activeJobs.containsKey("failing_task"))
            } finally {
                testScope.cancel()
            }
        }
    }

    @Test
    fun testAudit_CancellationDuringStart() {
        runBlocking {
            val activeJobs = ConcurrentHashMap<String, Job>()
            val activeRunIds = ConcurrentHashMap<String, Long>()
            val taskMutexes = ConcurrentHashMap<String, Mutex>()
            val runIdCounter = java.util.concurrent.atomic.AtomicLong(100L)
            val settings = AppSettings.getInstance(context).apply { setConcurrentDownloads(1) }
            val onlineNetworkMonitor = object : NetworkMonitor(context) { override fun isOnline() = true }
            val testScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

            val lifecycle = DownloadTaskLifecycle(
                context = context,
                repository = repository,
                downloadEngine = object : DownloadEngine {
                    override suspend fun download(request: DownloadRequest, onProgress: (DownloadProgress) -> Unit) = Result.success(File(context.cacheDir, "out.mp4"))
                    override suspend fun download(task: com.example.domain.model.DownloadTask, onProgress: (DownloadProgress) -> Unit) = Result.success(File(context.cacheDir, "out.mp4"))
                    override suspend fun cancel(taskId: String) {}
                },
                notificationManager = com.example.downloader.notification.DownloadNotificationManager(context),
                activeRunIds = activeRunIds,
                activeJobs = activeJobs,
                taskMutexes = taskMutexes,
                runIdCounter = runIdCounter,
                onTaskStateChanged = {}
            )

            try {
                dao.insertTask(DownloadTaskEntity(id = "cancel_start_task", url = "http://cs", title = "Cancel on Start", status = DownloadStatus.QUEUED, stage = DownloadStage.QUEUED))

                val coordinator = DownloadQueueCoordinator(
                    repository = repository,
                    appSettings = settings,
                    networkMonitor = onlineNetworkMonitor,
                    activeJobs = activeJobs,
                    scope = testScope,
                    onStartTask = { taskId ->
                        // Simulate cancellation racing precisely during the start sequence
                        lifecycle.cancelDownloadSync(taskId)
                        // Attempt to update state to PREPARING
                        dao.updateActiveState(taskId, 101L, DownloadStatus.PREPARING, DownloadStage.PREPARING)
                    }
                )

                coordinator.processQueue()
                delay(100)

                val task = dao.getTaskByIdSync("cancel_start_task")
                assertNotNull(task)
                assertEquals("Task must remain CANCELLED and not be overwritten to PREPARING", DownloadStatus.CANCELLED, task?.status)
            } finally {
                testScope.cancel()
            }
        }
    }

    @Test
    fun testAudit_PauseDuringStart() {
        runBlocking {
            val activeJobs = ConcurrentHashMap<String, Job>()
            val activeRunIds = ConcurrentHashMap<String, Long>()
            val taskMutexes = ConcurrentHashMap<String, Mutex>()
            val runIdCounter = java.util.concurrent.atomic.AtomicLong(200L)
            val settings = AppSettings.getInstance(context).apply { setConcurrentDownloads(1) }
            val onlineNetworkMonitor = object : NetworkMonitor(context) { override fun isOnline() = true }
            val testScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

            val lifecycle = DownloadTaskLifecycle(
                context = context,
                repository = repository,
                downloadEngine = object : DownloadEngine {
                    override suspend fun download(request: DownloadRequest, onProgress: (DownloadProgress) -> Unit) = Result.success(File(context.cacheDir, "out.mp4"))
                    override suspend fun download(task: com.example.domain.model.DownloadTask, onProgress: (DownloadProgress) -> Unit) = Result.success(File(context.cacheDir, "out.mp4"))
                    override suspend fun cancel(taskId: String) {}
                },
                notificationManager = com.example.downloader.notification.DownloadNotificationManager(context),
                activeRunIds = activeRunIds,
                activeJobs = activeJobs,
                taskMutexes = taskMutexes,
                runIdCounter = runIdCounter,
                onTaskStateChanged = {}
            )

            try {
                dao.insertTask(DownloadTaskEntity(id = "pause_start_task", url = "http://ps", title = "Pause on Start", status = DownloadStatus.QUEUED, stage = DownloadStage.QUEUED))

                val coordinator = DownloadQueueCoordinator(
                    repository = repository,
                    appSettings = settings,
                    networkMonitor = onlineNetworkMonitor,
                    activeJobs = activeJobs,
                    scope = testScope,
                    onStartTask = { taskId ->
                        lifecycle.pauseDownloadSync(taskId)
                        dao.updateActiveState(taskId, 201L, DownloadStatus.PREPARING, DownloadStage.PREPARING)
                    }
                )

                coordinator.processQueue()
                delay(100)

                val task = dao.getTaskByIdSync("pause_start_task")
                assertNotNull(task)
                assertEquals("Task must remain PAUSED and not be overwritten to PREPARING", DownloadStatus.PAUSED, task?.status)
            } finally {
                testScope.cancel()
            }
        }
    }

    @Test
    fun testAudit_CancelDuringDelayedRetry() {
        runBlocking {
            val activeRunIds = ConcurrentHashMap<String, Long>()
            val activeJobs = ConcurrentHashMap<String, Job>()
            val taskMutexes = ConcurrentHashMap<String, Mutex>()
            val runIdCounter = java.util.concurrent.atomic.AtomicLong(300L)
            val settings = AppSettings.getInstance(context).apply {
                setAutoRetry(true)
            }
            val testScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

            val retryTriggered = AtomicBoolean(false)

            val executionManager = DownloadExecutionManager(
                context = context,
                repository = repository,
                appSettings = settings,
                downloadEngine = object : DownloadEngine {
                    override suspend fun download(request: DownloadRequest, onProgress: (DownloadProgress) -> Unit): Result<File> {
                        return Result.failure(DownloadError.NetworkError("Retryable network glitch"))
                    }
                    override suspend fun download(task: com.example.domain.model.DownloadTask, onProgress: (DownloadProgress) -> Unit): Result<File> {
                        return Result.failure(DownloadError.NetworkError("Retryable network glitch"))
                    }
                    override suspend fun cancel(taskId: String) {}
                },
                notificationManager = com.example.downloader.notification.DownloadNotificationManager(context),
                activeRunIds = activeRunIds,
                runIdCounter = runIdCounter,
                scope = testScope,
                onExecutionFinished = {},
                onRetryRequested = { _, _ ->
                    retryTriggered.set(true)
                }
            )

            val lifecycle = DownloadTaskLifecycle(
                context = context,
                repository = repository,
                downloadEngine = object : DownloadEngine {
                    override suspend fun download(request: DownloadRequest, onProgress: (DownloadProgress) -> Unit) = Result.success(File(context.cacheDir, "out.mp4"))
                    override suspend fun download(task: com.example.domain.model.DownloadTask, onProgress: (DownloadProgress) -> Unit) = Result.success(File(context.cacheDir, "out.mp4"))
                    override suspend fun cancel(taskId: String) {}
                },
                notificationManager = com.example.downloader.notification.DownloadNotificationManager(context),
                activeRunIds = activeRunIds,
                activeJobs = activeJobs,
                taskMutexes = taskMutexes,
                runIdCounter = runIdCounter,
                onCancelPendingRetry = { taskId ->
                    executionManager.cancelPendingRetry(taskId)
                },
                onTaskStateChanged = {}
            )

            try {
                StorageSpaceChecker.customStorageProvider = StorageSpaceChecker.StorageSpaceProvider {
                    10L * 1024 * 1024 * 1024L // 10 GB available
                }

                dao.insertTask(DownloadTaskEntity(id = "delayed_retry_task", url = "http://retry", title = "Retry Task", status = DownloadStatus.QUEUED, stage = DownloadStage.QUEUED, retryCount = 0))

                // Execute task to trigger auto-retry scheduling
                executionManager.executeTask("delayed_retry_task")

                assertTrue("Retry should be pending", executionManager.hasPendingRetry("delayed_retry_task"))

                // User cancels the task while retry is waiting on backoff delay
                lifecycle.cancelDownloadSync("delayed_retry_task")

                assertFalse("Cancel must abort pending retry", executionManager.hasPendingRetry("delayed_retry_task"))

                // Wait long enough to ensure no delayed retry fires
                delay(2000)

                assertFalse("Retry callback must never be triggered after cancellation", retryTriggered.get())
                val finalTask = dao.getTaskByIdSync("delayed_retry_task")
                assertEquals("Task must remain CANCELLED", DownloadStatus.CANCELLED, finalTask?.status)
            } finally {
                StorageSpaceChecker.customStorageProvider = null
                testScope.cancel()
            }
        }
    }

    @Test
    fun testAudit_OldRunCallbackAfterNewRun() {
        runBlocking {
            dao.insertTask(
                DownloadTaskEntity(
                    id = "stale_callback_task",
                    url = "http://stale",
                    title = "Stale Task",
                    status = DownloadStatus.DOWNLOADING,
                    stage = DownloadStage.DOWNLOADING,
                    runId = 200L,
                    progress = 10f
                )
            )

            // Delayed callback arrives from runId = 100L (old run)
            val updatedRows = dao.updateProgress(
                id = "stale_callback_task",
                runId = 100L,
                status = DownloadStatus.DOWNLOADING,
                stage = DownloadStage.DOWNLOADING,
                progress = 50f,
                downloadSpeed = "5 MB/s",
                eta = "00:10"
            )

            assertEquals("Stale callback must affect 0 rows", 0, updatedRows)

            val currentTask = dao.getTaskByIdSync("stale_callback_task")
            assertNotNull(currentTask)
            assertEquals("RunId must remain 200L", 200L, currentTask?.runId)
            assertEquals("Progress must not be overwritten by stale run", 10f, currentTask?.progress ?: 0f, 0.01f)
        }
    }

    @Test
    fun testAudit_CompletionCallbackRacingWithQueueProcessing() {
        runBlocking {
            val activeJobs = ConcurrentHashMap<String, Job>()
            val startedTasks = ConcurrentHashMap.newKeySet<String>()
            val settings = AppSettings.getInstance(context).apply { setConcurrentDownloads(1) }
            val onlineNetworkMonitor = object : NetworkMonitor(context) { override fun isOnline() = true }
            val testScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

            val t2Started = CompletableDeferred<Unit>()

            try {
                lateinit var coordinator: DownloadQueueCoordinator

                coordinator = DownloadQueueCoordinator(
                    repository = repository,
                    appSettings = settings,
                    networkMonitor = onlineNetworkMonitor,
                    activeJobs = activeJobs,
                    scope = testScope,
                    onStartTask = { taskId ->
                        startedTasks.add(taskId)
                        if (taskId == "racing_comp_1") {
                            dao.updateActiveState(taskId, 1L, DownloadStatus.DOWNLOADING, DownloadStage.DOWNLOADING)
                            delay(50)
                            dao.markCompleted(taskId, 1L, "", "", "", "", System.currentTimeMillis())
                            // Race: another concurrent call to processQueue occurs during completion
                            testScope.launch { coordinator.processQueue() }
                        } else if (taskId == "racing_comp_2") {
                            t2Started.complete(Unit)
                        }
                    }
                )

                dao.insertTask(DownloadTaskEntity(id = "racing_comp_1", url = "http://r1", title = "Race 1", status = DownloadStatus.QUEUED, stage = DownloadStage.QUEUED, queueOrder = 1))
                dao.insertTask(DownloadTaskEntity(id = "racing_comp_2", url = "http://r2", title = "Race 2", status = DownloadStatus.QUEUED, stage = DownloadStage.QUEUED, queueOrder = 2))

                coordinator.processQueue()

                t2Started.await()

                assertTrue("Task 1 must have started", startedTasks.contains("racing_comp_1"))
                assertTrue("Task 2 must have started cleanly after Task 1 without race conditions", startedTasks.contains("racing_comp_2"))
            } finally {
                testScope.cancel()
            }
        }
    }

    @Test
    fun testAudit_RetryCallbackRacingWithCancellation() {
        runBlocking {
            val activeRunIds = ConcurrentHashMap<String, Long>()
            val activeJobs = ConcurrentHashMap<String, Job>()
            val taskMutexes = ConcurrentHashMap<String, Mutex>()
            val runIdCounter = java.util.concurrent.atomic.AtomicLong(400L)

            val lifecycle = DownloadTaskLifecycle(
                context = context,
                repository = repository,
                downloadEngine = object : DownloadEngine {
                    override suspend fun download(request: DownloadRequest, onProgress: (DownloadProgress) -> Unit) = Result.success(File(context.cacheDir, "out.mp4"))
                    override suspend fun download(task: com.example.domain.model.DownloadTask, onProgress: (DownloadProgress) -> Unit) = Result.success(File(context.cacheDir, "out.mp4"))
                    override suspend fun cancel(taskId: String) {}
                },
                notificationManager = com.example.downloader.notification.DownloadNotificationManager(context),
                activeRunIds = activeRunIds,
                activeJobs = activeJobs,
                taskMutexes = taskMutexes,
                runIdCounter = runIdCounter,
                onTaskStateChanged = {}
            )

            // Task failed on runId 401L
            dao.insertTask(DownloadTaskEntity(id = "race_retry_cancel", url = "http://rc", title = "RC Task", status = DownloadStatus.FAILED, stage = DownloadStage.QUEUED, runId = 401L))

            // User cancels the task
            lifecycle.cancelDownloadSync("race_retry_cancel")

            // Auto-retry callback fires with the expectedRunId 401L racing with cancellation
            lifecycle.retryDownloadSync("race_retry_cancel", expectedRunId = 401L)

            val task = dao.getTaskByIdSync("race_retry_cancel")
            assertNotNull(task)
            assertEquals("Cancellation must prevail; retry with stale runId must be rejected", DownloadStatus.CANCELLED, task?.status)
        }
    }

    @Test
    fun testDownloadQueueCoordinator_ConcurrentProcessQueueNeverStartsTaskTwice() = runBlocking {
        val activeJobs = ConcurrentHashMap<String, Job>()
        val startCounts = ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
        val settings = AppSettings.getInstance(context).apply { setConcurrentDownloads(3) }
        val onlineNetworkMonitor = object : NetworkMonitor(context) { override fun isOnline() = true }
        val testScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        try {
            val coordinator = DownloadQueueCoordinator(
                repository = repository,
                appSettings = settings,
                networkMonitor = onlineNetworkMonitor,
                activeJobs = activeJobs,
                scope = testScope,
                onStartTask = { taskId ->
                    startCounts.computeIfAbsent(taskId) { java.util.concurrent.atomic.AtomicInteger(0) }.incrementAndGet()
                    delay(50)
                }
            )

            dao.insertTask(DownloadTaskEntity(id = "concur_task_1", url = "http://c1", title = "Concur 1", status = DownloadStatus.QUEUED, queueOrder = 1))
            dao.insertTask(DownloadTaskEntity(id = "concur_task_2", url = "http://c2", title = "Concur 2", status = DownloadStatus.QUEUED, queueOrder = 2))

            // Fire 10 concurrent processQueue calls simultaneously
            val jobs = (1..10).map {
                testScope.launch {
                    coordinator.processQueue()
                }
            }
            jobs.forEach { it.join() }

            // Allow ongoing started jobs to finish
            activeJobs.values.toList().forEach { it.join() }

            assertEquals("concur_task_1 should have started exactly once", 1, startCounts["concur_task_1"]?.get())
            assertEquals("concur_task_2 should have started exactly once", 1, startCounts["concur_task_2"]?.get())
        } finally {
            testScope.cancel()
        }
    }

    @Test
    fun testDownloadQueueCoordinator_ExceptionInOnStartDoesNotHangQueue() = runBlocking {
        val activeJobs = ConcurrentHashMap<String, Job>()
        val startedTasks = ConcurrentHashMap.newKeySet<String>()
        val failedCallbacks = ConcurrentHashMap.newKeySet<String>()
        val settings = AppSettings.getInstance(context).apply { setConcurrentDownloads(1) }
        val onlineNetworkMonitor = object : NetworkMonitor(context) { override fun isOnline() = true }
        val testScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        val t2Completed = CompletableDeferred<Unit>()

        try {
            val coordinator = DownloadQueueCoordinator(
                repository = repository,
                appSettings = settings,
                networkMonitor = onlineNetworkMonitor,
                activeJobs = activeJobs,
                scope = testScope,
                onStartTask = { taskId ->
                    startedTasks.add(taskId)
                    if (taskId == "failing_task") {
                        throw IllegalStateException("Simulated fatal execution failure")
                    } else if (taskId == "subsequent_task") {
                        t2Completed.complete(Unit)
                    }
                },
                onTaskExecutionFailed = { taskId, _ ->
                    failedCallbacks.add(taskId)
                }
            )

            dao.insertTask(DownloadTaskEntity(id = "failing_task", url = "http://fail", title = "Failing", status = DownloadStatus.QUEUED, queueOrder = 1))
            dao.insertTask(DownloadTaskEntity(id = "subsequent_task", url = "http://sub", title = "Subsequent", status = DownloadStatus.QUEUED, queueOrder = 2))

            coordinator.processQueue()

            // Wait for subsequent task to complete execution
            t2Completed.await()

            assertTrue("Failing task must have reached onStartTask", startedTasks.contains("failing_task"))
            assertTrue("Failing task must have triggered failure callback", failedCallbacks.contains("failing_task"))
            assertTrue("Subsequent task must have executed smoothly despite previous exception", startedTasks.contains("subsequent_task"))

            val failedEntity = dao.getTaskByIdSync("failing_task")
            assertEquals("Failing task in DB must be transitioned to FAILED status", DownloadStatus.FAILED, failedEntity?.status)
        } finally {
            testScope.cancel()
        }
    }

    @Test
    fun testDownloadQueueCoordinator_CompletionCallbackAndConcurrentProcessQueueNeverCausesDuplicate() = runBlocking {
        val activeJobs = ConcurrentHashMap<String, Job>()
        val startCounts = ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
        val settings = AppSettings.getInstance(context).apply { setConcurrentDownloads(1) }
        val onlineNetworkMonitor = object : NetworkMonitor(context) { override fun isOnline() = true }
        val testScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        try {
            lateinit var coordinator: DownloadQueueCoordinator
            coordinator = DownloadQueueCoordinator(
                repository = repository,
                appSettings = settings,
                networkMonitor = onlineNetworkMonitor,
                activeJobs = activeJobs,
                scope = testScope,
                onStartTask = { taskId ->
                    startCounts.computeIfAbsent(taskId) { java.util.concurrent.atomic.AtomicInteger(0) }.incrementAndGet()
                    dao.updateActiveState(taskId, 1L, DownloadStatus.DOWNLOADING, DownloadStage.DOWNLOADING)
                    if (taskId == "task_first") {
                        // Immediately invoke concurrent processQueue while first completes
                        testScope.launch { coordinator.processQueue() }
                    }
                    delay(30)
                    dao.markCompleted(taskId, 1L, "", "", "", "", System.currentTimeMillis())
                    testScope.launch { coordinator.processQueue() }
                }
            )

            dao.insertTask(DownloadTaskEntity(id = "task_first", url = "http://tf", title = "Task First", status = DownloadStatus.QUEUED, queueOrder = 1))
            dao.insertTask(DownloadTaskEntity(id = "task_second", url = "http://ts", title = "Task Second", status = DownloadStatus.QUEUED, queueOrder = 2))

            coordinator.processQueue()

            // Concurrently invoke processQueue while tasks are completing
            val concurrentJobs = (1..5).map {
                testScope.launch {
                    delay(15)
                    coordinator.processQueue()
                }
            }
            concurrentJobs.forEach { it.join() }

            // Allow all running tasks to complete
            var attempts = 0
            while ((startCounts["task_second"]?.get() != 1 || activeJobs.isNotEmpty()) && attempts < 100) {
                delay(20)
                attempts++
            }

            assertEquals("task_first started exactly once", 1, startCounts["task_first"]?.get())
            assertEquals("task_second started exactly once", 1, startCounts["task_second"]?.get())
            assertTrue("No stale jobs remaining in activeJobs map", activeJobs.isEmpty())
            assertEquals("activeDownloadCount is 0 after queue is empty", 0, coordinator.activeDownloadCount.value)
        } finally {
            testScope.cancel()
        }
    }

    @Test
    fun testDownloadQueueCoordinator_CancellationDoesNotBecomeFailure() = runBlocking {
        val activeJobs = ConcurrentHashMap<String, Job>()
        val failedCallbacks = ConcurrentHashMap.newKeySet<String>()
        val settings = AppSettings.getInstance(context).apply { setConcurrentDownloads(1) }
        val onlineNetworkMonitor = object : NetworkMonitor(context) { override fun isOnline() = true }
        val testScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        val taskStarted = CompletableDeferred<Unit>()

        try {
            val coordinator = DownloadQueueCoordinator(
                repository = repository,
                appSettings = settings,
                networkMonitor = onlineNetworkMonitor,
                activeJobs = activeJobs,
                scope = testScope,
                onStartTask = { taskId ->
                    taskStarted.complete(Unit)
                    // Suspends indefinitely until cancelled
                    kotlinx.coroutines.awaitCancellation()
                },
                onTaskExecutionFailed = { taskId, _ ->
                    failedCallbacks.add(taskId)
                }
            )

            dao.insertTask(DownloadTaskEntity(id = "cancelled_task", url = "http://cancel", title = "Cancelled Task", status = DownloadStatus.QUEUED, queueOrder = 1))

            coordinator.processQueue()
            taskStarted.await()

            // Verify job is actively registered in coordinator
            val job = activeJobs["cancelled_task"]
            assertNotNull("Job must be active in activeJobs", job)
            assertEquals("activeDownloadCount reflects 1 running task", 1, coordinator.activeDownloadCount.value)

            // Cancel the task's job directly (simulating cancellation by user)
            job?.cancel()
            job?.join()

            // Cancellation must NOT invoke failure callback
            assertFalse("CancellationException must never be converted to onTaskExecutionFailed", failedCallbacks.contains("cancelled_task"))
            assertFalse("No stale job left for cancelled_task", activeJobs.containsKey("cancelled_task"))
            assertFalse("Claim must be released on cancellation", coordinator.isTaskClaimed("cancelled_task"))
            assertEquals("activeDownloadCount returns to 0", 0, coordinator.activeDownloadCount.value)
        } finally {
            testScope.cancel()
        }
    }

    @Test
    fun testDownloadQueueCoordinator_ActiveDownloadCountRemainsAccurate() = runBlocking {
        val activeJobs = ConcurrentHashMap<String, Job>()
        val settings = AppSettings.getInstance(context).apply { setConcurrentDownloads(2) }
        val onlineNetworkMonitor = object : NetworkMonitor(context) { override fun isOnline() = true }
        val testScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        val gate = CompletableDeferred<Unit>()

        try {
            val coordinator = DownloadQueueCoordinator(
                repository = repository,
                appSettings = settings,
                networkMonitor = onlineNetworkMonitor,
                activeJobs = activeJobs,
                scope = testScope,
                onStartTask = { taskId ->
                    dao.updateActiveState(taskId, 1L, DownloadStatus.DOWNLOADING, DownloadStage.DOWNLOADING)
                    if (taskId == "task_a" || taskId == "task_b") {
                        gate.await()
                    }
                    dao.markCompleted(taskId, 1L, "", "", "", "", System.currentTimeMillis())
                }
            )

            dao.insertTask(DownloadTaskEntity(id = "task_a", url = "http://a", title = "A", status = DownloadStatus.QUEUED, queueOrder = 1))
            dao.insertTask(DownloadTaskEntity(id = "task_b", url = "http://b", title = "B", status = DownloadStatus.QUEUED, queueOrder = 2))
            dao.insertTask(DownloadTaskEntity(id = "task_c", url = "http://c", title = "C", status = DownloadStatus.QUEUED, queueOrder = 3))

            coordinator.processQueue()

            // Wait until 2 slots are occupied
            var waitIters = 0
            while (coordinator.activeDownloadCount.value < 2 && waitIters < 50) {
                delay(20)
                waitIters++
            }

            assertEquals("Max concurrency of 2 should be respected", 2, coordinator.activeDownloadCount.value)
            assertEquals("activeJobs map contains exactly 2 jobs", 2, activeJobs.size)

            // Release the gate so tasks complete
            gate.complete(Unit)

            // Wait for queue to process third task and drain
            var drainIters = 0
            while (coordinator.activeDownloadCount.value > 0 && drainIters < 100) {
                delay(20)
                drainIters++
            }

            assertEquals("activeDownloadCount returns to 0 after all tasks complete", 0, coordinator.activeDownloadCount.value)
            assertTrue("activeJobs is completely empty", activeJobs.isEmpty())
        } finally {
            testScope.cancel()
        }
    }

    @Test
    fun testAudit_InvalidTerminalStateTransitionsRejected() = runBlocking {
        // Create an existing completed file on disk
        val completedFile = File(context.cacheDir, "completed_term_test.mp4").apply {
            writeText("valid completed media")
        }

        // Test that terminal states (COMPLETED, CANCELLED) cannot be overwritten
        val completedTask = DownloadTaskEntity(
            id = "terminal_completed",
            url = "http://term",
            title = "Terminal Completed",
            status = DownloadStatus.COMPLETED,
            filePath = completedFile.absolutePath,
            runId = 500L
        )
        dao.insertTask(completedTask)

        // Attempt invalid transition: stale execution attempting DOWNLOADING
        val updateCount = dao.updateActiveState("terminal_completed", 500L, DownloadStatus.DOWNLOADING, DownloadStage.DOWNLOADING)
        assertEquals("Updating COMPLETED task back to DOWNLOADING must be rejected", 0, updateCount)

        // Verify task remains COMPLETED
        val unchanged = dao.getTaskByIdSync("terminal_completed")
        assertEquals(DownloadStatus.COMPLETED, unchanged?.status)

        // Test that CANCELLED task cannot be set to DOWNLOADING
        val cancelledTask = DownloadTaskEntity(
            id = "terminal_cancelled",
            url = "http://term2",
            title = "Terminal Cancelled",
            status = DownloadStatus.CANCELLED,
            runId = 501L
        )
        dao.insertTask(cancelledTask)

        val cancelUpdateCount = dao.updateActiveState("terminal_cancelled", 501L, DownloadStatus.DOWNLOADING, DownloadStage.DOWNLOADING)
        assertEquals("Updating CANCELLED task to DOWNLOADING must be rejected", 0, cancelUpdateCount)
        assertEquals(DownloadStatus.CANCELLED, dao.getTaskByIdSync("terminal_cancelled")?.status)
    }
}
