package com.example.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.DownloadVideosApplication
import com.example.data.local.DownloadTaskEntity
import com.example.domain.model.CutSettings
import com.example.domain.model.DownloadError
import com.example.domain.model.DownloadRequest
import com.example.domain.model.FormatInfo
import com.example.domain.model.PlaylistEntry
import com.example.domain.model.PlaylistInfo
import com.example.domain.model.TimeRange
import com.example.ytdlp.FormatSelection
import com.example.ytdlp.SimpleQualityPreset
import com.example.ytdlp.SmartFormatEngine
import com.example.ytdlp.YtDlpErrorMapper
import com.example.ytdlp.YtDlpLogger
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel for Home screen state management.
 * Coordinates video extraction via yt-dlp, format categorization, smart pairing, and quality presets.
 */
class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as? DownloadVideosApplication)?.container
        ?: com.example.di.AppContainer(application)

    private val _urlInput = MutableStateFlow("")
    val urlInput: StateFlow<String> = _urlInput.asStateFlow()

    private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.Idle)
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _duplicateWarningTask = MutableStateFlow<DownloadTaskEntity?>(null)
    val duplicateWarningTask: StateFlow<DownloadTaskEntity?> = _duplicateWarningTask.asStateFlow()

    private var analysisJob: Job? = null
    private var currentProcessId: String? = null

    val recentTasks: StateFlow<List<DownloadTaskEntity>> =
        container.downloadRepository.allTasks
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun onUrlChange(newUrl: String) {
        _urlInput.value = newUrl
        if (_uiState.value is HomeUiState.Error) {
            _uiState.value = HomeUiState.Idle
        }
    }

    fun analyzeUrl() {
        val url = _urlInput.value.trim()
        if (url.isEmpty()) {
            _uiState.value = HomeUiState.Error(
                DownloadError.InvalidUrl("Please enter a valid video URL", "URL cannot be empty")
            )
            return
        }

        analysisJob?.cancel()

        val processId = "analyze_${System.currentTimeMillis()}"
        currentProcessId = processId

        analysisJob = viewModelScope.launch {
            _uiState.value = HomeUiState.Analyzing(processId)

            // Check if user entered a playlist link
            if (container.ytDlpMediaEngine.isPlaylistUrl(url)) {
                val playlistRes = container.ytDlpMediaEngine.extractPlaylist(url, processId)
                if (playlistRes.isSuccess) {
                    val playlistInfo = playlistRes.getOrNull()
                    if (playlistInfo != null && playlistInfo.entries.isNotEmpty()) {
                        _uiState.value = HomeUiState.PlaylistReady(
                            playlistInfo = playlistInfo,
                            selectedIds = playlistInfo.entries.map { it.id }.toSet()
                        )
                        return@launch
                    }
                }
            }

            val result = container.ytDlpMediaEngine.extractInfo(url, processId)
            result.fold(
                onSuccess = { videoInfo ->
                    val categorized = SmartFormatEngine.categorize(videoInfo.formats)
                    val availablePresets = SmartFormatEngine.getAvailablePresets(videoInfo.formats)
                    val smartBest = SmartFormatEngine.selectBestQuality(videoInfo.formats)

                    // Choose initial active tab depending on available format types
                    val initialTab = when {
                        categorized.videoAndAudioFormats.isNotEmpty() -> FormatTab.VIDEO_AND_AUDIO
                        categorized.videoOnlyFormats.isNotEmpty() -> FormatTab.VIDEO_ONLY
                        categorized.audioOnlyFormats.isNotEmpty() -> FormatTab.AUDIO_ONLY
                        else -> FormatTab.ALL
                    }

                    _uiState.value = HomeUiState.Ready(
                        videoInfo = videoInfo,
                        categorizedFormats = categorized,
                        selectedFormat = smartBest.videoFormat ?: smartBest.audioFormat,
                        selectedSelection = smartBest,
                        selectionMode = FormatSelectionMode.SIMPLE,
                        activeTab = initialTab,
                        activePreset = QualityPreset.BEST_QUALITY,
                        availablePresets = availablePresets
                    )
                },
                onFailure = { throwable ->
                    val error = if (throwable is DownloadError) {
                        throwable
                    } else {
                        YtDlpErrorMapper.map(throwable)
                    }
                    _uiState.value = HomeUiState.Error(error)
                }
            )
        }
    }

    fun togglePlaylistEntry(id: String) {
        val current = _uiState.value as? HomeUiState.PlaylistReady ?: return
        val currentIds = current.selectedIds.toMutableSet()
        if (currentIds.contains(id)) {
            currentIds.remove(id)
        } else {
            currentIds.add(id)
        }
        _uiState.value = current.copy(selectedIds = currentIds)
    }

    fun selectAllPlaylistEntries() {
        val current = _uiState.value as? HomeUiState.PlaylistReady ?: return
        _uiState.value = current.copy(selectedIds = current.playlistInfo.entries.map { it.id }.toSet())
    }

    fun deselectAllPlaylistEntries() {
        val current = _uiState.value as? HomeUiState.PlaylistReady ?: return
        _uiState.value = current.copy(selectedIds = emptySet())
    }

    fun setPlaylistPreset(preset: QualityPreset) {
        val current = _uiState.value as? HomeUiState.PlaylistReady ?: return
        _uiState.value = current.copy(
            selectedPreset = preset,
            isAudioOnly = (preset == QualityPreset.BEST_AUDIO)
        )
    }

    fun downloadPlaylist(onNavigateToDownloads: () -> Unit) {
        val current = _uiState.value as? HomeUiState.PlaylistReady ?: return
        val selectedEntries = current.playlistInfo.entries.filter { current.selectedIds.contains(it.id) }
        if (selectedEntries.isEmpty()) return

        val formatSelector = when (current.selectedPreset) {
            QualityPreset.BEST_QUALITY -> "bestvideo+bestaudio/best"
            QualityPreset.BEST_VIDEO -> "bestvideo+bestaudio/best"
            QualityPreset.P1080 -> "bestvideo[height<=1080]+bestaudio/best[height<=1080]"
            QualityPreset.P720 -> "bestvideo[height<=720]+bestaudio/best[height<=720]"
            QualityPreset.P480 -> "bestvideo[height<=480]+bestaudio/best[height<=480]"
            QualityPreset.BEST_AUDIO -> "bestaudio/best"
        }

        val requests = selectedEntries.map { entry ->
            DownloadRequest(
                url = entry.url,
                title = entry.title,
                thumbnailUrl = entry.thumbnailUrl,
                formatSelector = formatSelector,
                formatDescription = current.selectedPreset.label,
                isAudioOnly = current.isAudioOnly
            )
        }

        container.downloadManager.enqueueBatch(requests)
        _uiState.value = HomeUiState.Idle
        _urlInput.value = ""
        onNavigateToDownloads()
    }

    fun toggleSubtitles(enabled: Boolean) {
        val current = _uiState.value as? HomeUiState.Ready ?: return
        _uiState.value = current.copy(downloadSubtitles = enabled)
    }

    fun selectSubtitleLanguage(lang: String) {
        val current = _uiState.value as? HomeUiState.Ready ?: return
        _uiState.value = current.copy(selectedSubtitleLang = lang)
    }

    fun cancelAnalysis() {
        val procId = currentProcessId
        analysisJob?.cancel()
        analysisJob = null

        if (procId != null) {
            viewModelScope.launch {
                container.ytDlpMediaEngine.cancel(procId)
            }
        }

        _uiState.value = HomeUiState.Idle
    }

    fun setSelectionMode(mode: FormatSelectionMode) {
        val current = _uiState.value as? HomeUiState.Ready ?: return
        _uiState.value = current.copy(selectionMode = mode)
    }

    fun selectPreset(preset: SimpleQualityPreset, selection: FormatSelection) {
        val current = _uiState.value as? HomeUiState.Ready ?: return
        YtDlpLogger.logFormatSelected(selection.formatSelector, false)
        _uiState.value = current.copy(
            selectedSelection = selection,
            selectedFormat = selection.videoFormat ?: selection.audioFormat,
            activePreset = when (preset) {
                SimpleQualityPreset.BEST_QUALITY -> QualityPreset.BEST_QUALITY
                SimpleQualityPreset.P1080 -> QualityPreset.P1080
                SimpleQualityPreset.P720 -> QualityPreset.P720
                SimpleQualityPreset.P480 -> QualityPreset.P480
                SimpleQualityPreset.BEST_AUDIO -> QualityPreset.BEST_AUDIO
                else -> null
            },
            isManualInputEnabled = false
        )
    }

    fun selectFormat(format: FormatInfo) {
        val current = _uiState.value as? HomeUiState.Ready ?: return
        val smartSelection = SmartFormatEngine.selectFromFormatInfo(current.videoInfo.formats, format)
        YtDlpLogger.logFormatSelected(smartSelection.formatSelector, false)
        _uiState.value = current.copy(
            selectedFormat = format,
            selectedSelection = smartSelection,
            activePreset = null,
            isManualInputEnabled = false
        )
    }

    fun selectTab(tab: FormatTab) {
        val current = _uiState.value as? HomeUiState.Ready ?: return
        _uiState.value = current.copy(activeTab = tab)
    }

    fun selectQualityPreset(preset: QualityPreset) {
        val current = _uiState.value as? HomeUiState.Ready ?: return
        val formats = current.videoInfo.formats

        val simplePreset = when (preset) {
            QualityPreset.BEST_QUALITY -> SimpleQualityPreset.BEST_QUALITY
            QualityPreset.BEST_VIDEO -> SimpleQualityPreset.BEST_QUALITY
            QualityPreset.P1080 -> SimpleQualityPreset.P1080
            QualityPreset.P720 -> SimpleQualityPreset.P720
            QualityPreset.P480 -> SimpleQualityPreset.P480
            QualityPreset.BEST_AUDIO -> SimpleQualityPreset.BEST_AUDIO
        }

        val selection = SmartFormatEngine.selectByPreset(formats, simplePreset)
        val targetTab = when {
            preset == QualityPreset.BEST_AUDIO -> FormatTab.AUDIO_ONLY
            selection.videoFormat?.isVideoAndAudio == true -> FormatTab.VIDEO_AND_AUDIO
            selection.videoFormat?.isVideoOnly == true -> FormatTab.VIDEO_ONLY
            else -> current.activeTab
        }

        YtDlpLogger.logFormatSelected(selection.formatSelector, false)
        _uiState.value = current.copy(
            selectedSelection = selection,
            selectedFormat = selection.videoFormat ?: selection.audioFormat,
            activePreset = preset,
            activeTab = targetTab,
            isManualInputEnabled = false
        )
    }

    fun toggleManualInput(enabled: Boolean) {
        val current = _uiState.value as? HomeUiState.Ready ?: return
        _uiState.value = current.copy(isManualInputEnabled = enabled)
    }

    fun onManualFormatInputChange(input: String) {
        val current = _uiState.value as? HomeUiState.Ready ?: return
        _uiState.value = current.copy(manualFormatInput = input)
    }

    fun applyManualFormatId() {
        val current = _uiState.value as? HomeUiState.Ready ?: return
        val manualId = current.manualFormatInput.trim()
        if (manualId.isEmpty()) return

        val smartSelection = SmartFormatEngine.selectCustom(current.videoInfo.formats, manualId)
        val formatToUse = smartSelection.videoFormat ?: smartSelection.audioFormat ?: FormatInfo(
            formatId = manualId,
            formatNote = "Manual Custom ID",
            extension = "mp4",
            resolution = manualId,
            hasVideo = !manualId.contains("audio"),
            hasAudio = !manualId.contains("video")
        )

        YtDlpLogger.logFormatSelected(manualId, true)
        _uiState.value = current.copy(
            selectedFormat = formatToUse,
            selectedSelection = smartSelection,
            activePreset = null
        )
    }

    fun resetAnalysis() {
        _uiState.value = HomeUiState.Idle
    }

    fun updateCutSettings(cutSettings: CutSettings) {
        val current = _uiState.value as? HomeUiState.Ready ?: return
        _uiState.value = current.copy(cutSettings = cutSettings)
    }

    fun startDownload(onNavigateToDownloads: () -> Unit) {
        val current = _uiState.value as? HomeUiState.Ready ?: return

        val formatSelector = current.effectiveFormatSelector
        val startTime = current.cutSettings.startTime
        val endTime = current.cutSettings.endTime

        viewModelScope.launch {
            val duplicate = container.downloadManager.checkDuplicate(
                url = current.videoInfo.webpageUrl,
                formatId = formatSelector,
                startTime = if (current.cutSettings.enabled) startTime else null,
                endTime = if (current.cutSettings.enabled) endTime else null
            )

            if (duplicate != null && duplicate.status == com.example.domain.model.DownloadStatus.COMPLETED) {
                _duplicateWarningTask.value = duplicate
            } else {
                forceStartDownload(onNavigateToDownloads)
            }
        }
    }

    fun confirmDownloadAgain(onNavigateToDownloads: () -> Unit) {
        _duplicateWarningTask.value = null
        forceStartDownload(onNavigateToDownloads)
    }

    fun dismissDuplicateWarning() {
        _duplicateWarningTask.value = null
    }

    private fun forceStartDownload(onNavigateToDownloads: () -> Unit) {
        val current = _uiState.value as? HomeUiState.Ready ?: return

        val formatSelector = current.effectiveFormatSelector
        val formatDescription = current.effectiveDisplayTitle
        val isAudioOnly = current.isAudioOnly

        val startTime = current.cutSettings.startTime
        val endTime = current.cutSettings.endTime
        val timeRange = if (current.cutSettings.enabled && !startTime.isNullOrBlank() && !endTime.isNullOrBlank()) {
            TimeRange(
                startTime = startTime,
                endTime = endTime,
                cutMode = current.cutSettings.mode
            )
        } else null

        container.downloadManager.startDownload(
            url = current.videoInfo.webpageUrl,
            title = current.videoInfo.title,
            thumbnailUrl = current.videoInfo.thumbnail,
            formatId = formatSelector,
            formatDescription = formatDescription,
            isAudioOnly = isAudioOnly,
            timeRange = timeRange,
            downloadSubtitles = current.downloadSubtitles,
            subtitleLanguage = current.selectedSubtitleLang
        )

        _uiState.value = HomeUiState.Idle
        _urlInput.value = ""
        onNavigateToDownloads()
    }

    fun clearInput() {
        _urlInput.value = ""
        _uiState.value = HomeUiState.Idle
    }
}
