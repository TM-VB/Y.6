package com.example.domain.model

import java.util.Locale

data class FormatOption(
    val formatId: String,
    val ext: String,
    val resolution: String,
    val width: Int? = null,
    val height: Int? = null,
    val fps: Double? = null,
    val vcodec: String? = null,
    val acodec: String? = null,
    val fileSize: Long = 0L,
    val bitrate: Double? = null,
    val isAudioOnly: Boolean = false,
    val isVideoOnly: Boolean = false,
    val isCombined: Boolean = false,
    val note: String = ""
) {
    val displayTitle: String
        get() {
            return when {
                isAudioOnly -> "Audio • $ext ${if (bitrate != null && bitrate > 0) "${bitrate.toInt()} kbps" else ""}"
                height != null && height > 0 -> "${height}p${if (fps != null && fps > 30) " $fps" else ""} • $ext"
                resolution.isNotBlank() && resolution != "none" -> "$resolution • $ext"
                else -> "Format $formatId • $ext"
            }.trim()
        }

    val displaySubtitle: String
        get() {
            val parts = mutableListOf<String>()
            if (vcodec != null && vcodec != "none") parts.add("V: $vcodec")
            if (acodec != null && acodec != "none") parts.add("A: $acodec")
            if (fileSize > 0) parts.add(formatFileSize(fileSize))
            return if (parts.isNotEmpty()) parts.joinToString(" | ") else "ID: $formatId"
        }

    companion object {
        fun formatFileSize(bytes: Long): String {
            if (bytes <= 0) return "Unknown size"
            val kb = bytes / 1024.0
            val mb = kb / 1024.0
            val gb = mb / 1024.0
            return when {
                gb >= 1.0 -> String.format(Locale.US, "%.2f GB", gb)
                mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
                kb >= 1.0 -> String.format(Locale.US, "%.1f KB", kb)
                else -> "$bytes B"
            }
        }
    }
}
