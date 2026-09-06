package com.example.domain.model

/**
 * Explicit lifecycle stages of a download task.
 * Eliminates heuristic stage detection and guarantees deterministic execution.
 */
enum class DownloadStage {
    QUEUED,
    PREPARING,
    DOWNLOADING,
    MERGING,
    CUTTING,
    PUBLISHING,
    COMPLETED
}
