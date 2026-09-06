package com.example.ui.components

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.media.MediaMetadataRetriever
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.ui.PlayerView
import com.example.R
import com.example.storage.MediaStoreHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

private const val TAG_PLAYER = "PLAYER_DEBUG"
private const val TAG_MEDIA_FILE = "MEDIA_FILE_DEBUG"

/**
 * Extracts accurate media duration in milliseconds using Android's native MediaMetadataRetriever.
 */
fun extractMediaDuration(context: Context, uri: Uri?, filePath: String?): Long {
    var retriever: MediaMetadataRetriever? = null
    try {
        retriever = MediaMetadataRetriever()
        if (!filePath.isNullOrBlank() && File(filePath).exists()) {
            retriever.setDataSource(filePath)
        } else if (uri != null) {
            retriever.setDataSource(context, uri)
        } else {
            return 0L
        }
        val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
        return durationStr?.toLongOrNull() ?: 0L
    } catch (e: Exception) {
        Log.w(TAG_MEDIA_FILE, "MediaMetadataRetriever duration failed: ${e.message}")
        return 0L
    } finally {
        try {
            retriever?.release()
        } catch (_: Exception) {}
    }
}

/**
 * Production-ready in-app media player dialog powered by AndroidX Media3 ExoPlayer.
 *
 * Architecture Principles:
 * 1. Prioritizes direct file URI whenever the local media file exists on disk to enable instant RandomAccessFile seeking.
 * 2. Pre-extracts duration via MediaMetadataRetriever so total duration displays immediately.
 * 3. Configures DefaultExtractorsFactory with constant-bitrate seeking and MP4 workaround flags.
 * 4. Play/Pause resumes cleanly without resetting position.
 * 5. rememberSaveable preserves currentPositionMs across recompositions & fullscreen toggles.
 * 6. Seeking and scrubbing guarded against uninitialized or zero durations.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun InAppMediaPlayerDialog(
    title: String,
    mediaPath: String?,
    contentUri: String?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var isFullscreen by remember { mutableStateOf(false) }

    // Resolve media URI safely: prefer direct file URI first for fast random access, then content URI
    val mediaUri = remember(contentUri, mediaPath) {
        val directFile = mediaPath?.let { File(it) }
        val resolved = when {
            directFile != null && directFile.exists() && directFile.length() > 0L -> {
                Uri.fromFile(directFile)
            }
            !contentUri.isNullOrBlank() -> contentUri.toUri()
            !mediaPath.isNullOrBlank() -> {
                if (mediaPath.startsWith("content://") || mediaPath.startsWith("file://")) {
                    mediaPath.toUri()
                } else {
                    val f = File(mediaPath)
                    if (f.exists()) Uri.fromFile(f) else mediaPath.toUri()
                }
            }
            else -> null
        }

        // Diagnostic File & Content URI logging
        val existsCheck = when {
            resolved == null -> "null"
            resolved.scheme == "content" -> {
                try {
                    context.contentResolver.openFileDescriptor(resolved, "r")?.use { "accessible (size=${it.statSize})" }
                        ?: "cannot open pfd"
                } catch (e: Exception) {
                    "content access error: ${e.message}"
                }
            }
            resolved.scheme == "file" -> {
                val f = File(resolved.path ?: "")
                if (f.exists()) "file exists (size=${f.length()})" else "file missing"
            }
            else -> "unknown scheme"
        }

        Log.d(
            TAG_MEDIA_FILE,
            "contentUri=$contentUri, mediaPath=$mediaPath, resolvedUri=$resolved, access=$existsCheck"
        )
        resolved
    }

    // Pre-extract actual media duration asynchronously using MediaMetadataRetriever
    var precomputedDurationMs by remember(mediaUri) { mutableLongStateOf(0L) }

    // Persist playback position across recompositions and screen state changes for this specific URI
    var savedPositionMs by rememberSaveable(mediaUri?.toString()) { mutableLongStateOf(0L) }

    // Single ExoPlayer instance instantiated once per mediaUri with extractors enabled for seeking
    val exoPlayer = remember(mediaUri) {
        if (mediaUri != null) {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build()

            val extractorsFactory = DefaultExtractorsFactory()
                .setConstantBitrateSeekingEnabled(true)
                .setMp4ExtractorFlags(
                    Mp4Extractor.FLAG_WORKAROUND_IGNORE_EDIT_LISTS or
                    Mp4Extractor.FLAG_READ_SEF_DATA
                )

            val mediaSourceFactory = DefaultMediaSourceFactory(context, extractorsFactory)

            val mimeType = MediaStoreHelper.getMimeType(mediaPath ?: contentUri ?: "")
            val mediaItem = MediaItem.Builder()
                .setUri(mediaUri)
                .setMimeType(mimeType)
                .build()

            ExoPlayer.Builder(context, mediaSourceFactory)
                .setAudioAttributes(audioAttributes, true)
                .setSeekBackIncrementMs(10000L)
                .setSeekForwardIncrementMs(10000L)
                .build().apply {
                    setMediaItem(mediaItem)
                    prepare()
                    if (savedPositionMs > 0L) {
                        seekTo(savedPositionMs)
                    }
                    playWhenReady = true
                    Log.d(TAG_PLAYER, "ExoPlayer initialized for URI: $mediaUri with savedPosition=$savedPositionMs")
                }
        } else {
            Log.e(TAG_PLAYER, "Cannot initialize ExoPlayer: mediaUri is null")
            null
        }
    }

    // Release player strictly when this Dialog leaves the composition hierarchy
    DisposableEffect(exoPlayer) {
        onDispose {
            Log.d(TAG_PLAYER, "Releasing ExoPlayer on dialog dispose")
            exoPlayer?.release()
        }
    }

    // High-level playback state observables
    var isPlaying by remember { mutableStateOf(exoPlayer?.isPlaying ?: false) }
    var playbackState by remember { mutableIntStateOf(exoPlayer?.playbackState ?: Player.STATE_IDLE) }
    var currentPosition by remember { mutableLongStateOf(savedPositionMs) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var isDurationReady by remember { mutableStateOf(false) }
    var isSeekable by remember { mutableStateOf(false) }
    var videoAspectRatio by remember { mutableFloatStateOf(16f / 9f) }
    var errorMessage by remember { mutableStateOf<String?>(if (exoPlayer == null) "Could not load media source" else null) }

    // Asynchronously extract duration without blocking the main composition thread
    LaunchedEffect(mediaUri, mediaPath, contentUri) {
        withContext(Dispatchers.IO) {
            val dur = extractMediaDuration(context, mediaUri, mediaPath)
            if (dur > 0L) {
                withContext(Dispatchers.Main) {
                    precomputedDurationMs = dur
                    if (durationMs <= 0L) {
                        durationMs = dur
                        isDurationReady = true
                        isSeekable = true
                    }
                }
            }
        }
    }

    // Interactive UI controls state
    var isSeeking by remember { mutableStateOf(false) }
    var seekPreviewFraction by remember { mutableFloatStateOf(0f) }
    var volume by remember { mutableFloatStateOf(1.0f) }
    var isMuted by remember { mutableStateOf(false) }
    var isLooping by remember { mutableStateOf(false) }
    var currentSpeed by remember { mutableFloatStateOf(1.0f) }
    var showSpeedMenu by remember { mutableStateOf(false) }
    var areControlsVisible by remember { mutableStateOf(true) }
    var lastUserInteractionTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var doubleTapSeekFeedback by remember { mutableStateOf<String?>(null) }

    // Synchronize ExoPlayer events with UI state
    DisposableEffect(exoPlayer) {
        if (exoPlayer == null) return@DisposableEffect onDispose {}

        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                Log.d(TAG_PLAYER, "onIsPlayingChanged: playing=$playing, position=${exoPlayer.currentPosition}")
            }

            override fun onPlaybackStateChanged(state: Int) {
                playbackState = state
                val dur = exoPlayer.duration
                if (dur != C.TIME_UNSET && dur > 0L) {
                    durationMs = dur
                    isDurationReady = true
                }
                isSeekable = exoPlayer.isCurrentMediaItemSeekable
                Log.d(
                    TAG_PLAYER,
                    "onPlaybackStateChanged: state=$state, duration=$dur, isSeekable=$isSeekable, pos=${exoPlayer.currentPosition}"
                )
            }

            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                val dur = exoPlayer.duration
                if (dur != C.TIME_UNSET && dur > 0L) {
                    durationMs = dur
                    isDurationReady = true
                }
                isSeekable = exoPlayer.isCurrentMediaItemSeekable
                Log.d(
                    TAG_PLAYER,
                    "onTimelineChanged: windows=${timeline.windowCount}, duration=$dur, isSeekable=$isSeekable"
                )
            }

            override fun onEvents(player: Player, events: Player.Events) {
                isPlaying = player.isPlaying
                playbackState = player.playbackState
                val dur = player.duration
                if (dur != C.TIME_UNSET && dur > 0L) {
                    durationMs = dur
                    isDurationReady = true
                }
                isSeekable = player.isCurrentMediaItemSeekable
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    val ratio = videoSize.width.toFloat() / videoSize.height.toFloat()
                    videoAspectRatio = ratio.coerceIn(0.4f, 2.8f)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG_PLAYER, "ExoPlayer error: ${error.errorCodeName} - ${error.message}", error)
                errorMessage = error.message ?: "Playback error occurred"
            }
        }

        exoPlayer.addListener(listener)

        // Seed initial values
        isPlaying = exoPlayer.isPlaying
        playbackState = exoPlayer.playbackState
        val dur = exoPlayer.duration
        if (dur != C.TIME_UNSET && dur > 0L) {
            durationMs = dur
            isDurationReady = true
        }
        isSeekable = exoPlayer.isCurrentMediaItemSeekable

        onDispose {
            exoPlayer.removeListener(listener)
        }
    }

    // Periodic timeline synchronization (200ms interval for smooth Compose updates)
    LaunchedEffect(exoPlayer) {
        while (isActive) {
            exoPlayer?.let { player ->
                if (!isSeeking) {
                    val pos = player.currentPosition.coerceAtLeast(0L)
                    currentPosition = pos
                    savedPositionMs = pos
                }
                val dur = player.duration
                if (dur != C.TIME_UNSET && dur > 0L && dur != durationMs) {
                    durationMs = dur
                    isDurationReady = true
                }
                isSeekable = player.isCurrentMediaItemSeekable
                isPlaying = player.isPlaying
                playbackState = player.playbackState
            }
            delay(200)
        }
    }

    // Auto-hide HUD controls after 3.5 seconds of inactivity while playing
    LaunchedEffect(areControlsVisible, isPlaying, lastUserInteractionTime) {
        if (areControlsVisible && isPlaying) {
            delay(3500)
            areControlsVisible = false
        }
    }

    // Helper functions for user actions
    fun triggerInteraction() {
        lastUserInteractionTime = System.currentTimeMillis()
        areControlsVisible = true
    }

    fun togglePlayPause() {
        triggerInteraction()
        exoPlayer?.let { player ->
            when {
                player.playbackState == Player.STATE_ENDED -> {
                    Log.d(TAG_PLAYER, "REPLAY from end -> seekTo(0)")
                    player.seekTo(0L)
                    savedPositionMs = 0L
                    player.play()
                }
                player.isPlaying -> {
                    Log.d(TAG_PLAYER, "PAUSE position=${player.currentPosition}")
                    player.pause()
                }
                else -> {
                    Log.d(TAG_PLAYER, "PLAY position=${player.currentPosition}")
                    player.play()
                }
            }
        }
    }

    fun seekRelative(offsetMs: Long) {
        triggerInteraction()
        exoPlayer?.let { player ->
            val cur = player.currentPosition
            val maxDur = if (durationMs > 0L) durationMs else if (player.duration > 0L && player.duration != C.TIME_UNSET) player.duration else Long.MAX_VALUE
            val target = (cur + offsetMs).coerceIn(0L, maxDur)
            Log.d(TAG_PLAYER, "SEEK relative offset=$offsetMs from=$cur to=$target")
            player.seekTo(target)
            currentPosition = target
            savedPositionMs = target
            if (player.playbackState == Player.STATE_ENDED && offsetMs < 0) {
                player.play()
            }
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        currentSpeed = speed
        exoPlayer?.setPlaybackSpeed(speed)
    }

    fun updateVolume(newVol: Float) {
        volume = newVol.coerceIn(0f, 1f)
        isMuted = newVol <= 0f
        exoPlayer?.volume = if (isMuted) 0f else volume
    }

    fun toggleMute() {
        isMuted = !isMuted
        exoPlayer?.volume = if (isMuted) 0f else volume
    }

    val displayTitle = title.ifBlank { stringResource(R.string.media_player_title) }

    // Dialog window containing player
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = !isFullscreen
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (isFullscreen) Modifier.background(Color.Black)
                    else Modifier
                        .background(Color.Black.copy(alpha = 0.65f))
                        .padding(16.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            val containerModifier = if (isFullscreen) {
                Modifier.fillMaxSize()
            } else {
                Modifier
                    .fillMaxWidth()
                    .widthIn(max = 680.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.Black)
            }

            Box(
                modifier = containerModifier,
                contentAlignment = Alignment.Center
            ) {
                // 1. Video Surface View (ExoPlayer PlayerView)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (isFullscreen) Modifier.fillMaxSize()
                            else Modifier.aspectRatio(videoAspectRatio, matchHeightConstraintsFirst = false)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (exoPlayer != null) {
                        AndroidView(
                            factory = { ctx ->
                                PlayerView(ctx).apply {
                                    player = exoPlayer
                                    useController = false
                                    layoutParams = FrameLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                                }
                            },
                            update = { playerView ->
                                if (playerView.player != exoPlayer) {
                                    playerView.player = exoPlayer
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                // 2. Gesture Detector for Tap & Double-Tap Seeks
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = {
                                    areControlsVisible = !areControlsVisible
                                    if (areControlsVisible) triggerInteraction()
                                },
                                onDoubleTap = { offset ->
                                    triggerInteraction()
                                    val isLeft = offset.x < (size.width / 2)
                                    if (isLeft) {
                                        seekRelative(-10000L)
                                        doubleTapSeekFeedback = "-10s"
                                    } else {
                                        seekRelative(10000L)
                                        doubleTapSeekFeedback = "+10s"
                                    }
                                }
                            )
                        }
                )

                // 3. Double-tap Seek Feedback Popup
                LaunchedEffect(doubleTapSeekFeedback) {
                    if (doubleTapSeekFeedback != null) {
                        delay(650)
                        doubleTapSeekFeedback = null
                    }
                }
                AnimatedVisibility(
                    visible = doubleTapSeekFeedback != null,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.Center)
                ) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.75f),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = if (doubleTapSeekFeedback?.startsWith("-") == true) Icons.Default.Replay10 else Icons.Default.Forward10,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                            Text(
                                text = doubleTapSeekFeedback.orEmpty(),
                                color = Color.White,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // 4. Buffering / Loading Indicator
                val isBuffering = playbackState == Player.STATE_BUFFERING || (playbackState == Player.STATE_IDLE && exoPlayer != null)
                if (isBuffering && errorMessage == null) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp)
                    )
                }

                // 5. Error Overlay
                if (errorMessage != null) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.85f),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .padding(24.dp)
                            .align(Alignment.Center)
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.player_error),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = errorMessage.orEmpty(),
                                color = Color.White.copy(alpha = 0.7f),
                                style = MaterialTheme.typography.labelSmall,
                                textAlign = TextAlign.Center
                            )
                            TextButton(onClick = onDismiss) {
                                Text(stringResource(R.string.btn_close), color = Color.White)
                            }
                        }
                    }
                }

                // 6. Complete HUD Controls Overlay
                AnimatedVisibility(
                    visible = areControlsVisible && errorMessage == null,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.fillMaxSize()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        Color.Black.copy(alpha = 0.75f),
                                        Color.Transparent,
                                        Color.Transparent,
                                        Color.Black.copy(alpha = 0.85f)
                                    )
                                )
                            )
                    ) {
                        // Top Bar: Title, Speed, Loop, Fullscreen & Close
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.TopCenter)
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = displayTitle,
                                color = Color.White,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )

                            // Speed Selector
                            Box {
                                IconButton(onClick = {
                                    triggerInteraction()
                                    showSpeedMenu = !showSpeedMenu
                                }) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.Speed,
                                            contentDescription = stringResource(R.string.player_speed),
                                            tint = if (currentSpeed != 1.0f) MaterialTheme.colorScheme.primary else Color.White,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(2.dp))
                                        Text(
                                            text = "${currentSpeed}x",
                                            color = if (currentSpeed != 1.0f) MaterialTheme.colorScheme.primary else Color.White,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                DropdownMenu(
                                    expanded = showSpeedMenu,
                                    onDismissRequest = { showSpeedMenu = false }
                                ) {
                                    listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { speed ->
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    text = if (speed == 1.0f) "Normal (1.0x)" else "${speed}x",
                                                    fontWeight = if (speed == currentSpeed) FontWeight.Bold else FontWeight.Normal
                                                )
                                            },
                                            onClick = {
                                                setPlaybackSpeed(speed)
                                                showSpeedMenu = false
                                                triggerInteraction()
                                            }
                                        )
                                    }
                                }
                            }

                            // Repeat / Loop Toggle
                            IconButton(onClick = {
                                triggerInteraction()
                                isLooping = !isLooping
                                exoPlayer?.repeatMode = if (isLooping) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                            }) {
                                Icon(
                                    imageVector = if (isLooping) Icons.Default.RepeatOne else Icons.Default.Repeat,
                                    contentDescription = stringResource(R.string.player_loop),
                                    tint = if (isLooping) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.7f),
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            // Fullscreen Toggle Button
                            IconButton(
                                onClick = {
                                    triggerInteraction()
                                    isFullscreen = !isFullscreen
                                },
                                modifier = Modifier.testTag("btn_player_fullscreen")
                            ) {
                                Icon(
                                    imageVector = if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                                    contentDescription = if (isFullscreen) stringResource(R.string.player_exit_fullscreen) else stringResource(R.string.player_fullscreen),
                                    tint = Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                            }

                            // Close Button
                            IconButton(
                                onClick = onDismiss,
                                modifier = Modifier.testTag("btn_player_close")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = stringResource(R.string.btn_close),
                                    tint = Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }

                        // Center Controls: Rewind 10s, Play/Pause/Replay, Fast-Forward 10s
                        Row(
                            modifier = Modifier.align(Alignment.Center),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(24.dp)
                        ) {
                            // Rewind 10s
                            FilledTonalIconButton(
                                onClick = { seekRelative(-10000L) },
                                modifier = Modifier
                                    .size(52.dp)
                                    .testTag("btn_player_rewind"),
                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = Color.White.copy(alpha = 0.2f),
                                    contentColor = Color.White
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Replay10,
                                    contentDescription = stringResource(R.string.player_seek_backward),
                                    modifier = Modifier.size(28.dp)
                                )
                            }

                            // Main Play / Pause Button
                            FilledIconButton(
                                onClick = { togglePlayPause() },
                                modifier = Modifier
                                    .size(72.dp)
                                    .testTag("btn_player_play_pause"),
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                )
                            ) {
                                Icon(
                                    imageVector = when {
                                        playbackState == Player.STATE_ENDED -> Icons.Default.Replay
                                        isPlaying -> Icons.Default.Pause
                                        else -> Icons.Default.PlayArrow
                                    },
                                    contentDescription = when {
                                        playbackState == Player.STATE_ENDED -> "Replay"
                                        isPlaying -> "Pause"
                                        else -> "Play"
                                    },
                                    modifier = Modifier.size(40.dp)
                                )
                            }

                            // Fast Forward 10s
                            FilledTonalIconButton(
                                onClick = { seekRelative(10000L) },
                                modifier = Modifier
                                    .size(52.dp)
                                    .testTag("btn_player_forward"),
                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = Color.White.copy(alpha = 0.2f),
                                    contentColor = Color.White
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Forward10,
                                    contentDescription = stringResource(R.string.player_seek_forward),
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }

                        // Bottom Controls: Timeline Slider, Timestamps & Volume
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.BottomCenter)
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            // Timeline Progress Slider
                            val effectivePosition = if (isSeeking) {
                                if (durationMs > 0L) (seekPreviewFraction * durationMs).toLong().coerceIn(0L, durationMs) else 0L
                            } else {
                                currentPosition
                            }

                            val progressFraction = if (durationMs > 0L) {
                                (effectivePosition.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                            } else {
                                0f
                            }

                            Slider(
                                value = progressFraction,
                                onValueChange = { frac ->
                                    triggerInteraction()
                                    isSeeking = true
                                    seekPreviewFraction = frac
                                },
                                onValueChangeFinished = {
                                    if (durationMs > 0L) {
                                        val targetMs = (seekPreviewFraction * durationMs).toLong().coerceIn(0L, durationMs)
                                        Log.d(TAG_PLAYER, "SEEK from=${exoPlayer?.currentPosition} to=$targetMs")
                                        exoPlayer?.seekTo(targetMs)
                                        currentPosition = targetMs
                                        savedPositionMs = targetMs
                                    }
                                    isSeeking = false
                                    triggerInteraction()
                                },
                                enabled = (durationMs > 0L) && (isSeekable || isDurationReady),
                                colors = SliderDefaults.colors(
                                    thumbColor = MaterialTheme.colorScheme.primary,
                                    activeTrackColor = MaterialTheme.colorScheme.primary,
                                    inactiveTrackColor = Color.White.copy(alpha = 0.35f),
                                    disabledThumbColor = Color.White.copy(alpha = 0.4f),
                                    disabledActiveTrackColor = Color.White.copy(alpha = 0.3f),
                                    disabledInactiveTrackColor = Color.White.copy(alpha = 0.15f)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("slider_player_timeline")
                            )

                            // Timestamp and Volume row
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val currentFormatted = formatDuration(effectivePosition)
                                val durationFormatted = if (isDurationReady && durationMs > 0L) {
                                    formatDuration(durationMs)
                                } else {
                                    "--:--"
                                }

                                Text(
                                    text = "$currentFormatted / $durationFormatted",
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.testTag("text_player_timestamps")
                                )

                                Spacer(modifier = Modifier.weight(1f))

                                // Quick Volume Slider & Mute Toggle
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    IconButton(
                                        onClick = {
                                            triggerInteraction()
                                            toggleMute()
                                        },
                                        modifier = Modifier
                                            .size(36.dp)
                                            .testTag("btn_player_mute")
                                    ) {
                                        val volIcon = when {
                                            isMuted || volume == 0f -> Icons.AutoMirrored.Filled.VolumeOff
                                            volume < 0.5f -> Icons.AutoMirrored.Filled.VolumeDown
                                            else -> Icons.AutoMirrored.Filled.VolumeUp
                                        }
                                        Icon(
                                            imageVector = volIcon,
                                            contentDescription = stringResource(R.string.player_volume),
                                            tint = if (isMuted) MaterialTheme.colorScheme.error else Color.White,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }

                                    Slider(
                                        value = if (isMuted) 0f else volume,
                                        onValueChange = { newVol ->
                                            triggerInteraction()
                                            updateVolume(newVol)
                                        },
                                        modifier = Modifier
                                            .width(80.dp)
                                            .testTag("slider_player_volume"),
                                        colors = SliderDefaults.colors(
                                            thumbColor = MaterialTheme.colorScheme.primary,
                                            activeTrackColor = MaterialTheme.colorScheme.primary,
                                            inactiveTrackColor = Color.White.copy(alpha = 0.35f)
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Formats milliseconds into standard duration strings:
 * - 00:25 (25 seconds)
 * - 01:00 (1 minute)
 * - 10:35 (10 minutes 35 seconds)
 * - 1:25:42 (1 hour 25 minutes 42 seconds)
 */
private fun formatDuration(millis: Long): String {
    if (millis <= 0L) return "00:00"
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}
