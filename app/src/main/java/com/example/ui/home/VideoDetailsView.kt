package com.example.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.R
import com.example.domain.model.CutMode
import com.example.domain.model.FormatInfo
import com.example.domain.validator.TimeValidator
import com.example.ytdlp.FormatSelection
import com.example.ytdlp.SimpleQualityPreset

@Composable
fun VideoDetailsView(
    state: HomeUiState.Ready,
    viewModel: HomeViewModel,
    onReset: () -> Unit,
    onNavigateToDownloads: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val video = state.videoInfo
    val formats = state.visibleFormats

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Top action bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = stringResource(R.string.video_details_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Extracted via yt-dlp Smart Format Engine",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            OutlinedButton(
                onClick = onReset,
                modifier = Modifier.testTag("change_url_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.btn_new_search))
            }
        }

        // Video Information Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("video_info_card"),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Thumbnail with duration overlay
                    Box(
                        modifier = Modifier
                            .size(width = 136.dp, height = 86.dp)
                            .clip(RoundedCornerShape(10.dp))
                    ) {
                        if (!video.thumbnail.isNullOrBlank()) {
                            AsyncImage(
                                model = video.thumbnail,
                                contentDescription = video.title,
                                modifier = Modifier
                                    .size(width = 136.dp, height = 86.dp)
                                    .testTag("video_thumbnail"),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(width = 136.dp, height = 86.dp)
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Movie,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // Duration pill overlay
                        if (video.duration != null && video.duration > 0) {
                            Surface(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(4.dp),
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.8f)
                            ) {
                                Text(
                                    text = video.formattedDuration,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.inverseOnSurface,
                                    fontSize = 11.sp,
                                    modifier = Modifier
                                        .padding(horizontal = 4.dp, vertical = 2.dp)
                                        .testTag("video_duration")
                                )
                            }
                        }
                    }

                    // Metadata details column
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = video.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("video_title")
                        )

                        val author = video.uploader ?: video.channel
                        if (!author.isNullOrBlank()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Person,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = author,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.testTag("video_uploader")
                                )
                            }
                        }

                        // Extractor tag pill
                        val extractorName = video.extractor?.uppercase() ?: "YT-DLP"
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Text(
                                text = extractorName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }

        // Selection Mode Switch (Simple vs Advanced)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            ) {
                TabRow(
                    selectedTabIndex = state.selectionMode.ordinal,
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    divider = {}
                ) {
                    Tab(
                        selected = state.selectionMode == FormatSelectionMode.SIMPLE,
                        onClick = { viewModel.setSelectionMode(FormatSelectionMode.SIMPLE) },
                        modifier = Modifier.testTag("tab_mode_simple"),
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text("Simple (سريع)", fontWeight = FontWeight.Bold)
                            }
                        }
                    )

                    Tab(
                        selected = state.selectionMode == FormatSelectionMode.ADVANCED,
                        onClick = { viewModel.setSelectionMode(FormatSelectionMode.ADVANCED) },
                        modifier = Modifier.testTag("tab_mode_advanced"),
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Tune,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text("Advanced (متقدم)", fontWeight = FontWeight.Bold)
                            }
                        }
                    )
                }
            }
        }

        // Mode Content: Simple Preset Cards vs Advanced Stream List
        if (state.selectionMode == FormatSelectionMode.SIMPLE) {
            // Simple Quality Cards
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Recommended Qualities",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                if (state.availablePresets.isEmpty()) {
                    Text(
                        text = "No preset resolutions available.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    state.availablePresets.forEach { (preset, selection) ->
                        val isSelected = state.selectedSelection?.formatSelector == selection.formatSelector

                        SimplePresetCard(
                            preset = preset,
                            selection = selection,
                            isSelected = isSelected,
                            onSelect = { viewModel.selectPreset(preset, selection) }
                        )
                    }
                }
            }
        } else {
            // Advanced Mode: Format Classification Tabs & Full Streams List
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("formats_section"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.formats_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )

                    // Category Tabs
                    ScrollableTabRow(
                        selectedTabIndex = state.activeTab.ordinal,
                        edgePadding = 16.dp,
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        divider = {}
                    ) {
                        FormatTab.values().forEach { tab ->
                            val count = when (tab) {
                                FormatTab.VIDEO_AND_AUDIO -> state.categorizedFormats.videoAndAudioFormats.size
                                FormatTab.VIDEO_ONLY -> state.categorizedFormats.videoOnlyFormats.size
                                FormatTab.AUDIO_ONLY -> state.categorizedFormats.audioOnlyFormats.size
                                FormatTab.ALL -> state.categorizedFormats.allFormats.size
                            }

                            Tab(
                                selected = state.activeTab == tab,
                                onClick = { viewModel.selectTab(tab) },
                                modifier = Modifier.testTag("tab_${tab.name.lowercase()}"),
                                text = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(tab.label)
                                        Badge(containerColor = MaterialTheme.colorScheme.surfaceVariant) {
                                            Text(
                                                text = "$count",
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            )
                        }
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(top = 8.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )

                    // Formats items list
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (formats.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "No formats found in this category",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            formats.forEach { format ->
                                val isSelected = state.selectedFormat?.formatId == format.formatId
                                FormatItemRow(
                                    format = format,
                                    isSelected = isSelected,
                                    onSelect = { viewModel.selectFormat(format) }
                                )
                            }
                        }
                    }
                }
            }

            // Advanced Option: Enter Format ID / Selector manually
            var manualExpanded by remember { mutableStateOf(false) }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("manual_format_card"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { manualExpanded = !manualExpanded },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Tune,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = stringResource(R.string.manual_format_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        IconButton(
                            onClick = { manualExpanded = !manualExpanded },
                            modifier = Modifier.testTag("manual_format_toggle")
                        ) {
                            Icon(
                                imageVector = if (manualExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = "Toggle manual format"
                            )
                        }
                    }

                    AnimatedVisibility(
                        visible = manualExpanded,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "Directly specify a custom yt-dlp format selector (e.g. 137+140, 22, bestvideo[height<=1080]+bestaudio/best).",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = state.manualFormatInput,
                                    onValueChange = { viewModel.onManualFormatInputChange(it) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("manual_format_input"),
                                    label = { Text("Format Selector") },
                                    placeholder = { Text("137+140 or 22") },
                                    singleLine = true
                                )

                                Button(
                                    onClick = { viewModel.applyManualFormatId() },
                                    enabled = state.manualFormatInput.isNotBlank(),
                                    modifier = Modifier.testTag("apply_manual_format_button")
                                ) {
                                    Text(stringResource(R.string.btn_apply))
                                }
                            }
                        }
                    }
                }
            }
        }

        // Download Settings & Action Section
        val selectedSummary = state.selectedSelection?.displaySummary
            ?: state.selectedFormat?.let { "${it.displayTitle} • ID: ${it.formatId} • ${it.extension.uppercase()}" }
            ?: "Best Quality"

        val cutSettings = state.cutSettings
        val durationSeconds = video.duration
        val defaultEndTime = if (durationSeconds != null && durationSeconds > 0) {
            TimeValidator.formatSeconds(durationSeconds)
        } else "00:01:30"

        val currentStartTime = cutSettings.startTime ?: "00:00:00"
        val currentEndTime = cutSettings.endTime ?: defaultEndTime

        val validationResult = if (cutSettings.enabled) {
            TimeValidator.validateTimeRange(currentStartTime, currentEndTime, durationSeconds)
        } else TimeValidator.ValidationResult.Valid

        val isDownloadEnabled = validationResult is TimeValidator.ValidationResult.Valid

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("download_settings_card"),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header with format summary
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.download_settings_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = selectedSummary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                HorizontalDivider()

                // Section mode options: Full Video vs Trim/Cut Video
                Text(
                    text = "Download Mode",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = !cutSettings.enabled,
                        onClick = {
                            viewModel.updateCutSettings(cutSettings.copy(enabled = false))
                        },
                        label = { Text("Full Video") },
                        leadingIcon = if (!cutSettings.enabled) {
                            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        } else null,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("mode_full_video_chip")
                    )

                    FilterChip(
                        selected = cutSettings.enabled,
                        onClick = {
                            viewModel.updateCutSettings(
                                cutSettings.copy(
                                    enabled = true,
                                    startTime = currentStartTime,
                                    endTime = currentEndTime
                                )
                            )
                        },
                        label = { Text("Trim / Cut") },
                        leadingIcon = {
                            Icon(Icons.Default.ContentCut, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("mode_trim_chip")
                    )
                }

                // If Trim is enabled, show Start/End inputs, presets, and Cut Mode selector
                AnimatedVisibility(visible = cutSettings.enabled) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surfaceContainerHighest,
                                RoundedCornerShape(12.dp)
                            )
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = currentStartTime,
                                onValueChange = { newStart ->
                                    viewModel.updateCutSettings(
                                        cutSettings.copy(
                                            startTime = newStart,
                                            endTime = currentEndTime
                                        )
                                    )
                                },
                                label = { Text(stringResource(R.string.start_time)) },
                                placeholder = { Text("00:00:00") },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("cut_start_time_input")
                            )

                            OutlinedTextField(
                                value = currentEndTime,
                                onValueChange = { newEnd ->
                                    viewModel.updateCutSettings(
                                        cutSettings.copy(
                                            startTime = currentStartTime,
                                            endTime = newEnd
                                        )
                                    )
                                },
                                label = { Text(stringResource(R.string.end_time)) },
                                placeholder = { Text("00:01:30") },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("cut_end_time_input")
                            )
                        }

                        // Quick preset buttons
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    viewModel.updateCutSettings(
                                        cutSettings.copy(startTime = "00:00:00")
                                    )
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(36.dp)
                            ) {
                                Icon(Icons.Default.Timer, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Start: 00:00:00", fontSize = 12.sp)
                            }

                            if (durationSeconds != null && durationSeconds > 0) {
                                OutlinedButton(
                                    onClick = {
                                        viewModel.updateCutSettings(
                                            cutSettings.copy(endTime = defaultEndTime)
                                        )
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(36.dp)
                                ) {
                                    Icon(Icons.Default.Timer, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("End: Full", fontSize = 12.sp)
                                }
                            }
                        }

                        // Validation error
                        if (validationResult is TimeValidator.ValidationResult.Invalid) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.padding(top = 4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ErrorOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = validationResult.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                        // Cut Mode Selection (Fast Cut vs Precise Cut)
                        Text(
                            text = "Cutting Mode",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )

                        // Fast Cut Card
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    viewModel.updateCutSettings(cutSettings.copy(mode = CutMode.FAST_CUT))
                                }
                                .testTag("cut_mode_fast"),
                            shape = RoundedCornerShape(8.dp),
                            color = if (cutSettings.mode == CutMode.FAST_CUT) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                            } else MaterialTheme.colorScheme.surface,
                            border = if (cutSettings.mode == CutMode.FAST_CUT) {
                                androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
                            } else null
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                RadioButton(
                                    selected = cutSettings.mode == CutMode.FAST_CUT,
                                    onClick = { viewModel.updateCutSettings(cutSettings.copy(mode = CutMode.FAST_CUT)) }
                                )
                                Column {
                                    Text(
                                        text = "Fast Cut (سريع)",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = stringResource(R.string.fast_cut_explanation),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        // Precise Cut Card
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    viewModel.updateCutSettings(cutSettings.copy(mode = CutMode.PRECISE_CUT))
                                }
                                .testTag("cut_mode_precise"),
                            shape = RoundedCornerShape(8.dp),
                            color = if (cutSettings.mode == CutMode.PRECISE_CUT) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                            } else MaterialTheme.colorScheme.surface,
                            border = if (cutSettings.mode == CutMode.PRECISE_CUT) {
                                androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
                            } else null
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                RadioButton(
                                    selected = cutSettings.mode == CutMode.PRECISE_CUT,
                                    onClick = { viewModel.updateCutSettings(cutSettings.copy(mode = CutMode.PRECISE_CUT)) }
                                )
                                Column {
                                    Text(
                                        text = "Precise Cut (دقيق)",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = stringResource(R.string.precise_cut_explanation),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                // Subtitles & Closed Captions (CC) Section
                if (!state.isAudioOnly) {
                    HorizontalDivider()

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.subtitles_section),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = stringResource(R.string.subtitles_enable_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        androidx.compose.material3.Switch(
                            checked = state.downloadSubtitles,
                            onCheckedChange = { viewModel.toggleSubtitles(it) },
                            modifier = Modifier.testTag("subtitles_switch")
                        )
                    }

                    if (state.downloadSubtitles) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.subtitles_language),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                val langs = listOf(
                                    "ar" to "العربية (Arabic)",
                                    "en" to "English",
                                    "fr" to "Français",
                                    "es" to "Español",
                                    "tr" to "Türkçe"
                                )

                                langs.forEach { (code, label) ->
                                    FilterChip(
                                        selected = state.selectedSubtitleLang == code,
                                        onClick = { viewModel.selectSubtitleLanguage(code) },
                                        label = { Text(label) },
                                        leadingIcon = if (state.selectedSubtitleLang == code) {
                                            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                        } else null
                                    )
                                }
                            }
                        }
                    }
                }

                // Big Download Button
                Button(
                    onClick = {
                        viewModel.startDownload(onNavigateToDownloads)
                    },
                    enabled = isDownloadEnabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("start_download_button"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (state.isAudioOnly) "Download Audio" else "Download Video",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
fun SimplePresetCard(
    preset: SimpleQualityPreset,
    selection: FormatSelection,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { onSelect() }
            .testTag("preset_card_${preset.name.lowercase()}"),
        shape = RoundedCornerShape(14.dp),
        color = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        border = if (isSelected) {
            androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = isSelected,
                onClick = onSelect,
                modifier = Modifier.testTag("radio_preset_${preset.name.lowercase()}")
            )

            // Icon according to preset
            val icon = when {
                preset.isAudioOnly -> Icons.Default.Audiotrack
                preset == SimpleQualityPreset.BEST_QUALITY -> Icons.Default.AutoAwesome
                preset == SimpleQualityPreset.P2160 || preset == SimpleQualityPreset.P1440 || preset == SimpleQualityPreset.P1080 -> Icons.Default.HighQuality
                else -> Icons.Default.Videocam
            }

            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = preset.label,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                    )

                    // Extension container badge
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = selection.container.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }

                    if (selection.requiresMerge) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer
                        ) {
                            Text(
                                text = "Merged",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                }

                // Subtitle details: estimated size, fps, selector
                val detailParts = mutableListOf<String>()
                selection.displaySize?.let { detailParts.add(it) }
                if (selection.fps != null && selection.fps > 0) {
                    detailParts.add("${selection.fps.toInt()} fps")
                }
                if (selection.isAudioOnly && selection.audioCodec != null) {
                    detailParts.add(selection.audioCodec)
                }

                Text(
                    text = if (detailParts.isNotEmpty()) detailParts.joinToString(" • ") else "Format: ${selection.formatSelector}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}

@Composable
fun FormatItemRow(
    format: FormatInfo,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onSelect() }
            .testTag("format_item_${format.formatId}"),
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        border = if (isSelected) {
            androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
        } else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = isSelected,
                onClick = onSelect,
                modifier = Modifier.testTag("radio_${format.formatId}")
            )

            val typeIcon = when {
                format.isAudioOnly -> Icons.Default.Audiotrack
                format.isVideoOnly -> Icons.Default.Videocam
                else -> Icons.Default.Movie
            }
            Icon(
                imageVector = typeIcon,
                contentDescription = null,
                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = format.displayTitle,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                    )

                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = "[${format.formatId}]",
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 10.sp,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }

                    if (format.isVideoOnly) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Text(
                                text = "+Audio",
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 9.sp,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp)
                            )
                        }
                    }
                }

                Text(
                    text = format.displaySubtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}
