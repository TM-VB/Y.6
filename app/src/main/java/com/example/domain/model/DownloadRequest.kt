package com.example.domain.model

import java.util.UUID

/**
 * Domain model representing a structured download request.
 * Encapsulates the target URL, format specification, cut parameters, and output destination.
 */
data class DownloadRequest(
    val id: String = UUID.randomUUID().toString(),
    val runId: Long = 0L,
    val url: String,
    val formatSelector: String = "bestvideo+bestaudio/best",
    val startTime: String? = null,
    val endTime: String? = null,
    val cutMode: CutMode = CutMode.FAST_CUT,
    val outputName: String? = null,
    val outputDestination: String? = null,
    val isAudioOnly: Boolean = false,
    val isVideoOnly: Boolean = false,
    val title: String = "Video",
    val thumbnailUrl: String? = null,
    val formatDescription: String = "Best Quality",
    val downloadSubtitles: Boolean = false,
    val subtitleLanguage: String? = null,
    val expectedMediaSizeBytes: Long? = null,
    val videoStreamBytes: Long? = null,
    val audioStreamBytes: Long? = null
) {
    val isFullVideo: Boolean
        get() = startTime.isNullOrBlank() || endTime.isNullOrBlank()

    val hasTimeTrim: Boolean
        get() = !isFullVideo

    /**
     * Resolves the final yt-dlp format parameter string.
     * Ensures video-only formats (such as 137, 248) are merged with bestaudio
     * so that the resulting video is not silent.
     */
    fun resolveFormatSelector(): String {
        val trimmed = formatSelector.trim()
        return when {
            isAudioOnly -> {
                if (trimmed.isNotBlank() && trimmed != "best") trimmed else "bestaudio/best"
            }
            isVideoOnly -> {
                if (trimmed.isNotBlank() && trimmed != "best") trimmed else "bestvideo/best"
            }
            trimmed.contains("+") || trimmed.contains("/") || trimmed.equals("best", ignoreCase = true) -> {
                trimmed
            }
            trimmed.all { it.isDigit() } -> {
                // If it's a numeric format ID for video only, combine with bestaudio unless video-only was requested
                "$trimmed+bestaudio/best"
            }
            else -> {
                if (trimmed.isNotBlank()) "$trimmed+bestaudio/best" else "bestvideo+bestaudio/best"
            }
        }
    }
}
