package com.example.domain.model

data class TimeRange(
    val startTime: String, // HH:MM:SS format
    val endTime: String,   // HH:MM:SS format
    val cutMode: CutMode = CutMode.FAST_CUT
)
