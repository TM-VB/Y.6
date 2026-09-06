package com.example.domain.model

import java.util.Locale

data class VideoMetadata(
    val id: String,
    val title: String,
    val uploader: String,
    val durationSeconds: Int,
    val thumbnailUrl: String?,
    val webpageUrl: String,
    val formats: List<FormatOption> = emptyList()
) {
    val formattedDuration: String
        get() {
            if (durationSeconds <= 0) return "Live / Unknown"
            val hours = durationSeconds / 3600
            val minutes = (durationSeconds % 3600) / 60
            val seconds = durationSeconds % 60
            return if (hours > 0) {
                String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format(Locale.US, "%02d:%02d", minutes, seconds)
            }
        }
}
