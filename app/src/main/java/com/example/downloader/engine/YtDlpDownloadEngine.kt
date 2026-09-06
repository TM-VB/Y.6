package com.example.downloader.engine

import android.content.Context
import com.example.data.settings.AppSettings
import com.example.data.settings.CookieSecurityManager
import com.example.domain.model.DownloadError
import com.example.domain.model.DownloadProgress
import com.example.domain.model.DownloadRequest
import com.example.domain.model.DownloadStage
import com.example.domain.model.DownloadTask
import com.example.domain.model.FormatInfo
import com.example.domain.model.PlaylistEntry
import com.example.domain.model.PlaylistInfo
import com.example.domain.model.SubtitleTrack
import com.example.domain.model.VideoInfo
import com.example.domain.model.VideoMetadata
import com.example.downloader.ffmpeg.FFmpegManager
import com.example.storage.MediaStoreHelper
import com.example.storage.StorageSpaceChecker
import com.example.ytdlp.FormatParser
import com.example.ytdlp.YtDlpErrorMapper
import com.example.ytdlp.YtDlpLogger
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * Unified single source of truth for all yt-dlp operations.
 *
 * Implements [YtDlpMediaEngine], unifying:
 * - extractInfo & playlist extraction
 * - video/audio downloads
 * - deterministic output file detection (no maxByOrNull)
 * - process lifecycle & cancellation
 * - cookie management
 * - error mapping
 */
