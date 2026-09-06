package com.example.ytdlp

import com.example.domain.model.FormatInfo

data class FormatSelection(
    val formatSelector: String,
    val qualityLabel: String,
    val container: String = "mp4",
    val requiresMerge: Boolean = false,
    val isAudioOnly: Boolean = false,
    val displaySize: String? = null,
    val fps: Double? = null,
    val audioCodec: String? = null,
    val videoFormat: FormatInfo? = null,
    val audioFormat: FormatInfo? = null,
    val displaySummary: String = "$qualityLabel • ${container.uppercase()}${displaySize?.let { " • $it" } ?: ""}"
)
