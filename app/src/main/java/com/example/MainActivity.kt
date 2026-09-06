package com.example

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.content.ContextCompat
import com.example.data.settings.ThemeMode
import com.example.ui.MainScreen
import com.example.ui.downloads.DownloadsViewModel
import com.example.ui.home.HomeViewModel
import com.example.ui.settings.SettingsViewModel
import com.example.ui.theme.VideoDownloaderTheme
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val homeViewModel: HomeViewModel by viewModels()
    private val downloadsViewModel: DownloadsViewModel by viewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()

    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            // Notification permission result handled
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        requestNotificationPermissionIfNeeded()
        handleSharedIntent(intent)

        setContent {
            val settingsState by settingsViewModel.uiState.collectAsState()
            val isDarkTheme = when (settingsState.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }

            val layoutDirection = when (settingsState.languageCode) {
                "ar" -> LayoutDirection.Rtl
                "en" -> LayoutDirection.Ltr
                else -> if (Locale.getDefault().language == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr
            }

            val localizedContext = remember(settingsState.languageCode) {
                getLocalizedContext(this, settingsState.languageCode)
            }

            CompositionLocalProvider(
                LocalContext provides localizedContext,
                LocalLayoutDirection provides layoutDirection
            ) {
                VideoDownloaderTheme(darkTheme = isDarkTheme) {
                    MainScreen(
                        homeViewModel = homeViewModel,
                        downloadsViewModel = downloadsViewModel,
                        settingsViewModel = settingsViewModel
                    )
                }
            }
        }
    }

    private fun getLocalizedContext(baseContext: Context, languageCode: String): Context {
        val locale = when (languageCode) {
            "ar" -> Locale.forLanguageTag("ar")
            "en" -> Locale.forLanguageTag("en")
            else -> Locale.getDefault()
        }
        Locale.setDefault(locale)
        val config = Configuration(baseContext.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return baseContext.createConfigurationContext(config)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSharedIntent(intent)
    }

    private fun handleSharedIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true) {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            if (!sharedText.isNullOrBlank()) {
                val url = extractUrl(sharedText)
                if (url.isNotBlank()) {
                    homeViewModel.onUrlChange(url)
                    homeViewModel.analyzeUrl()
                }
            }
        }
    }

    private fun extractUrl(text: String): String {
        val urlRegex = Regex("""(https?://[^\s]+)""")
        val match = urlRegex.find(text)
        return match?.value?.trim() ?: text.trim()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
