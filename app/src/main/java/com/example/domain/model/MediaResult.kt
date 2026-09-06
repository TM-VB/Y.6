package com.example.domain.model

import java.io.File

/**
 * Result representation of a completed media processing task.
 */
data class MediaResult(
    val outputFile: File,
    val mimeType: String,
    val durationSeconds: Double? = null,
    val sizeBytes: Long = 0L,
    val operation: String = "Media Processing"
)
