package com.example.downloader.ffmpeg

import com.example.domain.model.CutMode
import java.io.File

/**
 * Builds safe, structured, injection-free argument arrays for FFmpeg process execution.
 * Enforces codec preservation, fast-start streaming headers, and container compatibility.
 */
object FFmpegCommandBuilder {

    private val TIME_REGEX = Regex("""^(\d{1,2}:)?\d{1,2}:\d{2}(\.\d+)?$|^\d+(\.\d+)?$""")
    private val CODEC_REGEX = Regex("""^[a-zA-Z0-9_-]+$""")

    private fun sanitizeTime(timeStr: String, fallback: String = "00:00:00"): String {
        val trimmed = timeStr.trim()
        return if (trimmed.matches(TIME_REGEX) && !trimmed.startsWith("-")) {
            trimmed
        } else {
            fallback
        }
    }

    private fun sanitizeCodec(codec: String, fallback: String = "aac"): String {
        val trimmed = codec.trim()
        return if (trimmed.matches(CODEC_REGEX) && !trimmed.startsWith("-")) {
            trimmed
        } else {
            fallback
        }
    }

    /**
     * Builds command arguments to merge separate video and audio streams.
     * Uses stream copying (-c:v copy) to avoid unnecessary re-encoding whenever possible.
     */
    fun buildMergeArgs(
        binaryPath: String,
        videoFile: File,
        audioFile: File,
        outputFile: File,
        targetContainer: String = outputFile.extension.ifBlank { "mp4" }
    ): List<String> {
        val args = mutableListOf(
            binaryPath,
            "-y",
            "-fflags", "+genpts",
            "-i", videoFile.absolutePath,
            "-i", audioFile.absolutePath,
            "-map", "0:v:0?",
            "-map", "1:a:0?",
            "-c:v", "copy",
            "-avoid_negative_ts", "make_zero"
        )

        // Audio codec handling for container
        if (targetContainer.equals("mp4", ignoreCase = true) || targetContainer.equals("m4v", ignoreCase = true)) {
            val audioExt = audioFile.extension.lowercase()
            if (audioExt == "m4a" || audioExt == "aac") {
                args.addAll(listOf("-c:a", "copy"))
            } else {
                // Remux/transcode audio stream to AAC for standard MP4 container compatibility
                args.addAll(listOf("-c:a", "aac", "-b:a", "192k"))
            }
            args.addAll(listOf("-movflags", "+faststart"))
        } else if (targetContainer.equals("webm", ignoreCase = true)) {
            val audioExt = audioFile.extension.lowercase()
            if (audioExt == "opus" || audioExt == "vorbis") {
                args.addAll(listOf("-c:a", "copy"))
            } else {
                args.addAll(listOf("-c:a", "libopus", "-b:a", "128k"))
            }
        } else {
            // General container (e.g. MKV) supports all codecs
            args.addAll(listOf("-c:a", "copy"))
        }

        // Output progress in standard parseable format
        args.addAll(listOf("-progress", "pipe:1"))
        args.add(outputFile.absolutePath)
        return args
    }

    /**
     * Builds command arguments for Fast Cut (Keyframe-based stream copy).
     * Fastest speed, 0% quality loss, zero re-encoding.
     */
    fun buildFastCutArgs(
        binaryPath: String,
        inputFile: File,
        outputFile: File,
        startTime: String,
        endTime: String
    ): List<String> {
        val isMp4 = outputFile.extension.equals("mp4", ignoreCase = true) ||
                outputFile.extension.equals("m4a", ignoreCase = true)

        val args = mutableListOf(
            binaryPath,
            "-y",
            "-ss", startTime.trim(),
            "-to", endTime.trim(),
            "-i", inputFile.absolutePath,
            "-c", "copy",
            "-avoid_negative_ts", "make_zero"
        )

        if (isMp4) {
            args.addAll(listOf("-movflags", "+faststart"))
        }

        args.addAll(listOf("-progress", "pipe:1"))
        args.add(outputFile.absolutePath)
        return args
    }

    /**
     * Builds command arguments for Precise Cut (Accurate frame-level re-encoding).
     * Ensures exact start/end time adherence even between keyframes.
     */
    fun buildPreciseCutArgs(
        binaryPath: String,
        inputFile: File,
        outputFile: File,
        startTime: String,
        endTime: String
    ): List<String> {
        val isMp4 = outputFile.extension.equals("mp4", ignoreCase = true) ||
                outputFile.extension.equals("m4a", ignoreCase = true)

        val args = mutableListOf(
            binaryPath,
            "-y",
            "-ss", startTime.trim(),
            "-to", endTime.trim(),
            "-i", inputFile.absolutePath,
            "-c:v", "libx264",
            "-preset", "veryfast",
            "-crf", "22",
            "-c:a", "aac",
            "-b:a", "192k",
            "-avoid_negative_ts", "make_zero"
        )

        if (isMp4) {
            args.addAll(listOf("-movflags", "+faststart"))
        }

        args.addAll(listOf("-progress", "pipe:1"))
        args.add(outputFile.absolutePath)
        return args
    }

    /**
     * Builds command arguments for cutting media based on CutMode.
     */
    fun buildCutArgs(
        binaryPath: String,
        inputFile: File,
        outputFile: File,
        startTime: String,
        endTime: String,
        mode: CutMode
    ): List<String> {
        return when (mode) {
            CutMode.FAST_CUT -> buildFastCutArgs(binaryPath, inputFile, outputFile, startTime, endTime)
            CutMode.PRECISE_CUT -> buildPreciseCutArgs(binaryPath, inputFile, outputFile, startTime, endTime)
        }
    }

    /**
     * Builds command arguments to remux a media file into a different container format without re-encoding.
     */
    fun buildRemuxArgs(
        binaryPath: String,
        inputFile: File,
        outputFile: File
    ): List<String> {
        val isMp4 = outputFile.extension.equals("mp4", ignoreCase = true) ||
                outputFile.extension.equals("m4a", ignoreCase = true)

        val args = mutableListOf(
            binaryPath,
            "-y",
            "-i", inputFile.absolutePath,
            "-c", "copy"
        )

        if (isMp4) {
            args.addAll(listOf("-movflags", "+faststart"))
        }

        args.addAll(listOf("-progress", "pipe:1"))
        args.add(outputFile.absolutePath)
        return args
    }

    /**
     * Builds command arguments to extract audio from a video file.
     */
    fun buildExtractAudioArgs(
        binaryPath: String,
        inputFile: File,
        outputFile: File,
        audioCodec: String = "aac"
    ): List<String> {
        val args = mutableListOf(
            binaryPath,
            "-y",
            "-i", inputFile.absolutePath,
            "-vn"
        )

        if (audioCodec.equals("copy", ignoreCase = true)) {
            args.addAll(listOf("-c:a", "copy"))
        } else {
            args.addAll(listOf("-c:a", audioCodec, "-b:a", "192k"))
        }

        args.addAll(listOf("-progress", "pipe:1"))
        args.add(outputFile.absolutePath)
        return args
    }
}
