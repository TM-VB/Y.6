package com.example.ui.downloads

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownloadOff
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.R
import com.example.data.local.DownloadTaskEntity
import com.example.domain.model.DownloadStatus
import com.example.downloader.network.NetworkState
import com.example.ui.components.StatusBadge
import com.example.ui.theme.ErrorRed

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    viewModel: DownloadsViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val tasks by viewModel.tasks.collectAsState()
    val selectedFilter by viewModel.selectedFilter.collectAsState()
    val selectedDetailTask by viewModel.selectedDetailTask.collectAsState()
    val selectedTaskIds by viewModel.selectedTaskIds.collectAsState()
    val isSelectionMode by viewModel.isSelectionMode.collectAsState()
    val networkState by viewModel.networkState.collectAsState()

    var showClearHistoryDialog by remember { mutableStateOf(false) }
    var taskToPlay by remember { mutableStateOf<DownloadTaskEntity?>(null) }

    Scaffold(
        topBar = {
            if (isSelectionMode) {
                TopAppBar(
                    title = {
                        Text(
                            text = "${selectedTaskIds.size} selected",
                            fontWeight = FontWeight.Bold
                        )
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = { viewModel.clearSelection() },
                            modifier = Modifier.testTag("btn_close_selection")
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Close selection")
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { viewModel.selectAllVisible() },
                            modifier = Modifier.testTag("btn_select_all")
                        ) {
                            Icon(Icons.Default.SelectAll, contentDescription = stringResource(id = R.string.btn_select_all))
                        }
                        IconButton(
                            onClick = { viewModel.bulkRetrySelected() },
                            modifier = Modifier.testTag("btn_bulk_retry")
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(id = R.string.btn_bulk_retry))
                        }
                        IconButton(
                            onClick = { viewModel.bulkCancelSelected() },
                            modifier = Modifier.testTag("btn_bulk_cancel")
                        ) {
                            Icon(Icons.Default.Pause, contentDescription = stringResource(id = R.string.btn_bulk_cancel))
                        }
                        IconButton(
                            onClick = { viewModel.bulkDeleteSelected() },
                            modifier = Modifier.testTag("btn_bulk_delete")
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(id = R.string.btn_bulk_delete), tint = ErrorRed)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )
            } else {
                TopAppBar(
                    title = {
                        Text(
                            text = stringResource(id = R.string.screen_downloads_title),
                            fontWeight = FontWeight.Bold
                        )
                    },
                    actions = {
                        if (tasks.any { it.status == DownloadStatus.COMPLETED || it.status == DownloadStatus.FAILED || it.status == DownloadStatus.CANCELLED }) {
                            IconButton(
                                onClick = { showClearHistoryDialog = true },
                                modifier = Modifier.testTag("btn_clear_finished")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ClearAll,
                                    contentDescription = stringResource(id = R.string.btn_clear_history)
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            // Offline Warning Banner
            if (networkState !is NetworkState.Online) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.WifiOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = stringResource(id = R.string.offline_waiting),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            // Filter Chips Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedFilter == DownloadFilter.ALL,
                    onClick = { viewModel.setFilter(DownloadFilter.ALL) },
                    label = { Text(stringResource(id = R.string.filter_all)) },
                    modifier = Modifier.testTag("filter_all")
                )
                FilterChip(
                    selected = selectedFilter == DownloadFilter.ACTIVE,
                    onClick = { viewModel.setFilter(DownloadFilter.ACTIVE) },
                    label = { Text(stringResource(id = R.string.filter_active)) },
                    modifier = Modifier.testTag("filter_active")
                )
                FilterChip(
                    selected = selectedFilter == DownloadFilter.COMPLETED,
                    onClick = { viewModel.setFilter(DownloadFilter.COMPLETED) },
                    label = { Text(stringResource(id = R.string.filter_completed)) },
                    modifier = Modifier.testTag("filter_completed")
                )
                FilterChip(
                    selected = selectedFilter == DownloadFilter.FAILED,
                    onClick = { viewModel.setFilter(DownloadFilter.FAILED) },
                    label = { Text(stringResource(id = R.string.filter_failed)) },
                    modifier = Modifier.testTag("filter_failed")
                )
            }

            if (tasks.isEmpty()) {
                EmptyDownloadsView(filter = selectedFilter)
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("downloads_list"),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(
                        items = tasks,
                        key = { it.id }
                    ) { task ->
                        val isSelected = selectedTaskIds.contains(task.id)
                        DownloadTaskCard(
                            task = task,
                            isSelected = isSelected,
                            isSelectionMode = isSelectionMode,
                            onClick = {
                                if (isSelectionMode) {
                                    viewModel.toggleTaskSelection(task.id)
                                } else {
                                    viewModel.selectTaskForDetails(task)
                                }
                            },
                            onLongClick = {
                                viewModel.toggleTaskSelection(task.id)
                            },
                            onPause = { viewModel.pause(task.id) },
                            onResume = { viewModel.resume(task.id) },
                            onCancel = { viewModel.cancel(task.id) },
                            onRetry = { viewModel.retry(task.id) },
                            onDelete = { viewModel.delete(task.id) },
                            onMoveUp = { viewModel.moveUp(task.id) },
                            onMoveDown = { viewModel.moveDown(task.id) },
                            onOpen = { taskToPlay = task },
                            onShare = { viewModel.shareDownloadedFile(context, task) }
                        )
                    }
                }
            }
        }
    }

    // Task Details Dialog
    selectedDetailTask?.let { task ->
        DownloadDetailsDialog(
            task = task,
            onDismiss = { viewModel.selectTaskForDetails(null) },
            onPause = { viewModel.pause(it) },
            onResume = { viewModel.resume(it) },
            onRetry = { viewModel.retry(it) },
            onCancel = { viewModel.cancel(it) },
            onDelete = { viewModel.delete(it) },
            onOpen = { taskToPlay = task },
            onShare = { viewModel.shareDownloadedFile(context, it) }
        )
    }

    // In-App Media Player Dialog
    taskToPlay?.let { task ->
        androidx.compose.runtime.key(task.id) {
            com.example.ui.components.InAppMediaPlayerDialog(
                title = task.title,
                mediaPath = task.filePath,
                contentUri = task.contentUri,
                onDismiss = { taskToPlay = null }
            )
        }
    }

    // Clear History Dialog
    if (showClearHistoryDialog) {
        AlertDialog(
            onDismissRequest = { showClearHistoryDialog = false },
            title = { Text(stringResource(id = R.string.clear_history_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(id = R.string.clear_history_msg)) },
            confirmButton = {
                FilledTonalButton(
                    onClick = {
                        viewModel.clearFinished(deleteFiles = true)
                        showClearHistoryDialog = false
                    },
                    modifier = Modifier.testTag("btn_clear_history_and_files")
                ) {
                    Text(stringResource(id = R.string.clear_history_and_files))
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        viewModel.clearFinished(deleteFiles = false)
                        showClearHistoryDialog = false
                    },
                    modifier = Modifier.testTag("btn_clear_history_only")
                ) {
                    Text(stringResource(id = R.string.clear_history_only))
                }
            }
        )
    }
}

