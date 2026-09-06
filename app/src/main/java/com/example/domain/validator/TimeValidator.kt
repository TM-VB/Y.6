package com.example.domain.validator

sealed class TimeValidationResult {
    data class Success(val startSeconds: Int, val endSeconds: Int, val formattedStart: String, val formattedEnd: String) : TimeValidationResult()
    data class Error(val message: String) : TimeValidationResult()
}

/**
 * Validates and formats time ranges for video trimming.
 */
object TimeValidator {

    sealed class ValidationResult {
        object Valid : ValidationResult()
        data class Invalid(val message: String) : ValidationResult()
    }

    /**
     * Parses time string formatted as HH:MM:SS, MM:SS, or SS into total seconds.
     * Returns null if string format or range is invalid.
     */
    fun parseToSeconds(timeStr: String?): Long? {
        if (timeStr.isNullOrBlank()) return null
        val trimmed = timeStr.trim()
        val parts = trimmed.split(":")
        return try {
            when (parts.size) {
                1 -> {
                    val s = parts[0].toLong()
                    if (s >= 0) s else null
                }
                2 -> {
                    val minutes = parts[0].toLong()
                    val seconds = parts[1].toLong()
                    if (minutes in 0..59 && seconds in 0..59) {
                        minutes * 60 + seconds
                    } else null
                }
                3 -> {
                    val hours = parts[0].toLong()
                    val minutes = parts[1].toLong()
                    val seconds = parts[2].toLong()
                    if (hours >= 0 && minutes in 0..59 && seconds in 0..59) {
                        hours * 3600 + minutes * 60 + seconds
                    } else null
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Parses time string into Int seconds (convenience method).
     */
    fun parseTimeToSeconds(timeStr: String?): Int? = parseToSeconds(timeStr)?.toInt()

    /**
     * Formats total seconds into standard HH:MM:SS format.
     */
    fun formatSeconds(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        val hours = s / 3600
        val minutes = (s % 3600) / 60
        val sec = s % 60
        return String.format(java.util.Locale.US, "%02d:%02d:%02d", hours, minutes, sec)
    }

    /**
     * Formats total seconds to HH:MM:SS format (convenience method).
     */
    fun formatSecondsToTimestamp(totalSeconds: Int): String = formatSeconds(totalSeconds.toLong())

    /**
     * Validates that start and end times are valid, start >= 0, and end > start.
     * Optionally checks if end <= durationSeconds.
     */
    fun validateTimeRange(
        startTime: String?,
        endTime: String?,
        durationSeconds: Long? = null
    ): ValidationResult {
        if (startTime.isNullOrBlank()) {
            return ValidationResult.Invalid("Start time cannot be empty")
        }
        if (endTime.isNullOrBlank()) {
            return ValidationResult.Invalid("End time cannot be empty")
        }

        val startSec = parseToSeconds(startTime)
            ?: return ValidationResult.Invalid("Start time must be in HH:MM:SS or MM:SS format")
        val endSec = parseToSeconds(endTime)
            ?: return ValidationResult.Invalid("End time must be in HH:MM:SS or MM:SS format")

        if (startSec < 0) {
            return ValidationResult.Invalid("Start time cannot be negative")
        }

        if (endSec <= startSec) {
            return ValidationResult.Invalid("End time must be greater than start time")
        }

        if (durationSeconds != null && durationSeconds > 0 && endSec > durationSeconds) {
            return ValidationResult.Invalid("End time exceeds video duration (${formatSeconds(durationSeconds)})")
        }

        return ValidationResult.Valid
    }

    /**
     * Validates start time and end time relative to video duration.
     */
    fun validate(startTimeStr: String, endTimeStr: String, videoDurationSeconds: Int? = null): TimeValidationResult {
        val startSec = parseTimeToSeconds(startTimeStr)
            ?: return TimeValidationResult.Error("Invalid start time format. Use HH:MM:SS (e.g. 00:00:00)")

        val endSec = parseTimeToSeconds(endTimeStr)
            ?: return TimeValidationResult.Error("Invalid end time format. Use HH:MM:SS (e.g. 00:01:30)")

        if (startSec < 0) {
            return TimeValidationResult.Error("Start time cannot be negative.")
        }

        if (startSec >= endSec) {
            return TimeValidationResult.Error("Start time must be less than end time ($startSec >= $endSec).")
        }

        if (videoDurationSeconds != null && videoDurationSeconds > 0) {
            if (startSec >= videoDurationSeconds) {
                return TimeValidationResult.Error("Start time exceeds video duration ($videoDurationSeconds s).")
            }
            if (endSec > videoDurationSeconds + 5) {
                return TimeValidationResult.Error("End time exceeds video duration ($videoDurationSeconds s).")
            }
        }

        return TimeValidationResult.Success(
            startSeconds = startSec,
            endSeconds = endSec,
            formattedStart = formatSecondsToTimestamp(startSec),
            formattedEnd = formatSecondsToTimestamp(endSec)
        )
    }
}
