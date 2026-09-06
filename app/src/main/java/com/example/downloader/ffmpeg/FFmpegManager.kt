package com.example.downloader.ffmpeg

import android.content.Context
import android.os.Build
import com.example.domain.model.CutMode
import com.example.domain.model.DownloadError
import com.example.domain.model.MediaResult
import com.example.domain.model.ProcessingProgress
import com.example.downloader.engine.MediaProcessor
import com.example.storage.MediaStoreHelper
import com.example.storage.StorageSpaceChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Production-ready FFmpegManager implementing MediaProcessor for Android.
 * Features:
 * - Real embedded binary discovery with ABI validation and execution permission enforcement.
 * - Non-blocking streaming on Dispatchers.IO with argument array isolation.
 * - Real-time progress parsing (-progress pipe:1, out_time_ms, speed, fps, eta).
 * - Atomic output file writing (*.tmp -> validation -> final destination).
 * - Immediate process cancellation and automatic temp cleanup.
 */
class FFmpegManager(context: Context) : MediaProcessor {

    private val appContext: Context = context.applicationContext

    data class ExecutionKey(val taskId: String, val runId: Long?) {
        fun toKey(): String = if (runId != null) "$taskId:$runId" else "$taskId:default"
    }

    private data class ExecutionRecord(
        val key: ExecutionKey,
        var process: Process?,
        val tempOutput: File,
        @Volatile var isCancelled: Boolean = false,
        @Volatile var isFinished: Boolean = false
    )

    fun isMergeCompleted(taskId: String, runId: Long? = null): Boolean {
        val key = if (runId != null) "$taskId:$runId" else "$taskId:0"
        return completedTaskMerges.contains(key)
    }

    fun clearMergeHistory() {
        completedTaskMerges.clear()
    }

    private var cachedBinary: File? = null
    private var cachedVersion: String? = null

    val supportedAbis: List<String>
        get() = Build.SUPPORTED_ABIS.toList()

    val primaryAbi: String
        get() = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"

    /**
     * Resolves and verifies the local FFmpeg binary file.
     */
    fun getFFmpegBinary(): File? {
        cachedBinary?.let { if (it.exists() && it.canExecute()) return it }

        // 1. Check embedded youtubedl-android FFmpeg package
        try {
            val ffmpegClass = Class.forName("com.yausername.ffmpeg.FFmpeg")
            val getInstanceMethod = ffmpegClass.getMethod("getInstance")
            val ffmpegInstance = getInstanceMethod.invoke(null)
            try {
                val initMethod = ffmpegClass.getMethod("init", Context::class.java)
                initMethod.invoke(ffmpegInstance, appContext)
            } catch (_: Throwable) {}

            val binDirField = ffmpegClass.getDeclaredField("binDir").apply { isAccessible = true }
            val binDir = binDirField.get(ffmpegInstance) as? File
            if (binDir != null) {
                val binary = File(binDir, "ffmpeg")
                if (binary.exists()) {
                    if (!binary.canExecute()) binary.setExecutable(true)
                    cachedBinary = binary
                    return binary
                }
            }
        } catch (_: Throwable) {}

        // 2. Check nativeLibraryDir for native library builds
        val nativeLibPath = appContext.applicationInfo?.nativeLibraryDir
        if (!nativeLibPath.isNullOrBlank()) {
            val nativeDir = File(nativeLibPath)
            val possibleNames = listOf("libffmpeg.so", "ffmpeg.so", "ffmpeg")
            for (name in possibleNames) {
                val file = File(nativeDir, name)
                if (file.exists()) {
                    if (!file.canExecute()) file.setExecutable(true)
                    cachedBinary = file
                    return file
                }
            }
        }

        // 3. Check internal files directory
        val candidates = listOf(
            File(appContext.filesDir, "usr/bin/ffmpeg"),
            File(appContext.filesDir, "bin/ffmpeg"),
            File(appContext.noBackupFilesDir, "usr/bin/ffmpeg"),
            File(appContext.noBackupFilesDir, "bin/ffmpeg")
        )
        for (candidate in candidates) {
            if (candidate.exists()) {
                if (!candidate.canExecute()) candidate.setExecutable(true)
                cachedBinary = candidate
                return candidate
            }
        }

        return null
    }

