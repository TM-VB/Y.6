package com.example.downloader.queue

import android.content.Context
import com.example.data.local.AppDatabase
import com.example.data.local.DownloadTaskEntity
import com.example.data.repository.DownloadRepository
import com.example.data.settings.AppSettings
import com.example.domain.model.DownloadRequest
import com.example.domain.model.TimeRange
import com.example.downloader.engine.DownloadEngine
import com.example.downloader.engine.YtDlpDownloadEngine
import com.example.downloader.execution.DownloadExecutionManager
import com.example.downloader.lifecycle.DownloadTaskLifecycle
import com.example.downloader.network.NetworkMonitor
import com.example.downloader.notification.DownloadNotificationManager
import com.example.downloader.recovery.DownloadRecoveryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Advanced Queue & Lifecycle Facade for media downloads.
 * Preserves the exact public API, threading model, and behaviour for ViewModels,
 * services, and test suites, while delegating distinct responsibilities to:
 * - [DownloadQueueCoordinator]: Concurrency limit, queue slots, and network events.
 * - [DownloadTaskLifecycle]: Enqueue, pause, resume, cancel, retry, delete, reordering.
 * - [DownloadExecutionManager]: Pre-flight checks, download engine execution, progress & MediaStore.
 * - [DownloadRecoveryManager]: Post-crash consistency verification & interrupted recovery.
 * - [DownloadNotificationManager]: Foreground notification updates and throttling.
 */
