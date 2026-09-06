package com.example.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.DownloadVideosApplication
import com.example.data.settings.AppSettings
import com.example.data.settings.ThemeMode
import com.example.downloader.cleanup.CleanupManager
import com.example.downloader.ffmpeg.FFmpegStatus
import com.example.ytdlp.YtDlpLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val ffmpegStatus: FFmpegStatus,
    val ytDlpVersion: String = "2025.x",
    val isUpdatingYtDlp: Boolean = false,
    val storagePath: String = "Downloads/DownloadVideos",
    val primaryAbi: String,
    val concurrentDownloads: Int = 1,
    val autoRetry: Boolean = true,
    val showNotifications: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val languageCode: String = "system",
    val cookiesContent: String = "",
    val autoUpdateYtDlp: Boolean = true,
    val defaultSubtitles: Boolean = false,
    val defaultSubtitleLang: String = "ar",
    val cacheSize: String = "0 B",
    val statusMessage: String? = null
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application
    private val container = (application as? DownloadVideosApplication)?.container
        ?: com.example.di.AppContainer(application)
    private val appSettings = AppSettings.getInstance(application)

    private val _statusMessage = MutableStateFlow<String?>(null)
    private val _isUpdating = MutableStateFlow(false)
    private val _ytDlpVersion = MutableStateFlow(container.ytDlpMediaEngine.getVersion(application))
    private val _cacheSize = MutableStateFlow(
        CleanupManager.formatFileSize(CleanupManager.getCacheSizeBytes(application))
    )
    private val _showLogsDialog = MutableStateFlow(false)
    val showLogsDialog: StateFlow<Boolean> = _showLogsDialog.asStateFlow()

    private val _showCookiesDialog = MutableStateFlow(false)
    val showCookiesDialog: StateFlow<Boolean> = _showCookiesDialog.asStateFlow()

    private val _recentLogs = MutableStateFlow<List<String>>(emptyList())
    val recentLogs: StateFlow<List<String>> = _recentLogs.asStateFlow()

    private val prefsFlow1 = combine(
        appSettings.concurrentDownloads,
        appSettings.autoRetry,
        appSettings.showNotifications,
        appSettings.themeMode
    ) { concurrent, retry, notifs, theme ->
        listOf(concurrent, retry, notifs, theme)
    }

    private val prefsFlow2 = combine(
        appSettings.languageCode,
        appSettings.cookiesContent,
        appSettings.autoCheckEngineUpdates,
        appSettings.defaultSubtitles,
        appSettings.defaultSubtitleLang
    ) { lang, cookies, autoUpdate, defaultSubs, subLang ->
        listOf(lang, cookies, autoUpdate, defaultSubs, subLang)
    }

    val uiState: StateFlow<SettingsUiState> = combine(
        prefsFlow1,
        prefsFlow2,
        _cacheSize,
        _statusMessage,
        combine(_ytDlpVersion, _isUpdating) { v, u -> Pair(v, u) }
    ) { p1, p2, cache, message, enginePair ->
        val (version, updating) = enginePair
        SettingsUiState(
            ffmpegStatus = container.ffmpegManager.getStatus(),
            ytDlpVersion = version,
            isUpdatingYtDlp = updating,
            primaryAbi = container.ffmpegManager.primaryAbi,
            concurrentDownloads = p1[0] as Int,
            autoRetry = p1[1] as Boolean,
            showNotifications = p1[2] as Boolean,
            themeMode = p1[3] as ThemeMode,
            languageCode = p2[0] as String,
            cookiesContent = p2[1] as String,
            autoUpdateYtDlp = p2[2] as Boolean,
            defaultSubtitles = p2[3] as Boolean,
            defaultSubtitleLang = p2[4] as String,
            cacheSize = cache,
            statusMessage = message
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SettingsUiState(
            ffmpegStatus = container.ffmpegManager.getStatus(),
            ytDlpVersion = container.ytDlpMediaEngine.getVersion(application),
            primaryAbi = container.ffmpegManager.primaryAbi
        )
    )

    fun updateYtDlp() {
        if (_isUpdating.value) return
        _isUpdating.value = true
        _statusMessage.value = "Checking for yt-dlp update..."
        viewModelScope.launch {
            val result = container.ytDlpMediaEngine.updateEngine(app)
            result.fold(
                onSuccess = { status ->
                    _ytDlpVersion.value = container.ytDlpMediaEngine.getVersion(app)
                    _statusMessage.value = "yt-dlp update result: $status"
                },
                onFailure = { e ->
                    YtDlpLogger.logError("Settings", "Failed to update yt-dlp engine", e)
                    _statusMessage.value = "yt-dlp update failed: ${e.localizedMessage ?: "Check network connection"}"
                }
            )
            _isUpdating.value = false
        }
    }

    fun setConcurrentDownloads(limit: Int) {
        appSettings.setConcurrentDownloads(limit)
    }

    fun setAutoRetry(enabled: Boolean) {
        appSettings.setAutoRetry(enabled)
    }

    fun setShowNotifications(enabled: Boolean) {
        appSettings.setShowNotifications(enabled)
    }

    fun setThemeMode(mode: ThemeMode) {
        appSettings.setThemeMode(mode)
    }

    fun setLanguageCode(code: String) {
        appSettings.setLanguageCode(code)
    }

    fun setAutoUpdateYtDlp(enabled: Boolean) {
        appSettings.setAutoCheckEngineUpdates(enabled)
    }

    fun setDefaultSubtitles(enabled: Boolean) {
        appSettings.setDefaultSubtitles(enabled)
    }

    fun setDefaultSubtitleLang(lang: String) {
        appSettings.setDefaultSubtitleLang(lang)
    }

    fun saveCookies(content: String) {
        appSettings.setCookiesContent(content)
        _statusMessage.value = if (content.isNotBlank()) "Cookies saved successfully" else "Cookies cleared"
        _showCookiesDialog.value = false
    }

    fun openCookiesDialog() {
        _showCookiesDialog.value = true
    }

    fun closeCookiesDialog() {
        _showCookiesDialog.value = false
    }

    fun refreshStatus() {
        _cacheSize.value = CleanupManager.formatFileSize(CleanupManager.getCacheSizeBytes(app))
    }

    fun cleanTempStorage() {
        viewModelScope.launch {
            val freedBytes = CleanupManager.cleanupTempFiles(app)
            val freedText = CleanupManager.formatFileSize(freedBytes)
            _cacheSize.value = CleanupManager.formatFileSize(CleanupManager.getCacheSizeBytes(app))
            _statusMessage.value = "Freed $freedText of cache space"
        }
    }

    fun openLogs() {
        val rawLogs = YtDlpLogger.getRecentLogs()
        _recentLogs.value = rawLogs.map { "[${it.tag}] ${it.message}" }
        _showLogsDialog.value = true
    }

    fun closeLogs() {
        _showLogsDialog.value = false
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }
}
