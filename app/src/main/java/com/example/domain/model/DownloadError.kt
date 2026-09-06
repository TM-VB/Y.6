package com.example.domain.model

/**
 * Domain error hierarchy for video analysis, downloading, and media processing.
 * Maps raw engine/network exceptions to clear user-friendly messages while retaining technical details.
 */
sealed class DownloadError(
    val userFriendlyMessage: String,
    val technicalDetail: String? = null
) : Exception(userFriendlyMessage) {

    class InvalidUrl(
        msg: String = "Please enter a valid video URL",
        detail: String? = null
    ) : DownloadError(msg, detail)

    class VideoUnavailable(
        msg: String = "Video is unavailable",
        detail: String? = null
    ) : DownloadError(msg, detail)

    class PrivateVideo(
        msg: String = "Private videos are not supported",
        detail: String? = null
    ) : DownloadError(msg, detail)

    class SigninRequired(
        msg: String = "This video requires login or authentication.",
        detail: String? = null
    ) : DownloadError(msg, detail)

    class GeoRestricted(
        msg: String = "This video is not available in your region.",
        detail: String? = null
    ) : DownloadError(msg, detail)

    class NetworkError(
        msg: String = "Network error. Check your connection.",
        detail: String? = null
    ) : DownloadError(msg, detail)

    class NoFormats(
        msg: String = "No downloadable formats were found.",
        detail: String? = null
    ) : DownloadError(msg, detail)

    class Cancelled(
        msg: String = "Video analysis was cancelled.",
        detail: String? = null
    ) : DownloadError(msg, detail)

    class YtDlpError(
        msg: String = "yt-dlp engine encountered an error",
        detail: String? = null
    ) : DownloadError(msg, detail)

    class FfmpegError(
        msg: String = "FFmpeg media processing error",
        detail: String? = null
    ) : DownloadError(msg, detail)

    class StorageError(
        msg: String = "Storage permission or file creation error",
        detail: String? = null
    ) : DownloadError(msg, detail)

    class UnknownError(
        msg: String = "An unexpected error occurred",
        detail: String? = null
    ) : DownloadError(msg, detail)

    class Generic(
        msg: String = "An error occurred",
        detail: String? = null
    ) : DownloadError(msg, detail)
}
