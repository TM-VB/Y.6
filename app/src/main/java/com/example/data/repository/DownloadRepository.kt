package com.example.data.repository

import com.example.data.local.DownloadTaskDao
import com.example.data.local.DownloadTaskEntity
import kotlinx.coroutines.flow.Flow

class DownloadRepository(private val dao: DownloadTaskDao) {

    val allTasks: Flow<List<DownloadTaskEntity>> = dao.getAllTasks()

    fun getTaskById(id: String): Flow<DownloadTaskEntity?> = dao.getTaskById(id)

    suspend fun getTaskByIdSync(id: String): DownloadTaskEntity? = dao.getTaskByIdSync(id)

    suspend fun getQueuedTasks(): List<DownloadTaskEntity> = dao.getQueuedTasks()

    suspend fun getActiveTasksSync(): List<DownloadTaskEntity> = dao.getActiveTasksSync()

    suspend fun markActiveTasksAsInterrupted(): Int = dao.markActiveTasksAsInterrupted()

    suspend fun insertTask(task: DownloadTaskEntity) = dao.insertTask(task)

    suspend fun updateTask(task: DownloadTaskEntity) = dao.updateTask(task)

    suspend fun updateProgress(
        id: String,
        runId: Long,
        status: com.example.domain.model.DownloadStatus,
        stage: com.example.domain.model.DownloadStage,
        progress: Float,
        downloadSpeed: String,
        eta: String,
        downloadedSize: String = "",
        totalSize: String = ""
    ): Int = dao.updateProgress(id, runId, status, stage, progress, downloadSpeed, eta, downloadedSize, totalSize)

    suspend fun updateProgress(
        id: String,
        status: com.example.domain.model.DownloadStatus,
        progress: Float,
        downloadSpeed: String,
        eta: String,
        downloadedSize: String = "",
        totalSize: String = ""
    ): Int = dao.updateProgress(id, status, progress, downloadSpeed, eta, downloadedSize, totalSize)

    suspend fun updateActiveState(
        id: String,
        runId: Long,
        status: com.example.domain.model.DownloadStatus,
        stage: com.example.domain.model.DownloadStage
    ): Int = dao.updateActiveState(id, runId, status, stage)

    suspend fun updateActiveStage(
        id: String,
        runId: Long,
        stage: com.example.domain.model.DownloadStage
    ): Int = dao.updateActiveStage(id, runId, stage)

    suspend fun markCompleted(
        id: String,
        runId: Long,
        contentUri: String,
        filePath: String,
        downloadedSize: String,
        totalSize: String,
        completedAt: Long
    ): Int = dao.markCompleted(id, runId, contentUri, filePath, downloadedSize, totalSize, completedAt)

    suspend fun markFailedOrCancelled(
        id: String,
        runId: Long,
        status: com.example.domain.model.DownloadStatus,
        errorMessage: String
    ): Int = dao.markFailedOrCancelled(id, runId, status, errorMessage)

    suspend fun markQueuedTaskFailed(id: String, errorMessage: String): Int =
        dao.markQueuedTaskFailed(id, errorMessage)

    suspend fun findExistingTask(url: String, formatId: String, startTime: String?, endTime: String?): DownloadTaskEntity? =
        dao.findExistingTask(url, formatId, startTime, endTime)

    suspend fun updateQueueOrder(id: String, order: Long) = dao.updateQueueOrder(id, order)

    suspend fun deleteTasksByIds(ids: List<String>) = dao.deleteTasksByIds(ids)

    suspend fun getAllCompletedTasksSync(): List<DownloadTaskEntity> = dao.getAllCompletedTasksSync()

    suspend fun deleteTask(id: String) = dao.deleteTaskById(id)

    suspend fun clearFinishedTasks() = dao.clearFinishedTasks()
}
