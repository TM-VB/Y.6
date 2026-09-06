package com.example.downloader.engine

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Storage abstraction separating media saving and Scoped Storage/MediaStore operations
 * from UI and core logic.
 */
interface StorageManager {
    fun getTempDirectory(): File
    suspend fun saveVideoToMediaStore(
        tempFile: File,
        displayName: String,
        mimeType: String
    ): Result<Uri>
    fun openMediaFile(context: Context, filePath: String?, contentUriStr: String?)
    suspend fun cleanTempFiles()
}
