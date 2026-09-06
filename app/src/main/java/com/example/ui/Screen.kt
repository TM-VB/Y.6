package com.example.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Type-safe navigation screen destinations.
 */
sealed class Screen(val route: String, val titleRes: Int, val icon: ImageVector) {
    data object Home : Screen("home", com.example.R.string.tab_home, Icons.Default.Home)
    data object Downloads : Screen("downloads", com.example.R.string.tab_downloads, Icons.Default.Download)
    data object History : Screen("history", com.example.R.string.tab_history, Icons.Default.History)
    data object Settings : Screen("settings", com.example.R.string.tab_settings, Icons.Default.Settings)
}
