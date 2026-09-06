package com.example.ytdlp

import android.util.Log
import java.util.concurrent.ConcurrentLinkedQueue

data class LogEntry(
    val tag: String,
    val message: String,
    val timestamp: Long = System.currentTimeMillis()
)

object YtDlpLogger {

    private const val MAX_LOGS = 200
    private val logs = ConcurrentLinkedQueue<LogEntry>()

    fun getRecentLogs(): List<LogEntry> = logs.toList().takeLast(MAX_LOGS)

    fun logAnalyzeStarted(url: String, processId: String? = null) {
        val msg = "Analysis started: $url (PID: ${processId ?: "auto"})"
        appendLog("ANALYZE", msg)
        Log.i("YtDlpLogger", msg)
    }

    fun logAnalyzeCompleted(url: String, formatCount: Int, durationMs: Long, extractor: String? = null) {
        val msg = "Analysis completed for $url: found $formatCount formats in ${durationMs}ms [${extractor ?: "auto"}]"
        appendLog("ANALYZE", msg)
        Log.i("YtDlpLogger", msg)
    }

    fun logAnalyzeError(url: String, error: Any, durationMs: Long) {
        val msg = "Analysis failed for $url after ${durationMs}ms: $error"
        appendLog("ERROR", msg)
        Log.e("YtDlpLogger", msg)
    }

    fun logAnalyzeCancelled(url: String, durationMs: Long, processId: String? = null) {
        val msg = "Analysis cancelled for $url (PID: $processId) after ${durationMs}ms"
        appendLog("ANALYZE", msg)
        Log.w("YtDlpLogger", msg)
    }

    fun logDownloadStarted(taskId: String, url: String, formatSelector: String) {
        val msg = "Download started [$taskId]: $url (format: $formatSelector)"
        appendLog("DOWNLOAD", msg)
        Log.i("YtDlpLogger", msg)
    }

    fun logDownloadCompleted(taskId: String, outputFile: String, fileSizeBytes: Long, durationMs: Long) {
        val msg = "Download completed [$taskId]: $outputFile ($fileSizeBytes bytes in ${durationMs}ms)"
        appendLog("DOWNLOAD", msg)
        Log.i("YtDlpLogger", msg)
    }

    fun logDownloadError(taskId: String, error: Any, durationMs: Long) {
        val msg = "Download failed [$taskId] after ${durationMs}ms: $error"
        appendLog("ERROR", msg)
        Log.e("YtDlpLogger", msg)
    }

    fun logDownloadCancelled(taskId: String, durationMs: Long) {
        val msg = "Download cancelled [$taskId] after ${durationMs}ms"
        appendLog("DOWNLOAD", msg)
        Log.w("YtDlpLogger", msg)
    }

    fun logFormatSelected(formatSelector: String, isManual: Boolean) {
        val msg = "Format selected: $formatSelector (manual: $isManual)"
        appendLog("FORMAT", msg)
        Log.i("YtDlpLogger", msg)
    }

    fun logInfo(tag: String, message: String) {
        appendLog(tag, message)
        Log.i("YtDlpLogger", "[$tag] $message")
    }

    fun logError(tag: String, message: String, throwable: Throwable? = null) {
        val fullMsg = if (throwable != null) "$message: ${throwable.message}" else message
        appendLog(tag, fullMsg)
        Log.e("YtDlpLogger", "[$tag] $fullMsg", throwable)
    }

    private fun appendLog(tag: String, message: String) {
        logs.add(LogEntry(tag, message))
        while (logs.size > MAX_LOGS) {
            logs.poll()
        }
    }
}
