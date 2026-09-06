package com.example.downloader.execution

import android.content.Context
import com.example.data.local.DownloadTaskEntity
import com.example.data.repository.DownloadRepository
import com.example.data.settings.AppSettings
import com.example.domain.model.CutMode
import com.example.domain.model.DownloadError
import com.example.domain.model.DownloadRequest
import com.example.domain.model.DownloadStage
import com.example.domain.model.DownloadStatus
import com.example.downloader.cleanup.CleanupManager
import com.example.downloader.engine.DownloadEngine
import com.example.downloader.lifecycle.DownloadStateMachine
import com.example.downloader.notification.DownloadNotificationManager
import com.example.downloader.util.RetryPolicy
import com.example.downloader.util.SpeedSmoother
import com.example.storage.MediaStoreHelper
import com.example.storage.StorageSpaceChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Manages the actual background execution of download tasks:
 * - Pre-flight storage checks
 * - yt-dlp / engine invocation
 * - Throttled & runId-verified progress updates
 * - Publishing to MediaStore
 * - Failure handling and auto-retry policy
 */
class DownloadExecutionManager(
    private val context: Context,
    private val repository: DownloadRepository,
    private val appSettings: AppSettings,
    private val downloadEngine: DownloadEngine,
    private val notificationManager: DownloadNotificationManager,
    private val activeRunIds: ConcurrentHashMap<String, Long>,
    private val runIdCounter: AtomicLong,
    private val scope: CoroutineScope,
    private val onExecutionFinished: suspend (taskId: String) -> Unit,
    private val onRetryRequested: (taskId: String, scheduledRunId: Long) -> Unit
) {
    constructor(
        context: Context,
        repository: DownloadRepository,
        appSettings: AppSettings,
        downloadEngine: DownloadEngine,
        notificationManager: DownloadNotificationManager,
        activeRunIds: ConcurrentHashMap<String, Long>,
        runIdCounter: AtomicLong,
        scope: CoroutineScope,
        onExecutionFinished: suspend (taskId: String) -> Unit,
        onRetryRequestedSingle: (taskId: String) -> Unit
    ) : this(
        context = context,
        repository = repository,
        appSettings = appSettings,
        downloadEngine = downloadEngine,
        notificationManager = notificationManager,
        activeRunIds = activeRunIds,
        runIdCounter = runIdCounter,
        scope = scope,
        onExecutionFinished = onExecutionFinished,
        onRetryRequested = { taskId, _ -> onRetryRequestedSingle(taskId) }
    )

    private val speedSmoothers = ConcurrentHashMap<String, SpeedSmoother>()
    private val lastProgressUpdateTimes = ConcurrentHashMap<String, Long>()
    private val lastReportedProgress = ConcurrentHashMap<String, Float>()

    // Track scheduled auto-retry jobs and their runId identity
    private val retryLock = Any()
    private val scheduledRetryJobs = ConcurrentHashMap<String, Job>()
    private val scheduledRetryRunIds = ConcurrentHashMap<String, Long>()

    fun cancelPendingRetry(taskId: String) {
        synchronized(retryLock) {
            scheduledRetryJobs.remove(taskId)?.cancel()
            scheduledRetryRunIds.remove(taskId)
        }
    }

    fun hasPendingRetry(taskId: String): Boolean {
        synchronized(retryLock) {
            return scheduledRetryJobs.containsKey(taskId)
        }
    }

    fun clearTaskState(taskId: String) {
        speedSmoothers.remove(taskId)
        lastProgressUpdateTimes.remove(taskId)
        lastReportedProgress.remove(taskId)
    }

    suspend fun handleUnhandledException(taskId: String, error: Throwable) {
        val current = repository.getTaskByIdSync(taskId)
        if (current != null && !DownloadStateMachine.isTerminalOrPaused(current.status)) {
            val errorMsg = error.localizedMessage ?: "Execution error: ${error.javaClass.simpleName}"
            val updated = repository.markFailedOrCancelled(
                id = taskId,
                runId = current.runId,
                status = DownloadStatus.FAILED,
                errorMessage = errorMsg
            )
            if (updated == 0 && current.status == DownloadStatus.QUEUED) {
                repository.markQueuedTaskFailed(taskId, errorMsg)
            }
            notificationManager.onTaskFailed(taskId, current.title, errorMsg)
        }
    }

    suspend fun executeTask(taskId: String) {
        // Starting a new run cancels any pending retry for this task immediately
        cancelPendingRetry(taskId)

        val task = repository.getTaskByIdSync(taskId) ?: run {
            activeRunIds.remove(taskId)
            clearTaskState(taskId)
            onExecutionFinished(taskId)
            return
        }

        // Drop execution immediately if task is already terminal or paused
        if (DownloadStateMachine.isTerminalOrPaused(task.status)) {
            clearTaskState(taskId)
            onExecutionFinished(taskId)
            return
        }

        // Allocate unique runId for this actual execution
        val executionRunId = runIdCounter.incrementAndGet()
        activeRunIds[taskId] = executionRunId

        fun isExecutionActive(): Boolean {
            return activeRunIds[taskId] == executionRunId
        }

        // Re-verify immediately after registering runId to prevent race with concurrent Pause/Cancel
        if (!isExecutionActive()) {
            clearTaskState(taskId)
            onExecutionFinished(taskId)
            return
        }

        val taskAtStart = repository.getTaskByIdSync(taskId)
        if (taskAtStart == null || DownloadStateMachine.isTerminalOrPaused(taskAtStart.status) || !isExecutionActive()) {
            activeRunIds.remove(taskId, executionRunId)
            clearTaskState(taskId)
            onExecutionFinished(taskId)
            return
        }

        var lastKnownTask: DownloadTaskEntity = taskAtStart

        try {
            // Guard before storage check
            if (!isExecutionActive()) return

            // Pre-flight storage check
            val storageCheck = StorageSpaceChecker.validateDownloadSpace(context, taskAtStart)
            if (!storageCheck.hasEnoughSpace) {
                if (!isExecutionActive()) return
                val currentTask = repository.getTaskByIdSync(taskId) ?: taskAtStart
                if (DownloadStateMachine.isTerminalOrPaused(currentTask.status) || !isExecutionActive()) {
                    return
                }
                val errorMsg = storageCheck.errorMessage ?: "Insufficient storage space available."
                val failedUpdated = repository.markFailedOrCancelled(
                    id = taskId,
                    runId = executionRunId,
                    status = DownloadStatus.FAILED,
                    errorMessage = errorMsg
                )
                if (failedUpdated > 0 && isExecutionActive()) {
                    notificationManager.onTaskFailed(taskId, currentTask.title, errorMsg)
                }
                return
            }

            // Guard before PREPARING
            if (!isExecutionActive()) return
            val currentBeforePreparing = repository.getTaskByIdSync(taskId) ?: taskAtStart
            if (DownloadStateMachine.isTerminalOrPaused(currentBeforePreparing.status) || !isExecutionActive()) {
                return
            }
            lastKnownTask = currentBeforePreparing

            // Update stage to PREPARING (atomic check-and-set in DB)
            val preparingUpdated = repository.updateActiveState(
                id = taskId,
                runId = executionRunId,
                status = DownloadStatus.PREPARING,
                stage = DownloadStage.PREPARING
            )
            if (preparingUpdated == 0 || !isExecutionActive()) {
                return
            }
            notificationManager.startOrUpdate(taskId, currentBeforePreparing.title, 0, DownloadStatus.PREPARING, "")

            // Guard before DOWNLOADING
            if (!isExecutionActive()) return
            val currentBeforeDownloading = repository.getTaskByIdSync(taskId) ?: currentBeforePreparing
            if (DownloadStateMachine.isTerminalOrPaused(currentBeforeDownloading.status) || !isExecutionActive()) {
                return
            }
            lastKnownTask = currentBeforeDownloading

            // Update stage to DOWNLOADING (atomic check-and-set in DB)
            val downloadingUpdated = repository.updateActiveState(
                id = taskId,
                runId = executionRunId,
                status = DownloadStatus.DOWNLOADING,
                stage = DownloadStage.DOWNLOADING
            )
            if (downloadingUpdated == 0 || !isExecutionActive()) {
                return
            }
            notificationManager.startOrUpdate(taskId, currentBeforeDownloading.title, currentBeforeDownloading.progress.toInt(), DownloadStatus.DOWNLOADING, "")

            val cutMode = if (currentBeforeDownloading.cutMode.equals("precise", ignoreCase = true)) CutMode.PRECISE_CUT else CutMode.FAST_CUT
            val expectedSize = StorageSpaceChecker.parseSizeToBytes(currentBeforeDownloading.totalSize)
            val request = DownloadRequest(
                id = currentBeforeDownloading.id,
                runId = executionRunId,
                url = currentBeforeDownloading.url,
                formatSelector = currentBeforeDownloading.formatId,
                startTime = currentBeforeDownloading.startTime,
                endTime = currentBeforeDownloading.endTime,
                cutMode = cutMode,
                title = currentBeforeDownloading.title,
                thumbnailUrl = currentBeforeDownloading.thumbnailUrl,
                formatDescription = currentBeforeDownloading.formatDescription,
                isAudioOnly = currentBeforeDownloading.isAudioOnly,
                isVideoOnly = currentBeforeDownloading.isVideoOnly,
                downloadSubtitles = currentBeforeDownloading.downloadSubtitles,
                subtitleLanguage = currentBeforeDownloading.subtitleLanguage,
                expectedMediaSizeBytes = expectedSize
            )

            val smoother = speedSmoothers.getOrPut(taskId) { SpeedSmoother() }

            // Guard before engine invocation
            if (!isExecutionActive()) return
            val currentBeforeEngine = repository.getTaskByIdSync(taskId)
            if (currentBeforeEngine == null || DownloadStateMachine.isTerminalOrPaused(currentBeforeEngine.status) || !isExecutionActive() || currentBeforeEngine.runId != executionRunId) {
                return
            }

            val result = downloadEngine.download(request) { progress ->
                handleProgressUpdate(
                    taskId = taskId,
                    runId = if (progress.runId > 0L) progress.runId else executionRunId,
                    stage = progress.stage,
                    title = currentBeforeDownloading.title,
                    progress = progress.progressPercentage,
                    speed = progress.speed,
                    eta = progress.eta,
                    downloadedBytes = progress.downloadedBytes,
                    totalBytes = progress.totalBytes,
                    smoother = smoother
                )
            }

            clearTaskState(taskId)

            // If runId was invalidated or replaced (e.g. paused/cancelled), ignore completion
            if (!isExecutionActive()) {
                return
            }

            val currentAfterEngine = repository.getTaskByIdSync(taskId)
            if (currentAfterEngine == null || DownloadStateMachine.isTerminalOrPaused(currentAfterEngine.status) || !isExecutionActive() || currentAfterEngine.runId != executionRunId) {
                return
            }

            result.fold(
                onSuccess = { finalFile ->
                    if (!isExecutionActive()) {
                        return@fold
                    }

                    // Advance to PUBLISHING stage
                    val currentBeforePublish = repository.getTaskByIdSync(taskId)
                    if (currentBeforePublish == null || DownloadStateMachine.isTerminalOrPaused(currentBeforePublish.status) || !isExecutionActive() || currentBeforePublish.runId != executionRunId) {
                        return@fold
                    }
                    lastKnownTask = currentBeforePublish

                    val publishUpdated = repository.updateActiveStage(
                        id = taskId,
                        runId = executionRunId,
                        stage = DownloadStage.PUBLISHING
                    )
                    if (publishUpdated == 0 || !isExecutionActive()) {
                        return@fold
                    }

                    val (uri, savedPath) = MediaStoreHelper.saveToPublicDownloads(context, finalFile, currentBeforePublish.title)
                    if (uri == null || savedPath.isNullOrBlank()) {
                        val storageFailure = DownloadError.StorageError(
                            msg = "Failed to save downloaded file to device storage.",
                            detail = "MediaStore insert or file copy failed."
                        )
                        if (isExecutionActive()) {
                            handleDownloadFailure(taskId, executionRunId, currentBeforePublish, storageFailure)
                        }
                        return@fold
                    }

                    if (finalFile.exists() && savedPath != finalFile.absolutePath) {
                        finalFile.delete()
                    }

                    if (!isExecutionActive()) {
                        return@fold
                    }

                    val current = repository.getTaskByIdSync(taskId)
                    if (current == null || DownloadStateMachine.isTerminalOrPaused(current.status) || !isExecutionActive() || current.runId != executionRunId) {
                        return@fold
                    }

                    val f = File(savedPath)
                    val finalFileSize = if (f.exists()) CleanupManager.formatFileSize(f.length()) else ""

                    val completedUpdated = repository.markCompleted(
                        id = taskId,
                        runId = executionRunId,
                        contentUri = uri.toString(),
                        filePath = savedPath,
                        downloadedSize = if (current.downloadedSize.isNotBlank()) current.downloadedSize else finalFileSize,
                        totalSize = if (current.totalSize.isNotBlank()) current.totalSize else finalFileSize,
                        completedAt = System.currentTimeMillis()
                    )

                    if (completedUpdated == 0 || !isExecutionActive()) {
                        return@fold
                    }

                    activeRunIds.remove(taskId, executionRunId)
                    notificationManager.onTaskCompleted(taskId, currentBeforePublish.title, uri.toString())
                },
                onFailure = { error ->
                    if (isExecutionActive()) {
                        handleDownloadFailure(taskId, executionRunId, currentBeforeDownloading, error)
                    }
                }
            )
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (t: Throwable) {
            android.util.Log.e("DownloadExecutionManager", "Task $taskId crashed in executeTask", t)
            if (isExecutionActive()) {
                handleDownloadFailure(taskId, executionRunId, lastKnownTask, t)
            } else {
                handleUnhandledException(taskId, t)
            }
        } finally {
            activeRunIds.remove(taskId, executionRunId)
            clearTaskState(taskId)
            onExecutionFinished(taskId)
        }
    }

    internal suspend fun handleDownloadFailure(
        taskId: String,
        runId: Long,
        originalTask: DownloadTaskEntity,
        error: Throwable
    ) {
        if (activeRunIds[taskId] != runId) {
            return
        }
        activeRunIds.remove(taskId, runId)

        val isCancelled = error is DownloadError.Cancelled ||
                error.message?.contains("destroy", ignoreCase = true) == true ||
                error.message?.contains("interrupted", ignoreCase = true) == true ||
                error.message?.contains("cancel", ignoreCase = true) == true

        val currentTask = repository.getTaskByIdSync(taskId) ?: originalTask
        if (DownloadStateMachine.isTerminalOrPaused(currentTask.status) || currentTask.runId != runId) {
            return
        }

        val finalStatus = if (isCancelled) DownloadStatus.CANCELLED else DownloadStatus.FAILED
        val errorMsg = error.localizedMessage ?: "Download failed"

        val failedUpdated = repository.markFailedOrCancelled(
            id = taskId,
            runId = runId,
            status = finalStatus,
            errorMessage = errorMsg
        )
        if (failedUpdated == 0) {
            return
        }

        if (finalStatus == DownloadStatus.FAILED) {
            val isRetryable = RetryPolicy.isRetryable(error)
            val canAutoRetry = appSettings.autoRetry.value && currentTask.retryCount < RetryPolicy.MAX_RETRIES && isRetryable

            if (canAutoRetry) {
                val delayMs = RetryPolicy.getBackoffDelayMs(currentTask.retryCount)
                synchronized(retryLock) {
                    cancelPendingRetry(taskId)
                    scheduledRetryRunIds[taskId] = runId

                    val retryJob = scope.launch {
                        try {
                            delay(delayMs)
                            if (!isActive) return@launch

                            // Guard 1: Ensure this scheduled retry session is still the active one
                            if (scheduledRetryRunIds[taskId] != runId) return@launch

                            // Guard 2: Verify current state in database
                            // Task must not be CANCELLED or DELETED, and must strictly retain FAILED status and matching runId
                            val taskAfterDelay = repository.getTaskByIdSync(taskId)
                            if (taskAfterDelay == null ||
                                taskAfterDelay.status != DownloadStatus.FAILED ||
                                taskAfterDelay.runId != runId ||
                                activeRunIds[taskId] != null
                            ) {
                                return@launch
                            }

                            // Guard 3: Double check and consume scheduled token
                            val consumed = synchronized(retryLock) {
                                scheduledRetryRunIds.remove(taskId, runId)
                            }
                            if (consumed) {
                                onRetryRequested(taskId, runId)
                            }
                        } finally {
                            synchronized(retryLock) {
                                val currentJob = coroutineContext[Job]
                                if (currentJob != null) {
                                    scheduledRetryJobs.remove(taskId, currentJob)
                                } else {
                                    scheduledRetryJobs.remove(taskId)
                                }
                                scheduledRetryRunIds.remove(taskId, runId)
                            }
                        }
                    }
                    scheduledRetryJobs[taskId] = retryJob
                }
            } else {
                notificationManager.onTaskFailed(
                    taskId, originalTask.title, errorMsg
                )
            }
        } else {
            notificationManager.updateOrDismissIfIdle(
                taskId, originalTask.title, finalStatus, 0, ""
            )
        }
    }

    private fun handleProgressUpdate(
        taskId: String,
        runId: Long,
        stage: DownloadStage,
        title: String,
        progress: Float,
        speed: String,
        eta: String,
        downloadedBytes: Long,
        totalBytes: Long,
        smoother: SpeedSmoother
    ) {
        val expectedRunId = activeRunIds[taskId]
        if (expectedRunId == null || expectedRunId != runId) {
            return
        }

        val now = System.currentTimeMillis()
        val lastTime = lastProgressUpdateTimes[taskId] ?: 0L
        val lastProg = lastReportedProgress[taskId] ?: 0f
        val progressDelta = Math.abs(progress - lastProg)

        val isSignificant = progressDelta >= 0.5f || progress >= 100f || (now - lastTime >= 400L)
        if (!isSignificant) {
            return
        }

        lastProgressUpdateTimes[taskId] = now
        lastReportedProgress[taskId] = progress

        val speedText = speed
        val downloadedText = if (downloadedBytes > 0) CleanupManager.formatFileSize(downloadedBytes) else ""
        val totalText = if (totalBytes > 0) CleanupManager.formatFileSize(totalBytes) else ""

        val effectiveStatus = when (stage) {
            DownloadStage.MERGING, DownloadStage.CUTTING -> DownloadStatus.PROCESSING_FFMPEG
            else -> DownloadStatus.DOWNLOADING
        }

        scope.launch {
            if (activeRunIds[taskId] != runId) {
                return@launch
            }

            val updatedRows = repository.updateProgress(
                id = taskId,
                runId = runId,
                status = effectiveStatus,
                stage = stage,
                progress = progress,
                downloadSpeed = speedText,
                eta = eta,
                downloadedSize = downloadedText,
                totalSize = totalText
            )

            if (updatedRows == 0) {
                return@launch
            }

            notificationManager.onProgressUpdateThrottled(
                taskId = taskId,
                title = title,
                progress = progress,
                status = effectiveStatus,
                speedText = speedText
            )
        }
    }
}
