package com.example.domain.model

import java.util.Locale

/**
 * Core model representing extracted video metadata.
 * Flexible and nullable for varied platform sources supported by yt-dlp.
 */
data class VideoInfo(
    val id: String,
    val title: String,
    val uploader: String? = null,
    val channel: String? = null,
    val duration: Long? = null,
    val thumbnail: String? = null,
    val webpageUrl: String,
    val description: String? = null,
    val extractor: String? = null,
    val availability: String? = null,
    val formats: List<FormatInfo> = emptyList(),
    val subtitles: List<SubtitleTrack> = emptyList()
) {
    val formattedDuration: String
        get() {
            val totalSeconds = duration ?: return "00:00"
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60
            return if (hours > 0) {
                String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format(Locale.US, "%02d:%02d", minutes, seconds)
            }
        }
}