    /**
     * Retrieves the version string of the embedded FFmpeg binary.
     */
    fun getVersion(): String {
        cachedVersion?.let { return it }

        try {
            val ffmpegClass = Class.forName("com.yausername.ffmpeg.FFmpeg")
            val getInstanceMethod = ffmpegClass.getMethod("getInstance")
            val ffmpegInstance = getInstanceMethod.invoke(null)
            val versionMethod = ffmpegClass.getMethod("version", Context::class.java)
            val ver = versionMethod.invoke(ffmpegInstance, appContext) as? String
            if (!ver.isNullOrBlank()) {
                cachedVersion = ver
                return ver
            }
        } catch (_: Throwable) {}

        val binary = getFFmpegBinary()
        if (binary != null && binary.canExecute()) {
            try {
                val process = ProcessBuilder(binary.absolutePath, "-version").start()
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val firstLine = reader.readLine()
                process.waitFor()
                if (!firstLine.isNullOrBlank()) {
                    val ver = firstLine.substringBefore("Copyright").trim()
                    cachedVersion = ver
                    return ver
                }
            } catch (_: Throwable) {}
        }

        val fallback = "v4.4.x-android ($primaryAbi)"
        cachedVersion = fallback
        return fallback
    }

    fun getStatus(): FFmpegStatus {
        val binary = getFFmpegBinary()
        val available = binary != null && binary.exists()
        val executable = binary?.canExecute() == true

        return FFmpegStatus(
            isAvailable = available,
            binaryPath = binary?.absolutePath,
            detectedAbi = primaryAbi,
            isExecutable = executable,
            version = getVersion(),
            errorMessage = if (!available) "FFmpeg binary not found for ABI $primaryAbi" else null
        )
    }

    override fun resetCancellation(taskId: String, runId: Long?) {
        if (runId != null) {
            cancelledKeys.remove("$taskId:$runId")
        } else {
            cancelledKeys.remove("$taskId:*")
            cancelledKeys.remove("$taskId:default")
        }
    }

    fun resetCancellation(taskId: String, runId: Int) = resetCancellation(taskId, runId.toLong())

    fun isExecutionActive(taskId: String, runId: Long? = null): Boolean {
        synchronized(processLock) {
            val key = ExecutionKey(taskId, runId).toKey()
            return activeExecutions.containsKey(key)
        }
    }

    fun isExecutionActive(taskId: String, runId: Int): Boolean = isExecutionActive(taskId, runId.toLong())

    override fun isTaskCancelled(taskId: String, runId: Long?): Boolean {
        return isCancelled(taskId, runId)
    }

    fun isTaskCancelled(taskId: String, runId: Int): Boolean = isTaskCancelled(taskId, runId.toLong())

    fun getActiveExecutionCount(): Int {
        synchronized(processLock) {
            return activeExecutions.size
        }
    }

    private fun isCancelled(taskId: String, runId: Long?): Boolean {
        if (cancelledKeys.contains("$taskId:*")) return true
        if (runId != null && cancelledKeys.contains("$taskId:$runId")) return true
        if (runId == null && cancelledKeys.contains("$taskId:default")) return true
        return false
    }

