package com.example.downloader.util

import com.example.domain.model.DownloadError

/**
 * Intelligent retry policy with exponential backoff and error classification.
 * Prevents useless retries on permanent client errors while automatically recovering from transient network glitches.
 */
object RetryPolicy {

    const val MAX_RETRIES = 3

    /**
     * Determines whether an error is transient and safe to retry automatically.
     */
    fun isRetryable(error: Throwable?): Boolean {
        if (error == null) return false

        // Cancelled or explicitly paused tasks should never auto-retry
        if (error is DownloadError.Cancelled) return false
        val msg = error.message?.lowercase() ?: ""
        if (msg.contains("cancel") || msg.contains("pause") || msg.contains("destroy")) return false

        // Non-retryable permanent errors
        if (error is DownloadError.InvalidUrl ||
            error is DownloadError.PrivateVideo ||
            error is DownloadError.SigninRequired ||
            error is DownloadError.GeoRestricted ||
            error is DownloadError.NoFormats ||
            error is DownloadError.StorageError
        ) {
            return false
        }

        // Retryable network & connection drops
        if (error is DownloadError.NetworkError ||
            error is java.net.SocketException ||
            error is java.net.SocketTimeoutException ||
            error is java.net.UnknownHostException ||
            error is java.net.ConnectException ||
            error is java.io.IOException
        ) {
            return true
        }

        if (msg.contains("timeout") ||
            msg.contains("connection refused") ||
            msg.contains("broken pipe") ||
            msg.contains("connection reset") ||
            msg.contains("socket") ||
            msg.contains("network") ||
            msg.contains("unable to resolve") ||
            msg.contains("host") ||
            msg.contains("502") ||
            msg.contains("503") ||
            msg.contains("504") ||
            msg.contains("429") ||
            msg.contains("temporary failure")
        ) {
            return true
        }

        return false
    }

    /**
     * Calculates exponential backoff delay in milliseconds for the given attempt.
     * Attempt 1 -> 2,000ms
     * Attempt 2 -> 5,000ms
     * Attempt 3 -> 10,000ms
     */
    fun getBackoffDelayMs(retryCount: Int): Long {
        return when (retryCount) {
            0 -> 1_000L
            1 -> 2_000L
            2 -> 5_000L
            else -> 10_000L
        }
    }
}