@Composable
fun DownloadTaskCard(
    task: DownloadTaskEntity,
    isSelected: Boolean = false,
    isSelectionMode: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit = {},
    onMoveDown: () -> Unit = {},
    onOpen: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .testTag("task_card_${task.id}"),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            }
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isSelectionMode) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onClick() },
                        modifier = Modifier.size(24.dp)
                    )
                }

                // Thumbnail or media type icon
                Box(
                    modifier = Modifier
                        .size(width = 80.dp, height = 56.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (!task.thumbnailUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(task.thumbnailUrl)
                                .crossfade(true)
                                .build(),
                            contentDescription = task.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = if (task.isAudioOnly) Icons.Default.Audiotrack else Icons.Default.Movie,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                }

                // Info: Title & Format description
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = task.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = task.formatDescription,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!task.startTime.isNullOrBlank() && !task.endTime.isNullOrBlank()) {
                        Text(
                            text = "${task.startTime} - ${task.endTime} (${task.cutMode})",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // Status Badge
                StatusBadge(status = task.status)
            }

            // Progress Bar if in active state
            if (task.status == DownloadStatus.DOWNLOADING ||
                task.status == DownloadStatus.PROCESSING_FFMPEG ||
                task.status == DownloadStatus.PAUSED ||
                task.status == DownloadStatus.PREPARING
            ) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { (task.progress / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val progressLabel = when {
                        task.downloadedSize.isNotBlank() && task.totalSize.isNotBlank() ->
                            "${task.progress.toInt()}% (${task.downloadedSize} / ${task.totalSize})"
                        task.downloadedSize.isNotBlank() ->
                            "${task.progress.toInt()}% (${task.downloadedSize})"
                        else ->
                            "${task.progress.toInt()}%"
                    }
                    Text(
                        text = progressLabel,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold
                    )
                    if (task.downloadSpeed.isNotBlank()) {
                        Text(
                            text = task.downloadSpeed,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    if (task.eta.isNotBlank()) {
                        Text(
                            text = "ETA: ${task.eta}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Action Buttons Row
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Queue Reordering arrows for queued items
                if (task.status == DownloadStatus.QUEUED) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        IconButton(
                            onClick = onMoveUp,
                            modifier = Modifier.size(32.dp).testTag("btn_move_up_${task.id}")
                        ) {
                            Icon(Icons.Default.KeyboardArrowUp, contentDescription = stringResource(id = R.string.btn_move_up), modifier = Modifier.size(16.dp))
                        }
                        IconButton(
                            onClick = onMoveDown,
                            modifier = Modifier.size(32.dp).testTag("btn_move_down_${task.id}")
                        ) {
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(id = R.string.btn_move_down), modifier = Modifier.size(16.dp))
                        }
                    }
                } else {
                    Spacer(modifier = Modifier.width(1.dp))
                }

                Row(
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    when (task.status) {
                        DownloadStatus.DOWNLOADING, DownloadStatus.PROCESSING_FFMPEG, DownloadStatus.PREPARING -> {
                            FilledTonalIconButton(
                                onClick = onPause,
                                modifier = Modifier
                                    .size(36.dp)
                                    .testTag("btn_pause_${task.id}")
                            ) {
                                Icon(Icons.Default.Pause, contentDescription = stringResource(id = R.string.btn_pause), modifier = Modifier.size(18.dp))
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            OutlinedIconButton(
                                onClick = onCancel,
                                modifier = Modifier
                                    .size(36.dp)
                                    .testTag("btn_cancel_${task.id}")
                            ) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(id = R.string.btn_cancel), tint = ErrorRed, modifier = Modifier.size(18.dp))
                            }
                        }
                        DownloadStatus.QUEUED -> {
                            OutlinedIconButton(
                                onClick = onCancel,
                                modifier = Modifier
                                    .size(36.dp)
                                    .testTag("btn_cancel_${task.id}")
                            ) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(id = R.string.btn_cancel), tint = ErrorRed, modifier = Modifier.size(18.dp))
                            }
                        }
                        DownloadStatus.PAUSED, DownloadStatus.INTERRUPTED -> {
                            FilledTonalIconButton(
                                onClick = onResume,
                                modifier = Modifier
                                    .size(36.dp)
                                    .testTag("btn_resume_${task.id}")
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = stringResource(id = R.string.btn_resume), modifier = Modifier.size(18.dp))
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            OutlinedIconButton(
                                onClick = onDelete,
                                modifier = Modifier
                                    .size(36.dp)
                                    .testTag("btn_delete_${task.id}")
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = stringResource(id = R.string.btn_delete), tint = ErrorRed, modifier = Modifier.size(18.dp))
                            }
                        }
                        DownloadStatus.COMPLETED -> {
                            FilledTonalIconButton(
                                onClick = onOpen,
                                modifier = Modifier
                                    .size(36.dp)
                                    .testTag("btn_open_${task.id}")
                            ) {
                                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = stringResource(id = R.string.btn_open), modifier = Modifier.size(18.dp))
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            IconButton(
                                onClick = onShare,
                                modifier = Modifier
                                    .size(36.dp)
                                    .testTag("btn_share_${task.id}")
                            ) {
                                Icon(Icons.Default.Share, contentDescription = stringResource(id = R.string.btn_share), modifier = Modifier.size(18.dp))
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            IconButton(
                                onClick = onDelete,
                                modifier = Modifier
                                    .size(36.dp)
                                    .testTag("btn_delete_${task.id}")
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = stringResource(id = R.string.btn_delete), modifier = Modifier.size(18.dp))
                            }
                        }
                        DownloadStatus.FAILED, DownloadStatus.CANCELLED -> {
                            FilledTonalIconButton(
                                onClick = onRetry,
                                modifier = Modifier
                                    .size(36.dp)
                                    .testTag("btn_retry_${task.id}")
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = stringResource(id = R.string.btn_retry), modifier = Modifier.size(18.dp))
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            IconButton(
                                onClick = onDelete,
                                modifier = Modifier
                                    .size(36.dp)
                                    .testTag("btn_delete_${task.id}")
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = stringResource(id = R.string.btn_delete), tint = ErrorRed, modifier = Modifier.size(18.dp))
                            }
                        }
                        else -> {}
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyDownloadsView(filter: DownloadFilter) {
    val messageRes = when (filter) {
        DownloadFilter.ACTIVE -> R.string.no_active_downloads
        DownloadFilter.COMPLETED -> R.string.no_completed_downloads
        DownloadFilter.FAILED -> R.string.no_failed_downloads
        DownloadFilter.ALL -> R.string.no_downloads
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.FileDownloadOff,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Text(
                text = stringResource(id = messageRes),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
