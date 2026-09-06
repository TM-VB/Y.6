package com.example.domain.model

/**
 * Real-time download progress model.
 */
data class DownloadProgress(
    val taskId: String,
    val runId: Long = 0L,
    val stage: DownloadStage = DownloadStage.DOWNLOADING,
    val progressPercentage: Float = 0f,
    val speed: String = "",
    val eta: String = "",
    val totalBytes: Long = 0L,
    val downloadedBytes: Long = 0L,
    val statusText: String = ""
)
