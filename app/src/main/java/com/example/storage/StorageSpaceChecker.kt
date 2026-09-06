package com.example.storage

import android.content.Context
import android.os.Environment
import android.os.StatFs
import com.example.data.local.DownloadTaskEntity
import com.example.domain.model.DownloadRequest
import com.example.domain.model.DownloadTask
import java.io.File

/**
 * Unified single source of truth for storage space calculation and validation.
 * Used across YtDlpDownloadEngine, DownloadQueueManager, and FFmpegManager.
 *
 * Accurately calculates required storage based on:
 * - Expected media size (if available)
 * - Video stream size
 * - Audio stream size
 * - Temporary yt-dlp download files (.part / raw fragments)
 * - FFmpeg intermediate output (for merge and cut operations)
 * - Final output file before cleanup
 * - Dynamic safety margin (minimum 50MB or 10% of payload)
 *
 * Strictly avoids using a fixed 50MB check as the sole condition.
 */
object StorageSpaceChecker {

    const val MIN_SAFETY_MARGIN_BYTES: Long = 50 * 1024 * 1024L // 50 MB minimum safety buffer
    const val DEFAULT_FALLBACK_MEDIA_BYTES: Long = 50 * 1024 * 1024L // 50 MB fallback when unknown
    const val SAFETY_MARGIN_RATIO: Double = 0.10 // 10% headroom

    /**
     * Optional provider for testing and custom storage calculation.
     */
    fun interface StorageSpaceProvider {
        fun getAvailableBytes(path: String): Long
    }

    @Volatile
    var customStorageProvider: StorageSpaceProvider? = null

    data class StorageRequirement(
        val baseMediaSizeBytes: Long,
        val expectedMediaSizeBytes: Long? = null,
        val videoStreamBytes: Long = 0L,
        val audioStreamBytes: Long = 0L,
        val tempYtDlpBytes: Long,
        val ffmpegIntermediateBytes: Long,
        val mergeIntermediateBytes: Long = 0L,
        val cutIntermediateBytes: Long = 0L,
        val finalOutputBytes: Long,
        val safetyMarginBytes: Long,
        val totalRequiredBytes: Long,
        val requiresMerge: Boolean,
        val requiresCut: Boolean
    )

    data class StorageValidationResult(
        val hasEnoughSpace: Boolean,
        val requiredBytes: Long,
        val availableBytes: Long,
        val missingBytes: Long = (requiredBytes - availableBytes).coerceAtLeast(0L),
        val requirement: StorageRequirement? = null,
        val errorMessage: String? = null
    )

    /**
     * Parses human-readable file size strings (e.g., "120 MB", "1.5 GB", "500 KB", "1048576") to bytes.
     */
    fun parseSizeToBytes(sizeStr: String?): Long? {
        if (sizeStr.isNullOrBlank()) return null
        val clean = sizeStr.trim().uppercase()
        val regex = Regex("""^([\d.]+)\s*([KMGTP]?I?B?)$""")
        val match = regex.find(clean) ?: return clean.toLongOrNull()
        val value = match.groupValues[1].toDoubleOrNull() ?: return null
        val unit = match.groupValues[2]
        return when {
            unit.startsWith("T") -> (value * 1024L * 1024L * 1024L * 1024L).toLong()
            unit.startsWith("G") -> (value * 1024L * 1024L * 1024L).toLong()
            unit.startsWith("M") -> (value * 1024L * 1024L).toLong()
            unit.startsWith("K") -> (value * 1024L).toLong()
            unit.startsWith("B") -> value.toLong()
            else -> value.toLong()
        }
    }

