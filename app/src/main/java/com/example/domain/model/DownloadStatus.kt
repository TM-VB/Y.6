package com.example.domain.model

enum class DownloadStatus {
    IDLE,
    QUEUED,
    PREPARING,
    ANALYZING,
    DOWNLOADING,
    PAUSED,
    PROCESSING_FFMPEG,
    COMPLETED,
    FAILED,
    CANCELLED,
    INTERRUPTED
}
