package com.example.downloader.engine

import com.example.domain.model.FormatInfo

/**
 * Format classification structure according to yt-dlp stream capabilities.
 */
data class CategorizedFormats(
    val videoAndAudioFormats: List<FormatInfo>,
    val videoOnlyFormats: List<FormatInfo>,
    val audioOnlyFormats: List<FormatInfo>,
    val allFormats: List<FormatInfo>
) {
    val totalCount: Int
        get() = allFormats.size
}

/**
 * Interface responsible for categorizing, sorting, and selecting optimal formats.
 */
interface FormatProvider {
    fun categorize(formats: List<FormatInfo>): CategorizedFormats
    fun getBestQuality(formats: List<FormatInfo>): FormatInfo?
    fun getBestVideo(formats: List<FormatInfo>): FormatInfo?
    fun getBestAudio(formats: List<FormatInfo>): FormatInfo?
    fun findByHeight(formats: List<FormatInfo>, targetHeight: Int): FormatInfo?
    fun findByFormatId(formats: List<FormatInfo>, formatId: String): FormatInfo?
    fun getSmartSelection(formats: List<FormatInfo>, format: FormatInfo): com.example.ytdlp.FormatSelection
    fun getSmartBestSelection(formats: List<FormatInfo>): com.example.ytdlp.FormatSelection
}

/**
 * Production implementation of FormatProvider using yt-dlp format attributes.
 */
class DefaultFormatProvider : FormatProvider {

    override fun categorize(formats: List<FormatInfo>): CategorizedFormats {
        return com.example.ytdlp.SmartFormatEngine.categorize(formats)
    }

    override fun getBestQuality(formats: List<FormatInfo>): FormatInfo? {
        val categorized = categorize(formats)
        return categorized.videoAndAudioFormats.firstOrNull()
            ?: categorized.videoOnlyFormats.firstOrNull()
            ?: categorized.allFormats.firstOrNull()
    }

    override fun getBestVideo(formats: List<FormatInfo>): FormatInfo? {
        val categorized = categorize(formats)
        val allVideoStreams = categorized.allFormats.filter { it.hasVideo }
        return allVideoStreams.maxByOrNull { (it.height ?: 0) * 1000000 + (it.bitrate ?: 0.0).toInt() }
    }

    override fun getBestAudio(formats: List<FormatInfo>): FormatInfo? {
        return com.example.ytdlp.SmartFormatEngine.getBestAudioFormat(formats)
    }

    override fun findByHeight(formats: List<FormatInfo>, targetHeight: Int): FormatInfo? {
        val categorized = categorize(formats)
        return categorized.videoOnlyFormats.firstOrNull { it.height == targetHeight }
            ?: categorized.videoAndAudioFormats.firstOrNull { it.height == targetHeight }
            ?: categorized.allFormats.firstOrNull { (it.height ?: 0) in (targetHeight - 50)..(targetHeight + 50) }
    }

    override fun findByFormatId(formats: List<FormatInfo>, formatId: String): FormatInfo? {
        val trimmed = formatId.trim()
        return formats.firstOrNull { it.formatId.equals(trimmed, ignoreCase = true) }
    }

    override fun getSmartSelection(formats: List<FormatInfo>, format: FormatInfo): com.example.ytdlp.FormatSelection {
        return com.example.ytdlp.SmartFormatEngine.selectFromFormatInfo(formats, format)
    }

    override fun getSmartBestSelection(formats: List<FormatInfo>): com.example.ytdlp.FormatSelection {
        return com.example.ytdlp.SmartFormatEngine.selectBestQuality(formats)
    }
}
