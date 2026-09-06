package com.example.ui.downloads

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.local.DownloadTaskEntity
import com.example.domain.model.DownloadStatus
import com.example.ui.components.StatusBadge
import com.example.ui.theme.ErrorRed
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DownloadDetailsDialog(
    task: DownloadTaskEntity,
    onDismiss: () -> Unit,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onRetry: (String) -> Unit,
    onCancel: (String) -> Unit,
    onDelete: (String) -> Unit,
    onOpen: (DownloadTaskEntity) -> Unit,
    onShare: (DownloadTaskEntity) -> Unit
) {
    val context = LocalContext.current
    val dateFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(id = R.string.dialog_details_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                StatusBadge(status = task.status)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Title
                DetailItem(
                    label = stringResource(id = R.string.detail_title),
                    value = task.title
                )

                // URL with copy action
                Column {
                    Text(
                        text = stringResource(id = R.string.detail_url),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = task.url,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("URL", task.url))
                                Toast.makeText(context, "URL copied", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy URL",
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                HorizontalDivider()

                // Format & Quality
                DetailItem(
                    label = stringResource(id = R.string.detail_format),
                    value = "${task.formatDescription} (${task.formatId})"
                )

                // Cut range if applicable
                if (!task.startTime.isNullOrBlank() && !task.endTime.isNullOrBlank()) {
                    DetailItem(
                        label = stringResource(id = R.string.detail_cut_range),
                        value = "${task.startTime} → ${task.endTime} (${task.cutMode.uppercase()})"
                    )
                }

                // Progress if downloading
                if (task.status == DownloadStatus.DOWNLOADING || task.status == DownloadStatus.PROCESSING_FFMPEG || task.status == DownloadStatus.PAUSED) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val progressText = when {
                                task.downloadedSize.isNotBlank() && task.totalSize.isNotBlank() ->
                                    "${task.progress.toInt()}% (${task.downloadedSize} / ${task.totalSize})"
                                task.downloadedSize.isNotBlank() ->
                                    "${task.progress.toInt()}% (${task.downloadedSize})"
                                else ->
                                    "${task.progress.toInt()}%"
                            }
                            Text(
                                text = progressText,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                            if (task.downloadSpeed.isNotBlank()) {
                                Text(
                                    text = task.downloadSpeed,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        LinearProgressIndicator(
                            progress = { (task.progress / 100f).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // File Path / Destination
                if (!task.filePath.isNullOrBlank() || !task.contentUri.isNullOrBlank()) {
                    DetailItem(
                        label = stringResource(id = R.string.detail_destination),
                        value = task.filePath ?: stringResource(id = R.string.public_downloads)
                    )
                }

                // Error message if any
                if (!task.errorMessage.isNullOrBlank()) {
                    Surface(
                        color = ErrorRed.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text(
                                text = stringResource(id = R.string.detail_error),
                                style = MaterialTheme.typography.labelSmall,
                                color = ErrorRed,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = task.errorMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = ErrorRed
                            )
                        }
                    }
                }

                HorizontalDivider()

                // Timestamps
                DetailItem(
                    label = stringResource(id = R.string.detail_created_at),
                    value = dateFormatter.format(Date(task.createdAt))
                )

                if (task.completedAt != null) {
                    DetailItem(
                        label = stringResource(id = R.string.detail_completed_at),
                        value = dateFormatter.format(Date(task.completedAt))
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (task.status) {
                    DownloadStatus.DOWNLOADING, DownloadStatus.PROCESSING_FFMPEG, DownloadStatus.PREPARING -> {
                        FilledTonalButton(
                            onClick = { onPause(task.id) },
                            modifier = Modifier.testTag("dialog_btn_pause")
                        ) {
                            Icon(Icons.Default.Pause, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(id = R.string.btn_pause))
                        }
                        OutlinedButton(
                            onClick = { onCancel(task.id) },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = ErrorRed),
                            modifier = Modifier.testTag("dialog_btn_cancel")
                        ) {
                            Text(stringResource(id = R.string.btn_cancel))
                        }
                    }
                    DownloadStatus.PAUSED, DownloadStatus.INTERRUPTED -> {
                        Button(
                            onClick = { onResume(task.id) },
                            modifier = Modifier.testTag("dialog_btn_resume")
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(id = R.string.btn_resume))
                        }
                        OutlinedButton(
                            onClick = { onCancel(task.id) },
                            modifier = Modifier.testTag("dialog_btn_cancel")
                        ) {
                            Text(stringResource(id = R.string.btn_cancel))
                        }
                    }
                    DownloadStatus.COMPLETED -> {
                        Button(
                            onClick = { onOpen(task) },
                            modifier = Modifier.testTag("dialog_btn_open")
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(id = R.string.btn_play_media))
                        }
                        FilledTonalButton(
                            onClick = { onShare(task) },
                            modifier = Modifier.testTag("dialog_btn_share")
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(id = R.string.btn_share))
                        }
                    }
                    DownloadStatus.FAILED, DownloadStatus.CANCELLED -> {
                        Button(
                            onClick = { onRetry(task.id) },
                            modifier = Modifier.testTag("dialog_btn_retry")
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(id = R.string.btn_retry))
                        }
                        OutlinedButton(
                            onClick = { onDelete(task.id) },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = ErrorRed),
                            modifier = Modifier.testTag("dialog_btn_delete")
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(id = R.string.btn_delete))
                        }
                    }
                    else -> {}
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("dialog_btn_close")
            ) {
                Text(stringResource(id = R.string.btn_close))
            }
        }
    )
}

@Composable
private fun DetailItem(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}
