package com.example.storage

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import androidx.core.net.toUri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.example.domain.util.FileNameSanitizer
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

object MediaStoreHelper {

    private const val TAG = "MEDIASTORE_DEBUG"

    fun getTempDownloadDir(context: Context): File {
        val dir = File(context.cacheDir, "ytdlp_downloads")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "opus" -> "audio/opus"
            "ogg" -> "audio/ogg"
            "wav" -> "audio/wav"
            "flac" -> "audio/flac"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "video/mp4"
        }
    }

    fun isVideoMime(mimeType: String): Boolean = mimeType.startsWith("video/")
    fun isAudioMime(mimeType: String): Boolean = mimeType.startsWith("audio/")

    fun resolveMimeType(context: Context, uri: Uri?, filePath: String?): String {
        if (uri != null) {
            try {
                val resolved = context.contentResolver.getType(uri)
                if (!resolved.isNullOrBlank()) {
                    return resolved
                }
            } catch (_: Exception) {}
        }
        val name = filePath ?: uri?.lastPathSegment.orEmpty()
        return getMimeType(name)
    }

    /**
     * Copies a completed download file to the public Movies/DownloadVideos or Music/DownloadVideos directory using MediaStore.
     * Triggers MediaScannerConnection so Android Gallery & Music players instantly generate thumbnails and metadata.
     */
    fun saveToPublicDownloads(
        context: Context,
        sourceFile: File,
        rawTitle: String,
        customDisplayName: String? = null,
        customMimeType: String? = null
    ): Pair<Uri?, String?> {
        if (!sourceFile.exists() || sourceFile.length() < 512L) {
            return Pair(null, null)
        }

        val extension = sourceFile.extension.ifBlank { "mp4" }
        val titleStem = if (!customDisplayName.isNullOrBlank()) customDisplayName else rawTitle
        val displayName = FileNameSanitizer.sanitize(titleStem, extension)
        val mimeType = if (!customMimeType.isNullOrBlank()) {
            customMimeType
        } else {
            getMimeType(displayName)
        }
        val isVideo = isVideoMime(mimeType)
        val isAudio = isAudioMime(mimeType)

        val targetDir = when {
            isVideo -> Environment.DIRECTORY_MOVIES
            isAudio -> Environment.DIRECTORY_MUSIC
            else -> Environment.DIRECTORY_DOWNLOADS
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val collectionUri = when {
                    isVideo -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    isAudio -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    else -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                }

                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "$targetDir/DownloadVideos")
                    put(MediaStore.MediaColumns.SIZE, sourceFile.length())
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                    if (isVideo) {
                        put(MediaStore.Video.Media.TITLE, rawTitle)
                    } else if (isAudio) {
                        put(MediaStore.Audio.Media.TITLE, rawTitle)
                    }
                }

                val uri = context.contentResolver.insert(collectionUri, values)

                if (uri != null) {
                    var bytesWritten = 0L
                    var copySuccess = false

                    try {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            FileInputStream(sourceFile).use { input ->
                                bytesWritten = input.copyTo(out, bufferSize = 64 * 1024)
                                out.flush()
                            }
                        }
                        // Verify complete file size was written
                        if (bytesWritten == sourceFile.length() && bytesWritten > 0L) {
                            copySuccess = true
                        }
                    } catch (copyEx: Exception) {
                        Log.e(TAG, "Error writing media data to MediaStore uri $uri", copyEx)
                    }

                    if (copySuccess) {
                        values.clear()
                        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                        values.put(MediaStore.MediaColumns.SIZE, bytesWritten)
                        context.contentResolver.update(uri, values, null, null)

                        val publicPath = "${Environment.getExternalStoragePublicDirectory(targetDir)}/DownloadVideos/$displayName"
                        Log.d(TAG, "Saved to MediaStore: uri=$uri, sourceSize=${sourceFile.length()}, written=$bytesWritten, publicPath=$publicPath")

                        return Pair(uri, publicPath)
                    } else {
                        // Copy failed or size mismatch: delete orphaned pending MediaStore entry
                        try {
                            context.contentResolver.delete(uri, null, null)
                        } catch (_: Exception) {}
                    }
                }
            } else {
                val publicDir = File(
                    Environment.getExternalStoragePublicDirectory(targetDir),
                    "DownloadVideos"
                )
                if (!publicDir.exists()) {
                    publicDir.mkdirs()
                }
                val destFile = File(publicDir, displayName)
                if (!destFile.canonicalPath.startsWith(publicDir.canonicalPath + File.separator)) {
                    Log.e(TAG, "Path traversal detected in destination file name: $displayName")
                    return Pair(null, null)
                }
                var bytesWritten = 0L
                FileInputStream(sourceFile).use { input ->
                    FileOutputStream(destFile).use { output ->
                        bytesWritten = input.copyTo(output, bufferSize = 64 * 1024)
                        output.flush()
                    }
                }

                if (bytesWritten == sourceFile.length()) {
                    val uri = try {
                        FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.provider",
                            destFile
                        )
                    } catch (_: Exception) {
                        Uri.fromFile(destFile)
                    }

                    try {
                        MediaScannerConnection.scanFile(
                            context.applicationContext,
                            arrayOf(destFile.absolutePath),
                            arrayOf(mimeType),
                            null
                        )
                    } catch (_: Exception) {}

                    return Pair(uri, destFile.absolutePath)
                } else {
                    destFile.delete()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // If MediaStore save or direct public copy failed, return failure so the caller knows persistence failed
        return Pair(null, null)
    }

    private fun isSafeToShare(context: Context, file: File): Boolean {
        return try {
            val canonical = file.canonicalPath
            val privateCookies = File(context.cacheDir, "yt_cookies_private").canonicalPath
            if (canonical.startsWith(privateCookies)) return false
            val dataDir = context.applicationInfo.dataDir
            val databases = File(dataDir, "databases").canonicalPath
            if (canonical.startsWith(databases)) return false
            val sharedPrefs = File(dataDir, "shared_prefs").canonicalPath
            if (canonical.startsWith(sharedPrefs)) return false
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Opens downloaded file with system video or audio player
     */
    fun openFile(context: Context, filePath: String?, contentUriStr: String?) {
        try {
            val uri: Uri = when {
                !contentUriStr.isNullOrBlank() -> contentUriStr.toUri()
                !filePath.isNullOrBlank() -> {
                    val file = File(filePath)
                    if (file.exists() && isSafeToShare(context, file)) {
                        FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.provider",
                            file
                        )
                    } else return
                }
                else -> return
            }

            val mimeType = resolveMimeType(context, uri, filePath)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "Open with").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Shares the downloaded media file with other apps via system share sheet
     */
    fun shareFile(context: Context, filePath: String?, contentUriStr: String?) {
        try {
            val uri: Uri = when {
                !contentUriStr.isNullOrBlank() -> contentUriStr.toUri()
                !filePath.isNullOrBlank() -> {
                    val file = File(filePath)
                    if (file.exists() && isSafeToShare(context, file)) {
                        FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.provider",
                            file
                        )
                    } else return
                }
                else -> return
            }

            val mimeType = resolveMimeType(context, uri, filePath)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(shareIntent, "Share media").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun hasEnoughStorageSpace(context: Context, requiredBytes: Long = 50 * 1024 * 1024L): Boolean {
        return StorageSpaceChecker.hasEnoughSpace(context, requiredBytes)
    }
}
