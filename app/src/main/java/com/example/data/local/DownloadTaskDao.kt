package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.domain.model.DownloadStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadTaskDao {

    @Query("SELECT * FROM download_tasks ORDER BY queueOrder ASC, createdAt DESC")
    fun getAllTasks(): Flow<List<DownloadTaskEntity>>

    @Query("SELECT * FROM download_tasks WHERE id = :id")
    fun getTaskById(id: String): Flow<DownloadTaskEntity?>

    @Query("SELECT * FROM download_tasks WHERE id = :id")
    suspend fun getTaskByIdSync(id: String): DownloadTaskEntity?

    @Query("SELECT * FROM download_tasks WHERE status = 'QUEUED' ORDER BY queueOrder ASC, createdAt ASC")
    suspend fun getQueuedTasks(): List<DownloadTaskEntity>

    @Query("SELECT * FROM download_tasks WHERE status = 'DOWNLOADING' OR status = 'PREPARING' OR status = 'PROCESSING_FFMPEG'")
    suspend fun getActiveTasksSync(): List<DownloadTaskEntity>

    @Query("UPDATE download_tasks SET status = 'INTERRUPTED', downloadSpeed = '', eta = '' WHERE status = 'DOWNLOADING' OR status = 'PREPARING' OR status = 'PROCESSING_FFMPEG'")
    suspend fun markActiveTasksAsInterrupted(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: DownloadTaskEntity)

    @Update
    suspend fun updateTask(task: DownloadTaskEntity)

    @Query("""
        UPDATE download_tasks 
        SET status = :status,
            stage = :stage,
            progress = :progress, 
            downloadSpeed = :downloadSpeed, 
            eta = :eta, 
            downloadedSize = CASE WHEN :downloadedSize != '' THEN :downloadedSize ELSE downloadedSize END, 
            totalSize = CASE WHEN :totalSize != '' THEN :totalSize ELSE totalSize END 
        WHERE id = :id AND (runId = :runId OR (:runId = 0 AND runId = 0)) AND status NOT IN ('PAUSED', 'CANCELLED', 'COMPLETED', 'FAILED')
    """)
    suspend fun updateProgress(
        id: String,
        runId: Long,
        status: DownloadStatus,
        stage: com.example.domain.model.DownloadStage,
        progress: Float,
        downloadSpeed: String,
        eta: String,
        downloadedSize: String = "",
        totalSize: String = ""
    ): Int

    suspend fun updateProgress(
        id: String,
        status: DownloadStatus,
        progress: Float,
        downloadSpeed: String,
        eta: String,
        downloadedSize: String = "",
        totalSize: String = ""
    ): Int = updateProgress(
        id = id,
        runId = 0L,
        status = status,
        stage = com.example.domain.model.DownloadStage.DOWNLOADING,
        progress = progress,
        downloadSpeed = downloadSpeed,
        eta = eta,
        downloadedSize = downloadedSize,
        totalSize = totalSize
    )

    @Query("""
        UPDATE download_tasks 
        SET status = :status,
            stage = :stage,
            runId = :runId
        WHERE id = :id 
          AND (status = 'QUEUED' OR status = 'INTERRUPTED' OR runId = :runId) 
          AND status NOT IN ('PAUSED', 'CANCELLED', 'COMPLETED', 'FAILED')
    """)
    suspend fun updateActiveState(
        id: String,
        runId: Long,
        status: DownloadStatus,
        stage: com.example.domain.model.DownloadStage
    ): Int

    @Query("""
        UPDATE download_tasks 
        SET stage = :stage
        WHERE id = :id AND runId = :runId AND status NOT IN ('PAUSED', 'CANCELLED', 'COMPLETED', 'FAILED')
    """)
    suspend fun updateActiveStage(
        id: String,
        runId: Long,
        stage: com.example.domain.model.DownloadStage
    ): Int

    @Query("""
        UPDATE download_tasks 
        SET status = 'COMPLETED',
            stage = 'COMPLETED',
            progress = 100,
            contentUri = :contentUri,
            filePath = :filePath,
            downloadSpeed = '',
            eta = '',
            downloadedSize = CASE WHEN :downloadedSize != '' THEN :downloadedSize ELSE downloadedSize END,
            totalSize = CASE WHEN :totalSize != '' THEN :totalSize ELSE totalSize END,
            completedAt = :completedAt
        WHERE id = :id AND runId = :runId AND status NOT IN ('PAUSED', 'CANCELLED', 'FAILED')
    """)
    suspend fun markCompleted(
        id: String,
        runId: Long,
        contentUri: String,
        filePath: String,
        downloadedSize: String,
        totalSize: String,
        completedAt: Long
    ): Int

    @Query("""
        UPDATE download_tasks 
        SET status = :status,
            downloadSpeed = '',
            eta = '',
            errorMessage = :errorMessage
        WHERE id = :id 
          AND (runId = :runId OR (status = 'QUEUED' AND :runId = 0)) 
          AND status NOT IN ('PAUSED', 'CANCELLED', 'COMPLETED')
    """)
    suspend fun markFailedOrCancelled(
        id: String,
        runId: Long,
        status: DownloadStatus,
        errorMessage: String
    ): Int

    @Query("""
        UPDATE download_tasks 
        SET status = 'FAILED',
            downloadSpeed = '',
            eta = '',
            errorMessage = :errorMessage
        WHERE id = :id AND status = 'QUEUED'
    """)
    suspend fun markQueuedTaskFailed(id: String, errorMessage: String): Int

    @Query("SELECT * FROM download_tasks WHERE url = :url AND formatId = :formatId AND (((startTime IS NULL OR startTime = '') AND (:startTime IS NULL OR :startTime = '')) OR startTime = :startTime) AND (((endTime IS NULL OR endTime = '') AND (:endTime IS NULL OR :endTime = '')) OR endTime = :endTime) LIMIT 1")
    suspend fun findExistingTask(url: String, formatId: String, startTime: String?, endTime: String?): DownloadTaskEntity?

    @Query("UPDATE download_tasks SET queueOrder = :order WHERE id = :id")
    suspend fun updateQueueOrder(id: String, order: Long)

    @Query("DELETE FROM download_tasks WHERE id IN (:ids)")
    suspend fun deleteTasksByIds(ids: List<String>)

    @Query("SELECT * FROM download_tasks WHERE status = 'COMPLETED'")
    suspend fun getAllCompletedTasksSync(): List<DownloadTaskEntity>

    @Query("DELETE FROM download_tasks WHERE id = :id")
    suspend fun deleteTaskById(id: String)

    @Query("DELETE FROM download_tasks WHERE status = 'COMPLETED' OR status = 'CANCELLED' OR status = 'FAILED' OR status = 'INTERRUPTED'")
    suspend fun clearFinishedTasks()
}
