package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.domain.model.DownloadStatus
import com.example.downloader.ffmpeg.FFmpegManager
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.WarningAmber

@Composable
fun StatusBadge(status: DownloadStatus, modifier: Modifier = Modifier) {
    val (bgColor, textColor, icon, labelRes) = when (status) {
        DownloadStatus.QUEUED -> Quadruple(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            Icons.Default.Refresh,
            R.string.status_queued
        )
        DownloadStatus.PREPARING -> Quadruple(
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
            Icons.Default.Refresh,
            R.string.status_preparing
        )
        DownloadStatus.DOWNLOADING -> Quadruple(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
            Icons.Default.Refresh,
            R.string.status_downloading
        )
        DownloadStatus.PAUSED -> Quadruple(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            Icons.Default.Pause,
            R.string.status_paused
        )
        DownloadStatus.PROCESSING_FFMPEG -> Quadruple(
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
            Icons.Default.Refresh,
            R.string.status_processing_ffmpeg
        )
        DownloadStatus.COMPLETED -> Quadruple(
            SuccessGreen.copy(alpha = 0.15f),
            SuccessGreen,
            Icons.Default.CheckCircle,
            R.string.status_completed
        )
        DownloadStatus.FAILED -> Quadruple(
            ErrorRed.copy(alpha = 0.15f),
            ErrorRed,
            Icons.Default.Warning,
            R.string.status_failed
        )
        DownloadStatus.CANCELLED -> Quadruple(
            WarningAmber.copy(alpha = 0.15f),
            WarningAmber,
            Icons.Default.Close,
            R.string.status_cancelled
        )
        DownloadStatus.INTERRUPTED -> Quadruple(
            WarningAmber.copy(alpha = 0.2f),
            WarningAmber,
            Icons.Default.Warning,
            R.string.status_interrupted
        )
        else -> Quadruple(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            Icons.Default.Refresh,
            R.string.status_queued
        )
    }

    Surface(
        color = bgColor,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = textColor,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = stringResource(id = labelRes),
                color = textColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
fun EngineDiagnosticsDialog(
    onDismiss: () -> Unit,
    onUpdateEngine: () -> Unit,
    isUpdating: Boolean,
    engineMessage: String?
) {
    val context = LocalContext.current
    val ffmpegStatus = remember(context) { FFmpegManager.getInstance(context).getStatus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(
                text = stringResource(id = R.string.engine_status),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                DiagnosticItem(
                    title = "Embedded yt-dlp",
                    subtitle = stringResource(id = R.string.ytdlp_ready),
                    isOk = true
                )
                DiagnosticItem(
                    title = "Embedded FFmpeg",
                    subtitle = stringResource(id = R.string.ffmpeg_ready),
                    isOk = ffmpegStatus.isAvailable
                )
                DiagnosticItem(
                    title = "Primary ABI",
                    subtitle = ffmpegStatus.detectedAbi,
                    isOk = true
                )

                AnimatedVisibility(
                    visible = !engineMessage.isNullOrBlank(),
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = engineMessage.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onUpdateEngine,
                enabled = !isUpdating
            ) {
                Text(
                    text = if (isUpdating) "Updating..." else stringResource(id = R.string.btn_update_ytdlp)
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun DiagnosticItem(title: String, subtitle: String, isOk: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(if (isOk) SuccessGreen else WarningAmber)
        )
    }
}

private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
