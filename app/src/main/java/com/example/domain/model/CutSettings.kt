package com.example.domain.model

/**
 * Settings for trimming/cutting a section of the video.
 */
data class CutSettings(
    val enabled: Boolean = false,
    val startTime: String? = null,
    val endTime: String? = null,
    val mode: CutMode = CutMode.FAST_CUT
)
