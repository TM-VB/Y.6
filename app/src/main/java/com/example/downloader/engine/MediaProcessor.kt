package com.example.downloader.engine

import com.example.domain.model.CutMode
import com.example.domain.model.MediaResult
import com.example.domain.model.ProcessingProgress
import java.io.File

/**
 * High-level interface for media processing tasks (merging, fast cut, precise cut, remux, extraction).
 */
interface MediaProcessor {

    suspend fun mergeVideoAudio(
        videoFile: File,
        audioFile: File,
        outputFile: File,
        taskId: String? = null,
        runId: Long? = null,
        onProgress: ((ProcessingProgress) -> Unit)? = null
    ): Result<MediaResult>

    suspend fun fastCut(
        inputFile: File,
        startTime: String,
        endTime: String,
        outputFile: File,
        taskId: String? = null,
        runId: Long? = null,
        onProgress: ((ProcessingProgress) -> Unit)? = null
    ): Result<MediaResult>

    suspend fun preciseCut(
        inputFile: File,
        startTime: String,
        endTime: String,
        outputFile: File,
        taskId: String? = null,
        runId: Long? = null,
        onProgress: ((ProcessingProgress) -> Unit)? = null
    ): Result<MediaResult>

    suspend fun cutMedia(
        inputFile: File,
        outputFile: File,
        startTime: String,
        endTime: String,
        mode: CutMode,
        taskId: String? = null,
        runId: Long? = null,
        onProgress: ((ProcessingProgress) -> Unit)? = null
    ): Result<MediaResult>

    suspend fun remux(
        inputFile: File,
        outputFile: File,
        targetContainer: String,
        taskId: String? = null,
        runId: Long? = null,
        onProgress: ((ProcessingProgress) -> Unit)? = null
    ): Result<MediaResult>

    suspend fun extractAudio(
        inputFile: File,
        outputFile: File,
        audioCodec: String = "aac",
        taskId: String? = null,
        runId: Long? = null,
        onProgress: ((ProcessingProgress) -> Unit)? = null
    ): Result<MediaResult>

    fun cancel(processId: String)

    fun cancel(taskId: String, runId: Long?)

    fun isTaskCancelled(taskId: String, runId: Long? = null): Boolean = false

    fun resetCancellation(taskId: String, runId: Long? = null) {}
}

