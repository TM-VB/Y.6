package com.example.ui.history

import android.content.Context
import java.util.Locale
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
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
import com.example.ui.components.StatusBadge
import com.example.ui.downloads.DownloadDetailsDialog
import com.example.ui.downloads.DownloadsViewModel
import com.example.ui.downloads.HistorySortOption
import com.example.ui.theme.ErrorRed

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    viewModel: DownloadsViewModel,
    modifier: Modifier = Modifier,
    onNavigateToHome: () -> Unit = {}
) {
    val context = LocalContext.current
    val tasks by viewModel.historyTasks.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val sortOption by viewModel.sortOption.collectAsState()
    val selectedDetailTask by viewModel.selectedDetailTask.collectAsState()
    val selectedTaskIds by viewModel.selectedTaskIds.collectAsState()
    val isSelectionMode by viewModel.isSelectionMode.collectAsState()

    var showClearHistoryDialog by remember { mutableStateOf(false) }
    var taskToDelete by remember { mutableStateOf<DownloadTaskEntity?>(null) }
    var showSortMenu by remember { mutableStateOf(false) }
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
                            modifier = Modifier.testTag("history_btn_close_selection")
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Close selection")
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { viewModel.selectAllVisible() },
                            modifier = Modifier.testTag("history_btn_select_all")
                        ) {
                            Icon(Icons.Default.SelectAll, contentDescription = stringResource(R.string.btn_select_all))
                        }
                        IconButton(
                            onClick = { viewModel.bulkRetrySelected() },
                            modifier = Modifier.testTag("history_btn_bulk_retry")
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.btn_bulk_retry))
                        }
                        IconButton(
                            onClick = { viewModel.bulkDeleteSelected() },
                            modifier = Modifier.testTag("history_btn_bulk_delete")
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.btn_bulk_delete), tint = ErrorRed)
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
                            text = stringResource(R.string.tab_history),
                            fontWeight = FontWeight.Bold
                        )
                    },
                    actions = {
                        Box {
                            IconButton(
                                onClick = { showSortMenu = true },
                                modifier = Modifier.testTag("history_btn_sort")
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = stringResource(R.string.sort_by))
                            }
                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.sort_newest)) },
                                    onClick = {
                                        viewModel.setSortOption(HistorySortOption.NEWEST)
                                        showSortMenu = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.sort_oldest)) },
                                    onClick = {
                                        viewModel.setSortOption(HistorySortOption.OLDEST)
                                        showSortMenu = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.sort_name)) },
                                    onClick = {
                                        viewModel.setSortOption(HistorySortOption.NAME)
                                        showSortMenu = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.sort_size)) },
                                    onClick = {
                                        viewModel.setSortOption(HistorySortOption.SIZE)
                                        showSortMenu = false
                                    }
                                )
                            }
                        }

                        if (tasks.isNotEmpty()) {
                            IconButton(
                                onClick = { showClearHistoryDialog = true },
                                modifier = Modifier.testTag("history_btn_clear_all")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ClearAll,
                                    contentDescription = stringResource(R.string.btn_clear_history)
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
            // Search Input Field
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.setSearchQuery(it) },
                placeholder = { Text(stringResource(R.string.search_hint)) },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null)
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setSearchQuery("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag("history_search_input")
            )

            // Current Sort Option Indicator
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${tasks.size} items",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                val sortText = when (sortOption) {
                    HistorySortOption.NEWEST -> stringResource(R.string.sort_newest)
                    HistorySortOption.OLDEST -> stringResource(R.string.sort_oldest)
                    HistorySortOption.NAME -> stringResource(R.string.sort_name)
                    HistorySortOption.SIZE -> stringResource(R.string.sort_size)
                }

                Text(
                    text = "${stringResource(R.string.sort_by)}: $sortText",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }

            if (tasks.isEmpty()) {
                EmptyHistoryView(
                    isSearching = searchQuery.isNotBlank(),
                    onNavigateToHome = onNavigateToHome
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(
                        items = tasks,
                        key = { it.id }
                    ) { task ->
                        val isSelected = selectedTaskIds.contains(task.id)

                        HistoryTaskCard(
                            task = task,
                            isSelected = isSelected,
                            isSelectionMode = isSelectionMode,
                            onToggleSelection = { viewModel.toggleTaskSelection(task.id) },
                            onClick = {
                                if (isSelectionMode) {
                                    viewModel.toggleTaskSelection(task.id)
                                } else {
                                    viewModel.selectTaskForDetails(task)
                                }
                            },
                            onOpen = { taskToPlay = task },
                            onShare = { viewModel.shareDownloadedFile(context, task) },
                            onRetry = { viewModel.retry(task.id) },
                            onDelete = { taskToDelete = task },
                            onShowDetails = { viewModel.selectTaskForDetails(task) }
                        )
                    }
                }
            }
        }
    }

    // Delete single task confirmation dialog
    taskToDelete?.let { task ->
        AlertDialog(
            onDismissRequest = { taskToDelete = null },
            icon = {
                Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            },
            title = {
                Text(text = stringResource(R.string.delete_confirm_title))
            },
            text = {
                Text(
                    text = "${stringResource(R.string.delete_confirm_msg)}\n\n\"${task.title}\""
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.delete(task.id)
                        taskToDelete = null
                    },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.testTag("confirm_delete_button")
                ) {
                    Text(stringResource(R.string.btn_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { taskToDelete = null }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }

    // Clear History Dialog (Choice: DB only vs DB + Files)
    if (showClearHistoryDialog) {
        AlertDialog(
            onDismissRequest = { showClearHistoryDialog = false },
            icon = {
                Icon(Icons.Default.ClearAll, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            },
            title = {
                Text(text = stringResource(R.string.clear_history_title))
            },
            text = {
                Text(text = stringResource(R.string.clear_history_msg))
            },
            confirmButton = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            viewModel.clearFinished(deleteFiles = true)
                            showClearHistoryDialog = false
                        },
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        ),
                        modifier = Modifier.fillMaxWidth().testTag("btn_clear_history_and_files")
                    ) {
                        Text(stringResource(R.string.clear_history_and_files))
                    }

                    OutlinedButton(
                        onClick = {
                            viewModel.clearFinished(deleteFiles = false)
                            showClearHistoryDialog = false
                        },
                        modifier = Modifier.fillMaxWidth().testTag("btn_clear_history_only")
                    ) {
                        Text(stringResource(R.string.clear_history_only))
                    }

                    TextButton(
                        onClick = { showClearHistoryDialog = false },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text(stringResource(R.string.btn_cancel))
                    }
                }
            },
            dismissButton = null
        )
    }

    // Task Details Dialog
    selectedDetailTask?.let { task ->
        DownloadDetailsDialog(
            task = task,
            onDismiss = { viewModel.selectTaskForDetails(null) },
            onPause = { viewModel.pause(it) },
            onResume = { viewModel.resume(it) },
            onCancel = { viewModel.cancel(it) },
            onRetry = {
                viewModel.retry(it)
                viewModel.selectTaskForDetails(null)
            },
            onDelete = {
                viewModel.delete(it)
                viewModel.selectTaskForDetails(null)
            },
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
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun HistoryTaskCard(
    task: DownloadTaskEntity,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onToggleSelection: () -> Unit,
    onClick: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    onShowDetails: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onToggleSelection
            )
            .testTag("history_item_${task.id}"),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            }
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isSelectionMode) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onToggleSelection() },
                        modifier = Modifier.testTag("history_checkbox_${task.id}")
                    )
                }

                // Thumbnail
                Box(
                    modifier = Modifier
                        .size(width = 110.dp, height = 70.dp)
                        .clip(RoundedCornerShape(8.dp))
                ) {
                    if (!task.thumbnailUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(task.thumbnailUrl)
                                .crossfade(true)
                                .build(),
                            contentDescription = task.title,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (task.isAudioOnly) Icons.Default.Audiotrack else Icons.Default.Movie,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Metadata Column
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = task.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("history_title_${task.id}")
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        StatusBadge(status = task.status)

                        val displaySize = task.totalSize.ifBlank { task.downloadedSize }
                        if (displaySize.isNotBlank()) {
                            Text(
                                text = "• $displaySize",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Text(
                        text = task.formatDescription,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Bottom action bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (task.status == DownloadStatus.COMPLETED) {
                    FilledTonalIconButton(
                        onClick = onOpen,
                        modifier = Modifier.size(36.dp).testTag("history_btn_open_${task.id}")
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Play/Open", modifier = Modifier.size(18.dp))
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    OutlinedIconButton(
                        onClick = onShare,
                        modifier = Modifier.size(36.dp).testTag("history_btn_share_${task.id}")
                    ) {
                        Icon(Icons.Default.Share, contentDescription = "Share", modifier = Modifier.size(18.dp))
                    }
                } else if (task.status == DownloadStatus.FAILED || task.status == DownloadStatus.CANCELLED) {
                    FilledTonalIconButton(
                        onClick = onRetry,
                        modifier = Modifier.size(36.dp).testTag("history_btn_retry_${task.id}")
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Retry", modifier = Modifier.size(18.dp))
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))

                OutlinedIconButton(
                    onClick = onShowDetails,
                    modifier = Modifier.size(36.dp).testTag("history_btn_info_${task.id}")
                ) {
                    Icon(Icons.Default.Info, contentDescription = "Details", modifier = Modifier.size(18.dp))
                }

                Spacer(modifier = Modifier.width(6.dp))

                OutlinedIconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(36.dp).testTag("history_btn_delete_${task.id}")
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = ErrorRed, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
fun EmptyHistoryView(
    isSearching: Boolean,
    onNavigateToHome: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.size(80.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isSearching) Icons.Default.Search else Icons.Default.History,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(40.dp)
                    )
                }
            }

            Text(
                text = if (isSearching) "No matching history found" else stringResource(R.string.no_recent_downloads),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = if (isSearching) "Try another search keyword." else "Completed and past downloads will appear here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (!isSearching) {
                Button(
                    onClick = onNavigateToHome,
                    modifier = Modifier.testTag("empty_history_start_button")
                ) {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.start_download_cta))
                }
            }
        }
    }
}

private fun formatDuration(seconds: Long): String {
    val hrs = seconds / 3600
    val mins = (seconds % 3600) / 60
    val secs = seconds % 60
    return if (hrs > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hrs, mins, secs)
    } else {
        String.format(Locale.US, "%02d:%02d", mins, secs)
    }
}
