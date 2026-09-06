package com.example.downloader.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.domain.model.CutMode
import com.example.domain.model.DownloadRequest
import com.example.domain.model.MediaResult
import com.example.domain.model.ProcessingProgress
import com.example.downloader.ffmpeg.FFmpegManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests verifying the unified media merging architecture:
 *
 * 1. Architecture Determination: yt-dlp is strictly responsible for download only,
 *    and FFmpeg is the sole unified authority for merging and cutting.
 *    (yt-dlp request does not configure --ffmpeg-location or --download-sections).
 *
 * 2. Single-Merge Guarantee: Merging is never executed twice for the same task,
 *    even under retry, re-trigger, or concurrent invocations.
 *
 * 3. Clear Pipeline Flow:
 *    download -> merge (when needed) -> cut (when needed) -> publish.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaMergingUnificationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var testWorkDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        testWorkDir = tempFolder.newFolder("work_dir")
    }

    /**
     * Fake MediaProcessor implementation that records invocations and generates realistic outputs.
     */
    private class FakeMediaProcessor : MediaProcessor {
        val mergeCallCount = AtomicInteger(0)
        val cutCallCount = AtomicInteger(0)

        override suspend fun mergeVideoAudio(
            videoFile: File,
            audioFile: File,
            outputFile: File,
            taskId: String?,
            runId: Long?,
            onProgress: ((ProcessingProgress) -> Unit)?
        ): Result<MediaResult> {
            mergeCallCount.incrementAndGet()
            outputFile.parentFile?.mkdirs()
            outputFile.writeText("FAKE_MERGED_MEDIA_DATA")
            return Result.success(
                MediaResult(
                    outputFile = outputFile,
                    mimeType = "video/mp4",
                    durationSeconds = 60.0,
                    sizeBytes = outputFile.length(),
                    operation = "Video+Audio Merge"
                )
            )
        }

        override suspend fun fastCut(
            inputFile: File,
            startTime: String,
            endTime: String,
            outputFile: File,
            taskId: String?,
            runId: Long?,
            onProgress: ((ProcessingProgress) -> Unit)?
        ): Result<MediaResult> = cutMedia(inputFile, outputFile, startTime, endTime, CutMode.FAST_CUT, taskId, runId, onProgress)

        override suspend fun preciseCut(
            inputFile: File,
            startTime: String,
            endTime: String,
            outputFile: File,
            taskId: String?,
            runId: Long?,
            onProgress: ((ProcessingProgress) -> Unit)?
        ): Result<MediaResult> = cutMedia(inputFile, outputFile, startTime, endTime, CutMode.PRECISE_CUT, taskId, runId, onProgress)

        override suspend fun cutMedia(
            inputFile: File,
            outputFile: File,
            startTime: String,
            endTime: String,
            mode: CutMode,
            taskId: String?,
            runId: Long?,
            onProgress: ((ProcessingProgress) -> Unit)?
        ): Result<MediaResult> {
            cutCallCount.incrementAndGet()
            outputFile.parentFile?.mkdirs()
            outputFile.writeText("FAKE_CUT_MEDIA_DATA")
            return Result.success(
                MediaResult(
                    outputFile = outputFile,
                    mimeType = "video/mp4",
                    durationSeconds = 15.0,
                    sizeBytes = outputFile.length(),
                    operation = "Cut Media"
                )
            )
        }

        override suspend fun remux(
            inputFile: File,
            outputFile: File,
            targetContainer: String,
            taskId: String?,
            runId: Long?,
            onProgress: ((ProcessingProgress) -> Unit)?
        ): Result<MediaResult> = Result.success(MediaResult(outputFile, "video/$targetContainer"))

        override suspend fun extractAudio(
            inputFile: File,
            outputFile: File,
            audioCodec: String,
            taskId: String?,
            runId: Long?,
            onProgress: ((ProcessingProgress) -> Unit)?
        ): Result<MediaResult> = Result.success(MediaResult(outputFile, "audio/mp4"))

        override fun cancel(processId: String) {}
        override fun cancel(taskId: String, runId: Long?) {}
    }

    // ---------------------------------------------------------------------------------------------
    // 1. Architecture Determination: yt-dlp = download only, FFmpeg = merge & cut
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `yt-dlp is configured for download only and delegates merge and cut strictly to FFmpeg`() {
        val fakeProcessor = FakeMediaProcessor()
        val engine = YtDlpDownloadEngine(context, fakeProcessor)

        val cutRequest = DownloadRequest(
            id = "test_req_1",
            url = "https://example.com/watch?v=12345",
            startTime = "00:00:10",
            endTime = "00:00:25",
            formatSelector = "137+bestaudio/best"
        )

        val ytRequest = engine.buildYoutubeDLRequest(testWorkDir, cutRequest)
        val options = ytRequest.getOptions()

        // 1. yt-dlp must NOT be configured with --ffmpeg-location
        // yt-dlp should not perform post-processing mux behind our back
        assertFalse(
            "yt-dlp must not receive --ffmpeg-location so that merge is strictly delegated to FFmpegManager",
            options.contains("--ffmpeg-location")
        )

        // 2. yt-dlp must NOT be configured with --download-sections
        // Cutting is strictly handled in Stage 3 by FFmpegManager
        assertFalse(
            "yt-dlp must not receive --download-sections so that trimming is strictly delegated to FFmpegManager",
            options.contains("--download-sections")
        )
        assertFalse(
            "yt-dlp must not receive --force-keyframes-at-cuts",
            options.contains("--force-keyframes-at-cuts")
        )

        // 3. Format selector must be preserved for pure network acquisition
        assertTrue(options.contains("-f"))
        val formatIdx = options.indexOf("-f")
        assertTrue(formatIdx >= 0 && formatIdx < options.size - 1)
        assertEquals("137+bestaudio/best", options[formatIdx + 1])
    }

    // ---------------------------------------------------------------------------------------------
    // 2. Single-Merge Guarantee: Never allow merge twice for the same task
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `mergeMediaIfNeeded executes merge exactly once when separate video and audio streams exist`() = runBlocking {
        val fakeProcessor = FakeMediaProcessor()
        val engine = YtDlpDownloadEngine(context, fakeProcessor)
        engine.clearMergeHistory()

        val taskId = "task_once_1"
        val request = DownloadRequest(
            id = taskId,
            runId = 101L,
            url = "https://example.com/watch?v=once"
        )

        // Simulate separate downloaded video and audio stream fragments
        val videoFrag = File(testWorkDir, "task_${taskId}.f137.mp4").apply { writeText("raw video fragment") }
        val audioFrag = File(testWorkDir, "task_${taskId}.f140.m4a").apply { writeText("raw audio fragment") }

        assertTrue(videoFrag.exists())
        assertTrue(audioFrag.exists())

        val mergedResult = engine.mergeMediaIfNeeded(taskId, testWorkDir, request)

        // Merge must have executed exactly once
        assertEquals(1, fakeProcessor.mergeCallCount.get())
        assertNotNull(mergedResult)
        assertTrue(mergedResult!!.exists())
        assertEquals("task_${taskId}_merged.mp4", mergedResult.name)

        // Source fragments must have been deleted to physically prevent re-merge
        assertFalse(videoFrag.exists())
        assertFalse(audioFrag.exists())

        // In-memory status must record completion
        assertTrue(engine.isMergeExecutedForTask(taskId, 101L))
    }

    @Test
    fun `mergeMediaIfNeeded strictly skips duplicate merge if already executed for the same task`() = runBlocking {
        val fakeProcessor = FakeMediaProcessor()
        val engine = YtDlpDownloadEngine(context, fakeProcessor)
        engine.clearMergeHistory()

        val taskId = "task_duplicate_guard"
        val request = DownloadRequest(
            id = taskId,
            runId = 202L,
            url = "https://example.com/watch?v=duplicate"
        )

        // Simulate separate stream fragments
        File(testWorkDir, "task_${taskId}.f137.mp4").apply { writeText("raw video fragment") }
        File(testWorkDir, "task_${taskId}.f140.m4a").apply { writeText("raw audio fragment") }

        // First merge call: executes merge
        val firstResult = engine.mergeMediaIfNeeded(taskId, testWorkDir, request)
        assertNotNull(firstResult)
        assertEquals(1, fakeProcessor.mergeCallCount.get())

        // Second merge call for the SAME task: must NOT call mergeVideoAudio again
        val secondResult = engine.mergeMediaIfNeeded(taskId, testWorkDir, request)
        assertNotNull(secondResult)
        assertEquals(
            "mergeVideoAudio must NOT be invoked twice for the same task execution",
            1,
            fakeProcessor.mergeCallCount.get()
        )
        assertEquals(firstResult!!.absolutePath, secondResult!!.absolutePath)
    }

    @Test
    fun `mergeMediaIfNeeded skips merge if merged file already exists on disk`() = runBlocking {
        val fakeProcessor = FakeMediaProcessor()
        val engine = YtDlpDownloadEngine(context, fakeProcessor)
        engine.clearMergeHistory()

        val taskId = "task_disk_exists"
        val request = DownloadRequest(
            id = taskId,
            runId = 303L,
            url = "https://example.com/watch?v=disk"
        )

        // Pre-create already merged output file
        val existingMerged = File(testWorkDir, "task_${taskId}_merged.mp4").apply {
            writeText("PRE_EXISTING_MERGED_FILE")
        }

        val result = engine.mergeMediaIfNeeded(taskId, testWorkDir, request)
        assertNotNull(result)
        assertEquals(existingMerged.absolutePath, result!!.absolutePath)

        // Processor merge should not have been called
        assertEquals(0, fakeProcessor.mergeCallCount.get())
        assertTrue(engine.isMergeExecutedForTask(taskId, 303L))
    }

    @Test
    fun `mergeMediaIfNeeded skips merge when not needed for audio-only or single-stream downloads`() = runBlocking {
        val fakeProcessor = FakeMediaProcessor()
        val engine = YtDlpDownloadEngine(context, fakeProcessor)

        val taskId = "task_audio_only"
        val audioRequest = DownloadRequest(
            id = taskId,
            url = "https://example.com/watch?v=audio",
            isAudioOnly = true
        )

        // Only single audio file exists
        File(testWorkDir, "task_${taskId}.mp3").apply { writeText("AUDIO_DATA") }

        val result = engine.mergeMediaIfNeeded(taskId, testWorkDir, audioRequest)
        assertNull("Merge must not run for audio-only request", result)
        assertEquals(0, fakeProcessor.mergeCallCount.get())
    }

    @Test
    fun `concurrent mergeMediaIfNeeded invocations for the same task execute merge exactly once`() = runBlocking {
        val fakeProcessor = FakeMediaProcessor()
        val engine = YtDlpDownloadEngine(context, fakeProcessor)
        engine.clearMergeHistory()

        val taskId = "task_concurrent_race"
        val request = DownloadRequest(
            id = taskId,
            runId = 404L,
            url = "https://example.com/watch?v=race"
        )

        File(testWorkDir, "task_${taskId}.f137.mp4").apply { writeText("raw video fragment") }
        File(testWorkDir, "task_${taskId}.f140.m4a").apply { writeText("raw audio fragment") }

        // Launch 5 parallel coroutines simultaneously attempting to merge the same task
        val deferredResults = (1..5).map {
            async(Dispatchers.IO) {
                engine.mergeMediaIfNeeded(taskId, testWorkDir, request)
            }
        }

        val results = deferredResults.awaitAll()
        assertEquals(
            "mergeVideoAudio must be executed exactly once even across concurrent attempts",
            1,
            fakeProcessor.mergeCallCount.get()
        )
        results.forEach { file ->
            assertNotNull(file)
            assertTrue(file!!.exists())
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 3. Media Pipeline: download -> merge (when needed) -> cut (when needed) -> publish
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `cutMediaIfNeeded executes cut only when time trim is requested`() = runBlocking {
        val fakeProcessor = FakeMediaProcessor()
        val engine = YtDlpDownloadEngine(context, fakeProcessor)

        val taskId = "task_cut_test"
        val initialMedia = File(testWorkDir, "task_${taskId}_merged.mp4").apply {
            writeText("FULL_LENGTH_VIDEO")
        }

        // Case A: No time trim requested
        val fullVideoReq = DownloadRequest(
            id = taskId,
            url = "https://example.com/watch?v=full"
        )
        val noCutResult = engine.cutMediaIfNeeded(taskId, testWorkDir, fullVideoReq, initialMedia)
        assertEquals(initialMedia.absolutePath, noCutResult.absolutePath)
        assertEquals(0, fakeProcessor.cutCallCount.get())

        // Case B: Time trim requested
        val trimReq = DownloadRequest(
            id = taskId,
            url = "https://example.com/watch?v=trim",
            startTime = "00:01:00",
            endTime = "00:02:00"
        )
        val cutResult = engine.cutMediaIfNeeded(taskId, testWorkDir, trimReq, initialMedia)
        assertEquals(1, fakeProcessor.cutCallCount.get())
        assertEquals("task_${taskId}_cut.mp4", cutResult.name)
        assertTrue(cutResult.exists())
    }

    @Test
    fun `FFmpegManager mergeVideoAudio prevents duplicate execution for the same task and runId`() = runBlocking {
        val ffmpegManager = FFmpegManager(context)
        ffmpegManager.clearMergeHistory()

        val taskId = "task_ffmpeg_dup_test"
        val runId = 999L

        val dummyVideo = File(testWorkDir, "dummy_video.mp4").apply { writeText("video") }
        val dummyAudio = File(testWorkDir, "dummy_audio.m4a").apply { writeText("audio") }
        val outputFile = File(testWorkDir, "merged_output.mp4").apply { writeText("already merged media") }

        // If outputFile exists and isMergeCompleted is marked, mergeVideoAudio returns cached success immediately
        val isFirstDone = ffmpegManager.isMergeCompleted(taskId, runId)
        assertFalse(isFirstDone)

        // Calling mergeVideoAudio when output exists:
        // First simulate a completed merge by recording key
        val key = "$taskId:$runId"
        val mergeField = FFmpegManager::class.java.getDeclaredField("completedTaskMerges")
        mergeField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val set = mergeField.get(ffmpegManager) as MutableSet<String>
        set.add(key)

        val result = ffmpegManager.mergeVideoAudio(dummyVideo, dummyAudio, outputFile, taskId, runId)
        assertTrue(result.isSuccess)
        assertEquals("Video+Audio Merge (Already Merged)", result.getOrNull()?.operation)
    }
}
