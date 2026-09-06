package com.example.ytdlp

import com.example.domain.model.FormatInfo
import com.example.downloader.engine.CategorizedFormats
import java.util.Locale

object SmartFormatEngine {

    fun categorize(formats: List<FormatInfo>): CategorizedFormats {
        val videoAndAudio = formats.filter { it.isVideoAndAudio }
        val videoOnly = formats.filter { it.isVideoOnly }
        val audioOnly = formats.filter { it.isAudioOnly }
        return CategorizedFormats(
            videoAndAudioFormats = videoAndAudio.sortedByDescending { it.height ?: 0 },
            videoOnlyFormats = videoOnly.sortedByDescending { it.height ?: 0 },
            audioOnlyFormats = audioOnly.sortedByDescending { it.bitrate ?: 0.0 },
            allFormats = formats
        )
    }

    fun getBestAudioFormat(formats: List<FormatInfo>): FormatInfo? {
        val audioOnly = formats.filter { it.isAudioOnly }
        if (audioOnly.isNotEmpty()) {
            return audioOnly.maxByOrNull { (it.bitrate ?: 0.0) * 1000000 + (it.effectiveFileSize ?: 0L) }
        }
        val withAudio = formats.filter { it.hasAudio }
        return withAudio.maxByOrNull { (it.bitrate ?: 0.0) * 1000000 + (it.effectiveFileSize ?: 0L) }
    }

    fun selectBestQuality(formats: List<FormatInfo>): FormatSelection {
        val bestVideo = formats.filter { it.hasVideo }.maxByOrNull { (it.height ?: 0) * 1000000 + (it.bitrate ?: 0.0).toInt() }
        val bestAudio = getBestAudioFormat(formats)

        return if (bestVideo != null) {
            if (bestVideo.hasAudio) {
                FormatSelection(
                    formatSelector = bestVideo.formatId,
                    qualityLabel = "${bestVideo.height ?: 720}p HD (Best)",
                    container = bestVideo.container ?: bestVideo.extension,
                    requiresMerge = false,
                    isAudioOnly = false,
                    displaySize = formatFileSize(bestVideo.effectiveFileSize),
                    fps = bestVideo.fps,
                    audioCodec = bestVideo.audioCodec ?: bestVideo.acodec,
                    videoFormat = bestVideo,
                    audioFormat = null
                )
            } else {
                val combinedSize = (bestVideo.effectiveFileSize ?: 0L) + (bestAudio?.effectiveFileSize ?: 0L)
                FormatSelection(
                    formatSelector = if (bestAudio != null) "${bestVideo.formatId}+${bestAudio.formatId}" else "${bestVideo.formatId}+bestaudio/best",
                    qualityLabel = "${bestVideo.height ?: 720}p HD (Best)",
                    container = "mp4",
                    requiresMerge = true,
                    isAudioOnly = false,
                    displaySize = if (combinedSize > 0) formatFileSize(combinedSize) else null,
                    fps = bestVideo.fps,
                    audioCodec = bestAudio?.audioCodec ?: bestAudio?.acodec ?: "aac",
                    videoFormat = bestVideo,
                    audioFormat = bestAudio
                )
            }
        } else if (bestAudio != null) {
            FormatSelection(
                formatSelector = bestAudio.formatId,
                qualityLabel = "Best Audio",
                container = bestAudio.container ?: bestAudio.extension,
                requiresMerge = false,
                isAudioOnly = true,
                displaySize = formatFileSize(bestAudio.effectiveFileSize),
                audioCodec = bestAudio.audioCodec ?: bestAudio.acodec,
                videoFormat = null,
                audioFormat = bestAudio
            )
        } else {
            FormatSelection(
                formatSelector = "bestvideo+bestaudio/best",
                qualityLabel = "Best Quality",
                container = "mp4",
                requiresMerge = true,
                isAudioOnly = false
            )
        }
    }

    fun selectFromFormatInfo(formats: List<FormatInfo>, format: FormatInfo): FormatSelection {
        if (format.isAudioOnly) {
            return FormatSelection(
                formatSelector = format.formatId,
                qualityLabel = format.displayTitle,
                container = format.container ?: format.extension,
                requiresMerge = false,
                isAudioOnly = true,
                displaySize = formatFileSize(format.effectiveFileSize),
                audioCodec = format.audioCodec ?: format.acodec,
                videoFormat = null,
                audioFormat = format
            )
        }

        if (format.isVideoAndAudio) {
            return FormatSelection(
                formatSelector = format.formatId,
                qualityLabel = format.displayTitle,
                container = format.container ?: format.extension,
                requiresMerge = false,
                isAudioOnly = false,
                displaySize = formatFileSize(format.effectiveFileSize),
                fps = format.fps,
                audioCodec = format.audioCodec ?: format.acodec,
                videoFormat = format,
                audioFormat = null
            )
        }

        val bestAudio = getBestAudioFormat(formats)
        val combinedSize = (format.effectiveFileSize ?: 0L) + (bestAudio?.effectiveFileSize ?: 0L)
        return FormatSelection(
            formatSelector = if (bestAudio != null) "${format.formatId}+${bestAudio.formatId}" else "${format.formatId}+bestaudio/best",
            qualityLabel = format.displayTitle,
            container = "mp4",
            requiresMerge = true,
            isAudioOnly = false,
            displaySize = if (combinedSize > 0) formatFileSize(combinedSize) else null,
            fps = format.fps,
            audioCodec = bestAudio?.audioCodec ?: bestAudio?.acodec ?: "aac",
            videoFormat = format,
            audioFormat = bestAudio
        )
    }