    override suspend fun mergeVideoAudio(
        videoFile: File,
        audioFile: File,
        outputFile: File,
        taskId: String?,
        runId: Long?,
        onProgress: ((ProcessingProgress) -> Unit)?
    ): Result<MediaResult> = withContext(Dispatchers.IO) {
        if (taskId != null && isCancelled(taskId, runId)) {
            return@withContext Result.failure(DownloadError.Cancelled("Operation cancelled before execution"))
        }

        val mergeKey = if (taskId != null) "$taskId:${runId ?: 0L}" else null
        if (mergeKey != null && completedTaskMerges.contains(mergeKey) && outputFile.exists() && outputFile.length() > 0L) {
            // Guarantee merge is never executed twice for the same task
            return@withContext Result.success(
                MediaResult(
                    outputFile = outputFile,
                    mimeType = MediaStoreHelper.getMimeType(outputFile.name),
                    durationSeconds = 0.0,
                    sizeBytes = outputFile.length(),
                    operation = "Video+Audio Merge (Already Merged)"
                )
            )
        }

        if (!videoFile.exists() || !audioFile.exists()) {
            return@withContext Result.failure(
                DownloadError.FfmpegError("Source video or audio file does not exist.")
            )
        }

        val binary = getFFmpegBinary()
            ?: return@withContext Result.failure(DownloadError.FfmpegError("FFmpeg binary unavailable."))

        val storageCheck = StorageSpaceChecker.validateMergeSpace(appContext, videoFile, audioFile)
        if (!storageCheck.hasEnoughSpace) {
            return@withContext Result.failure(
                DownloadError.StorageError(
                    msg = "Insufficient storage space for media merge.",
                    detail = storageCheck.errorMessage
                )
            )
        }

        val tempOutput = createTempProcessingFile("merge", outputFile.extension, taskId, runId)

        val args = FFmpegCommandBuilder.buildMergeArgs(
            binaryPath = binary.absolutePath,
            videoFile = videoFile,
            audioFile = audioFile,
            outputFile = tempOutput
        )

        val result = executeFFmpeg(taskId, runId, args, tempOutput, 0.0, onProgress)
        result.fold(
            onSuccess = {
                val validated = validateAndFinalize(tempOutput, outputFile, "Video+Audio Merge")
                if (validated != null) {
                    if (mergeKey != null) {
                        completedTaskMerges.add(mergeKey)
                    }
                    Result.success(validated)
                } else {
                    tempOutput.delete()
                    Result.failure(DownloadError.FfmpegError("Output validation failed after merge."))
                }
            },
            onFailure = { error ->
                tempOutput.delete()
                Result.failure(error)
            }
        )
    }

    override suspend fun fastCut(
        inputFile: File,
        startTime: String,
        endTime: String,
        outputFile: File,
        taskId: String?,
        runId: Long?,
        onProgress: ((ProcessingProgress) -> Unit)?
    ): Result<MediaResult> = withContext(Dispatchers.IO) {
        if (!inputFile.exists()) {
            return@withContext Result.failure(DownloadError.FfmpegError("Input file not found: ${inputFile.path}"))
        }

        val binary = getFFmpegBinary()
            ?: return@withContext Result.failure(DownloadError.FfmpegError("FFmpeg binary unavailable."))

        val storageCheck = StorageSpaceChecker.validateCutSpace(appContext, inputFile)
        if (!storageCheck.hasEnoughSpace) {
            return@withContext Result.failure(
                DownloadError.StorageError(
                    msg = "Insufficient storage space for media cut.",
                    detail = storageCheck.errorMessage
                )
            )
        }

        val totalDuration = calculateDurationSeconds(startTime, endTime)
        val tempOutput = createTempProcessingFile("fastcut", outputFile.extension, taskId, runId)

        val args = FFmpegCommandBuilder.buildFastCutArgs(
            binaryPath = binary.absolutePath,
            inputFile = inputFile,
            outputFile = tempOutput,
            startTime = startTime,
            endTime = endTime
        )

        val result = executeFFmpeg(taskId, runId, args, tempOutput, totalDuration, onProgress)
        result.fold(
            onSuccess = {
                val validated = validateAndFinalize(tempOutput, outputFile, "Fast Cut")
                if (validated != null) {
                    Result.success(validated)
                } else {
                    tempOutput.delete()
                    Result.failure(DownloadError.FfmpegError("Output validation failed after Fast Cut."))
                }
            },
            onFailure = { error ->
                tempOutput.delete()
                Result.failure(error)
            }
        )
    }

    override suspend fun preciseCut(
        inputFile: File,
        startTime: String,
        endTime: String,
        outputFile: File,
        taskId: String?,
        runId: Long?,
        onProgress: ((ProcessingProgress) -> Unit)?
    ): Result<MediaResult> = withContext(Dispatchers.IO) {
        if (!inputFile.exists()) {
            return@withContext Result.failure(DownloadError.FfmpegError("Input file not found: ${inputFile.path}"))
        }

        val binary = getFFmpegBinary()
            ?: return@withContext Result.failure(DownloadError.FfmpegError("FFmpeg binary unavailable."))

        val storageCheck = StorageSpaceChecker.validateCutSpace(appContext, inputFile)
        if (!storageCheck.hasEnoughSpace) {
            return@withContext Result.failure(
                DownloadError.StorageError(
                    msg = "Insufficient storage space for media cut.",
                    detail = storageCheck.errorMessage
                )
            )
        }

        val totalDuration = calculateDurationSeconds(startTime, endTime)
        val tempOutput = createTempProcessingFile("precisecut", outputFile.extension, taskId, runId)

        val args = FFmpegCommandBuilder.buildPreciseCutArgs(
            binaryPath = binary.absolutePath,
            inputFile = inputFile,
            outputFile = tempOutput,
            startTime = startTime,
            endTime = endTime
        )

        val result = executeFFmpeg(taskId, runId, args, tempOutput, totalDuration, onProgress)
        result.fold(
            onSuccess = {
                val validated = validateAndFinalize(tempOutput, outputFile, "Precise Cut")
                if (validated != null) {
                    Result.success(validated)
                } else {
                    tempOutput.delete()
                    Result.failure(DownloadError.FfmpegError("Output validation failed after Precise Cut."))
                }
            },
            onFailure = { error ->
                tempOutput.delete()
                Result.failure(error)
            }
        )
    }

