package com.example.downloader.queue

import com.example.data.repository.DownloadRepository
import com.example.data.settings.AppSettings
import com.example.downloader.network.NetworkMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Coordinates concurrency limits, queue slots, and network availability.
 * Decides which queued tasks to dispatch for execution.
 */
class DownloadQueueCoordinator(
    private val repository: DownloadRepository,
    private val appSettings: AppSettings,
    private val networkMonitor: NetworkMonitor,
    private val activeJobs: ConcurrentHashMap<String, Job>,
    private val scope: CoroutineScope,
    private val onStartTask: suspend (taskId: String) -> Unit,
    private val onTaskExecutionFailed: (suspend (taskId: String, throwable: Throwable) -> Unit)? = null
) {
    private val queueMutex = Mutex()
    private val claimedTaskIds = ConcurrentHashMap.newKeySet<String>()
    private val _activeDownloadCount = MutableStateFlow(0)
    val activeDownloadCount: StateFlow<Int> = _activeDownloadCount.asStateFlow()

    fun updateActiveCount(count: Int) {
        _activeDownloadCount.value = count
    }

    fun isTaskClaimed(taskId: String): Boolean = claimedTaskIds.contains(taskId)

    fun releaseClaim(taskId: String) {
        claimedTaskIds.remove(taskId)
        activeJobs.remove(taskId)
        _activeDownloadCount.value = activeJobs.size
    }

    suspend fun processQueue() {
        queueMutex.withLock {
            // Purge any inactive/completed/cancelled jobs to guarantee accurate active counts
            activeJobs.entries.removeIf { entry ->
                val inactive = !entry.value.isActive
                if (inactive && entry.value.isCancelled) {
                    claimedTaskIds.remove(entry.key)
                }
                inactive
            }

            val maxConcurrency = appSettings.concurrentDownloads.value.coerceIn(1, 3)
            val currentActiveCount = activeJobs.size
            val availableSlots = maxConcurrency - currentActiveCount

            _activeDownloadCount.value = currentActiveCount

            if (availableSlots <= 0) {
                return
            }

            if (!networkMonitor.isOnline()) {
                return
            }

            val queuedTasks = repository.getQueuedTasks()
                .filter { task ->
                    !claimedTaskIds.contains(task.id) &&
                    (activeJobs[task.id]?.isActive != true)
                }
                .distinctBy { it.id }

            val tasksToStart = mutableListOf<com.example.data.local.DownloadTaskEntity>()
            for (task in queuedTasks) {
                if (tasksToStart.size >= availableSlots) break
                if (claimedTaskIds.add(task.id)) {
                    tasksToStart.add(task)
                }
            }

            for (task in tasksToStart) {
                val existingJob = activeJobs[task.id]
                if (existingJob == null || !existingJob.isActive) {
                    val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                        try {
                            onStartTask(task.id)
                        } catch (ce: kotlinx.coroutines.CancellationException) {
                            throw ce
                        } catch (t: Throwable) {
                            android.util.Log.e(
                                "DownloadQueueCoordinator",
                                "Task ${task.id} execution failed with uncaught exception",
                                t
                            )
                            try {
                                onTaskExecutionFailed?.invoke(task.id, t)
                            } catch (callbackEx: Throwable) {
                                android.util.Log.e("DownloadQueueCoordinator", "Error in onTaskExecutionFailed for ${task.id}", callbackEx)
                            }
                            // State machine safety: Ensure task is never left dangling in non-terminal state
                            try {
                                val current = repository.getTaskByIdSync(task.id)
                                if (current != null && !com.example.downloader.lifecycle.DownloadStateMachine.isTerminalOrPaused(current.status)) {
                                    val errorMsg = t.localizedMessage ?: "Unexpected error: ${t.javaClass.simpleName}"
                                    val updated = repository.markFailedOrCancelled(
                                        id = task.id,
                                        runId = current.runId,
                                        status = com.example.domain.model.DownloadStatus.FAILED,
                                        errorMessage = errorMsg
                                    )
                                    if (updated == 0 && current.status == com.example.domain.model.DownloadStatus.QUEUED) {
                                        repository.markQueuedTaskFailed(task.id, errorMsg)
                                    }
                                }
                            } catch (dbEx: Throwable) {
                                android.util.Log.e("DownloadQueueCoordinator", "Failed to update DB for failed task ${task.id}", dbEx)
                            }
                        }
                    }

                    job.invokeOnCompletion { cause ->
                        if (cause is kotlinx.coroutines.CancellationException || job.isCancelled) {
                            claimedTaskIds.remove(task.id)
                        }
                        if (activeJobs.remove(task.id, job)) {
                            _activeDownloadCount.value = activeJobs.size
                            scope.launch { processQueue() }
                        }
                    }

                    activeJobs[task.id] = job
                    _activeDownloadCount.value = activeJobs.size
                    job.start()
                } else {
                    claimedTaskIds.remove(task.id)
                }
            }
        }
    }
}