    fun getAvailablePresets(formats: List<FormatInfo>): List<Pair<SimpleQualityPreset, FormatSelection>> {
        val result = mutableListOf<Pair<SimpleQualityPreset, FormatSelection>>()
        val bestQuality = selectBestQuality(formats)
        result.add(Pair(SimpleQualityPreset.BEST_QUALITY, bestQuality))

        val targetHeights = listOf(
            SimpleQualityPreset.P2160 to 2160,
            SimpleQualityPreset.P1440 to 1440,
            SimpleQualityPreset.P1080 to 1080,
            SimpleQualityPreset.P720 to 720,
            SimpleQualityPreset.P480 to 480,
            SimpleQualityPreset.P360 to 360
        )

        val bestAudio = getBestAudioFormat(formats)

        for ((preset, height) in targetHeights) {
            val matchingFormat = formats.filter { it.hasVideo }.firstOrNull { (it.height ?: 0) == height }
                ?: formats.filter { it.hasVideo }.firstOrNull { (it.height ?: 0) in (height - 40)..(height + 40) }

            if (matchingFormat != null) {
                val selection = selectFromFormatInfo(formats, matchingFormat)
                result.add(Pair(preset, selection))
            }
        }

        if (bestAudio != null) {
            result.add(
                Pair(
                    SimpleQualityPreset.BEST_AUDIO,
                    FormatSelection(
                        formatSelector = bestAudio.formatId,
                        qualityLabel = "Best Audio (${bestAudio.bitrate?.toInt() ?: 128} kbps)",
                        container = "mp3",
                        requiresMerge = false,
                        isAudioOnly = true,
                        displaySize = formatFileSize(bestAudio.effectiveFileSize),
                        audioCodec = bestAudio.audioCodec ?: bestAudio.acodec,
                        videoFormat = null,
                        audioFormat = bestAudio
                    )
                )
            )
        }

        return result
    }

    fun selectByPreset(formats: List<FormatInfo>, preset: SimpleQualityPreset): FormatSelection {
        if (preset == SimpleQualityPreset.BEST_QUALITY) {
            return selectBestQuality(formats)
        }
        if (preset.isAudioOnly) {
            val bestAudio = getBestAudioFormat(formats)
            return FormatSelection(
                formatSelector = bestAudio?.formatId ?: "bestaudio/best",
                qualityLabel = preset.label,
                container = if (preset == SimpleQualityPreset.AUDIO_M4A) "m4a" else "mp3",
                requiresMerge = false,
                isAudioOnly = true,
                displaySize = formatFileSize(bestAudio?.effectiveFileSize),
                audioCodec = bestAudio?.audioCodec ?: bestAudio?.acodec,
                videoFormat = null,
                audioFormat = bestAudio
            )
        }

        val target = formats.filter { it.hasVideo }.firstOrNull { (it.height ?: 0) == preset.targetHeight }
            ?: formats.filter { it.hasVideo }.firstOrNull { (it.height ?: 0) in (preset.targetHeight - 50)..(preset.targetHeight + 50) }

        return if (target != null) {
            selectFromFormatInfo(formats, target)
        } else {
            selectBestQuality(formats)
        }
    }

    fun selectCustom(formats: List<FormatInfo>, customId: String): FormatSelection {
        val trimmed = customId.trim()
        val matching = formats.firstOrNull { it.formatId.equals(trimmed, ignoreCase = true) }
        return if (matching != null) {
            selectFromFormatInfo(formats, matching)
        } else {
            FormatSelection(
                formatSelector = trimmed,
                qualityLabel = "Custom ($trimmed)",
                container = "mp4",
                requiresMerge = trimmed.contains("+"),
                isAudioOnly = trimmed.contains("audio", ignoreCase = true)
            )
        }
    }

    private fun formatFileSize(bytes: Long?): String? {
        if (bytes == null || bytes <= 0) return null
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024.0) {
            String.format(Locale.US, "%.2f GB", mb / 1024.0)
        } else {
            String.format(Locale.US, "%.1f MB", mb)
        }
    }
}
