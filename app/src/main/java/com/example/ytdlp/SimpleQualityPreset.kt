package com.example.ytdlp

enum class SimpleQualityPreset(
    val label: String,
    val isAudioOnly: Boolean,
    val targetHeight: Int = 0
) {
    BEST_QUALITY("Best Quality (Auto)", false, 0),
    P2160("4K UHD (2160p)", false, 2160),
    P1440("2K QHD (1440p)", false, 1440),
    P1080("Full HD (1080p)", false, 1080),
    P720("HD (720p)", false, 720),
    P480("SD (480p)", false, 480),
    P360("Low (360p)", false, 360),
    BEST_AUDIO("Best Audio (Auto)", true, 0),
    AUDIO_MP3("Audio (MP3)", true, 0),
    AUDIO_M4A("Audio (M4A / AAC)", true, 0)
}