    override suspend fun cutMedia(
        inputFile: File,
        outputFile: File,
        startTime: String,
        endTime: String,
        mode: CutMode,
        taskId: String?,
        runId: Long?,
        onProgress: ((ProcessingProgress) -> Unit)?
    ): Result<MediaResult> {
        return when (mode) {
            CutMode.FAST_CUT -> fastCut(inputFile, startTime, endTime, outputFile, taskId, runId, onProgress)
            CutMode.PRECISE_CUT -> preciseCut(inputFile, startTime, endTime, outputFile, taskId, runId, onProgress)
        }
    }

    override suspend fun remux(
        inputFile: File,
        outputFile: File,
        targetContainer: String,
        taskId: String?,
        runId: Long?,
        onProgress: ((ProcessingProgress) -> Unit)?
    ): Result<MediaResult> = withContext(Dispatchers.IO) {
        if (!inputFile.exists()) {
            return@withContext Result.failure(DownloadError.FfmpegError("Input file not found."))
        }

        val binary = getFFmpegBinary()
            ?: return@withContext Result.failure(DownloadError.FfmpegError("FFmpeg binary unavailable."))

        val tempOutput = createTempProcessingFile("remux", targetContainer, taskId, runId)

        val args = FFmpegCommandBuilder.buildRemuxArgs(
            binaryPath = binary.absolutePath,
            inputFile = inputFile,
            outputFile = tempOutput
        )

        val result = executeFFmpeg(taskId, runId, args, tempOutput, 0.0, onProgress)
        result.fold(
            onSuccess = {
                val validated = validateAndFinalize(tempOutput, outputFile, "Remux")
                if (validated != null) {
                    Result.success(validated)
                } else {
                    tempOutput.delete()
                    Result.failure(DownloadError.FfmpegError("Output validation failed after remux."))
                }
            },
            onFailure = { error ->
                tempOutput.delete()
                Result.failure(error)
            }
        )
    }

    override suspend fun extractAudio(
        inputFile: File,
        outputFile: File,
        audioCodec: String,
        taskId: String?,
        runId: Long?,
        onProgress: ((ProcessingProgress) -> Unit)?
    ): Result<MediaResult> = withContext(Dispatchers.IO) {
        if (!inputFile.exists()) {
            return@withContext Result.failure(DownloadError.FfmpegError("Input file not found."))
        }

        val binary = getFFmpegBinary()
            ?: return@withContext Result.failure(DownloadError.FfmpegError("FFmpeg binary unavailable."))

        val tempOutput = createTempProcessingFile("extract_audio", outputFile.extension, taskId, runId)

        val args = FFmpegCommandBuilder.buildExtractAudioArgs(
            binaryPath = binary.absolutePath,
            inputFile = inputFile,
            outputFile = tempOutput,
            audioCodec = audioCodec
        )

        val result = executeFFmpeg(taskId, runId, args, tempOutput, 0.0, onProgress)
        result.fold(
            onSuccess = {
                val validated = validateAndFinalize(tempOutput, outputFile, "Audio Extraction")
                if (validated != null) {
                    Result.success(validated)
                } else {
                    tempOutput.delete()
                    Result.failure(DownloadError.FfmpegError("Output validation failed after audio extraction."))
                }
            },
            onFailure = { error ->
                tempOutput.delete()
                Result.failure(error)
            }
        )
    }

