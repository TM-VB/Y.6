package com.example.domain.model

import java.util.Locale

/**
 * Detailed real-time progress model for FFmpeg media processing tasks.
 */
data class ProcessingProgress(
    val percentage: Float,
    val timeSeconds: Double = 0.0,
    val totalDurationSeconds: Double = 0.0,
    val speed: String = "",
    val fps: Double = 0.0,
    val frame: Long = 0L,
    val etaSeconds: Long = 0L,
    val statusDescription: String = "Processing media...",
    val runId: Long = 0L
) {
    val etaFormatted: String
        get() = if (etaSeconds > 0) {
            val m = etaSeconds / 60
            val s = etaSeconds % 60
            String.format(Locale.US, "%02d:%02d", m, s)
        } else ""

    val progressInt: Int
        get() = percentage.toInt().coerceIn(0, 100)
}
