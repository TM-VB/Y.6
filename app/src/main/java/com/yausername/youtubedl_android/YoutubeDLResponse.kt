package com.yausername.youtubedl_android

data class YoutubeDLResponse(
    val command: List<String> = emptyList(),
    val exitCode: Int = 0,
    val out: String = "",
    val err: String = "",
    val elapsedTime: Long = 0L
)
