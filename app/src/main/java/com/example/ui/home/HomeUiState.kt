package com.example.ui.home

import com.example.domain.model.CutSettings
import com.example.domain.model.DownloadError
import com.example.domain.model.FormatInfo
import com.example.domain.model.PlaylistInfo
import com.example.domain.model.VideoInfo
import com.example.downloader.engine.CategorizedFormats
import com.example.ytdlp.FormatSelection
import com.example.ytdlp.SimpleQualityPreset

enum class FormatSelectionMode(val label: String) {
    SIMPLE("Simple"),
    ADVANCED("Advanced")
}

enum class FormatTab(val label: String) {
    VIDEO_AND_AUDIO("Video + Audio"),
    VIDEO_ONLY("Video Only"),
    AUDIO_ONLY("Audio Only"),
    ALL("All Formats")
}

enum class QualityPreset(val label: String) {
    BEST_QUALITY("Best Quality"),
    BEST_VIDEO("Best Video"),
    P1080("1080p"),
    P720("720p"),
    P480("480p"),
    BEST_AUDIO("Best Audio")
}

/**
 * UI State for the Home screen following Clean Architecture and MVI state patterns.
 */
sealed interface HomeUiState {
    data object Idle : HomeUiState

    data class Analyzing(val processId: String) : HomeUiState

    data class Ready(
        val videoInfo: VideoInfo,
        val categorizedFormats: CategorizedFormats,
        val selectedFormat: FormatInfo? = null,
        val selectedSelection: FormatSelection? = null,
        val selectionMode: FormatSelectionMode = FormatSelectionMode.SIMPLE,
        val activeTab: FormatTab = FormatTab.VIDEO_AND_AUDIO,
        val activePreset: QualityPreset? = QualityPreset.BEST_QUALITY,
        val isManualInputEnabled: Boolean = false,
        val manualFormatInput: String = "",
        val availablePresets: List<Pair<SimpleQualityPreset, FormatSelection>> = emptyList(),
        val cutSettings: CutSettings = CutSettings(),
        val downloadSubtitles: Boolean = false,
        val selectedSubtitleLang: String = "ar"
    ) : HomeUiState {
        val visibleFormats: List<FormatInfo>
            get() = when (activeTab) {
                FormatTab.VIDEO_AND_AUDIO -> categorizedFormats.videoAndAudioFormats
                FormatTab.VIDEO_ONLY -> categorizedFormats.videoOnlyFormats
                FormatTab.AUDIO_ONLY -> categorizedFormats.audioOnlyFormats
                FormatTab.ALL -> categorizedFormats.allFormats
            }

        val effectiveFormatSelector: String
            get() = selectedSelection?.formatSelector ?: selectedFormat?.formatId ?: "bestvideo+bestaudio/best"

        val effectiveDisplayTitle: String
            get() = selectedSelection?.qualityLabel ?: selectedFormat?.displayTitle ?: "Best Quality"

        val isAudioOnly: Boolean
            get() = selectedSelection?.isAudioOnly == true || selectedFormat?.isAudioOnly == true
    }

    data class PlaylistReady(
        val playlistInfo: PlaylistInfo,
        val selectedIds: Set<String> = emptySet(),
        val selectedPreset: QualityPreset = QualityPreset.BEST_QUALITY,
        val isAudioOnly: Boolean = false
    ) : HomeUiState {
        val selectedCount: Int
            get() = selectedIds.size

        val isAllSelected: Boolean
            get() = playlistInfo.entries.isNotEmpty() && selectedIds.size == playlistInfo.entries.size
    }

    data class Error(val error: DownloadError) : HomeUiState
}
