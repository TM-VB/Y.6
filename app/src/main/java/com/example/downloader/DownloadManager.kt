package com.example.downloader

import android.content.Context
import com.example.data.local.DownloadTaskEntity
import com.example.domain.model.DownloadRequest
import com.example.domain.model.TimeRange
import com.example.downloader.queue.DownloadQueueManager
import kotlinx.coroutines.flow.StateFlow

/**
 * Top-level facade for download orchestration. Delegates lifecycle and concurrency
 * operations to [DownloadQueueManager].
 */
class DownloadManager private constructor(context: Context) {

    private val queueManager = DownloadQueueManager.getInstance(context.applicationContext)

    val activeCount: StateFlow<Int> = queueManager.activeDownloadCount

    fun hasActiveDownloads(): Boolean = queueManager.activeDownloadCount.value > 0

    fun getActiveCount(): Int = queueManager.activeDownloadCount.value

    suspend fun recoverInterruptedDownloads() {
        queueManager.recoverInterruptedDownloads()
    }

    suspend fun checkDuplicate(url: String, formatId: String, startTime: String?, endTime: String?): DownloadTaskEntity? {
        return queueManager.checkDuplicate(url, formatId, startTime, endTime)
    }

    fun startDownload(request: DownloadRequest): String {
        return queueManager.enqueueDownload(request)
    }

    fun startDownload(
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
        return queueManager.enqueueDownload(
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

    fun enqueueBatch(requests: List<DownloadRequest>) {
        queueManager.enqueueBatch(requests)
    }

    fun pauseDownload(taskId: String) {
        queueManager.pauseDownload(taskId)
    }

    fun resumeDownload(taskId: String) {
        queueManager.resumeDownload(taskId)
    }

    fun cancelDownload(taskId: String) {
        queueManager.cancelDownload(taskId)
    }

    fun retryDownload(taskId: String) {
        queueManager.retryDownload(taskId)
    }

    fun deleteDownload(taskId: String) {
        queueManager.deleteDownload(taskId)
    }

    fun clearFinished(deleteFiles: Boolean = false) {
        queueManager.clearHistory(deleteFiles)
    }

    fun reorderTask(taskId: String, newOrder: Long) {
        queueManager.reorderTask(taskId, newOrder)
    }

    fun moveTaskUp(taskId: String) {
        queueManager.moveTaskUp(taskId)
    }

    fun moveTaskDown(taskId: String) {
        queueManager.moveTaskDown(taskId)
    }

    fun bulkCancel(taskIds: List<String>) {
        queueManager.bulkCancel(taskIds)
    }

    fun bulkRetry(taskIds: List<String>) {
        queueManager.bulkRetry(taskIds)
    }

    fun bulkDelete(taskIds: List<String>) {
        queueManager.bulkDelete(taskIds)
    }

    suspend fun processQueue() {
        queueManager.processQueue()
    }

    companion object {
        @Volatile
        private var INSTANCE: DownloadManager? = null

        fun getInstance(context: Context): DownloadManager {
            return INSTANCE ?: synchronized(this) {
                val instance = DownloadManager(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }
}