    /**
     * Calculates the full storage requirements for a media download and processing workflow.
     * Accurately takes into account:
     * - Expected media size (if available)
     * - Video stream
     * - Audio stream
     * - Temporary yt-dlp files (downloaded parts/streams)
     * - FFmpeg intermediate output (for merge and cut operations)
     * - Final output
     * - Dynamic safety margin (minimum 50MB or 10% of payload)
     *
     * Never uses a fixed 50MB check as the sole condition.
     */
    fun calculateRequiredSpace(
        expectedMediaSizeBytes: Long? = null,
        videoStreamBytes: Long? = null,
        audioStreamBytes: Long? = null,
        requiresMerge: Boolean = false,
        requiresCut: Boolean = false,
        customSafetyMarginBytes: Long? = null
    ): StorageRequirement {
        val vidBytes = (videoStreamBytes ?: 0L).coerceAtLeast(0L)
        val audBytes = (audioStreamBytes ?: 0L).coerceAtLeast(0L)
        val streamSum = vidBytes + audBytes

        val baseMediaSize = when {
            streamSum > 0L && expectedMediaSizeBytes != null && expectedMediaSizeBytes > 0L -> {
                maxOf(streamSum, expectedMediaSizeBytes)
            }
            streamSum > 0L -> streamSum
            expectedMediaSizeBytes != null && expectedMediaSizeBytes > 0L -> expectedMediaSizeBytes
            else -> DEFAULT_FALLBACK_MEDIA_BYTES
        }

        // 1. Temporary yt-dlp files (downloaded parts/raw streams)
        val tempYtDlp = if (streamSum > 0L) streamSum else baseMediaSize

        // 2. FFmpeg intermediate output files:
        //    - Merge intermediate file if merging video + audio
        //    - Cut intermediate file if trimming media
        val mergeIntermediate = if (requiresMerge) baseMediaSize else 0L
        val cutIntermediate = if (requiresCut) baseMediaSize else 0L
        val ffmpegIntermediate = mergeIntermediate + cutIntermediate

        // 3. Final output file before isolated work dir cleanup
        val finalOutput = baseMediaSize

        // 4. Dynamic safety margin: max of minimum buffer (50MB) and 10% of payload
        val margin = customSafetyMarginBytes ?: maxOf(
            MIN_SAFETY_MARGIN_BYTES,
            (baseMediaSize * SAFETY_MARGIN_RATIO).toLong()
        )

        // Total calculated storage requirement
        val total = tempYtDlp + ffmpegIntermediate + finalOutput + margin

        return StorageRequirement(
            baseMediaSizeBytes = baseMediaSize,
            expectedMediaSizeBytes = expectedMediaSizeBytes,
            videoStreamBytes = vidBytes,
            audioStreamBytes = audBytes,
            tempYtDlpBytes = tempYtDlp,
            ffmpegIntermediateBytes = ffmpegIntermediate,
            mergeIntermediateBytes = mergeIntermediate,
            cutIntermediateBytes = cutIntermediate,
            finalOutputBytes = finalOutput,
            safetyMarginBytes = margin,
            totalRequiredBytes = total,
            requiresMerge = requiresMerge,
            requiresCut = requiresCut
        )
    }

    /**
     * Calculates storage required specifically for merging video and audio files in FFmpegManager.
     */
    fun calculateMergeRequiredSpace(
        videoBytes: Long,
        audioBytes: Long,
        safetyMargin: Long? = null
    ): Long {
        val baseSize = (videoBytes + audioBytes).coerceAtLeast(1L)
        val intermediateOutput = baseSize
        val margin = safetyMargin ?: maxOf(MIN_SAFETY_MARGIN_BYTES, (baseSize * SAFETY_MARGIN_RATIO).toLong())
        return intermediateOutput + margin
    }

    /**
     * Calculates storage required specifically for cutting an input media file in FFmpegManager.
     */
    fun calculateCutRequiredSpace(
        inputFileBytes: Long,
        safetyMargin: Long? = null
    ): Long {
        val baseSize = inputFileBytes.coerceAtLeast(1L)
        val intermediateOutput = baseSize
        val margin = safetyMargin ?: maxOf(MIN_SAFETY_MARGIN_BYTES, (baseSize * SAFETY_MARGIN_RATIO).toLong())
        return intermediateOutput + margin
    }

    /**
     * Returns currently available storage bytes at the given path or context cache dir.
     */
    fun getAvailableStorage(context: Context, targetDir: File? = null): Long {
        val provider = customStorageProvider
        if (provider != null) {
            val path = targetDir?.path ?: context.cacheDir.path
            return provider.getAvailableBytes(path)
        }

        return try {
            val dir = targetDir ?: context.cacheDir
            if (!dir.exists()) {
                dir.mkdirs()
            }
            val stat = StatFs(dir.path)
            stat.availableBlocksLong * stat.blockSizeLong
        } catch (_: Throwable) {
            Long.MAX_VALUE
        }
    }

    /**
     * Validates if device has sufficient storage space for the calculated requirement.
     */
    fun validateStorage(
        context: Context,
        requirement: StorageRequirement,
        targetDir: File? = null
    ): StorageValidationResult {
        val availableBytes = getAvailableStorage(context, targetDir)
        val hasEnough = availableBytes >= requirement.totalRequiredBytes

        val errorMessage = if (!hasEnough) {
            val reqMb = requirement.totalRequiredBytes / (1024 * 1024)
            val availMb = availableBytes / (1024 * 1024)
            val missingMb = (requirement.totalRequiredBytes - availableBytes) / (1024 * 1024)
            "Insufficient storage space: $reqMb MB required, but only $availMb MB available ($missingMb MB missing)."
        } else null

        return StorageValidationResult(
            hasEnoughSpace = hasEnough,
            requiredBytes = requirement.totalRequiredBytes,
            availableBytes = availableBytes,
            requirement = requirement,
            errorMessage = errorMessage
        )
    }