class DownloadQueueManager(
    context: Context,
    private val repository: DownloadRepository = DownloadRepository(AppDatabase.getInstance(context.applicationContext).downloadTaskDao()),
    private val appSettings: AppSettings = AppSettings.getInstance(context.applicationContext),
    private val networkMonitor: NetworkMonitor = NetworkMonitor(context.applicationContext),
    private val downloadEngine: DownloadEngine = YtDlpDownloadEngine.getInstance(context.applicationContext),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    private val appContext = context.applicationContext
    private val runIdCounter = AtomicLong(System.currentTimeMillis())
    private val activeRunIds = ConcurrentHashMap<String, Long>()
    private val taskMutexes = ConcurrentHashMap<String, Mutex>()
    private val activeJobs = ConcurrentHashMap<String, Job>()

    private val notificationManager = DownloadNotificationManager(appContext)
    private val recoveryManager = DownloadRecoveryManager(appContext, repository)

    private val coordinator: DownloadQueueCoordinator = DownloadQueueCoordinator(
        repository = repository,
        appSettings = appSettings,
        networkMonitor = networkMonitor,
        activeJobs = activeJobs,
        scope = scope,
        onStartTask = { taskId ->
            executionManager.executeTask(taskId)
        },
        onTaskExecutionFailed = { taskId, error ->
            executionManager.handleUnhandledException(taskId, error)
        }
    )

    private val executionManager = DownloadExecutionManager(
        context = appContext,
        repository = repository,
        appSettings = appSettings,
        downloadEngine = downloadEngine,
        notificationManager = notificationManager,
        activeRunIds = activeRunIds,
        runIdCounter = runIdCounter,
        scope = scope,
        onExecutionFinished = { taskId ->
            coordinator.updateActiveCount(activeJobs.size)
        },
        onRetryRequested = { taskId, scheduledRunId ->
            retryDownload(taskId, scheduledRunId)
        }
    )

    private val taskLifecycle = DownloadTaskLifecycle(
        context = appContext,
        repository = repository,
        downloadEngine = downloadEngine,
        notificationManager = notificationManager,
        activeRunIds = activeRunIds,
        activeJobs = activeJobs,
        taskMutexes = taskMutexes,
        runIdCounter = runIdCounter,
        onCancelPendingRetry = { taskId ->
            executionManager.cancelPendingRetry(taskId)
        },
        onReleaseClaim = { taskId ->
            coordinator.releaseClaim(taskId)
        },
        onTaskStateChanged = {
            coordinator.updateActiveCount(activeJobs.size)
            coordinator.processQueue()
        }
    )

    val activeDownloadCount: StateFlow<Int> = coordinator.activeDownloadCount

    init {
        // Startup: Recover tasks that were abruptly interrupted by app restart or OS termination
        scope.launch {
            recoveryManager.recoverInterruptedDownloads()
            recoveryManager.verifyDatabaseConsistency()
        }

        // Network monitoring: Auto-resume queued downloads when connectivity returns
        scope.launch {
            networkMonitor.isOnlineFlow.collect { isOnline ->
                if (isOnline) {
                    coordinator.processQueue()
                }
            }
        }
    }

    fun getActiveRunId(taskId: String): Long? = taskLifecycle.getActiveRunId(taskId)

    suspend fun recoverInterruptedDownloads() {
        recoveryManager.recoverInterruptedDownloads()
    }

    suspend fun verifyDatabaseConsistency() {
        recoveryManager.verifyDatabaseConsistency()
    }

    suspend fun checkDuplicate(
        url: String,
        formatId: String,
        startTime: String?,
        endTime: String?
    ): DownloadTaskEntity? {
        return recoveryManager.checkDuplicate(url, formatId, startTime, endTime)
    }

    fun enqueueDownload(request: DownloadRequest): String {
        val taskId = request.id
        scope.launch {
            taskLifecycle.enqueueDownload(request)
        }
        return taskId
    }

    fun enqueueDownload(
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
        val taskId = java.util.UUID.randomUUID().toString()
        scope.launch {
            taskLifecycle.enqueueDownload(
                url = url,
                title = title,
                thumbnailUrl = thumbnailUrl,
                formatId = formatId,
                formatDescription = formatDescription,
                isAudioOnly = isAudioOnly,
                timeRange = timeRange,
                downloadSubtitles = downloadSubtitles,
                subtitleLanguage = subtitleLanguage
            )
        }
        return taskId
    }

    fun enqueueBatch(requests: List<DownloadRequest>) {
        scope.launch {
            taskLifecycle.enqueueBatch(requests)
        }
    }

    suspend fun pauseDownloadSync(taskId: String) {
        taskLifecycle.pauseDownloadSync(taskId)
    }

    fun pauseDownload(taskId: String): Job = scope.launch {
        pauseDownloadSync(taskId)
    }

    suspend fun resumeDownloadSync(taskId: String) {
        taskLifecycle.resumeDownloadSync(taskId)
    }

    fun resumeDownload(taskId: String): Job = scope.launch {
        resumeDownloadSync(taskId)
    }

    suspend fun cancelDownloadSync(taskId: String) {
        taskLifecycle.cancelDownloadSync(taskId)
    }

    fun cancelDownload(taskId: String): Job = scope.launch {
        cancelDownloadSync(taskId)
    }

    suspend fun retryDownloadSync(taskId: String, expectedRunId: Long? = null) {
        taskLifecycle.retryDownloadSync(taskId, expectedRunId)
    }

    fun retryDownload(taskId: String, expectedRunId: Long? = null): Job = scope.launch {
        retryDownloadSync(taskId, expectedRunId)
    }

    suspend fun deleteDownloadSync(taskId: String) {
        taskLifecycle.deleteDownloadSync(taskId)
    }

    fun deleteDownload(taskId: String): Job = scope.launch {
        deleteDownloadSync(taskId)
    }

    fun reorderTask(taskId: String, newOrder: Long) {
        scope.launch {
            taskLifecycle.reorderTask(taskId, newOrder)
        }
    }

    fun moveTaskUp(taskId: String) {
        scope.launch {
            taskLifecycle.moveTaskUp(taskId)
        }
    }

    fun moveTaskDown(taskId: String) {
        scope.launch {
            taskLifecycle.moveTaskDown(taskId)
        }
    }

    fun bulkCancel(taskIds: List<String>) {
        taskIds.forEach { cancelDownload(it) }
    }

    fun bulkRetry(taskIds: List<String>) {
        taskIds.forEach { retryDownload(it) }
    }

    fun bulkDelete(taskIds: List<String>) {
        scope.launch {
            for (id in taskIds) {
                deleteDownloadSync(id)
            }
        }
    }

    fun clearHistory(deletePhysicalFiles: Boolean) {
        scope.launch {
            taskLifecycle.clearHistory(deletePhysicalFiles)
        }
    }

    suspend fun processQueue() {
        coordinator.processQueue()
    }

    companion object {
        @Volatile
        private var INSTANCE: DownloadQueueManager? = null

        fun getInstance(context: Context): DownloadQueueManager {
            return INSTANCE ?: synchronized(this) {
                val instance = DownloadQueueManager(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }
}
