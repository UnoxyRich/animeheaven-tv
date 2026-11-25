package me.animeheaven.tv.ui.screens.player

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import me.animeheaven.tv.data.cache.VideoCacheManager
import me.animeheaven.tv.data.network.SkipSegment
import me.animeheaven.tv.data.network.SkipSegmentType
import me.animeheaven.tv.ui.components.AhErrorBox
import me.animeheaven.tv.ui.components.AhFocusableBox
import me.animeheaven.tv.ui.components.AhIcons
import me.animeheaven.tv.ui.components.AhOverscanHorizontal
import me.animeheaven.tv.ui.components.AhShape3
import me.animeheaven.tv.ui.components.AhShape4
import me.animeheaven.tv.ui.components.AhShape20
import me.animeheaven.tv.ui.components.AhStaticFocusableBox
import me.animeheaven.tv.ui.components.formatDurationMs
import me.animeheaven.tv.ui.theme.LocalAhPalette

@OptIn(UnstableApi::class)
private enum class ResizeOption(val label: String, val mode: Int) {
    FIT("Fit", AspectRatioFrameLayout.RESIZE_MODE_FIT),
    FILL("Fill", AspectRatioFrameLayout.RESIZE_MODE_FILL),
    ZOOM("Zoom", AspectRatioFrameLayout.RESIZE_MODE_ZOOM)
}

private val PLAYBACK_SPEEDS = listOf(0.75f, 1.0f, 1.25f, 1.5f)
private val HUD_TOP_SCRIM = Brush.verticalGradient(listOf(Color(0xE6000000), Color.Transparent))
private val HUD_BOTTOM_SCRIM = Brush.verticalGradient(listOf(Color.Transparent, Color(0xEC000000)))
private val HUD_TEXT_SECONDARY = Color(0xFFD2D2D2)
private val HUD_SURFACE_ELEVATED = Color(0xFF262626)
private val HUD_BORDER_SUBTLE = Color(0x38FFFFFF)
private val SEEK_BADGE_SHAPE = RoundedCornerShape(6.dp)
private val SEEK_TRACK_BG = Color(0x55FFFFFF)
private val SEEK_BUFFER_COLOR = Color(0x77BDBDBD)
private val SKIP_PROMPT_TRANSFORM_ORIGIN = TransformOrigin(0f, 0.5f)

/**
 * Isolated mutable progress holder read ONLY inside leaf HUD composables (time labels & Canvas
 * seekbar) so 500ms playback ticks cause ZERO recompositions of [PlayerScreen] when HUD is hidden.
 */
@Stable
private class PlaybackProgressState {
    var positionMs by mutableLongStateOf(0L)
    var durationMs by mutableLongStateOf(0L)
    var bufferedMs by mutableLongStateOf(0L)
}

