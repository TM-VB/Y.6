package com.example.ytdlp

import com.example.domain.model.DownloadError
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

object YtDlpErrorMapper {

    fun map(throwable: Throwable): DownloadError {
        if (throwable is DownloadError) return throwable

        val message = throwable.message.orEmpty().lowercase()

        return when {
            message.contains("null") && message.contains("url") || message.contains("empty url") || message.contains("unsupported url") || message.contains("not a valid url") || message.contains("invalid url") -> {
                DownloadError.InvalidUrl(
                    msg = "Invalid or unsupported video URL.",
                    detail = throwable.message
                )
            }
            throwable is UnknownHostException || throwable is ConnectException || throwable is javax.net.ssl.SSLException ||
                message.contains("ssl") || message.contains("certificate") || message.contains("network") ||
                message.contains("connection") || message.contains("failed to connect") || message.contains("http error 5") -> {
                DownloadError.NetworkError(
                    msg = "Network connection failed. Please check your internet connection.",
                    detail = throwable.message
                )
            }
            throwable is SocketTimeoutException || message.contains("timed out") || message.contains("timeout") -> {
                DownloadError.NetworkError(
                    msg = "Request timed out. The server took too long to respond.",
                    detail = throwable.message
                )
            }
            message.contains("private video") || message.contains("this video is private") -> {
                DownloadError.PrivateVideo(
                    msg = "This video is private and cannot be downloaded.",
                    detail = throwable.message
                )
            }
            message.contains("sign in") || message.contains("login") || message.contains("confirm your age") ||
                message.contains("bot") || message.contains("http error 429") || message.contains("captcha") -> {
                DownloadError.SigninRequired(
                    msg = "This video requires login authentication or age verification.",
                    detail = throwable.message
                )
            }
            message.contains("not available in your country") || message.contains("geo") || message.contains("region") -> {
                DownloadError.GeoRestricted(
                    msg = "This video is not available in your region.",
                    detail = throwable.message
                )
            }
            message.contains("unavailable") || message.contains("video is unavailable") || message.contains("not found") ||
                message.contains("deleted") || message.contains("http error 404") || message.contains("this video has been removed") -> {
                DownloadError.VideoUnavailable(
                    msg = "This video is unavailable or has been removed.",
                    detail = throwable.message
                )
            }
            message.contains("no format") || message.contains("requested format not available") || message.contains("no video formats found") -> {
                DownloadError.NoFormats(
                    msg = "No compatible media format found for this video.",
                    detail = throwable.message
                )
            }
            message.contains("storage") || message.contains("enospc") || message.contains("permission denied") ||
                message.contains("disk full") || message.contains("no space left") -> {
                DownloadError.StorageError(
                    msg = "Storage error or insufficient device space.",
                    detail = throwable.message
                )
            }
            message.contains("ffmpeg") || message.contains("muxing") || message.contains("merger") || message.contains("conversion") -> {
                DownloadError.FfmpegError(
                    msg = "Media processing failed during conversion.",
                    detail = throwable.message
                )
            }
            message.contains("cancel") || message.contains("interrupted") -> {
                DownloadError.Cancelled(
                    msg = "The operation was cancelled.",
                    detail = throwable.message
                )
            }
            else -> {
                DownloadError.YtDlpError(
                    msg = throwable.message ?: "An unexpected error occurred during processing.",
                    detail = throwable.localizedMessage
                )
            }
        }
    }
}
