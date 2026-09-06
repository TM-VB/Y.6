package com.example.downloader.util

/**
 * Applies Exponential Weighted Moving Average (EWMA) to download speed calculations
 * to provide a jitter-free, smooth UI display and accurate remaining time estimation (ETA).
 */
class SpeedSmoother(private val alpha: Float = 0.25f) {

    private var smoothedSpeedBytesPerSec: Double = 0.0
    private var hasInitialReading = false

    /**
     * Updates the smoothed speed with the newly measured bytes-per-second value.
     */
    fun update(rawBytesPerSec: Double): Double {
        if (rawBytesPerSec <= 0) return smoothedSpeedBytesPerSec

        smoothedSpeedBytesPerSec = if (!hasInitialReading) {
            hasInitialReading = true
            rawBytesPerSec
        } else {
            (alpha * rawBytesPerSec) + ((1f - alpha) * smoothedSpeedBytesPerSec)
        }
        return smoothedSpeedBytesPerSec
    }

    /**
     * Calculates the Estimated Time of Arrival (ETA) in seconds.
     * Returns null if total bytes is unknown or speed is 0.
     */
    fun calculateEtaSeconds(downloadedBytes: Long, totalBytes: Long): Long? {
        if (totalBytes <= 0 || downloadedBytes >= totalBytes || smoothedSpeedBytesPerSec <= 0) {
            return null
        }
        val remainingBytes = (totalBytes - downloadedBytes).toDouble()
        val etaSec = remainingBytes / smoothedSpeedBytesPerSec
        return if (etaSec in 0.0..86400.0) etaSec.toLong() else null
    }

    /**
     * Resets the smoother state.
     */
    fun reset() {
        smoothedSpeedBytesPerSec = 0.0
        hasInitialReading = false
    }

    companion object {
        fun formatSpeed(bytesPerSec: Double): String {
            if (bytesPerSec <= 0) return "0 B/s"
            val kb = bytesPerSec / 1024.0
            val mb = kb / 1024.0
            return when {
                mb >= 1.0 -> String.format(java.util.Locale.US, "%.2f MB/s", mb)
                kb >= 1.0 -> String.format(java.util.Locale.US, "%.2f KB/s", kb)
                else -> String.format(java.util.Locale.US, "%.0f B/s", bytesPerSec)
            }
        }

        fun formatEta(etaSeconds: Long?): String {
            if (etaSeconds == null || etaSeconds <= 0) return "--:--"
            val hours = etaSeconds / 3600
            val minutes = (etaSeconds % 3600) / 60
            val seconds = etaSeconds % 60
            return if (hours > 0) {
                String.format(java.util.Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds)
            }
        }

        fun formatBytes(bytes: Long): String {
            if (bytes <= 0) return "0 B"
            val kb = bytes / 1024.0
            val mb = kb / 1024.0
            val gb = mb / 1024.0
            return when {
                gb >= 1.0 -> String.format(java.util.Locale.US, "%.2f GB", gb)
                mb >= 1.0 -> String.format(java.util.Locale.US, "%.1f MB", mb)
                kb >= 1.0 -> String.format(java.util.Locale.US, "%.0f KB", kb)
                else -> "$bytes B"
            }
        }
    }
}
