package com.example

import android.app.Application
import com.example.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Main Application class for Download Videos.
 * Initializes the dependency container and begins background preparation of native media engines.
 */
class DownloadVideosApplication : Application() {

    lateinit var container: AppContainer
        private set

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // Initialize Dependency Container
        container = AppContainer(this)

        // Bind facade to the container's media engine
        com.example.ytdlp.YtDlpEngine.setEngine(container.ytDlpMediaEngine)

        // Asynchronously initialize yt-dlp native engines in background
        applicationScope.launch {
            try {
                container.ytDlpMediaEngine.init(applicationContext)

                // Check for yt-dlp updates in background if auto-update is enabled
                if (container.appSettings.autoCheckEngineUpdates.value) {
                    try {
                        container.ytDlpMediaEngine.updateEngine(applicationContext)
                    } catch (_: Throwable) {}
                }
            } catch (t: Throwable) {
                t.printStackTrace()
            }
        }
    }
}
