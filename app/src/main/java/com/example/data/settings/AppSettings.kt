package com.example.data.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

class AppSettings private constructor(context: Context) {

    private val appContext: Context = context.applicationContext
    private val prefs: SharedPreferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _concurrentDownloads = MutableStateFlow(
        prefs.getInt(KEY_CONCURRENT_DOWNLOADS, DEFAULT_CONCURRENT_DOWNLOADS).coerceIn(1, 3)
    )
    val concurrentDownloads: StateFlow<Int> = _concurrentDownloads.asStateFlow()

    private val _autoRetry = MutableStateFlow(
        prefs.getBoolean(KEY_AUTO_RETRY, DEFAULT_AUTO_RETRY)
    )
    val autoRetry: StateFlow<Boolean> = _autoRetry.asStateFlow()

    private val _showNotifications = MutableStateFlow(
        prefs.getBoolean(KEY_SHOW_NOTIFICATIONS, DEFAULT_SHOW_NOTIFICATIONS)
    )
    val showNotifications: StateFlow<Boolean> = _showNotifications.asStateFlow()

    private val _themeMode = MutableStateFlow(
        try {
            ThemeMode.valueOf(prefs.getString(KEY_THEME_MODE, ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name)
        } catch (_: Exception) {
            ThemeMode.SYSTEM
        }
    )
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _languageCode = MutableStateFlow(
        prefs.getString(KEY_LANGUAGE_CODE, "system") ?: "system"
    )
    val languageCode: StateFlow<String> = _languageCode.asStateFlow()

    private val _cookiesContent = MutableStateFlow(
        CookieSecurityManager.getCookies(appContext)
    )
    val cookiesContent: StateFlow<String> = _cookiesContent.asStateFlow()

    private val _autoCheckEngineUpdates = MutableStateFlow(
        prefs.getBoolean(KEY_AUTO_CHECK_UPDATES, true)
    )
    val autoCheckEngineUpdates: StateFlow<Boolean> = _autoCheckEngineUpdates.asStateFlow()

    private val _defaultSubtitles = MutableStateFlow(
        prefs.getBoolean(KEY_DEFAULT_SUBTITLES, false)
    )
    val defaultSubtitles: StateFlow<Boolean> = _defaultSubtitles.asStateFlow()

    private val _defaultSubtitleLang = MutableStateFlow(
        prefs.getString(KEY_DEFAULT_SUBTITLE_LANG, "ar") ?: "ar"
    )
    val defaultSubtitleLang: StateFlow<String> = _defaultSubtitleLang.asStateFlow()

    fun setCookiesContent(content: String): Boolean {
        val success = CookieSecurityManager.saveCookies(appContext, content)
        if (success) {
            _cookiesContent.value = CookieSecurityManager.getCookies(appContext)
        }
        return success
    }

    fun clearCookies() {
        CookieSecurityManager.clearCookies(appContext)
        _cookiesContent.value = ""
    }

    fun setAutoCheckEngineUpdates(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_AUTO_CHECK_UPDATES, enabled) }
        _autoCheckEngineUpdates.value = enabled
    }

    fun setDefaultSubtitles(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_DEFAULT_SUBTITLES, enabled) }
        _defaultSubtitles.value = enabled
    }

    fun setDefaultSubtitleLang(lang: String) {
        prefs.edit { putString(KEY_DEFAULT_SUBTITLE_LANG, lang) }
        _defaultSubtitleLang.value = lang
    }

    fun setConcurrentDownloads(limit: Int) {
        val safeLimit = limit.coerceIn(1, 3)
        prefs.edit { putInt(KEY_CONCURRENT_DOWNLOADS, safeLimit) }
        _concurrentDownloads.value = safeLimit
    }

    fun setAutoRetry(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_AUTO_RETRY, enabled) }
        _autoRetry.value = enabled
    }

    fun setShowNotifications(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_SHOW_NOTIFICATIONS, enabled) }
        _showNotifications.value = enabled
    }

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit { putString(KEY_THEME_MODE, mode.name) }
        _themeMode.value = mode
    }

    fun setLanguageCode(code: String) {
        val safeCode = when (code) {
            "ar", "en" -> code
            else -> "system"
        }
        prefs.edit { putString(KEY_LANGUAGE_CODE, safeCode) }
        _languageCode.value = safeCode
    }

    companion object {
        private const val PREFS_NAME = "download_videos_settings"
        private const val KEY_CONCURRENT_DOWNLOADS = "key_concurrent_downloads"
        private const val KEY_AUTO_RETRY = "key_auto_retry"
        private const val KEY_SHOW_NOTIFICATIONS = "key_show_notifications"
        private const val KEY_THEME_MODE = "key_theme_mode"
        private const val KEY_LANGUAGE_CODE = "key_language_code"
        private const val KEY_AUTO_CHECK_UPDATES = "key_auto_check_updates"
        private const val KEY_DEFAULT_SUBTITLES = "key_default_subtitles"
        private const val KEY_DEFAULT_SUBTITLE_LANG = "key_default_subtitle_lang"

        const val DEFAULT_CONCURRENT_DOWNLOADS = 1
        const val DEFAULT_AUTO_RETRY = true
        const val DEFAULT_SHOW_NOTIFICATIONS = true

        @Volatile
        private var INSTANCE: AppSettings? = null

        fun getInstance(context: Context): AppSettings {
            return INSTANCE ?: synchronized(this) {
                val instance = AppSettings(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }
}
