package com.example.downloader.cleanup

import android.content.Context
import java.io.File
import java.util.Locale

object CleanupManager {

    fun cleanupTempFiles(context: Context): Long {
        var freedBytes = 0L
        val cacheDir = context.cacheDir ?: return 0L

        val dirsToClean = listOf(
            File(cacheDir, "ytdlp_downloads"),
            File(cacheDir, "ffmpeg_temp"),
            File(cacheDir, "ffmpeg_work"),
            File(cacheDir, "downloads_temp"),
            File(cacheDir, "youtubedl-android")
        )

        for (dir in dirsToClean) {
            if (dir.exists()) {
                freedBytes += deleteRecursive(dir, deleteSelf = false)
            }
        }

        // Also clean orphaned .part and .ytdl files in cache root
        cacheDir.listFiles()?.forEach { file ->
            if (file.isFile && (file.name.endsWith(".part") || file.name.endsWith(".ytdl") || file.name.endsWith(".tmp"))) {
                freedBytes += file.length()
                file.delete()
            }
        }

        return freedBytes
    }

    fun cleanupTaskFiles(context: Context, taskId: String) {
        val cacheDir = context.cacheDir ?: return
        val taskDir = File(cacheDir, "ytdlp_downloads/$taskId")
        if (taskDir.exists()) {
            deleteRecursive(taskDir, deleteSelf = true)
        }
    }

    fun getCacheSizeBytes(context: Context): Long {
        val cacheDir = context.cacheDir ?: return 0L
        return getFolderSize(cacheDir)
    }

    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0

        return when {
            gb >= 1.0 -> String.format(Locale.US, "%.2f GB", gb)
            mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
            kb >= 1.0 -> String.format(Locale.US, "%.1f KB", kb)
            else -> "$bytes B"
        }
    }

    private fun getFolderSize(dir: File): Long {
        var size = 0L
        dir.listFiles()?.forEach { file ->
            size += if (file.isDirectory) getFolderSize(file) else file.length()
        }
        return size
    }

    private fun deleteRecursive(fileOrDir: File, deleteSelf: Boolean = true): Long {
        var freed = 0L
        if (fileOrDir.isDirectory) {
            fileOrDir.listFiles()?.forEach { child ->
                freed += deleteRecursive(child, deleteSelf = true)
            }
        }
        if (deleteSelf) {
            freed += fileOrDir.length()
            fileOrDir.delete()
        }
        return freed
    }
}
