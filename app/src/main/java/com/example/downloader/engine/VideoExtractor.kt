package com.example.downloader.engine

import com.example.domain.model.FormatInfo
import com.example.domain.model.VideoInfo

/**
 * Interface for extracting video information and formats from URLs.
 * Enables decoupling the UI and ViewModel from the underlying yt-dlp implementation.
 */
interface VideoExtractor {
    suspend fun validateUrl(url: String): Boolean
    suspend fun extractInfo(url: String, processId: String? = null): Result<VideoInfo>
    suspend fun getFormats(url: String, processId: String? = null): Result<List<FormatInfo>>
    suspend fun cancel(taskId: String)
}