@OptIn(UnstableApi::class, ExperimentalComposeUiApi::class)
@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel,
    videoCacheManager: VideoCacheManager,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val settings by viewModel.settingsStore.settings.collectAsStateWithLifecycle()
    val palette = LocalAhPalette.current

    val context = LocalContext.current
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val progressState = remember { PlaybackProgressState() }

    var isPlaying by remember { mutableStateOf(false) }
    var isBuffering by remember { mutableStateOf(true) }

    var hudVisible by remember { mutableStateOf(true) }
    var hudInteractionNonce by remember { mutableIntStateOf(0) }
    var seekFeedback by remember { mutableStateOf<String?>(null) }
    var seekFeedbackNonce by remember { mutableIntStateOf(0) }

    var resizeOption by remember { mutableStateOf(ResizeOption.FIT) }
    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }

    var upNextSecondsLeft by remember { mutableIntStateOf(0) }
    var showUpNextOverlay by remember { mutableStateOf(false) }

    var activeSkipSegment by remember { mutableStateOf<SkipSegment?>(null) }
    val handledSkipTypes = remember(state.stream?.episodeKey) { mutableSetOf<SkipSegmentType>() }

    // Keep latest snapshots accessible inside long-lived polling loop & listeners without restarting them
    val latestState by rememberUpdatedState(state)
    val latestSettings by rememberUpdatedState(settings)
    val latestHudVisible by rememberUpdatedState(hudVisible)
    val latestShowUpNext by rememberUpdatedState(showUpNextOverlay)
    val latestIsBuffering by rememberUpdatedState(isBuffering)

    val surfaceFocusRequester = remember { FocusRequester() }
    val playPauseFocusRequester = remember { FocusRequester() }
    val upNextPlayFocusRequester = remember { FocusRequester() }
    val retryFocusRequester = remember { FocusRequester() }

    val exoPlayer = remember(context, videoCacheManager) {
        val renderersFactory = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .forceEnableMediaCodecAsynchronousQueueing()
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        ExoPlayer.Builder(context, renderersFactory)
            .setPlaybackLooper(videoCacheManager.warmupPool.playbackLooper)
            .setLoadControl(videoCacheManager.buildFastLoadControl())
            .setMediaSourceFactory(videoCacheManager.buildProgressiveMediaSourceFactory())
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
                pauseAtEndOfMediaItems = true
                setPreloadConfiguration(ExoPlayer.PreloadConfiguration(15_000_000L))
            }
    }
    var resumeOnStart by remember(exoPlayer) { mutableStateOf(false) }

    // Debounce buffering spinner by 220 ms when disk cache is already warm so sub-100ms
    // local codec init or gapless Watch Next transitions never flash a buffering screen.
    var showBufferingSpinner by remember { mutableStateOf(state.isLoading && !state.isDiskCacheWarm) }
    LaunchedEffect(state.isLoading, isBuffering, state.errorMessage, state.isDiskCacheWarm) {
        val rawBusy = state.errorMessage == null && (state.isLoading || isBuffering)
        if (!rawBusy) {
            showBufferingSpinner = false
        } else if (state.isDiskCacheWarm && !state.isLoading) {
            delay(220L)
            showBufferingSpinner = true
        } else {
            showBufferingSpinner = true
        }
    }

    val lifecycleState by lifecycleOwner.lifecycle.currentStateFlow.collectAsStateWithLifecycle(
        minActiveState = Lifecycle.State.CREATED
    )
    val keepAwake = lifecycleState.isAtLeast(Lifecycle.State.STARTED) &&
        state.errorMessage == null && (isPlaying || isBuffering && exoPlayer.playWhenReady)
    DisposableEffect(view, keepAwake) {
        view.keepScreenOn = keepAwake
        onDispose { view.keepScreenOn = false }
    }

    DisposableEffect(view) {
        val window = (context as? Activity)?.window
        val insetsController = window?.let { WindowCompat.getInsetsController(it, view) }
        insetsController?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insetsController?.hide(WindowInsetsCompat.Type.systemBars())

        onDispose {
            insetsController?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    fun saveCurrentProgress() {
        val item = exoPlayer.currentMediaItem ?: return
        if (item.mediaId != latestState.stream?.episodeKey) return
        viewModel.persistProgress(exoPlayer.currentPosition.coerceAtLeast(0L),
            exoPlayer.duration.coerceAtLeast(progressState.durationMs))
    }

    fun handleEpisodeEnded() {
        viewModel.setPlaybackBufferingActive(false)
        val finalDur = exoPlayer.duration.coerceAtLeast(progressState.durationMs)
        viewModel.persistProgress(
            positionMs = finalDur,
            durationMs = finalDur,
            forceWatched = true
        )
        val nextKey = latestState.stream?.nextEpisodeKey
        if (latestSettings.autoPlayNext && !nextKey.isNullOrBlank()) {
            upNextSecondsLeft = 5
            showUpNextOverlay = true
            hudVisible = false
        } else {
            hudVisible = true
        }
    }

    fun switchToEpisodeInstant(nextKey: String) {
        showUpNextOverlay = false
        activeSkipSegment = null
        val curIndex = exoPlayer.currentMediaItemIndex
        val nextIndex = curIndex + 1
        val hasPreparedInPlaylist = nextIndex < exoPlayer.mediaItemCount &&
            exoPlayer.getMediaItemAt(nextIndex).mediaId == nextKey
        if (hasPreparedInPlaylist) {
            val promoted = viewModel.promotePreparedNextEpisode()
            if (promoted != null) {
                exoPlayer.seekTo(nextIndex, 0L)
                if (curIndex >= 0 && curIndex < nextIndex) {
                    exoPlayer.removeMediaItem(curIndex)
                }
                exoPlayer.setPlaybackSpeed(playbackSpeed)
                exoPlayer.playWhenReady = true
                exoPlayer.play()
                progressState.positionMs = 0L
                val dur = exoPlayer.duration.coerceAtLeast(promoted.durationMs)
                progressState.durationMs = dur
                progressState.bufferedMs = exoPlayer.bufferedPosition.coerceAtLeast(0L)
                isBuffering = exoPlayer.playbackState == Player.STATE_BUFFERING
                promoted.primaryUrl?.let { promotedUrl ->
                    viewModel.startStreamingBoost(
                        activeUrl = promotedUrl,
                        activeCacheKey = promoted.activeCacheKey,
                        initialPositionMs = 0L,
                        initialDurationMs = dur
                    )
                }
                viewModel.onPlaybackStarted()
                return
            }
        }
        viewModel.loadEpisode(nextKey)
    }

    DisposableEffect(exoPlayer) {
        val playerListener = object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
                    handleEpisodeEnded()
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                if (!playing) {
                    saveCurrentProgress()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING
                when (playbackState) {
                    Player.STATE_BUFFERING -> viewModel.setPlaybackBufferingActive(true)
                    Player.STATE_READY -> {
                        val dur = exoPlayer.duration.coerceAtLeast(0L)
                        if (dur > 0L) progressState.durationMs = dur
                        viewModel.updateBoosterProgress(
                            exoPlayer.currentPosition.coerceAtLeast(0L),
                            progressState.durationMs
                        )
                        viewModel.onPlaybackStarted()
                    }
                    Player.STATE_IDLE -> viewModel.setPlaybackBufferingActive(false)
                    Player.STATE_ENDED -> handleEpisodeEnded()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                viewModel.onMirrorPlaybackError(exoPlayer.currentPosition.coerceAtLeast(0L))
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                val curDur = exoPlayer.duration.coerceAtLeast(progressState.durationMs)
                viewModel.updateBoosterProgress(
                    newPosition.positionMs.coerceAtLeast(0L),
                    curDur
                )
            }
        }
        exoPlayer.addListener(playerListener)
        onDispose {
            exoPlayer.removeListener(playerListener)
            saveCurrentProgress()
            viewModel.stopStreamingBoost()
            exoPlayer.release()
        }
    }

    DisposableEffect(lifecycleOwner, exoPlayer) {
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                resumeOnStart = exoPlayer.playWhenReady
                viewModel.stopStreamingBoost()
                saveCurrentProgress()
                exoPlayer.pause()
            } else if (event == Lifecycle.Event.ON_START && resumeOnStart) {
                resumeOnStart = false
                val snapshot = latestState
                val url = snapshot.mirrorState.currentUrl
                if (url != null && snapshot.errorMessage == null &&
                    exoPlayer.currentMediaItem?.mediaId == snapshot.stream?.episodeKey
                ) {
                    exoPlayer.play()
                    viewModel.startStreamingBoost(url, snapshot.activeCacheKey ?: url,
                        exoPlayer.currentPosition, exoPlayer.duration)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)

        onDispose { lifecycleOwner.lifecycle.removeObserver(lifecycleObserver) }
    }

    // Load active mirror URL into ExoPlayer with shared episode cache key, PreloadMediaSource handoff & direct resume
    val activeUrl = state.mirrorState.currentUrl
    val activeCacheKey = state.activeCacheKey ?: activeUrl
    val episodeKey = state.stream?.episodeKey
    LaunchedEffect(exoPlayer, lifecycleOwner, activeUrl, episodeKey, state.errorMessage) {
        if (state.errorMessage != null || activeUrl.isNullOrBlank() || activeCacheKey.isNullOrBlank() || episodeKey == null) {
            exoPlayer.pause()
            exoPlayer.clearMediaItems()
        } else {
            videoCacheManager.cancelPreloadsExcept(setOfNotNull(episodeKey, state.stream?.nextEpisodeKey))
            // If already switched gaplessly via exoPlayer.seekTo(nextIndex), do NOT re-prepare!
            val currentItem = exoPlayer.currentMediaItem
            if (currentItem?.mediaId == episodeKey &&
                currentItem.localConfiguration?.uri?.toString() == activeUrl
            ) {
                return@LaunchedEffect
            }
            showUpNextOverlay = false
            activeSkipSegment = null
            isBuffering = true
            val resumeMs = state.initialResumePositionMs.coerceAtLeast(0L)
            progressState.positionMs = resumeMs
            progressState.durationMs = state.initialDurationMs
            progressState.bufferedMs = resumeMs

            val preloadedSource = videoCacheManager.warmupPool.takePreparedSource(
                episodeKey = episodeKey,
                cacheKey = activeCacheKey,
                resumePositionMs = resumeMs
            )
            if (preloadedSource != null) {
                exoPlayer.setMediaSource(preloadedSource, resumeMs)
            } else {
                val mediaItem = MediaItem.Builder()
                    .setUri(activeUrl)
                    .setMediaId(episodeKey)
                    .setCustomCacheKey(activeCacheKey)
                    .build()
                exoPlayer.setMediaItem(mediaItem, resumeMs)
            }
            exoPlayer.prepare()
            exoPlayer.setPlaybackSpeed(playbackSpeed)
            val started = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            exoPlayer.playWhenReady = started
            resumeOnStart = !started
            if (started) viewModel.startStreamingBoost(
                activeUrl = activeUrl,
                activeCacheKey = activeCacheKey,
                initialPositionMs = resumeMs,
                initialDurationMs = state.initialDurationMs
            )
            hudVisible = true
            hudInteractionNonce++
        }
    }

    // Append pre-warmed Watch Next episode to ExoPlayer playlist so Media3 pre-demuxes & pre-buffers 15s in RAM
    val preparedNext = state.preparedNextEpisode
    LaunchedEffect(exoPlayer, episodeKey, preparedNext?.episodeKey, preparedNext?.primaryUrl) {
        val curKey = episodeKey ?: return@LaunchedEffect
        val nextUrl = preparedNext?.primaryUrl ?: return@LaunchedEffect
        if (exoPlayer.currentMediaItem?.mediaId != curKey) return@LaunchedEffect
        val nextIndex = exoPlayer.currentMediaItemIndex + 1
        if (nextIndex < exoPlayer.mediaItemCount &&
            exoPlayer.getMediaItemAt(nextIndex).mediaId == preparedNext.episodeKey
        ) {
            return@LaunchedEffect
        }
        val nextMediaItem = MediaItem.Builder()
            .setUri(nextUrl)
            .setMediaId(preparedNext.episodeKey)
            .setCustomCacheKey(preparedNext.activeCacheKey)
            .build()
        if (nextIndex < exoPlayer.mediaItemCount) {
            exoPlayer.removeMediaItems(nextIndex, exoPlayer.mediaItemCount)
        }
        exoPlayer.addMediaItem(nextIndex, nextMediaItem)
    }

    // Single lightweight 500ms polling loop: updates isolated PlaybackProgressState, booster,
    // Skip OP/ED trigger evaluation (0 LaunchedEffect churn!), stall watchdog, and 15s Room save.
    LaunchedEffect(exoPlayer, episodeKey) {
        var tick = 0
        var continuousBufferingMs = 0L
        while (true) {
            delay(500L)
            val pos = exoPlayer.currentPosition.coerceAtLeast(0L)
            val dur = exoPlayer.duration
            val resolvedDur = if (dur > 0L) dur else progressState.durationMs
            val buf = exoPlayer.bufferedPosition.coerceAtLeast(0L)

            progressState.positionMs = pos
            if (dur > 0L) progressState.durationMs = dur
            progressState.bufferedMs = buf

            viewModel.updateBoosterProgress(pos, resolvedDur, buf)
            val playingNow = exoPlayer.isPlaying
            if (playingNow && (buf - pos) >= 15_000L) {
                viewModel.onPlaybackHealthyBufferAhead()
            }
            if (latestIsBuffering && exoPlayer.playWhenReady && !latestShowUpNext && (buf - pos) < 1_500L) {
                continuousBufferingMs += 500L
                if (continuousBufferingMs >= 15_000L) {
                    continuousBufferingMs = 0L
                    viewModel.onBufferingStallDetected(pos)
                }
            } else {
                continuousBufferingMs = 0L
            }

            // Evaluate Skip OP / Skip ED triggers directly here without restarting Compose effects
            val curSegment = activeSkipSegment
            if (curSegment != null) {
                if (latestHudVisible ||
                    latestShowUpNext ||
                    !SkipPromptLogic.isStillInSegmentWindow(pos, resolvedDur, curSegment)
                ) {
                    activeSkipSegment = null
                }
            } else {
                val curState = latestState
                val curSettings = latestSettings
                if (curSettings.skipPromptEnabled &&
                    curState.skipTimesResolved &&
                    playingNow &&
                    !latestHudVisible &&
                    !latestShowUpNext &&
                    resolvedDur > 0L
                ) {
                    val allowFallbackOp = curState.initialResumePositionMs < FALLBACK_OP_TRIGGER_END_MS
                    val candidates = SkipPromptLogic.resolveCandidateSegments(
                        exactSkipTimes = curState.skipTimes,
                        durationMs = resolvedDur,
                        skipOpFallbackSeconds = curSettings.skipOpSeconds,
                        allowFallbackOp = allowFallbackOp
                    )
                    val triggered = SkipPromptLogic.findTriggerableSegment(
                        positionMs = pos,
                        durationMs = resolvedDur,
                        segments = candidates,
                        handledTypes = handledSkipTypes
                    )
                    if (triggered != null) {
                        handledSkipTypes.add(triggered.type)
                        activeSkipSegment = triggered
                    }
                }
            }

            tick++
            // Persist progress every 15s during active playback (also saved on pause/stop/dispose)
            if (tick % 30 == 0 && playingNow && resolvedDur > 0L) {
                viewModel.persistProgress(pos, resolvedDur)
            }
        }
    }

    // Auto-hide HUD after 4.5s while playing
    LaunchedEffect(hudVisible, isPlaying, hudInteractionNonce, showUpNextOverlay) {
        if (hudVisible && isPlaying && !showUpNextOverlay) {
            delay(4500L)
            hudVisible = false
            runCatching { surfaceFocusRequester.requestFocus() }
        }
    }

    // Focus Play/Pause when HUD opens, or surface when HUD closes
    LaunchedEffect(hudVisible) {
        if (hudVisible) activeSkipSegment = null
        delay(50L)
        if (hudVisible) {
            runCatching { playPauseFocusRequester.requestFocus() }
        } else {
            runCatching { surfaceFocusRequester.requestFocus() }
        }
    }

    // Focus Up Next Play Now button when overlay shows + 5s countdown
    LaunchedEffect(showUpNextOverlay) {
        if (showUpNextOverlay) {
            activeSkipSegment = null
            delay(60L)
            runCatching { upNextPlayFocusRequester.requestFocus() }
            while (upNextSecondsLeft > 0 && showUpNextOverlay) {
                delay(1000L)
                upNextSecondsLeft--
            }
            if (showUpNextOverlay && upNextSecondsLeft <= 0) {
                showUpNextOverlay = false
                latestState.stream?.nextEpisodeKey?.let { switchToEpisodeInstant(it) }
            }
        }
    }

    // Clear seek badge after 1.2s
    LaunchedEffect(seekFeedbackNonce) {
        if (seekFeedback != null) {
            delay(1200L)
            seekFeedback = null
        }
    }

    fun triggerSeek(deltaMs: Long, label: String) {
        val dur = exoPlayer.duration.coerceAtLeast(progressState.durationMs)
        val target = (exoPlayer.currentPosition + deltaMs).coerceAtLeast(0L).let {
            if (dur > 0L) it.coerceAtMost(dur) else it
        }
        exoPlayer.seekTo(target)
        progressState.positionMs = target
        seekFeedback = label
        seekFeedbackNonce++
        hudInteractionNonce++
    }

    fun executeSkipAction(action: SkipAction) {
        val dur = exoPlayer.duration.coerceAtLeast(progressState.durationMs)
        if (action.kind == SkipActionKind.NEXT_EPISODE) {
            val nextKey = latestState.stream?.nextEpisodeKey
            if (!nextKey.isNullOrBlank()) {
                viewModel.persistProgress(
                    positionMs = dur,
                    durationMs = dur,
                    forceWatched = true
                )
                seekFeedback = action.feedbackLabel
                seekFeedbackNonce++
                switchToEpisodeInstant(nextKey)
                return
            }
        }
        val target = action.targetPositionMs.coerceAtLeast(0L).let {
            if (dur > 0L) it.coerceAtMost(dur) else it
        }
        exoPlayer.seekTo(target)
        progressState.positionMs = target
        seekFeedback = action.feedbackLabel
        seekFeedbackNonce++
        hudInteractionNonce++
    }

    fun exitPlayer() {
        viewModel.persistProgress(
            positionMs = exoPlayer.currentPosition.coerceAtLeast(0L),
            durationMs = exoPlayer.duration.coerceAtLeast(progressState.durationMs)
        )
        onBack()
    }

    BackHandler {
        when {
            showUpNextOverlay -> showUpNextOverlay = false
            activeSkipSegment != null && !hudVisible -> activeSkipSegment = null
            hudVisible && state.errorMessage == null && !state.isLoading -> hudVisible = false
            else -> exitPlayer()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(surfaceFocusRequester)
            .focusable()
            .onPreviewKeyEvent { keyEvent ->
                if (state.errorMessage != null || keyEvent.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (keyEvent.key) {
                    Key.MediaPlayPause -> {
                        if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                        hudVisible = true
                        hudInteractionNonce++
                        true
                    }
                    Key.MediaPlay -> {
                        exoPlayer.play()
                        hudInteractionNonce++
                        true
                    }
                    Key.MediaPause -> {
                        exoPlayer.pause()
                        hudVisible = true
                        hudInteractionNonce++
                        true
                    }
                    Key.MediaRewind -> {
                        triggerSeek(-10_000L, "-10s")
                        true
                    }
                    Key.MediaFastForward -> {
                        triggerSeek(+10_000L, "+10s")
                        true
                    }
                    Key.DirectionLeft -> {
                        if (!hudVisible && !showUpNextOverlay) {
                            triggerSeek(-10_000L, "-10s")
                            true
                        } else {
                            hudInteractionNonce++
                            false
                        }
                    }
                    Key.DirectionRight -> {
                        if (!hudVisible && !showUpNextOverlay) {
                            triggerSeek(+10_000L, "+10s")
                            true
                        } else {
                            hudInteractionNonce++
                            false
                        }
                    }
                    Key.DirectionUp, Key.DirectionDown -> {
                        if (!hudVisible && !showUpNextOverlay) {
                            activeSkipSegment = null
                            hudVisible = true
                            hudInteractionNonce++
                            true
                        } else {
                            hudInteractionNonce++
                            false
                        }
                    }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        val segment = activeSkipSegment
                        if (segment != null && !hudVisible && !showUpNextOverlay) {
                            val action = SkipPromptLogic.computeSkipAction(
                                segment = segment,
                                currentPositionMs = exoPlayer.currentPosition.coerceAtLeast(0L),
                                durationMs = exoPlayer.duration.coerceAtLeast(progressState.durationMs),
                                fallbackSkipSeconds = latestSettings.skipOpSeconds,
                                hasNextEpisode = !latestState.stream?.nextEpisodeKey.isNullOrBlank()
                            )
                            activeSkipSegment = null
                            executeSkipAction(action)
                            true
                        } else if (!hudVisible && !showUpNextOverlay) {
                            hudVisible = true
                            hudInteractionNonce++
                            true
                        } else {
                            hudInteractionNonce++
                            false
                        }
                    }
                    else -> false
                }
            }
    ) {
        // Video Surface (zero-copy hardware SurfaceView)
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    setKeepContentOnPlayerReset(true)
                    resizeMode = resizeOption.mode
                    setBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            update = { playerView ->
                if (playerView.resizeMode != resizeOption.mode) {
                    playerView.resizeMode = resizeOption.mode
                }
            },
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize()
        )

        // Loading / Buffering Spinner (220ms anti-flicker debounce when disk cache is warm)
        if (showBufferingSpinner) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(color = palette.crimson)
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = if (state.isLoading) {
                        "Resolving stream..."
                    } else {
                        "Buffering Mirror ${state.mirrorState.displayIndex}/${state.mirrorState.totalCount}..."
                    },
                    color = Color.White,
                    fontSize = 15.sp
                )
            }
        }

        // Transient Seek Badge (-10s / +10s / +85s Skip OP)
        if (seekFeedback != null && !hudVisible) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0xCC121212), SEEK_BADGE_SHAPE)
                    .border(2.dp, palette.crimson, SEEK_BADGE_SHAPE)
                    .padding(horizontal = 24.dp, vertical = 12.dp)
            ) {
                Text(
                    text = seekFeedback.orEmpty(),
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Stream / Mirror Error Overlay
        if (state.errorMessage != null) {
            LaunchedEffect(state.errorMessage) {
                delay(60L)
                runCatching { retryFocusRequester.requestFocus() }
            }
            AhErrorBox(
                errorMessage = state.errorMessage ?: "Playback failed",
                onRetry = viewModel::retryCurrentEpisode,
                retryModifier = Modifier.focusRequester(retryFocusRequester)
            )
        }

        // AnimeHeaven TV Player HUD
        AnimatedVisibility(
            visible = hudVisible && !showUpNextOverlay && state.errorMessage == null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Top Gradient Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .background(HUD_TOP_SCRIM)
                        .padding(horizontal = AhOverscanHorizontal, vertical = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        HudPillButton(
                            icon = Icons.AutoMirrored.Rounded.ArrowBack,
                            label = "Back",
                            onClick = { exitPlayer() }
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = state.animeTitle.ifBlank { "AnimeHeaven" },
                                color = palette.crimson,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = state.stream?.episodeLabel ?: "Loading...",
                                color = Color.White,
                                fontSize = 15.sp,
                                maxLines = 1
                            )
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (state.mirrorState.totalCount > 0) {
                            HudPillButton(
                                icon = AhIcons.SwapHoriz,
                                label = "Mirror ${state.mirrorState.displayIndex}/${state.mirrorState.totalCount}",
                                onClick = {
                                    viewModel.switchMirrorManually(exoPlayer.currentPosition.coerceAtLeast(0L))
                                    hudInteractionNonce++
                                }
                            )
                        }
                        HudPillButton(
                            icon = AhIcons.AspectRatio,
                            label = resizeOption.label,
                            onClick = {
                                val entries = ResizeOption.entries
                                resizeOption = entries[(resizeOption.ordinal + 1) % entries.size]
                                hudInteractionNonce++
                            }
                        )
                        HudPillButton(
                            icon = AhIcons.Speed,
                            label = "${playbackSpeed}x",
                            onClick = {
                                val nextIdx = (PLAYBACK_SPEEDS.indexOf(playbackSpeed) + 1) % PLAYBACK_SPEEDS.size
                                playbackSpeed = PLAYBACK_SPEEDS[nextIdx]
                                exoPlayer.setPlaybackSpeed(playbackSpeed)
                                hudInteractionNonce++
                            }
                        )
                    }
                }

                // Bottom Gradient Bar: Isolated Seekbar + Control Row
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(HUD_BOTTOM_SCRIM)
                        .padding(horizontal = AhOverscanHorizontal, vertical = 22.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    PlayerSeekSection(
                        progressState = progressState,
                        seekFeedback = seekFeedback,
                        accentColor = palette.crimson,
                        secondaryTextColor = HUD_TEXT_SECONDARY
                    )

                    val stream = state.stream
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        HudActionButton(
                            icon = AhIcons.SkipPrevious,
                            label = "Prev Ep",
                            enabled = stream?.prevEpisodeKey != null,
                            onClick = {
                                stream?.prevEpisodeKey?.let { key ->
                                    viewModel.persistProgress(
                                        exoPlayer.currentPosition.coerceAtLeast(0L),
                                        exoPlayer.duration.coerceAtLeast(progressState.durationMs)
                                    )
                                    viewModel.loadEpisode(key)
                                }
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        HudActionButton(
                            icon = AhIcons.Replay10,
                            label = "-10s",
                            onClick = { triggerSeek(-10_000L, "-10s") }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        HudActionButton(
                            icon = if (isPlaying) AhIcons.Pause else Icons.Rounded.PlayArrow,
                            label = if (isPlaying) "Pause" else "Play",
                            isPrimary = true,
                            modifier = Modifier.focusRequester(playPauseFocusRequester),
                            onClick = {
                                if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                                hudInteractionNonce++
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        HudActionButton(
                            icon = AhIcons.FastForward,
                            label = "+10s",
                            onClick = { triggerSeek(+10_000L, "+10s") }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        HudActionButton(
                            icon = AhIcons.Forward30,
                            label = if (state.skipTimes.op != null) {
                                "Skip OP"
                            } else {
                                "Skip OP (+${settings.skipOpSeconds}s)"
                            },
                            onClick = {
                                val action = SkipPromptLogic.computeManualHudSkipOp(
                                    exactOp = state.skipTimes.op,
                                    currentPositionMs = exoPlayer.currentPosition.coerceAtLeast(0L),
                                    durationMs = exoPlayer.duration.coerceAtLeast(progressState.durationMs),
                                    fallbackSkipSeconds = settings.skipOpSeconds
                                )
                                executeSkipAction(action)
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        HudActionButton(
                            icon = AhIcons.FastForward,
                            label = if (state.skipTimes.ed != null || stream?.nextEpisodeKey == null) {
                                "Skip ED"
                            } else {
                                "Skip ED → Next"
                            },
                            onClick = {
                                val action = SkipPromptLogic.computeManualHudSkipEd(
                                    exactEd = state.skipTimes.ed,
                                    currentPositionMs = exoPlayer.currentPosition.coerceAtLeast(0L),
                                    durationMs = exoPlayer.duration.coerceAtLeast(progressState.durationMs),
                                    fallbackSkipSeconds = settings.skipOpSeconds,
                                    hasNextEpisode = !stream?.nextEpisodeKey.isNullOrBlank()
                                )
                                executeSkipAction(action)
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        HudActionButton(
                            icon = AhIcons.SkipNext,
                            label = "Next Ep",
                            enabled = stream?.nextEpisodeKey != null,
                            onClick = {
                                stream?.nextEpisodeKey?.let { key ->
                                    viewModel.persistProgress(
                                        exoPlayer.currentPosition.coerceAtLeast(0L),
                                        exoPlayer.duration.coerceAtLeast(progressState.durationMs)
                                    )
                                    switchToEpisodeInstant(key)
                                }
                            }
                        )
                    }
                }
            }
        }

        // Isolated One-Press OK Skip OP / Skip ED Floating Prompt (60fps GPU-animated bar, 0 root recompositions)
        val segment = activeSkipSegment
        AnimatedVisibility(
            visible = segment != null && !hudVisible && !showUpNextOverlay && state.errorMessage == null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(horizontal = AhOverscanHorizontal, vertical = 32.dp)
        ) {
            if (segment != null) {
                SkipPromptOverlay(
                    segment = segment,
                    currentPositionMs = exoPlayer.currentPosition.coerceAtLeast(0L),
                    durationMs = exoPlayer.duration.coerceAtLeast(progressState.durationMs),
                    fallbackSkipSeconds = settings.skipOpSeconds,
                    nextEpisodeLabel = state.stream?.nextEpisodeLabel,
                    hasNextEpisode = !state.stream?.nextEpisodeKey.isNullOrBlank(),
                    onExpired = {
                        if (activeSkipSegment == segment) activeSkipSegment = null
                    },
                    onExecute = { action ->
                        activeSkipSegment = null
                        executeSkipAction(action)
                    }
                )
            }
        }

        // Auto-Play Next Episode Countdown Overlay
        if (showUpNextOverlay && state.stream?.nextEpisodeKey != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(horizontal = AhOverscanHorizontal, vertical = 32.dp)
                    .background(Color(0xEE121212), AhShape4)
                    .border(2.dp, palette.crimson, AhShape4)
                    .padding(18.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "UP NEXT IN ${upNextSecondsLeft}s",
                        color = palette.crimson,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = "${state.animeTitle} — ${state.stream?.nextEpisodeLabel.orEmpty()}",
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        AhFocusableBox(
                            onClick = {
                                showUpNextOverlay = false
                                state.stream?.nextEpisodeKey?.let { switchToEpisodeInstant(it) }
                            },
                            modifier = Modifier.focusRequester(upNextPlayFocusRequester),
                            containerColor = palette.crimson,
                            focusedContainerColor = Color.White,
                            focusedBorderColor = palette.crimson
                        ) { isFocused ->
                            Text(
                                text = "Play Now",
                                color = if (isFocused) palette.crimson else Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                        AhStaticFocusableBox(
                            onClick = { showUpNextOverlay = false },
                            containerColor = HUD_SURFACE_ELEVATED,
                            focusedContainerColor = palette.crimson,
                            unfocusedBorderColor = HUD_BORDER_SUBTLE,
                            focusedBorderColor = Color.White
                        ) {
                            Text(
                                text = "Cancel",
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Isolated seekbar + time labels: only THIS tiny composable recomposes on 500ms ticks when the
 * HUD is visible, and the bar itself draws purely in the Draw phase via Canvas (zero relayout).
 */
@Composable
private fun PlayerSeekSection(
    progressState: PlaybackProgressState,
    seekFeedback: String?,
    accentColor: Color,
    secondaryTextColor: Color
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formatDurationMs(progressState.positionMs),
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            if (seekFeedback != null) {
                Text(
                    text = seekFeedback,
                    color = accentColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = formatDurationMs(progressState.durationMs),
                color = secondaryTextColor,
                fontSize = 14.sp
            )
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
        ) {
            val corner = CornerRadius(4.dp.toPx(), 4.dp.toPx())
            val safeDuration = progressState.durationMs.coerceAtLeast(1L).toFloat()
            val bufferFrac = (progressState.bufferedMs.toFloat() / safeDuration).coerceIn(0f, 1f)
            val progressFrac = (progressState.positionMs.toFloat() / safeDuration).coerceIn(0f, 1f)

            drawRoundRect(color = SEEK_TRACK_BG, cornerRadius = corner)
            if (bufferFrac > 0f) {
                drawRoundRect(
                    color = SEEK_BUFFER_COLOR,
                    size = Size(size.width * bufferFrac, size.height),
                    cornerRadius = corner
                )
            }
            if (progressFrac > 0f) {
                drawRoundRect(
                    color = accentColor,
                    size = Size(size.width * progressFrac, size.height),
                    cornerRadius = corner
                )
            }
        }
    }
}

/**
 * Isolated 7-second Skip OP/ED overlay: uses a single GPU-driven `Animatable` + `graphicsLayer`
 * `scaleX` for the depleting bar (60fps smooth, zero relayout/recomposition of PlayerScreen).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SkipPromptOverlay(
    segment: SkipSegment,
    currentPositionMs: Long,
    durationMs: Long,
    fallbackSkipSeconds: Int,
    nextEpisodeLabel: String?,
    hasNextEpisode: Boolean,
    onExpired: () -> Unit,
    onExecute: (SkipAction) -> Unit
) {
    val palette = LocalAhPalette.current
    var remainingSeconds by remember(segment) { mutableIntStateOf(SKIP_PROMPT_COUNTDOWN_SECONDS) }
    val barProgress = remember(segment) { Animatable(1f) }

    LaunchedEffect(segment) {
        barProgress.animateTo(
            targetValue = 0f,
            animationSpec = tween(
                durationMillis = SKIP_PROMPT_COUNTDOWN_SECONDS * 1000,
                easing = LinearEasing
            )
        )
        onExpired()
    }

    LaunchedEffect(segment) {
        for (sec in SKIP_PROMPT_COUNTDOWN_SECONDS downTo 1) {
            remainingSeconds = sec
            delay(1000L)
        }
    }

    val action = remember(segment, durationMs, fallbackSkipSeconds, hasNextEpisode) {
        SkipPromptLogic.computeSkipAction(
            segment = segment,
            currentPositionMs = currentPositionMs,
            durationMs = durationMs,
            fallbackSkipSeconds = fallbackSkipSeconds,
            hasNextEpisode = hasNextEpisode
        )
    }
    val titleText = when {
        segment.type == SkipSegmentType.OP -> "Skip OP"
        action.kind == SkipActionKind.NEXT_EPISODE -> "Skip ED • Next Ep"
        else -> "Skip ED"
    }
    val destinationText = when {
        action.kind == SkipActionKind.NEXT_EPISODE ->
            "Jump to ${nextEpisodeLabel ?: "Next Episode"}"
        segment.isExact ->
            "Jump to ${formatDurationMs(action.targetPositionMs)}"
        segment.type == SkipSegmentType.OP ->
            "Jump +${fallbackSkipSeconds}s"
        else ->
            "Jump to ${formatDurationMs(action.targetPositionMs)}"
    }

    Column(
        modifier = Modifier
            .width(285.dp)
            .background(Color(0xEE121212), AhShape4)
            .border(2.5.dp, palette.crimson, AhShape4)
            .focusProperties { canFocus = false }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                onExecute(action)
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .background(palette.crimson, AhShape3)
                            .padding(horizontal = 7.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "OK",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                    Text(
                        text = titleText,
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = "${remainingSeconds}s",
                    color = palette.crimson,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
            Text(
                text = "Press OK to $destinationText",
                color = HUD_TEXT_SECONDARY,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Smooth 60fps GPU-layer countdown bar (zero layout passes)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .background(Color(0x44FFFFFF))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        transformOrigin = SKIP_PROMPT_TRANSFORM_ORIGIN
                        scaleX = barProgress.value.coerceIn(0f, 1f)
                    }
                    .background(palette.crimson)
            )
        }
    }
}

@Composable
private fun HudPillButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    val palette = LocalAhPalette.current
    AhStaticFocusableBox(
        onClick = onClick,
        shape = AhShape20,
        containerColor = Color(0xCC121212),
        focusedContainerColor = palette.crimson,
        unfocusedBorderColor = HUD_BORDER_SUBTLE,
        focusedBorderColor = Color.White
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = Color.White,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = label,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

@Composable
private fun HudActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isPrimary: Boolean = false
) {
    val palette = LocalAhPalette.current
    AhFocusableBox(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = if (isPrimary) CircleShape else AhShape4,
        containerColor = when {
            !enabled -> Color(0x55121212)
            isPrimary -> palette.crimson
            else -> Color(0xCC121212)
        },
        focusedContainerColor = if (isPrimary) Color.White else palette.crimson,
        unfocusedBorderColor = if (isPrimary) palette.crimson else HUD_BORDER_SUBTLE,
        focusedBorderColor = if (isPrimary) palette.crimson else Color.White,
        focusScale = 1.07f
    ) { isFocused ->
        Row(
            modifier = Modifier.padding(
                horizontal = if (isPrimary) 14.dp else 10.dp,
                vertical = if (isPrimary) 10.dp else 8.dp
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = when {
                    !enabled -> Color(0x66FFFFFF)
                    isPrimary && isFocused -> palette.crimson
                    else -> Color.White
                },
                modifier = Modifier.size(if (isPrimary) 22.dp else 19.dp)
            )
            Text(
                text = label,
                color = when {
                    !enabled -> Color(0x66FFFFFF)
                    isPrimary && isFocused -> palette.crimson
                    else -> Color.White
                },
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}
