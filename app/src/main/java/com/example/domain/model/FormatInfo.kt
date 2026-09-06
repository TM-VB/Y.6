package com.example.domain.model

import java.util.Locale

/**
 * Format option metadata extracted from yt-dlp.
 * Designed to handle varied formats (video, audio, combined, raw) with nullable fields.
 */
data class FormatInfo(
    val formatId: String,
    val formatNote: String? = null,
    val extension: String,
    val resolution: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val fps: Double? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val audioChannels: Int? = null,
    val bitrate: Double? = null,
    val filesize: Long? = null,
    val filesizeApprox: Long? = null,
    val vcodec: String? = null,
    val acodec: String? = null,
    val dynamicRange: String? = null,
    val protocol: String? = null,
    val container: String? = null,
    val hasVideo: Boolean = true,
    val hasAudio: Boolean = true
) {
    val isVideoAndAudio: Boolean
        get() = hasVideo && hasAudio

    val isVideoOnly: Boolean
        get() = hasVideo && !hasAudio

    val isAudioOnly: Boolean
        get() = !hasVideo && hasAudio

    val effectiveFileSize: Long?
        get() = filesize ?: filesizeApprox

    val displayTitle: String
        get() = when {
            isAudioOnly -> "Audio • ${extension.uppercase()}"
            resolution != null && resolution.isNotBlank() && resolution != "audio only" -> "$resolution • ${extension.uppercase()}"
            height != null && height > 0 -> "${height}p • ${extension.uppercase()}"
            else -> "Format $formatId • ${extension.uppercase()}"
        }

    val displaySubtitle: String
        get() {
            val parts = mutableListOf<String>()
            if (fps != null && fps > 0 && !isAudioOnly) {
                parts.add("${fps.toInt()} fps")
            }
            if (bitrate != null && bitrate > 0) {
                parts.add("${bitrate.toInt()} kbps")
            }
            val size = effectiveFileSize
            if (size != null && size > 0) {
                val mb = size / (1024.0 * 1024.0)
                parts.add(String.format(Locale.US, "%.1f MB", mb))
            }
            if (!vcodec.isNullOrBlank() && vcodec != "none") {
                parts.add(vcodec.substringBefore("."))
            } else if (!acodec.isNullOrBlank() && acodec != "none") {
                parts.add(acodec.substringBefore("."))
            }
            if (!formatNote.isNullOrBlank()) {
                parts.add(formatNote)
            }
            return if (parts.isEmpty()) "Standard Stream" else parts.joinToString(" • ")
        }
}