    /**
     * Convenience check for a given DownloadRequest before starting download.
     */
    fun validateDownloadSpace(
        context: Context,
        request: DownloadRequest,
        targetDir: File? = null
    ): StorageValidationResult {
        val requiresMerge = !request.isAudioOnly && !request.isVideoOnly &&
            (request.formatSelector.contains("+") || request.formatSelector.contains("best") || request.formatSelector.isBlank())
        val requiresCut = request.hasTimeTrim

        val req = calculateRequiredSpace(
            expectedMediaSizeBytes = request.expectedMediaSizeBytes,
            videoStreamBytes = request.videoStreamBytes,
            audioStreamBytes = request.audioStreamBytes,
            requiresMerge = requiresMerge,
            requiresCut = requiresCut
        )
        return validateStorage(context, req, targetDir)
    }

    /**
     * Convenience check for a DownloadTask before enqueueing or starting.
     */
    fun validateDownloadSpace(
        context: Context,
        task: DownloadTask,
        targetDir: File? = null
    ): StorageValidationResult {
        val hasCut = task.cutSettings.enabled && !task.cutSettings.startTime.isNullOrBlank() && !task.cutSettings.endTime.isNullOrBlank()
        val isAudio = task.formatDescription.contains("Audio", ignoreCase = true) || task.formatId.contains("audio", ignoreCase = true)
        val requiresMerge = !isAudio &&
            (task.formatId.contains("+") || task.formatId.equals("best", ignoreCase = true) || task.formatId.isBlank())

        val req = calculateRequiredSpace(
            expectedMediaSizeBytes = task.expectedMediaSizeBytes,
            videoStreamBytes = task.videoStreamBytes,
            audioStreamBytes = task.audioStreamBytes,
            requiresMerge = requiresMerge,
            requiresCut = hasCut
        )
        return validateStorage(context, req, targetDir)
    }

    /**
     * Convenience check for a DownloadTaskEntity before enqueueing or starting.
     */
    fun validateDownloadSpace(
        context: Context,
        task: DownloadTaskEntity,
        targetDir: File? = null
    ): StorageValidationResult {
        val hasCut = !task.startTime.isNullOrBlank() && !task.endTime.isNullOrBlank()
        val isAudio = task.isAudioOnly || task.formatDescription.contains("Audio", ignoreCase = true) || task.formatId.contains("audio", ignoreCase = true)
        val requiresMerge = !isAudio && !task.isVideoOnly &&
            (task.formatId.contains("+") || task.formatId.equals("best", ignoreCase = true) || task.formatId.isBlank())

        val parsedExpectedSize = parseSizeToBytes(task.totalSize)

        val req = calculateRequiredSpace(
            expectedMediaSizeBytes = parsedExpectedSize,
            requiresMerge = requiresMerge,
            requiresCut = hasCut
        )
        return validateStorage(context, req, targetDir)
    }

    /**
     * Validates storage before FFmpeg merge.
     */
    fun validateMergeSpace(
        context: Context,
        videoFile: File,
        audioFile: File,
        targetDir: File? = null
    ): StorageValidationResult {
        val requiredBytes = calculateMergeRequiredSpace(videoFile.length(), audioFile.length())
        val availableBytes = getAvailableStorage(context, targetDir)
        val hasEnough = availableBytes >= requiredBytes

        val errorMessage = if (!hasEnough) {
            val reqMb = requiredBytes / (1024 * 1024)
            val availMb = availableBytes / (1024 * 1024)
            val missingMb = (requiredBytes - availableBytes) / (1024 * 1024)
            "Insufficient storage for merge: $reqMb MB required, but only $availMb MB available ($missingMb MB missing)."
        } else null

        return StorageValidationResult(
            hasEnoughSpace = hasEnough,
            requiredBytes = requiredBytes,
            availableBytes = availableBytes,
            missingBytes = (requiredBytes - availableBytes).coerceAtLeast(0L),
            errorMessage = errorMessage
        )
    }

    /**
     * Validates storage before FFmpeg cut.
     */
    fun validateCutSpace(
        context: Context,
        inputFile: File,
        targetDir: File? = null
    ): StorageValidationResult {
        val requiredBytes = calculateCutRequiredSpace(inputFile.length())
        val availableBytes = getAvailableStorage(context, targetDir)
        val hasEnough = availableBytes >= requiredBytes

        val errorMessage = if (!hasEnough) {
            val reqMb = requiredBytes / (1024 * 1024)
            val availMb = availableBytes / (1024 * 1024)
            val missingMb = (requiredBytes - availableBytes) / (1024 * 1024)
            "Insufficient storage for cut: $reqMb MB required, but only $availMb MB available ($missingMb MB missing)."
        } else null

        return StorageValidationResult(
            hasEnoughSpace = hasEnough,
            requiredBytes = requiredBytes,
            availableBytes = availableBytes,
            missingBytes = (requiredBytes - availableBytes).coerceAtLeast(0L),
            errorMessage = errorMessage
        )
    }

    /**
     * Simple boolean check for backward compatibility.
     */
    fun hasEnoughSpace(context: Context, requiredBytes: Long, targetDir: File? = null): Boolean {
        val available = getAvailableStorage(context, targetDir)
        return available >= requiredBytes
    }
}
