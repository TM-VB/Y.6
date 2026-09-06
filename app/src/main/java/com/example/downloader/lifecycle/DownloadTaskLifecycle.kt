package com.example.downloader.lifecycle

import android.content.Context
import androidx.core.net.toUri
import com.example.data.local.DownloadTaskEntity
import com.example.data.repository.DownloadRepository
import com.example.domain.model.DownloadRequest
import com.example.domain.model.DownloadStage
import com.example.domain.model.DownloadStatus
import com.example.domain.model.TimeRange
import com.example.downloader.cleanup.CleanupManager
import com.example.downloader.engine.DownloadEngine
import com.example.downloader.notification.DownloadNotificationManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Manages the lifecycle operations of individual download tasks:
 * enqueue, pause, resume, cancel, retry, delete, and ordering.
 * Enforces per-task Mutex synchronization and unique runId allocation.
 */
class DownloadTaskLifecycle(
    context: Context,
    private val repository: DownloadRepository,
    private val downloadEngine: DownloadEngine,
    private val notificationManager: DownloadNotificationManager,
    private val activeRunIds: ConcurrentHashMap<String, Long>,
    private val activeJobs: ConcurrentHashMap<String, Job>,
    private val taskMutexes: ConcurrentHashMap<String, Mutex>,
    private val runIdCounter: AtomicLong,
    private val onCancelPendingRetry: ((String) -> Unit)? = null,
    private val onReleaseClaim: ((String) -> Unit)? = null,
    private val onTaskStateChanged: suspend () -> Unit
) {
    private val appContext: Context = context.applicationContext

    fun generateRunId(): Long = runIdCounter.incrementAndGet()

    fun getTaskMutex(taskId: String): Mutex = taskMutexes.getOrPut(taskId) { Mutex() }

    fun getActiveRunId(taskId: String): Long? = activeRunIds[taskId]

    suspend fun enqueueDownload(request: DownloadRequest): String {
        val taskId = request.id
        val entity = DownloadTaskEntity(
            id = taskId,
            url = request.url,
            title = request.title,
            thumbnailUrl = request.thumbnailUrl,
            formatId = request.formatSelector,
            formatDescription = request.formatDescription,
            startTime = request.startTime,
            endTime = request.endTime,
            cutMode = request.cutMode.id,
            status = DownloadStatus.QUEUED,
            stage = DownloadStage.QUEUED,
            runId = 0L,
            progress = 0f,
            isAudioOnly = request.isAudioOnly,
            isVideoOnly = request.isVideoOnly,
            downloadSubtitles = request.downloadSubtitles,
            subtitleLanguage = request.subtitleLanguage,
            queueOrder = System.currentTimeMillis()
        )
        repository.insertTask(entity)
        onTaskStateChanged()
        return taskId
    }

    suspend fun enqueueDownload(
        url: String,
        title: String,
        thumbnailUrl: String?,
        formatId: String,
        formatDescription: String,
        isAudioOnly: Boolean,
        timeRange: TimeRange?,
        downloadSubtitles: Boolean = false,
        subtitleLanguage: String? = null
    ): String {
        val taskId = UUID.randomUUID().toString()
        val entity = DownloadTaskEntity(
            id = taskId,
            url = url,
            title = title,
            thumbnailUrl = thumbnailUrl,
            formatId = formatId,
            formatDescription = formatDescription,
            startTime = timeRange?.startTime,
            endTime = timeRange?.endTime,
            cutMode = timeRange?.cutMode?.id ?: "none",
            status = DownloadStatus.QUEUED,
            stage = DownloadStage.QUEUED,
            runId = 0L,
            progress = 0f,
            isAudioOnly = isAudioOnly,
            isVideoOnly = false,
            downloadSubtitles = downloadSubtitles,
            subtitleLanguage = subtitleLanguage,
            queueOrder = System.currentTimeMillis()
        )
        repository.insertTask(entity)
        onTaskStateChanged()
        return taskId
    }

    suspend fun enqueueBatch(requests: List<DownloadRequest>) {
        if (requests.isEmpty()) return
        val now = System.currentTimeMillis()
        val entities = requests.mapIndexed { idx, req ->
            DownloadTaskEntity(
                id = req.id,
                url = req.url,
                title = req.title,
                thumbnailUrl = req.thumbnailUrl,
                formatId = req.formatSelector,
                formatDescription = req.formatDescription,
                startTime = req.startTime,
                endTime = req.endTime,
                cutMode = req.cutMode.id,
                status = DownloadStatus.QUEUED,
                stage = DownloadStage.QUEUED,
                runId = 0L,
                progress = 0f,
                isAudioOnly = req.isAudioOnly,
                isVideoOnly = req.isVideoOnly,
                downloadSubtitles = req.downloadSubtitles,
                subtitleLanguage = req.subtitleLanguage,
                queueOrder = now + idx
            )
        }
        entities.forEach { repository.insertTask(it) }
        onTaskStateChanged()
    }

    suspend fun cancelDownloadInternal(taskId: String, cleanupFiles: Boolean = true) {
        activeRunIds.remove(taskId)
        try {
            downloadEngine.cancel(taskId)
        } catch (_: Throwable) {}
        val job = activeJobs.remove(taskId)
        job?.cancel()
        job?.join()
        notificationManager.clearTask(taskId)
        if (cleanupFiles) {
            CleanupManager.cleanupTaskFiles(appContext, taskId)
        }
    }

    suspend fun pauseDownloadSync(taskId: String) {
        getTaskMutex(taskId).withLock {
            onCancelPendingRetry?.invoke(taskId)
            onReleaseClaim?.invoke(taskId)

            // 1. Invalidate execution identity so in-flight callbacks are dropped immediately
            activeRunIds.remove(taskId)

            val currentBeforeStop = repository.getTaskByIdSync(taskId)
            if (currentBeforeStop != null && !DownloadStateMachine.isTerminal(currentBeforeStop.status)) {
                repository.updateTask(
                    currentBeforeStop.copy(
                        status = DownloadStatus.PAUSED,
                        downloadSpeed = "",
                        eta = ""
                    )
                )
            }

            // 2. Stop running engines and await job completion
            cancelDownloadInternal(taskId, cleanupFiles = false)

            // 3. Update notification
            val current = repository.getTaskByIdSync(taskId) ?: currentBeforeStop
            if (current != null && !DownloadStateMachine.isTerminal(current.status)) {
                notificationManager.updateOrDismissIfIdle(
                    taskId = taskId,
                    title = current.title,
                    status = DownloadStatus.PAUSED,
                    progress = current.progress.toInt(),
                    speed = ""
                )
            }

            onTaskStateChanged()
        }
    }

    suspend fun resumeDownloadSync(taskId: String) {
        getTaskMutex(taskId).withLock {
            onCancelPendingRetry?.invoke(taskId)
            onReleaseClaim?.invoke(taskId)

            // 1. Invalidate previous execution identity
            activeRunIds.remove(taskId)

            val current = repository.getTaskByIdSync(taskId) ?: return@withLock
            if (current.status == DownloadStatus.PAUSED || current.status == DownloadStatus.INTERRUPTED) {
                // Rely on persisted stage, with file check only as fallback recovery
                val recoveredStage = when (current.stage) {
                    DownloadStage.MERGING, DownloadStage.CUTTING -> {
                        val taskWorkDir = File(appContext.cacheDir, "ytdlp_downloads/$taskId")
                        val hasMedia = taskWorkDir.listFiles()?.any {
                            it.isFile && it.length() > 1024L && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl")
                        } == true
                        if (hasMedia) current.stage else DownloadStage.QUEUED
                    }
                    else -> DownloadStage.QUEUED
                }

                val targetStatus = if (recoveredStage == DownloadStage.MERGING || recoveredStage == DownloadStage.CUTTING) {
                    DownloadStatus.PROCESSING_FFMPEG
                } else {
                    DownloadStatus.QUEUED
                }

                // Start resume with fresh execution identity
                val newRunId = generateRunId()

                repository.updateTask(
                    current.copy(
                        status = targetStatus,
                        stage = recoveredStage,
                        runId = newRunId,
                        errorMessage = null,
                        queueOrder = System.currentTimeMillis()
                    )
                )
                onTaskStateChanged()
            }
        }
    }

    suspend fun cancelDownloadSync(taskId: String) {
        getTaskMutex(taskId).withLock {
            // Cancel any pending auto-retry coroutine immediately
            onCancelPendingRetry?.invoke(taskId)
            onReleaseClaim?.invoke(taskId)

            // 1. Invalidate execution identity
            activeRunIds.remove(taskId)

            val taskBeforeStop = repository.getTaskByIdSync(taskId)
            if (taskBeforeStop != null && taskBeforeStop.status != DownloadStatus.COMPLETED) {
                repository.updateTask(
                    taskBeforeStop.copy(
                        status = DownloadStatus.CANCELLED,
                        stage = DownloadStage.QUEUED,
                        runId = 0L,
                        downloadSpeed = "",
                        eta = ""
                    )
                )
            }

            // 2. Stop running process and clean up temporary files
            cancelDownloadInternal(taskId, cleanupFiles = true)

            // 3. Update notification
            val task = repository.getTaskByIdSync(taskId) ?: taskBeforeStop
            if (task != null && task.status != DownloadStatus.COMPLETED) {
                notificationManager.updateOrDismissIfIdle(
                    taskId = taskId,
                    title = task.title,
                    status = DownloadStatus.CANCELLED,
                    progress = task.progress.toInt(),
                    speed = ""
                )
            }

            onTaskStateChanged()
        }
    }

    suspend fun retryDownloadSync(taskId: String, expectedRunId: Long? = null) {
        getTaskMutex(taskId).withLock {
            onCancelPendingRetry?.invoke(taskId)
            onReleaseClaim?.invoke(taskId)

            val task = repository.getTaskByIdSync(taskId) ?: return@withLock

            // If an expectedRunId was provided (from auto-retry), enforce strict guard:
            // 1. Task must not have been cancelled or deleted
            // 2. Task status must strictly be FAILED
            // 3. Task runId must strictly match expectedRunId
            if (expectedRunId != null) {
                if (task.status != DownloadStatus.FAILED || task.runId != expectedRunId) {
                    return@withLock
                }
            }

            // 1. Invalidate old runId
            activeRunIds.remove(taskId)

            // 2. Cancel any running job and wait for exit
            val job = activeJobs.remove(taskId)
            job?.cancel()
            job?.join()
            try { downloadEngine.cancel(taskId) } catch (_: Throwable) {}

            val currentTask = repository.getTaskByIdSync(taskId) ?: return@withLock
            if (expectedRunId != null) {
                if (currentTask.status != DownloadStatus.FAILED || currentTask.runId != expectedRunId) {
                    return@withLock
                }
            }

            // 3. Allocate fresh runId for the retry
            val newRunId = generateRunId()

            // 4. Reset task state to QUEUED
            val updatedTask = currentTask.copy(
                status = DownloadStatus.QUEUED,
                stage = DownloadStage.QUEUED,
                runId = newRunId,
                progress = 0f,
                errorMessage = null,
                downloadSpeed = "",
                eta = "",
                retryCount = currentTask.retryCount + 1,
                queueOrder = System.currentTimeMillis()
            )

            repository.updateTask(updatedTask)
            notificationManager.updateOrDismissIfIdle(
                taskId = taskId,
                title = currentTask.title,
                status = DownloadStatus.QUEUED,
                progress = 0,
                speed = ""
            )
            onTaskStateChanged()
        }
    }

    suspend fun deleteDownloadSync(taskId: String) {
        getTaskMutex(taskId).withLock {
            // Cancel any pending auto-retry coroutine immediately
            onCancelPendingRetry?.invoke(taskId)
            onReleaseClaim?.invoke(taskId)

            // 1. Invalidate runId immediately
            activeRunIds.remove(taskId)

            // 2. Cancel process
            try {
                downloadEngine.cancel(taskId)
            } catch (_: Throwable) {}
            val job = activeJobs.remove(taskId)
            job?.cancel()

            // 3. Wait for process exit completely
            job?.join()

            notificationManager.clearTask(taskId)

            // 4. Cleanup files
            CleanupManager.cleanupTaskFiles(appContext, taskId)
            notificationManager.updateOrDismissIfIdle(
                taskId = taskId,
                title = "",
                status = DownloadStatus.CANCELLED,
                progress = 0,
                speed = ""
            )

            // 5. Delete DB record
            repository.deleteTask(taskId)

            taskMutexes.remove(taskId)
            onTaskStateChanged()
        }
    }

    suspend fun reorderTask(taskId: String, newOrder: Long) {
        repository.updateQueueOrder(taskId, newOrder)
        onTaskStateChanged()
    }

    suspend fun moveTaskUp(taskId: String) {
        val queued = repository.getQueuedTasks()
        val index = queued.indexOfFirst { it.id == taskId }
        if (index > 0) {
            val currentTask = queued[index]
            val prevTask = queued[index - 1]
            val newOrder = prevTask.queueOrder - 1
            repository.updateQueueOrder(currentTask.id, newOrder)
            onTaskStateChanged()
        }
    }

    suspend fun moveTaskDown(taskId: String) {
        val queued = repository.getQueuedTasks()
        val index = queued.indexOfFirst { it.id == taskId }
        if (index >= 0 && index < queued.size - 1) {
            val currentTask = queued[index]
            val nextTask = queued[index + 1]
            val newOrder = nextTask.queueOrder + 1
            repository.updateQueueOrder(currentTask.id, newOrder)
            onTaskStateChanged()
        }
    }

    suspend fun clearHistory(deletePhysicalFiles: Boolean) {
        if (deletePhysicalFiles) {
            val completedTasks = repository.getAllCompletedTasksSync()
            for (task in completedTasks) {
                task.filePath?.let { path ->
                    try {
                        File(path).delete()
                    } catch (_: Exception) {}
                }
                task.contentUri?.let { uriStr ->
                    try {
                        appContext.contentResolver.delete(uriStr.toUri(), null, null)
                    } catch (_: Exception) {}
                }
            }
        }
        repository.clearFinishedTasks()
        onTaskStateChanged()
    }
}
