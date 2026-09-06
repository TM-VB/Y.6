package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.domain.model.DownloadStage
import com.example.domain.model.DownloadStatus

@Entity(
    tableName = "download_tasks",
    indices = [
        androidx.room.Index(value = ["status"]),
        androidx.room.Index(value = ["url"]),
        androidx.room.Index(value = ["queueOrder"]),
        androidx.room.Index(value = ["createdAt"])
    ]
)
data class DownloadTaskEntity(
    @PrimaryKey val id: String,
    val url: String,
    val title: String,
    val thumbnailUrl: String? = null,
    val formatId: String = "best",
    val formatDescription: String = "Best Quality",
    val startTime: String? = null,
    val endTime: String? = null,
    val cutMode: String = "none",
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val stage: DownloadStage = DownloadStage.QUEUED,
    val runId: Long = 0L,
    val progress: Float = 0f,
    val downloadSpeed: String = "",
    val downloadedSize: String = "",
    val totalSize: String = "",
    val eta: String = "",
    val filePath: String? = null,
    val contentUri: String? = null,
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val retryCount: Int = 0,
    val maxRetries: Int = 3,
    val isAudioOnly: Boolean = false,
    val isVideoOnly: Boolean = false,
    val downloadSubtitles: Boolean = false,
    val subtitleLanguage: String? = null,
    val queueOrder: Long = System.currentTimeMillis()
)
