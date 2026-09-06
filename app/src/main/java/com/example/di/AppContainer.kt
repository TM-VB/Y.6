package com.example.di

import android.content.Context
import com.example.data.local.AppDatabase
import com.example.data.local.DownloadTaskDao
import com.example.data.repository.DownloadRepository
import com.example.data.storage.AndroidStorageManager
import com.example.downloader.DownloadManager
import com.example.downloader.engine.DownloadEngine
import com.example.downloader.engine.MediaProcessor
import com.example.downloader.engine.StorageManager
import com.example.downloader.engine.VideoExtractor
import com.example.downloader.ffmpeg.FFmpegManager
import com.example.downloader.ytdlp.YtDlpEngineBridge

/**
 * Dependency container providing singletons and clean abstraction boundaries.
 * Enables decoupling components without heavy reflection or build-time code generation.
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    val database: AppDatabase by lazy {
        AppDatabase.getInstance(appContext)
    }

    val appSettings: com.example.data.settings.AppSettings by lazy {
        com.example.data.settings.AppSettings.getInstance(appContext)
    }

    val downloadTaskDao: DownloadTaskDao by lazy {
        database.downloadTaskDao()
    }

    val downloadRepository: DownloadRepository by lazy {
        DownloadRepository(downloadTaskDao)
    }

    val storageManager: StorageManager by lazy {
        AndroidStorageManager(appContext)
    }

    val ffmpegManager: FFmpegManager by lazy {
        FFmpegManager.getInstance(appContext)
    }

    val mediaProcessor: MediaProcessor by lazy {
        ffmpegManager
    }

    val ytDlpMediaEngine: com.example.downloader.engine.YtDlpMediaEngine by lazy {
        com.example.downloader.engine.YtDlpDownloadEngine.getInstance(appContext)
    }

    val ytDlpEngineBridge: YtDlpEngineBridge by lazy {
        YtDlpEngineBridge(appContext, ytDlpMediaEngine)
    }

    val videoExtractor: VideoExtractor by lazy {
        ytDlpMediaEngine
    }

    val formatProvider: com.example.downloader.engine.FormatProvider by lazy {
        com.example.downloader.engine.DefaultFormatProvider()
    }

    val downloadEngine: DownloadEngine by lazy {
        ytDlpMediaEngine
    }

    val downloadManager: DownloadManager by lazy {
        DownloadManager.getInstance(appContext)
    }
}