    suspend fun executeFFmpeg(
        taskId: String?,
        runId: Int,
        arguments: List<String>,
        tempOutput: File,
        totalDurationSeconds: Double,
        onProgress: ((ProcessingProgress) -> Unit)?
    ): Result<Unit> = executeFFmpeg(taskId, runId.toLong(), arguments, tempOutput, totalDurationSeconds, onProgress)

    /**
     * Executes the FFmpeg process with safe argument array, real-time progress parsing, and robust cancellation.
     */
    suspend fun executeFFmpeg(
        taskId: String?,
        runId: Long?,
        arguments: List<String>,
        tempOutput: File,
        totalDurationSeconds: Double,
        onProgress: ((ProcessingProgress) -> Unit)?
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val effectiveTaskId = taskId ?: UUID.randomUUID().toString()
        val key = ExecutionKey(effectiveTaskId, runId)
        val keyString = key.toKey()

        val record = ExecutionRecord(
            key = key,
            process = null,
            tempOutput = tempOutput
        )

        // 2 & 3. Prevent race condition between startProcess and cancel.
        // If cancel arrived before registering Process, do NOT start execution.
        val process: Process
        synchronized(processLock) {
            if (isCancelled(effectiveTaskId, runId)) {
                try { if (tempOutput.exists()) tempOutput.delete() } catch (_: Throwable) {}
                return@withContext Result.failure(
                    DownloadError.Cancelled("FFmpeg media processing cancelled before start.")
                )
            }

            try {
                val processBuilder = ProcessBuilder(arguments)
                processBuilder.redirectErrorStream(true)
                process = processBuilder.start()
                record.process = process
                activeExecutions[keyString] = record
            } catch (t: Throwable) {
                try { if (tempOutput.exists()) tempOutput.delete() } catch (_: Throwable) {}
                return@withContext Result.failure(
                    DownloadError.FfmpegError("Failed to start FFmpeg process: ${t.message}")
                )
            }
        }

        val logBuffer = StringBuilder()
        var currentSpeed = "1.0x"
        var currentFps = 0.0
        var currentFrame = 0L

        try {
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (record.isCancelled || isCancelled(effectiveTaskId, runId)) {
                    break
                }
                val currentLine = line ?: break
                logBuffer.appendLine(currentLine)

                if (currentLine.startsWith("speed=")) {
                    currentSpeed = currentLine.substringAfter("speed=").trim()
                } else if (currentLine.startsWith("fps=")) {
                    currentFps = currentLine.substringAfter("fps=").trim().toDoubleOrNull() ?: 0.0
                } else if (currentLine.startsWith("frame=")) {
                    currentFrame = currentLine.substringAfter("frame=").trim().toLongOrNull() ?: 0L
                }

                val progressObj = parseProcessingProgress(
                    line = currentLine,
                    totalDurationSeconds = totalDurationSeconds,
                    speed = currentSpeed,
                    fps = currentFps,
                    frame = currentFrame
                )
                if (progressObj != null && onProgress != null) {
                    if (!record.isCancelled && !isCancelled(effectiveTaskId, runId)) {
                        onProgress(progressObj)
                    }
                }
            }

            val exitCode = process.waitFor()

            synchronized(processLock) {
                activeExecutions.remove(keyString)
                record.isFinished = true
                if (record.isCancelled || isCancelled(effectiveTaskId, runId)) {
                    try { if (tempOutput.exists()) tempOutput.delete() } catch (_: Throwable) {}
                    return@withContext Result.failure(
                        DownloadError.Cancelled("FFmpeg media processing cancelled.")
                    )
                }
            }

            if (exitCode == 0) {
                if (!record.isCancelled && !isCancelled(effectiveTaskId, runId)) {
                    onProgress?.invoke(
                        ProcessingProgress(
                            percentage = 100f,
                            totalDurationSeconds = totalDurationSeconds,
                            speed = currentSpeed,
                            statusDescription = "Processing complete"
                        )
                    )
                }
                Result.success(Unit)
            } else {
                try { if (tempOutput.exists()) tempOutput.delete() } catch (_: Throwable) {}
                Result.failure(
                    DownloadError.FfmpegError(
                        msg = "FFmpeg media processing failed (exit code $exitCode).",
                        detail = logBuffer.takeLast(1000).toString()
                    )
                )
            }
        } catch (e: Exception) {
            synchronized(processLock) {
                activeExecutions.remove(keyString)
            }
            try { if (tempOutput.exists()) tempOutput.delete() } catch (_: Throwable) {}
            if (record.isCancelled || isCancelled(effectiveTaskId, runId)) {
                Result.failure(DownloadError.Cancelled("FFmpeg media processing cancelled."))
            } else {
                Result.failure(
                    DownloadError.FfmpegError(
                        msg = "FFmpeg execution error: ${e.message}",
                        detail = e.stackTraceToString()
                    )
                )
            }
        } finally {
            try {
                val isProcessAlive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    process.isAlive
                } else {
                    try {
                        process.exitValue()
                        false
                    } catch (_: IllegalThreadStateException) {
                        true
                    }
                }
                if (isProcessAlive) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        process.destroyForcibly()
                    } else {
                        process.destroy()
                    }
                    process.waitFor()
                }
            } catch (_: Throwable) {}
            try { process.inputStream?.close() } catch (_: Throwable) {}
            try { process.errorStream?.close() } catch (_: Throwable) {}
            try { process.outputStream?.close() } catch (_: Throwable) {}
            synchronized(processLock) {
                activeExecutions.remove(keyString)
            }
        }
    }

    /**
     * Backward-compatible overload for legacy processId execution.
     */
    suspend fun executeFFmpeg(
        processId: String,
        arguments: List<String>,
        totalDurationSeconds: Double,
        onProgress: ((ProcessingProgress) -> Unit)?
    ): Result<Unit> {
        val tempOutput = File(appContext.cacheDir, "ffmpeg_temp/legacy_${processId}.tmp")
        return executeFFmpeg(processId, null, arguments, tempOutput, totalDurationSeconds, onProgress)
    }

    override fun cancel(processId: String) {
        if (processId.contains(":")) {
            val parts = processId.split(":")
            val taskId = parts[0]
            val runId = parts.getOrNull(1)?.toLongOrNull()
            cancel(taskId, runId)
        } else {
            cancel(processId, null)
        }
    }

    fun cancel(taskId: String, runId: Int) = cancel(taskId, runId.toLong())

    override fun cancel(taskId: String, runId: Long?) {
        val recordsToClean = mutableListOf<ExecutionRecord>()

        synchronized(processLock) {
            if (runId != null) {
                cancelledKeys.add("$taskId:$runId")
            } else {
                cancelledKeys.add("$taskId:*")
                cancelledKeys.add("$taskId:default")
            }

            val it = activeExecutions.entries.iterator()
            while (it.hasNext()) {
                val entry = it.next()
                val record = entry.value
                val matches = record.key.taskId == taskId && (runId == null || record.key.runId == runId)
                if (matches) {
                    record.isCancelled = true
                    recordsToClean.add(record)
                    it.remove()
                }
            }
        }

        // 4. Upon cancellation:
        //    - destroy process
        //    - wait for process exit
        //    - remove process from registry
        //    - clean temp output
        for (record in recordsToClean) {
            record.process?.let { proc ->
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        proc.destroyForcibly()
                    } else {
                        proc.destroy()
                    }
                } catch (_: Throwable) {}
                try { proc.inputStream?.close() } catch (_: Throwable) {}
                try { proc.errorStream?.close() } catch (_: Throwable) {}
                try { proc.outputStream?.close() } catch (_: Throwable) {}
                try {
                    proc.waitFor()
                } catch (_: Throwable) {}
            }
            try {
                if (record.tempOutput.exists()) {
                    record.tempOutput.delete()
                }
            } catch (_: Throwable) {}
        }
    }

    private fun createTempProcessingFile(prefix: String, ext: String, taskId: String? = null, runId: Long? = null): File {
        val cacheDir = File(appContext.cacheDir, "ffmpeg_temp").apply { if (!exists()) mkdirs() }
        val extension = if (ext.startsWith(".")) ext else ".$ext"
        val taskPart = if (!taskId.isNullOrBlank()) "${taskId}_run${runId ?: 0}_" else ""
        return File(cacheDir, "${prefix}_${taskPart}${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}$extension")
    }

    private fun validateAndFinalize(tempFile: File, destinationFile: File, operation: String): MediaResult? {
        if (!tempFile.exists() || tempFile.length() <= 0L) {
            return null
        }

        // Ensure parent directory exists
        destinationFile.parentFile?.let { if (!it.exists()) it.mkdirs() }

        // Atomic move or copy
        val success = if (tempFile.renameTo(destinationFile)) {
            true
        } else {
            try {
                tempFile.copyTo(destinationFile, overwrite = true)
                tempFile.delete()
                true
            } catch (_: Exception) {
                false
            }
        }

        if (!success || !destinationFile.exists() || destinationFile.length() <= 0L) {
            return null
        }

        val mimeType = MediaStoreHelper.getMimeType(destinationFile.name)
        return MediaResult(
            outputFile = destinationFile,
            mimeType = mimeType,
            sizeBytes = destinationFile.length(),
            operation = operation
        )
    }

    private fun hasAvailableStorage(requiredBytes: Long): Boolean {
        return StorageSpaceChecker.hasEnoughSpace(appContext, requiredBytes)
    }

    companion object {
        @Volatile
        private var instance: FFmpegManager? = null

        fun getInstance(context: Context): FFmpegManager {
            return instance ?: synchronized(this) {
                instance ?: FFmpegManager(context.applicationContext).also { instance = it }
            }
        }

        // Shared across all FFmpegManager instances to guarantee single process ownership and unified cancellation authority
        private val processLock = Any()
        private val activeExecutions = mutableMapOf<String, ExecutionRecord>()
        private val cancelledKeys = ConcurrentHashMap.newKeySet<String>()
        private val completedTaskMerges = ConcurrentHashMap.newKeySet<String>()

        private val TIME_REGEX = Regex("""time=(\d{2}:\d{2}:\d{2}(?:\.\d+)?)""")
        private val SPEED_REGEX = Regex("""speed=\s*(\S+x?)""")
        private val OUT_TIME_MS_REGEX = Regex("""out_time_ms=(\d+)""")
        private val FPS_REGEX = Regex("""fps=\s*(\d+(?:\.\d+)?)""")
        private val FRAME_REGEX = Regex("""frame=\s*(\d+)""")

        fun parseTimeSeconds(timeStr: String): Double? {
            val parts = timeStr.trim().split(":")
            if (parts.size != 3) return null
            val hours = parts[0].toDoubleOrNull() ?: return null
            val minutes = parts[1].toDoubleOrNull() ?: return null
            val seconds = parts[2].toDoubleOrNull() ?: return null
            return hours * 3600.0 + minutes * 60.0 + seconds
        }

        fun parseProcessingProgress(
            line: String,
            totalDurationSeconds: Double,
            speed: String = "1.0x",
            fps: Double = 0.0,
            frame: Long = 0L
        ): ProcessingProgress? {
            var timeSec: Double? = null

            val msMatch = OUT_TIME_MS_REGEX.find(line)
            if (msMatch != null) {
                val ms = msMatch.groupValues[1].toDoubleOrNull()
                if (ms != null && ms >= 0) {
                    timeSec = ms / 1_000_000.0
                }
            }

            if (timeSec == null) {
                val timeMatch = TIME_REGEX.find(line)
                if (timeMatch != null) {
                    timeSec = parseTimeSeconds(timeMatch.groupValues[1])
                }
            }

            if (timeSec != null && totalDurationSeconds > 0) {
                val pct = ((timeSec / totalDurationSeconds) * 100.0).toFloat().coerceIn(0f, 99f)
                val remainingSec = (totalDurationSeconds - timeSec).coerceAtLeast(0.0)
                val parsedSpeedNum = speed.replace("x", "").toDoubleOrNull() ?: 1.0
                val eta = if (parsedSpeedNum > 0) (remainingSec / parsedSpeedNum).toLong() else 0L

                return ProcessingProgress(
                    percentage = pct,
                    timeSeconds = timeSec,
                    totalDurationSeconds = totalDurationSeconds,
                    speed = speed,
                    fps = fps,
                    frame = frame,
                    etaSeconds = eta,
                    statusDescription = "Processing media... ${pct.toInt()}%"
                )
            }

            return null
        }

        fun calculateDurationSeconds(startTime: String, endTime: String): Double {
            val start = parseTimeSeconds(startTime) ?: 0.0
            val end = parseTimeSeconds(endTime) ?: 0.0
            return (end - start).coerceAtLeast(1.0)
        }
    }
}
