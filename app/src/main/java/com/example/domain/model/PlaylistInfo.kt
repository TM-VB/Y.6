package com.example.domain.model

import java.util.Locale

data class PlaylistEntry(
    val id: String,
    val title: String,
    val durationSeconds: Long? = null,
    val uploader: String? = null,
    val url: String,
    val thumbnailUrl: String? = null,
    val isSelected: Boolean = true
) {
    val formattedDuration: String
        get() {
            val totalSeconds = durationSeconds ?: return "00:00"
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

data class PlaylistInfo(
    val id: String,
    val title: String,
    val uploader: String? = null,
    val webpageUrl: String,
    val thumbnailUrl: String? = null,
    val entries: List<PlaylistEntry> = emptyList()
) {
    val totalCount: Int
        get() = entries.size
}