class YtDlpDownloadEngine(
    context: Context,
    ffmpegManager: MediaProcessor? = null
) : YtDlpMediaEngine {

    private val appContext: Context = context.applicationContext

    // Unified single authority for FFmpeg media processing
    private val ffmpegManager: MediaProcessor = ffmpegManager ?: FFmpegManager.getInstance(appContext)

    companion object {
        @Volatile
        private var instance: YtDlpDownloadEngine? = null

        fun getInstance(context: Context): YtDlpDownloadEngine {
            return instance ?: synchronized(this) {
                instance ?: YtDlpDownloadEngine(
                    context.applicationContext,
                    FFmpegManager.getInstance(context.applicationContext)
                ).also { instance = it }
            }
        }
    }

    private val speedPattern = Pattern.compile("""(\d+(?:\.\d+)?\s*(?:[kKMGT]?i?[bB])/s)""")
    private val sizePattern = Pattern.compile("""(\d+(?:\.\d+)?\s*(?:[kKMGT]?i?[bB]))\s*of\s*(?:~?\s*)(\d+(?:\.\d+)?\s*(?:[kKMGT]?i?[bB]))""")

    // Shared lifecycle tracking for active processes (extractions and downloads)
    private val activeProcesses = ConcurrentHashMap<String, String>()
    private val cancelledTasks = ConcurrentHashMap.newKeySet<String>()

    // Track task executions that have completed a merge operation to prevent duplicate merges
    private val mergedTaskExecutions = ConcurrentHashMap.newKeySet<String>()
    private val mergeMutexes = ConcurrentHashMap<String, Mutex>()

    fun isMergeExecutedForTask(taskId: String, runId: Long? = 0L): Boolean {
        val key = "$taskId:${runId ?: 0L}"
        return mergedTaskExecutions.contains(key)
    }

    fun markMergeExecuted(taskId: String, runId: Long? = 0L) {
        val key = "$taskId:${runId ?: 0L}"
        mergedTaskExecutions.add(key)
    }

    fun clearMergeHistory() {
        mergedTaskExecutions.clear()
        mergeMutexes.clear()
        cancelledTasks.clear()
    }

    @Volatile
    private var isInitialized = false

    init {
        init(appContext)
    }

    override fun init(context: Context): Result<Unit> {
        if (isInitialized) return Result.success(Unit)
        return synchronized(this) {
            if (isInitialized) return@synchronized Result.success(Unit)
            try {
                YoutubeDL.getInstance().init(context.applicationContext)
                com.yausername.ffmpeg.FFmpeg.getInstance().init(context.applicationContext)
                isInitialized = true
                Result.success(Unit)
            } catch (t: Throwable) {
                Result.failure(t)
            }
        }
    }

    override fun isReady(): Boolean = isInitialized

    override fun getVersion(context: Context): String {
        return try {
            YoutubeDL.getInstance().version(context.applicationContext) ?: "2025.x (Embedded)"
        } catch (_: Throwable) {
            "2025.x (Embedded)"
        }
    }

    override suspend fun updateEngine(context: Context): Result<String> = withContext(Dispatchers.IO) {
        try {
            val status = YoutubeDL.getInstance().updateYoutubeDL(context.applicationContext)
            Result.success(status?.name ?: "Updated")
        } catch (t: Throwable) {
            val domainError = YtDlpErrorMapper.map(t)
            Result.failure(domainError)
        }
    }

    override suspend fun validateUrl(url: String): Boolean = withContext(Dispatchers.IO) {
        val trimmed = url.trim()
        (trimmed.startsWith("http://") || trimmed.startsWith("https://")) && trimmed.length > 8
    }

    override fun isPlaylistUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("list=") || lower.contains("/playlist") || lower.contains("/sets/")
    }

    override suspend fun extractPlaylist(
        url: String,
        processId: String?
    ): Result<PlaylistInfo> = withContext(Dispatchers.IO) {
        val trimmedUrl = url.trim()
        if (!validateUrl(trimmedUrl)) {
            return@withContext Result.failure(
                DownloadError.InvalidUrl("Please enter a valid playlist URL", "URL must begin with http:// or https://")
            )
        }

        init(appContext)
        val procId = processId ?: "playlist_${System.currentTimeMillis()}"
        activeProcesses[procId] = trimmedUrl

        var cookiesFile: File? = null
        try {
            val request = YoutubeDLRequest(trimmedUrl).apply {
                addOption("--flat-playlist")
                addOption("--dump-single-json")
                addOption("--no-warnings")
                addOption("--socket-timeout", "30")
                addOption("--geo-bypass")
                addOption("--extractor-args", "youtube:player_client=android,web")
            }

            cookiesFile = prepareCookiesFile(procId)
            if (cookiesFile != null && cookiesFile.exists()) {
                request.addOption("--cookies", cookiesFile.absolutePath)
            }

            val response = YoutubeDL.getInstance().execute(request, procId)
            val jsonStr = response.out ?: "{}"
            val json = JSONObject(jsonStr)

            val title = json.optString("title", "Playlist")
            val uploader = if (json.has("uploader") && !json.isNull("uploader")) json.optString("uploader") else null
            val id = json.optString("id", "")
            val webpageUrl = json.optString("webpage_url", trimmedUrl)
            val entriesArray = json.optJSONArray("entries")
            val entries = mutableListOf<PlaylistEntry>()

            if (entriesArray != null) {
                for (i in 0 until entriesArray.length()) {
                    val item = entriesArray.optJSONObject(i) ?: continue
                    val entryId = item.optString("id", "$i")
                    val entryTitle = item.optString("title", "Video #${i + 1}")
                    val duration = if (item.has("duration") && !item.isNull("duration")) item.optLong("duration") else null
                    val itemUploader = if (item.has("uploader") && !item.isNull("uploader")) item.optString("uploader") else uploader
                    val rawUrl = item.optString("url", "")
                    val entryUrl = if (rawUrl.startsWith("http")) {
                        rawUrl
                    } else if (rawUrl.isNotBlank()) {
                        "https://www.youtube.com/watch?v=$rawUrl"
                    } else {
                        "https://www.youtube.com/watch?v=$entryId"
                    }
                    val thumbnail = if (item.has("thumbnail") && !item.isNull("thumbnail")) item.optString("thumbnail") else null

                    entries.add(
                        PlaylistEntry(
                            id = entryId,
                            title = entryTitle,
                            durationSeconds = duration,
                            uploader = itemUploader,
                            url = entryUrl,
                            thumbnailUrl = thumbnail,
                            isSelected = true
                        )
                    )
                }
            }

            val playlistInfo = PlaylistInfo(
                id = id,
                title = title,
                uploader = uploader,
                webpageUrl = webpageUrl,
                thumbnailUrl = if (entries.isNotEmpty()) entries[0].thumbnailUrl else null,
                entries = entries
            )

            Result.success(playlistInfo)
        } catch (e: Throwable) {
            CookieSecurityManager.deleteTempCookieFile(cookiesFile)
            val domainError = YtDlpErrorMapper.map(e)
            Result.failure(domainError)
        } finally {
            activeProcesses.remove(procId)
            CookieSecurityManager.deleteTempCookieFile(cookiesFile)
            CookieSecurityManager.deleteTempCookieFilesForTag(appContext, procId)
        }
    }

    override suspend fun extractInfo(
        url: String,
        processId: String?
    ): Result<VideoInfo> = withContext(Dispatchers.IO) {
        val trimmedUrl = url.trim()
        val startTime = System.currentTimeMillis()

        if (!validateUrl(trimmedUrl)) {
            return@withContext Result.failure(
                DownloadError.InvalidUrl("Please enter a valid video URL", "URL must begin with http:// or https://")
            )
        }

        init(appContext)
        val procId = processId ?: "extract_${System.currentTimeMillis()}"
        activeProcesses[procId] = trimmedUrl

        YtDlpLogger.logAnalyzeStarted(trimmedUrl, procId)

        val clientStrategies = listOf(
            "youtube:player_client=android,web,ios",
            "youtube:player_client=android_embedded,web_embedded",
            "youtube:player_client=mweb,tv,ios",
            "youtube:player_client=web,tv_embedded"
        )

        var lastException: Throwable? = null
        var cookiesFile: File? = null

        try {
            cookiesFile = prepareCookiesFile(procId)

            for ((index, clientArg) in clientStrategies.withIndex()) {
                try {
                    val request = YoutubeDLRequest(trimmedUrl).apply {
                        addOption("--no-playlist")
                        addOption("--no-warnings")
                        addOption("--socket-timeout", "25")
                        addOption("--geo-bypass")
                        addOption("--retries", "3")
                        addOption("--extractor-args", clientArg)
                    }

                    if (cookiesFile != null && cookiesFile.exists()) {
                        request.addOption("--cookies", cookiesFile.absolutePath)
                    }

                    val info = YoutubeDL.getInstance().getInfo(request)
                    val title = info.title?.trim().orEmpty().ifEmpty { "Video" }
                    val parsedFormats = FormatParser.parseFormats(info.formats)

                    if (parsedFormats.isNotEmpty()) {
                        val durationMs = System.currentTimeMillis() - startTime
                        YtDlpLogger.logAnalyzeCompleted(
                            url = trimmedUrl,
                            formatCount = parsedFormats.size,
                            durationMs = durationMs,
                            extractor = "${info.extractor} [fallback-level: $index]"
                        )

                        val availableSubtitles = emptyList<SubtitleTrack>()

                        val videoInfo = VideoInfo(
                            id = info.id.orEmpty(),
                            title = title,
                            uploader = info.uploader,
                            channel = info.uploaderId ?: info.uploader,
                            duration = if (info.duration > 0) info.duration.toLong() else null,
                            thumbnail = info.thumbnail,
                            webpageUrl = info.webpageUrl ?: trimmedUrl,
                            description = info.description,
                            extractor = info.extractor,
                            availability = "available",
                            formats = parsedFormats,
                            subtitles = availableSubtitles
                        )
                        return@withContext Result.success(videoInfo)
                    }
                } catch (e: Throwable) {
                    lastException = e
                }
            }

            val durationMs = System.currentTimeMillis() - startTime
            val finalError = lastException ?: Exception("No downloadable formats found after trying all fallback extractors.")
            YtDlpLogger.logAnalyzeError(trimmedUrl, finalError, durationMs)
            val domainError = YtDlpErrorMapper.map(finalError)
            Result.failure(domainError)
        } finally {
            activeProcesses.remove(procId)
            CookieSecurityManager.deleteTempCookieFile(cookiesFile)
            CookieSecurityManager.deleteTempCookieFilesForTag(appContext, procId)
        }
    }

    override suspend fun getFormats(url: String, processId: String?): Result<List<FormatInfo>> =
        extractInfo(url, processId).map { it.formats }

    override suspend fun fetchVideoInfo(url: String, processId: String?): Result<VideoMetadata> = withContext(Dispatchers.IO) {
        val trimmedUrl = url.trim()
        if (!validateUrl(trimmedUrl)) {
            return@withContext Result.failure(IllegalArgumentException("Video is invalid or unavailable."))
        }

        init(appContext)
        val procId = processId ?: "legacy_${System.currentTimeMillis()}"
        activeProcesses[procId] = trimmedUrl

        var cookiesFile: File? = null
        try {
            cookiesFile = prepareCookiesFile(procId)
            val request = YoutubeDLRequest(trimmedUrl).apply {
                addOption("--no-playlist")
                addOption("--no-warnings")
                addOption("--socket-timeout", "20")
            }
            if (cookiesFile != null && cookiesFile.exists()) {
                request.addOption("--cookies", cookiesFile.absolutePath)
            }

            val info = YoutubeDL.getInstance().getInfo(request)
            val title = info.title?.trim().orEmpty().ifEmpty { "Video" }
            val parsedOptions = FormatParser.parseFormatOptions(info.formats)

            val metadata = VideoMetadata(
                id = info.id.orEmpty(),
                title = title,
                uploader = info.uploader.orEmpty(),
                durationSeconds = info.duration,
                thumbnailUrl = info.thumbnail,
                webpageUrl = info.webpageUrl ?: trimmedUrl,
                formats = parsedOptions
            )
            Result.success(metadata)
        } catch (e: Exception) {
            CookieSecurityManager.deleteTempCookieFile(cookiesFile)
            val domainError = YtDlpErrorMapper.map(e)
            Result.failure(domainError)
        } finally {
            activeProcesses.remove(procId)
            CookieSecurityManager.deleteTempCookieFile(cookiesFile)
            CookieSecurityManager.deleteTempCookieFilesForTag(appContext, procId)
        }
    }

    override suspend fun download(
        request: DownloadRequest,
        onProgress: (DownloadProgress) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val taskId = request.id
        val startTimeMs = System.currentTimeMillis()

        // 1. Check storage space before proceeding using unified StorageSpaceChecker
        val storageCheck = StorageSpaceChecker.validateDownloadSpace(appContext, request)
        if (!storageCheck.hasEnoughSpace) {
            val storageError = DownloadError.StorageError(
                msg = "There is not enough storage space on the device to start this download.",
                detail = storageCheck.errorMessage ?: "Required storage space exceeds available capacity."
            )
            return@withContext Result.failure(storageError)
        }

        init(appContext)
        activeProcesses[taskId] = request.url
        cancelledTasks.remove(taskId)

        // Reset any stale FFmpeg cancellations for this taskId/runId
        ffmpegManager?.resetCancellation(taskId, request.runId)
        ffmpegManager?.resetCancellation(taskId, null)

        // 2. Prepare isolated working directory for this task
        val workDir = File(MediaStoreHelper.getTempDownloadDir(appContext), taskId)
        if (!workDir.exists()) {
            workDir.mkdirs()
        }

        YtDlpLogger.logDownloadStarted(taskId, request.url, request.resolveFormatSelector())

        var cookiesFile: File? = null
        val loggedDestinations = ConcurrentHashMap.newKeySet<String>()

        try {
            // Check cancellation before launching process
            if (cancelledTasks.contains(taskId)) {
                cleanupWorkDir(workDir)
                return@withContext Result.failure(DownloadError.Cancelled("Download was cancelled"))
            }

            cookiesFile = prepareCookiesFile(taskId)
            val ytdlRequest = buildYoutubeDLRequest(workDir, request, cookiesFile)

            YoutubeDL.getInstance().execute(ytdlRequest, taskId) { progress, etaInSeconds, line ->
                // Drop stale callbacks immediately if process was cancelled
                if (cancelledTasks.contains(taskId)) return@execute

                val rawLine = line.orEmpty()

                // Capture output paths logged by yt-dlp
                OutputFileDetector.extractDestinationFromLog(rawLine)?.let { loggedDest ->
                    loggedDestinations.add(loggedDest)
                }

                val speed = extractSpeed(rawLine)
                val etaFormatted = if (etaInSeconds > 0) {
                    val minutes = etaInSeconds / 60
                    val seconds = etaInSeconds % 60
                    String.format(Locale.US, "%02d:%02d", minutes, seconds)
                } else ""

                val (downloadedBytesStr, totalBytesStr) = extractSizes(rawLine)
                val downloadedBytes = parseByteString(downloadedBytesStr)
                val totalBytes = parseByteString(totalBytesStr)

                val calculatedProgress = if (totalBytes > 0L && downloadedBytes > 0L) {
                    ((downloadedBytes.toDouble() / totalBytes.toDouble()) * 100.0).toFloat().coerceIn(0f, 100f)
                } else {
                    progress.coerceIn(0f, 100f)
                }

                val progressObj = DownloadProgress(
                    taskId = taskId,
                    progressPercentage = calculatedProgress,
                    speed = speed,
                    eta = etaFormatted,
                    totalBytes = totalBytes,
                    downloadedBytes = downloadedBytes,
                    statusText = if (downloadedBytesStr.isNotBlank() && totalBytesStr.isNotBlank()) {
                        "$downloadedBytesStr / $totalBytesStr"
                    } else downloadedBytesStr,
                    runId = request.runId,
                    stage = DownloadStage.DOWNLOADING
                )
                onProgress(progressObj)
            }

            // Check cancellation after yt-dlp execution
            if (cancelledTasks.contains(taskId)) {
                cleanupWorkDir(workDir)
                return@withContext Result.failure(DownloadError.Cancelled("Download was cancelled"))
            }

            // 2. Media merge if separate unmerged video & audio fragments exist
            // Flow: download -> merge (when needed) -> cut (when needed) -> publish
            val taskPrefix = "task_$taskId"
            mergeMediaIfNeeded(
                taskId = taskId,
                workDir = workDir,
                request = request,
                loggedDestinations = loggedDestinations,
                onProgress = onProgress
            )

            // Check cancellation after merge
            if (cancelledTasks.contains(taskId)) {
                cleanupWorkDir(workDir)
                return@withContext Result.failure(DownloadError.Cancelled("Download was cancelled"))
            }

            // 3. Cut / Trim media if requested (Flow: download -> merge -> cut -> publish)
            if (request.hasTimeTrim) {
                val detectionBeforeCut = OutputFileDetector.resolveOutputFile(
                    workDir = workDir,
                    taskId = taskId,
                    isAudioOnly = request.isAudioOnly,
                    loggedDestinations = loggedDestinations,
                    taskPrefix = taskPrefix
                )
                if (detectionBeforeCut.isSuccess) {
                    cutMediaIfNeeded(
                        taskId = taskId,
                        workDir = workDir,
                        request = request,
                        inputFile = detectionBeforeCut.getOrThrow(),
                        loggedDestinations = loggedDestinations,
                        onProgress = onProgress
                    )
                } else {
                    cleanupWorkDir(workDir)
                    val domainError = detectionBeforeCut.exceptionOrNull() as? DownloadError
                        ?: DownloadError.Generic("Output file not found before cut")
                    YtDlpLogger.logDownloadError(taskId, domainError, System.currentTimeMillis() - startTimeMs)
                    return@withContext Result.failure(domainError)
                }
            }

            // Check cancellation after cut
            if (cancelledTasks.contains(taskId)) {
                cleanupWorkDir(workDir)
                return@withContext Result.failure(DownloadError.Cancelled("Download was cancelled"))
            }

            // 4. Deterministic Output File Detection (strictly avoids maxByOrNull)
            val detectionResult = OutputFileDetector.resolveOutputFile(
                workDir = workDir,
                taskId = taskId,
                isAudioOnly = request.isAudioOnly,
                loggedDestinations = loggedDestinations,
                taskPrefix = taskPrefix
            )

            if (detectionResult.isFailure) {
                cleanupWorkDir(workDir)
                val domainError = detectionResult.exceptionOrNull() as? DownloadError
                    ?: DownloadError.Generic("Failed to determine output file: ${detectionResult.exceptionOrNull()?.message}")
                YtDlpLogger.logDownloadError(taskId, domainError, System.currentTimeMillis() - startTimeMs)
                return@withContext Result.failure(domainError)
            }

            var finalFile = detectionResult.getOrThrow()

            if (!request.outputDestination.isNullOrBlank()) {
                try {
                    val targetDest = File(request.outputDestination)
                    targetDest.parentFile?.mkdirs()
                    if (finalFile.renameTo(targetDest)) {
                        finalFile = targetDest
                    } else {
                        finalFile.copyTo(targetDest, overwrite = true)
                        finalFile.delete()
                        finalFile = targetDest
                    }
                } catch (_: Throwable) {}
            }

            YtDlpLogger.logDownloadCompleted(
                taskId = taskId,
                outputFile = finalFile.absolutePath,
                fileSizeBytes = finalFile.length(),
                durationMs = System.currentTimeMillis() - startTimeMs
            )

            Result.success(finalFile)
        } catch (e: YoutubeDLException) {
            CookieSecurityManager.deleteTempCookieFile(cookiesFile)
            CookieSecurityManager.deleteTempCookieFilesForTag(appContext, taskId)
            val isCancelled = cancelledTasks.contains(taskId) ||
                e.message?.contains("cancel", ignoreCase = true) == true ||
                e.message?.contains("destroy", ignoreCase = true) == true

            val domainError = if (isCancelled) {
                DownloadError.Cancelled("Download was cancelled")
            } else {
                YtDlpErrorMapper.map(e)
            }
            cleanupWorkDir(workDir)
            YtDlpLogger.logDownloadError(taskId, domainError, System.currentTimeMillis() - startTimeMs)
            Result.failure(domainError)
        } catch (e: Throwable) {
            CookieSecurityManager.deleteTempCookieFile(cookiesFile)
            CookieSecurityManager.deleteTempCookieFilesForTag(appContext, taskId)
            val isCancelled = cancelledTasks.contains(taskId) ||
                e is DownloadError.Cancelled ||
                e.message?.contains("cancel", ignoreCase = true) == true

            val domainError = when {
                isCancelled -> DownloadError.Cancelled("Download was cancelled")
                e is DownloadError -> e
                else -> YtDlpErrorMapper.map(e)
            }
            cleanupWorkDir(workDir)
            YtDlpLogger.logDownloadError(taskId, domainError, System.currentTimeMillis() - startTimeMs)
            Result.failure(domainError)
        } finally {
            activeProcesses.remove(taskId)
            cancelledTasks.remove(taskId)
            mergeMutexes.remove(taskId)
            CookieSecurityManager.deleteTempCookieFile(cookiesFile)
            CookieSecurityManager.deleteTempCookieFilesForTag(appContext, taskId)
        }
    }

    override suspend fun download(
        task: DownloadTask,
        onProgress: (DownloadProgress) -> Unit
    ): Result<File> {
        val request = DownloadRequest(
            id = task.id,
            url = task.url,
            formatSelector = task.formatId,
            startTime = task.cutSettings.startTime,
            endTime = task.cutSettings.endTime,
            cutMode = task.cutSettings.mode,
            title = task.title,
            thumbnailUrl = task.thumbnailUrl,
            formatDescription = task.formatDescription,
            isAudioOnly = task.formatDescription.contains("Audio", ignoreCase = true),
            runId = task.runId
        )
        return download(request, onProgress)
    }

    override suspend fun cancel(taskId: String) {
        cancel(taskId, null)
    }

    suspend fun cancel(taskId: String, runId: Long?) {
        withContext(Dispatchers.IO) {
            try {
                cancelledTasks.add(taskId)
                activeProcesses.remove(taskId)
                CookieSecurityManager.deleteTempCookieFilesForTag(appContext, taskId)
                YtDlpLogger.logDownloadCancelled(taskId, runId ?: 0L)
                YoutubeDL.getInstance().destroyProcessById(taskId)
                ffmpegManager.cancel(taskId, runId)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Stage 2: Media Merge (عند الحاجة).
     * Unified architecture: FFmpeg is the sole authority for merging; yt-dlp is download only.
     *
     * Strict single-merge guarantee:
     * - Checks if already merged for (taskId, runId) in memory.
     * - Checks if merged output file already exists on disk.
     * - Checks if separate video & audio streams exist (if audio-only or single-stream, merge is skipped).
     * - Atomically claims merge execution key so concurrent or duplicate invocations are skipped.
     * - Executes FFmpeg merge exactly once.
     * - Deletes source video and audio stream fragments upon success so re-merge is physically impossible.
     */
    suspend fun mergeMediaIfNeeded(
        taskId: String,
        workDir: File,
        request: DownloadRequest,
        loggedDestinations: MutableSet<String> = ConcurrentHashMap.newKeySet(),
        onProgress: (DownloadProgress) -> Unit = {}
    ): File? {
        val executionKey = "${taskId}:${request.runId ?: 0L}"
        val mutex = mergeMutexes.computeIfAbsent(executionKey) { Mutex() }
        return mutex.withLock {
            val taskPrefix = "task_$taskId"
            val allFiles = workDir.listFiles()?.toList() ?: emptyList()
            val existingMerged = allFiles.firstOrNull {
                (it.name == "${taskPrefix}_merged.mp4" || it.name == "$taskPrefix.mp4") && it.length() > 0L
            }

            // 1. If already marked as merged, return existing merged file
            if (mergedTaskExecutions.contains(executionKey)) {
                existingMerged?.let { loggedDestinations.add(it.absolutePath) }
                return@withLock existingMerged
            }

            // 2. If audio-only or video-only, merging separate streams is not needed
            if (request.isAudioOnly || request.isVideoOnly) {
                return@withLock null
            }

            // 3. If merged file already exists, do not re-merge
            if (existingMerged != null) {
                mergedTaskExecutions.add(executionKey)
                loggedDestinations.add(existingMerged.absolutePath)
                return@withLock existingMerged
            }

            // 4. Find candidate video and audio streams
            val videoCandidates = allFiles.filter { file ->
                val ext = file.extension.lowercase()
                (ext == "mp4" || ext == "webm" || ext == "mkv") &&
                    !file.name.endsWith(".part") && !file.name.endsWith(".ytdl") &&
                    !file.name.endsWith("_merged.mp4") && !file.name.endsWith("_cut.mp4") &&
                    file.length() > 0L
            }
            val audioCandidates = allFiles.filter { file ->
                val ext = file.extension.lowercase()
                (ext == "m4a" || ext == "mp3" || ext == "opus" || ext == "aac" || ext == "ogg") &&
                    !file.name.endsWith(".part") && !file.name.endsWith(".ytdl") &&
                    file.length() > 0L
            }

            val primaryVideo = videoCandidates.firstOrNull { it.name.contains(".f") } ?: videoCandidates.firstOrNull()
            val primaryAudio = audioCandidates.firstOrNull { it.name.contains(".f") } ?: audioCandidates.firstOrNull()

            // 5. If separate streams do not exist, merge is not needed
            if (primaryVideo == null || primaryAudio == null || primaryVideo == primaryAudio) {
                return@withLock null
            }

            // 6. Atomic registration to prevent duplicate execution
            mergedTaskExecutions.add(executionKey)

            val mergedOut = File(workDir, "${taskPrefix}_merged.mp4")
            try {
                if (cancelledTasks.contains(taskId)) {
                    mergedTaskExecutions.remove(executionKey)
                    throw DownloadError.Cancelled("Download was cancelled before merge")
                }

                onProgress(
                    DownloadProgress(
                        taskId = taskId,
                        progressPercentage = 95f,
                        statusText = "Merging audio and video...",
                        runId = request.runId,
                        stage = DownloadStage.MERGING
                    )
                )
                val mergeResult = ffmpegManager.mergeVideoAudio(
                    videoFile = primaryVideo,
                    audioFile = primaryAudio,
                    outputFile = mergedOut,
                    taskId = taskId,
                    runId = request.runId
                ) { procProg ->
                    if (cancelledTasks.contains(taskId) || ffmpegManager.isTaskCancelled(taskId, request.runId)) return@mergeVideoAudio
                    onProgress(
                        DownloadProgress(
                            taskId = taskId,
                            progressPercentage = (95f + (procProg.percentage * 0.04f)).coerceIn(95f, 99f),
                            speed = procProg.speed,
                            eta = procProg.etaFormatted,
                            statusText = "Merging: ${procProg.percentage.toInt()}%",
                            runId = request.runId,
                            stage = DownloadStage.MERGING
                        )
                    )
                }

                if (mergeResult.isSuccess && mergedOut.exists() && mergedOut.length() > 0L) {
                    loggedDestinations.add(mergedOut.absolutePath)
                    // Remove source stream fragments so merge cannot be repeated
                    try { primaryVideo.delete() } catch (_: Throwable) {}
                    try { primaryAudio.delete() } catch (_: Throwable) {}
                    return@withLock mergedOut
                } else {
                    // If merge failed, remove execution key so user can retry
                    mergedTaskExecutions.remove(executionKey)
                    val error = mergeResult.exceptionOrNull()
                        ?: DownloadError.FfmpegError("Media merge output file is invalid or missing.")
                    throw error
                }
            } catch (t: Throwable) {
                mergedTaskExecutions.remove(executionKey)
                throw t
            }
        }
    }

    /**
     * Stage 3: Media Cut (عند الحاجة).
     * Trims media section using FFmpeg if requested by the user.
     */
    suspend fun cutMediaIfNeeded(
        taskId: String,
        workDir: File,
        request: DownloadRequest,
        inputFile: File,
        loggedDestinations: MutableSet<String> = ConcurrentHashMap.newKeySet(),
        onProgress: (DownloadProgress) -> Unit = {}
    ): File {
        if (!request.hasTimeTrim) return inputFile

        val taskPrefix = "task_$taskId"
        val cutOut = File(workDir, "${taskPrefix}_cut.${inputFile.extension}")
        try {
            if (cancelledTasks.contains(taskId)) {
                throw DownloadError.Cancelled("Download was cancelled before trimming")
            }

            onProgress(
                DownloadProgress(
                    taskId = taskId,
                    progressPercentage = 98f,
                    statusText = "Trimming media section...",
                    runId = request.runId,
                    stage = DownloadStage.CUTTING
                )
            )
            val cutResult = ffmpegManager.cutMedia(
                inputFile = inputFile,
                outputFile = cutOut,
                startTime = request.startTime!!.trim(),
                endTime = request.endTime!!.trim(),
                mode = request.cutMode,
                taskId = taskId,
                runId = request.runId
            ) { procProg ->
                if (cancelledTasks.contains(taskId) || ffmpegManager.isTaskCancelled(taskId, request.runId)) return@cutMedia
                onProgress(
                    DownloadProgress(
                        taskId = taskId,
                        progressPercentage = (98f + (procProg.percentage * 0.02f)).coerceIn(98f, 99f),
                        speed = procProg.speed,
                        eta = procProg.etaFormatted,
                        statusText = "Trimming: ${procProg.percentage.toInt()}%",
                        runId = request.runId,
                        stage = DownloadStage.CUTTING
                    )
                )
            }
            if (cutResult.isSuccess && cutOut.exists() && cutOut.length() > 0L) {
                loggedDestinations.add(cutOut.absolutePath)
                try { inputFile.delete() } catch (_: Throwable) {}
                return cutOut
            } else {
                val error = cutResult.exceptionOrNull()
                    ?: DownloadError.FfmpegError("Trimming failed: output file is invalid or missing.")
                throw error
            }
        } catch (t: Throwable) {
            val error = if (t is DownloadError) t else DownloadError.FfmpegError("Trimming failed: ${t.message}")
            throw error
        }
    }

    fun buildYoutubeDLRequest(workDir: File, request: DownloadRequest, cookiesFile: File? = null): YoutubeDLRequest {
        // Standard deterministic output template using the known task prefix
        val taskPrefix = "task_${request.id}"
        val outputPattern = "${workDir.absolutePath}/$taskPrefix.%(ext)s"

        val req = YoutubeDLRequest(request.url.trim())

        req.addOption("-o", outputPattern)
        req.addOption("-c") // continue partially downloaded files
        req.addOption("--no-playlist")
        req.addOption("--no-mtime")
        req.addOption("--concurrent-fragments", "4")
        req.addOption("--no-warnings")
        req.addOption("--socket-timeout", "30")
        req.addOption("--geo-bypass")
        req.addOption("--retries", "10")
        req.addOption("--fragment-retries", "10")
        req.addOption("--retry-sleep", "1")
        req.addOption("--extractor-args", "youtube:player_client=android,web,ios")

        // Responsibility B: yt-dlp is strictly responsible for DOWNLOAD ONLY.
        // We do NOT pass --ffmpeg-location or --download-sections to yt-dlp.
        // FFmpegManager is the unified authority for merging and cutting.

        // Apply Cookies if provided
        if (cookiesFile != null && cookiesFile.exists()) {
            req.addOption("--cookies", cookiesFile.absolutePath)
        }

        // Subtitles handling
        if (request.downloadSubtitles) {
            req.addOption("--write-subs")
            req.addOption("--write-auto-subs")
            val lang = request.subtitleLanguage?.ifBlank { "ar,en" } ?: "ar,en"
            req.addOption("--sub-lang", lang)
            if (!request.isAudioOnly) {
                req.addOption("--embed-subs")
            }
        }

        // Format selection: yt-dlp is responsible for download only
        val formatSelector = request.resolveFormatSelector()
        req.addOption("-f", formatSelector)
        req.addOption("--embed-metadata")

        return req
    }

    private fun prepareCookiesFile(id: String): File? {
        return CookieSecurityManager.createTempCookieFile(appContext, id)
    }

    private fun extractSpeed(line: String): String {
        val matcher = speedPattern.matcher(line)
        return if (matcher.find()) {
            matcher.group(1).orEmpty()
        } else ""
    }

    private fun extractSizes(line: String): Pair<String, String> {
        val matcher = sizePattern.matcher(line)
        return if (matcher.find()) {
            Pair(matcher.group(1).orEmpty().trim(), matcher.group(2).orEmpty().trim())
        } else Pair("", "")
    }

    private fun parseByteString(sizeStr: String): Long {
        if (sizeStr.isBlank()) return 0L
        val pattern = Pattern.compile("""(\d+(?:\.\d+)?)\s*([a-zA-Z]+)?""")
        val matcher = pattern.matcher(sizeStr.trim())
        if (!matcher.find()) return 0L
        val value = matcher.group(1)?.toDoubleOrNull() ?: return 0L
        val unit = matcher.group(2)?.lowercase() ?: ""

        return when {
            unit.startsWith("kib") || unit == "kb" || unit == "k" -> (value * 1024).toLong()
            unit.startsWith("mib") || unit == "mb" || unit == "m" -> (value * 1024 * 1024).toLong()
            unit.startsWith("gib") || unit == "gb" || unit == "g" -> (value * 1024 * 1024 * 1024).toLong()
            unit.startsWith("tib") || unit == "tb" || unit == "t" -> (value * 1024L * 1024L * 1024L * 1024L).toLong()
            else -> value.toLong()
        }
    }

    private fun cleanupWorkDir(workDir: File) {
        try {
            workDir.deleteRecursively()
        } catch (_: Exception) {}
    }

    private fun hasAvailableStorage(context: Context, requiredBytes: Long): Boolean {
        return StorageSpaceChecker.hasEnoughSpace(context.applicationContext, requiredBytes)
    }
}
