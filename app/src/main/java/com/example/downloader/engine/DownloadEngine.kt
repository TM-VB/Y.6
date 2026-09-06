package com.example.downloader.engine

import com.example.domain.model.DownloadProgress
import com.example.domain.model.DownloadRequest
import com.example.domain.model.DownloadTask
import java.io.File

/**
 * Interface for executing and managing video downloads.
 */
interface DownloadEngine {
    suspend fun download(
        request: DownloadRequest,
        onProgress: (DownloadProgress) -> Unit
    ): Result<File>

    suspend fun download(
        task: DownloadTask,
        onProgress: (DownloadProgress) -> Unit
    ): Result<File>

    suspend fun cancel(taskId: String)
}
