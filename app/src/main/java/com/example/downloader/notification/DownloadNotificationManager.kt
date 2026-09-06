package com.example.downloader.notification

import android.content.Context
import com.example.domain.model.DownloadStatus
import com.example.service.DownloadForegroundService
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages foreground service notification updates and throttling for downloads.
 */
class DownloadNotificationManager(
    private val context: Context
) {
    private val lastNotificationTimes = ConcurrentHashMap<String, Long>()

    fun startOrUpdate(
        taskId: String,
        title: String,
        progress: Int,
        status: DownloadStatus,
        speed: String = ""
    ) {
        DownloadForegroundService.startOrUpdate(context, taskId, title, progress, status, speed)
    }

    fun onProgressUpdateThrottled(
        taskId: String,
        title: String,
        progress: Float,
        status: DownloadStatus,
        speedText: String
    ) {
        val now = System.currentTimeMillis()
        val lastNotif = lastNotificationTimes[taskId] ?: 0L
        if (now - lastNotif >= 1000L || progress >= 100f) {
            lastNotificationTimes[taskId] = now
            DownloadForegroundService.startOrUpdate(
                context = context,
                taskId = taskId,
                title = title,
                progress = progress.toInt(),
                status = status,
                speed = speedText
            )
        }
    }

    fun onTaskCompleted(taskId: String, title: String, contentUri: String) {
        lastNotificationTimes.remove(taskId)
        DownloadForegroundService.onTaskCompleted(context, taskId, title, contentUri)
    }

    fun onTaskFailed(taskId: String, title: String, errorMessage: String) {
        lastNotificationTimes.remove(taskId)
        DownloadForegroundService.onTaskFailed(context, taskId, title, errorMessage)
    }

    fun updateOrDismissIfIdle(
        taskId: String,
        title: String,
        status: DownloadStatus,
        progress: Int = 0,
        speed: String = ""
    ) {
        lastNotificationTimes.remove(taskId)
        DownloadForegroundService.updateOrDismissIfIdle(context, taskId, title, status, progress, speed)
    }

    fun clearTask(taskId: String) {
        lastNotificationTimes.remove(taskId)
    }
}
