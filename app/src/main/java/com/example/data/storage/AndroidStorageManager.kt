package com.example.data.storage

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.example.domain.util.FileNameSanitizer
import com.example.downloader.engine.StorageManager
import com.example.storage.MediaStoreHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

/**
 * Concrete implementation of StorageManager using modern Android Scoped Storage and MediaStore APIs.
 * Automatically saves files to the user's public 'Downloads/DownloadVideos' directory.
 */
class AndroidStorageManager(private val context: Context) : StorageManager {

    override fun getTempDirectory(): File {
        val tempDir = File(context.cacheDir, "downloads_temp")
        if (!tempDir.exists()) {
            tempDir.mkdirs()
        }
        return tempDir
    }

    override suspend fun saveVideoToMediaStore(
        tempFile: File,
        displayName: String,
        mimeType: String
    ): Result<Uri> = withContext(Dispatchers.IO) {
        try {
            val (uri, _) = MediaStoreHelper.saveToPublicDownloads(
                context = context,
                sourceFile = tempFile,
                rawTitle = displayName,
                customDisplayName = displayName,
                customMimeType = mimeType
            )
            if (uri != null) {
                if (tempFile.exists()) {
                    tempFile.delete()
                }
                Result.success(uri)
            } else {
                Result.failure(Exception("Failed to persist media file to MediaStore"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun openMediaFile(context: Context, filePath: String?, contentUriStr: String?) {
        MediaStoreHelper.openFile(context, filePath, contentUriStr)
    }

    override suspend fun cleanTempFiles() {
        withContext(Dispatchers.IO) {
            val tempDir = getTempDirectory()
            tempDir.listFiles()?.forEach { file ->
                if (file.isFile && System.currentTimeMillis() - file.lastModified() > 24 * 3600 * 1000) {
                    file.delete()
                }
            }
        }
    }
}
