package com.example.ui.downloads

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.AppDatabase
import com.example.data.local.DownloadTaskEntity
import com.example.data.repository.DownloadRepository
import com.example.domain.model.DownloadStatus
import com.example.downloader.DownloadManager
import com.example.downloader.network.NetworkMonitor
import com.example.downloader.network.NetworkState
import com.example.storage.MediaStoreHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class DownloadFilter {
    ALL,
    ACTIVE,
    COMPLETED,
    FAILED
}

enum class HistorySortOption {
    NEWEST,
    OLDEST,
    NAME,
    SIZE
}

class DownloadsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = DownloadRepository(AppDatabase.getInstance(application).downloadTaskDao())
    private val downloadManager = DownloadManager.getInstance(application)
    private val networkMonitor = NetworkMonitor(application)

    val networkState: StateFlow<NetworkState> = networkMonitor.networkState

    private val _selectedFilter = MutableStateFlow(DownloadFilter.ALL)
    val selectedFilter: StateFlow<DownloadFilter> = _selectedFilter.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _sortOption = MutableStateFlow(HistorySortOption.NEWEST)
    val sortOption: StateFlow<HistorySortOption> = _sortOption.asStateFlow()

    private val _selectedDetailTask = MutableStateFlow<DownloadTaskEntity?>(null)
    val selectedDetailTask: StateFlow<DownloadTaskEntity?> = _selectedDetailTask.asStateFlow()

    // Multi-selection for bulk actions
    private val _selectedTaskIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedTaskIds: StateFlow<Set<String>> = _selectedTaskIds.asStateFlow()

    val isSelectionMode: StateFlow<Boolean> = combine(_selectedTaskIds) { selected ->
        selected.first().isNotEmpty()
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = false
    )

    val tasks: StateFlow<List<DownloadTaskEntity>> = combine(
        repository.allTasks,
        _selectedFilter
    ) { allTasks, filter ->
        when (filter) {
            DownloadFilter.ALL -> allTasks
            DownloadFilter.ACTIVE -> allTasks.filter {
                it.status == DownloadStatus.DOWNLOADING ||
                        it.status == DownloadStatus.QUEUED ||
                        it.status == DownloadStatus.PREPARING ||
                        it.status == DownloadStatus.PROCESSING_FFMPEG ||
                        it.status == DownloadStatus.PAUSED
            }
            DownloadFilter.COMPLETED -> allTasks.filter { it.status == DownloadStatus.COMPLETED }
            DownloadFilter.FAILED -> allTasks.filter {
                it.status == DownloadStatus.FAILED ||
                        it.status == DownloadStatus.CANCELLED ||
                        it.status == DownloadStatus.INTERRUPTED
            }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val activeDownloads: StateFlow<List<DownloadTaskEntity>> = repository.allTasks.combine(MutableStateFlow(Unit)) { allTasks, _ ->
        allTasks.filter {
            it.status == DownloadStatus.DOWNLOADING ||
                    it.status == DownloadStatus.QUEUED ||
                    it.status == DownloadStatus.PREPARING ||
                    it.status == DownloadStatus.PROCESSING_FFMPEG ||
                    it.status == DownloadStatus.PAUSED ||
                    it.status == DownloadStatus.INTERRUPTED
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val historyTasks: StateFlow<List<DownloadTaskEntity>> = combine(
        repository.allTasks,
        _searchQuery,
        _sortOption
    ) { allTasks, query, sort ->
        val finished = allTasks.filter {
            it.status == DownloadStatus.COMPLETED ||
                    it.status == DownloadStatus.FAILED ||
                    it.status == DownloadStatus.CANCELLED
        }

        val filtered = if (query.isBlank()) {
            finished
        } else {
            val q = query.trim().lowercase()
            finished.filter {
                it.title.lowercase().contains(q) ||
                        it.formatDescription.lowercase().contains(q) ||
                        it.url.lowercase().contains(q)
            }
        }

        when (sort) {
            HistorySortOption.NEWEST -> filtered.sortedByDescending { it.completedAt ?: it.createdAt }
            HistorySortOption.OLDEST -> filtered.sortedBy { it.completedAt ?: it.createdAt }
            HistorySortOption.NAME -> filtered.sortedBy { it.title.lowercase() }
            HistorySortOption.SIZE -> filtered.sortedByDescending { it.totalSize.ifBlank { it.downloadedSize } }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val activeCount: StateFlow<Int> = repository.allTasks.combine(_selectedFilter) { allTasks, _ ->
        allTasks.count {
            it.status == DownloadStatus.DOWNLOADING ||
                    it.status == DownloadStatus.QUEUED ||
                    it.status == DownloadStatus.PREPARING ||
                    it.status == DownloadStatus.PROCESSING_FFMPEG ||
                    it.status == DownloadStatus.PAUSED
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = 0
    )

    fun setFilter(filter: DownloadFilter) {
        _selectedFilter.update { filter }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setSortOption(sort: HistorySortOption) {
        _sortOption.value = sort
    }

    fun selectTaskForDetails(task: DownloadTaskEntity?) {
        _selectedDetailTask.update { task }
    }

    fun toggleTaskSelection(taskId: String) {
        _selectedTaskIds.update { current ->
            if (current.contains(taskId)) {
                current - taskId
            } else {
                current + taskId
            }
        }
    }

    fun selectAllVisible() {
        val visibleIds = tasks.value.map { it.id }.toSet()
        _selectedTaskIds.value = visibleIds
    }

    fun clearSelection() {
        _selectedTaskIds.value = emptySet()
    }

    fun pause(taskId: String) {
        downloadManager.pauseDownload(taskId)
    }

    fun resume(taskId: String) {
        downloadManager.resumeDownload(taskId)
    }

    fun cancel(taskId: String) {
        downloadManager.cancelDownload(taskId)
    }

    fun retry(taskId: String) {
        downloadManager.retryDownload(taskId)
    }

    fun delete(taskId: String) {
        downloadManager.deleteDownload(taskId)
        if (_selectedDetailTask.value?.id == taskId) {
            _selectedDetailTask.value = null
        }
        _selectedTaskIds.update { it - taskId }
    }

    fun moveUp(taskId: String) {
        downloadManager.moveTaskUp(taskId)
    }

    fun moveDown(taskId: String) {
        downloadManager.moveTaskDown(taskId)
    }

    fun bulkCancelSelected() {
        val ids = _selectedTaskIds.value.toList()
        downloadManager.bulkCancel(ids)
        clearSelection()
    }

    fun bulkRetrySelected() {
        val ids = _selectedTaskIds.value.toList()
        downloadManager.bulkRetry(ids)
        clearSelection()
    }

    fun bulkDeleteSelected() {
        val ids = _selectedTaskIds.value.toList()
        downloadManager.bulkDelete(ids)
        clearSelection()
    }

    fun clearFinished(deleteFiles: Boolean = false) {
        downloadManager.clearFinished(deleteFiles)
    }

    fun openDownloadedFile(context: Context, task: DownloadTaskEntity) {
        MediaStoreHelper.openFile(context, task.filePath, task.contentUri)
    }

    fun shareDownloadedFile(context: Context, task: DownloadTaskEntity) {
        MediaStoreHelper.shareFile(context, task.filePath, task.contentUri)
    }
}
