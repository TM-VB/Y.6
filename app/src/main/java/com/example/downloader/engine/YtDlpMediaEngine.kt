package com.example.downloader.engine

import android.content.Context
import com.example.domain.model.PlaylistInfo
import com.example.domain.model.VideoMetadata

/**
 * Unified interface for all yt-dlp operations across the entire application.
 *
 * Serves as the single source of truth for:
 * - extractInfo & format resolution
 * - playlist extraction
 * - video download & FFmpeg processing
 * - process lifecycle & unified cancellation
 * - cookies management
 * - error mapping
 *
 * Both the UI layer (ViewModels) and Queue layer interact solely with this interface.
 */
interface YtDlpMediaEngine : VideoExtractor, DownloadEngine {

    /**
     * Initializes yt-dlp and its native Python environment.
     */
    fun init(context: Context): Result<Unit>

    /**
     * Checks if the underlying engine is initialized and ready for execution.
     */
    fun isReady(): Boolean

    /**
     * Returns the current version of the embedded yt-dlp binary.
     */
    fun getVersion(context: Context): String

    /**
     * Updates yt-dlp to the latest channel release.
     */
    suspend fun updateEngine(context: Context): Result<String>

    /**
     * Checks whether a URL is a playlist or playlist-like collection.
     */
    fun isPlaylistUrl(url: String): Boolean

    /**
     * Extracts full playlist entries and metadata.
     */
    suspend fun extractPlaylist(url: String, processId: String? = null): Result<PlaylistInfo>

    /**
     * Compatibility extraction returning VideoMetadata.
     */
    suspend fun fetchVideoInfo(url: String, processId: String? = null): Result<VideoMetadata>
}
