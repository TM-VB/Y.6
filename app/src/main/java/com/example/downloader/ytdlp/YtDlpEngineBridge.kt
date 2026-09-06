package com.example.downloader.ytdlp

import android.content.Context
import com.example.domain.model.DownloadProgress
import com.example.domain.model.DownloadRequest
import com.example.domain.model.DownloadTask
import com.example.domain.model.FormatInfo
import com.example.domain.model.PlaylistInfo
import com.example.domain.model.VideoInfo
import com.example.domain.model.VideoMetadata
import com.example.downloader.engine.YtDlpDownloadEngine
import com.example.downloader.engine.YtDlpMediaEngine
import java.io.File

/**
 * Bridge implementation of [YtDlpMediaEngine].
 *
 * Fully delegates to [YtDlpDownloadEngine], which serves as the single source of truth
 * for extraction, download, process lifecycle, cookies, and cancellation.
 */
class YtDlpEngineBridge(
    context: Context,
    private val delegate: YtDlpMediaEngine = YtDlpDownloadEngine.getInstance(context)
) : YtDlpMediaEngine by delegate {

    override suspend fun validateUrl(url: String): Boolean = delegate.validateUrl(url)

    override suspend fun extractInfo(url: String, processId: String?): Result<VideoInfo> =
        delegate.extractInfo(url, processId)

    override suspend fun getFormats(url: String, processId: String?): Result<List<FormatInfo>> =
        delegate.getFormats(url, processId)

    override suspend fun cancel(taskId: String) = delegate.cancel(taskId)

    override suspend fun download(
        request: DownloadRequest,
        onProgress: (DownloadProgress) -> Unit
    ): Result<File> = delegate.download(request, onProgress)

    override suspend fun download(
        task: DownloadTask,
        onProgress: (DownloadProgress) -> Unit
    ): Result<File> = delegate.download(task, onProgress)

    override fun init(context: Context): Result<Unit> = delegate.init(context)

    override fun isReady(): Boolean = delegate.isReady()

    override fun getVersion(context: Context): String = delegate.getVersion(context)

    override suspend fun updateEngine(context: Context): Result<String> = delegate.updateEngine(context)

    override fun isPlaylistUrl(url: String): Boolean = delegate.isPlaylistUrl(url)

    override suspend fun extractPlaylist(url: String, processId: String?): Result<PlaylistInfo> =
        delegate.extractPlaylist(url, processId)

    override suspend fun fetchVideoInfo(url: String, processId: String?): Result<VideoMetadata> =
        delegate.fetchVideoInfo(url, processId)
}
