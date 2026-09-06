package com.example.ytdlp

import com.example.domain.model.FormatInfo
import com.example.domain.model.FormatOption
import com.yausername.youtubedl_android.mapper.VideoFormat

object FormatParser {

    fun parseFormats(rawFormats: List<VideoFormat>?): List<FormatInfo> {
        if (rawFormats.isNullOrEmpty()) return emptyList()

        return rawFormats.map { format ->
            val hasV = !format.vcodec.isNullOrBlank() && format.vcodec != "none"
            val hasA = !format.acodec.isNullOrBlank() && format.acodec != "none"
            val ext = format.ext?.lowercase() ?: "mp4"

            FormatInfo(
                formatId = format.formatId ?: "best",
                formatNote = format.formatNote,
                extension = ext,
                resolution = format.resolution ?: if (format.height > 0) "${format.height}p" else null,
                width = if (format.width > 0) format.width else null,
                height = if (format.height > 0) format.height else null,
                fps = if (format.fps > 0) format.fps.toDouble() else null,
                videoCodec = format.vcodec,
                audioCodec = format.acodec,
                audioChannels = null,
                bitrate = if (format.tbr > 0) format.tbr.toDouble() else if (format.abr > 0) format.abr.toDouble() else null,
                filesize = if (format.fileSize > 0) format.fileSize else null,
                filesizeApprox = if (format.fileSizeApprox > 0) format.fileSizeApprox else null,
                vcodec = format.vcodec,
                acodec = format.acodec,
                dynamicRange = null,
                protocol = format.protocol,
                container = ext,
                hasVideo = hasV || (!hasV && !hasA && ext != "m4a" && ext != "mp3"),
                hasAudio = hasA || (!hasV && !hasA && (ext == "m4a" || ext == "mp3"))
            )
        }
    }

    fun parseFormatOptions(rawFormats: List<VideoFormat>?): List<FormatOption> {
        if (rawFormats.isNullOrEmpty()) return emptyList()
        return rawFormats.map { format ->
            val hasV = !format.vcodec.isNullOrBlank() && format.vcodec != "none"
            val hasA = !format.acodec.isNullOrBlank() && format.acodec != "none"
            val isAudio = !hasV && hasA
            val isVideo = hasV && !hasA
            val isCombined = hasV && hasA
            FormatOption(
                formatId = format.formatId ?: "best",
                ext = format.ext?.lowercase() ?: "mp4",
                resolution = format.resolution ?: if (format.height > 0) "${format.height}p" else "unknown",
                width = if (format.width > 0) format.width else null,
                height = if (format.height > 0) format.height else null,
                fps = if (format.fps > 0) format.fps.toDouble() else null,
                vcodec = format.vcodec,
                acodec = format.acodec,
                fileSize = format.fileSize,
                bitrate = if (format.tbr > 0) format.tbr.toDouble() else if (format.abr > 0) format.abr.toDouble() else null,
                isAudioOnly = isAudio,
                isVideoOnly = isVideo,
                isCombined = isCombined,
                note = format.formatNote ?: ""
            )
        }
    }

    fun getCategorizedFormats(formats: List<FormatInfo>): Triple<List<FormatInfo>, List<FormatInfo>, List<FormatInfo>> {
        val videoFormats = formats.filter { it.hasVideo }
        val audioFormats = formats.filter { it.isAudioOnly }
        return Triple(videoFormats, audioFormats, formats)
    }

    fun getCategorizedOptions(options: List<FormatOption>): Triple<List<FormatOption>, List<FormatOption>, List<FormatOption>> {
        val videoFormats = options.filter { !it.isAudioOnly }
        val audioFormats = options.filter { it.isAudioOnly }
        return Triple(videoFormats, audioFormats, options)
    }
}

