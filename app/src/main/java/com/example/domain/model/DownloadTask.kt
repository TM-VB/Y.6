package com.example.domain.model

/**
 * Domain representation of a download task.
 */
data class DownloadTask(
    val id: String,
    val url: String,
    val title: String,
    val thumbnailUrl: String? = null,
    val formatId: String,
    val formatDescription: String,
    val cutSettings: CutSettings = CutSettings(),
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val stage: DownloadStage = DownloadStage.QUEUED,
    val runId: Long = 0L,
    val progress: Float = 0f,
    val downloadSpeed: String = "",
    val eta: String = "",
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val outputPath: String? = null,
    val contentUri: String? = null,
    val expectedMediaSizeBytes: Long? = null,
    val videoStreamBytes: Long? = null,
    val audioStreamBytes: Long? = null
)
