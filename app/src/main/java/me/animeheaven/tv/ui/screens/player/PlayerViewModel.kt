package me.animeheaven.tv.ui.screens.player

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.animeheaven.tv.data.cache.AnimePreloader
import me.animeheaven.tv.data.cache.PreparedEpisodeWarmup
import me.animeheaven.tv.data.cache.VideoCacheManager
import me.animeheaven.tv.data.local.AppSettingsStore
import me.animeheaven.tv.data.model.EpisodeStream
import me.animeheaven.tv.data.network.EpisodeSkipTimes
import me.animeheaven.tv.data.network.SkipTimesClient
import me.animeheaven.tv.data.network.SkipTimesParser
import me.animeheaven.tv.data.repository.AnimeHeavenRepository
import me.animeheaven.tv.data.runCatchingCancellable

@Immutable
data class MirrorQueueState(
    val sources: List<String> = emptyList(),
    val activeIndex: Int = 0,
    val exhausted: Boolean = false
) {
    val currentUrl: String? get() = sources.getOrNull(activeIndex)
    val totalCount: Int get() = sources.size
    val displayIndex: Int get() = if (sources.isEmpty()) 0 else activeIndex + 1

    fun advanceOnError(): MirrorQueueState {
        return if (activeIndex + 1 < sources.size) {
            copy(activeIndex = activeIndex + 1, exhausted = false)
        } else {
            copy(exhausted = true)
        }
    }

    fun cycleManual(): MirrorQueueState {
        if (sources.isEmpty()) return this
        return copy(activeIndex = (activeIndex + 1) % sources.size, exhausted = false)
    }
}

@Immutable
data class PreparedNextEpisode(
    val episodeKey: String,
    val stream: EpisodeStream,
    val rankedMirrors: List<String>,
    val activeCacheKey: String,
    val skipTimes: EpisodeSkipTimes = EpisodeSkipTimes(),
    val durationMs: Long = 0L
) {
    val primaryUrl: String? get() = rankedMirrors.firstOrNull()
}

@Immutable
data class PlayerUiState(
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val stream: EpisodeStream? = null,
    val mirrorState: MirrorQueueState = MirrorQueueState(),
    val activeCacheKey: String? = null,
    val initialResumePositionMs: Long = 0L,
    val initialDurationMs: Long = 0L,
    val isDiskCacheWarm: Boolean = false,
    val animeId: String = "",
    val animeTitle: String = "",
    val coverUrl: String = "",
    val skipTimes: EpisodeSkipTimes = EpisodeSkipTimes(),
    val skipTimesResolved: Boolean = false,
    val preparedNextEpisode: PreparedNextEpisode? = null
)

class PlayerViewModel(
    private val repository: AnimeHeavenRepository,
    val settingsStore: AppSettingsStore,
    private val skipTimesClient: SkipTimesClient,
    initialEpisodeKey: String,
    initialAnimeId: String,
    initialAnimeTitle: String,
    initialCoverUrl: String,
    private val progressScope: CoroutineScope,
    private val videoCacheManager: VideoCacheManager? = null,
    private val preloader: AnimePreloader? = null
) : ViewModel() {

    private var skipTimesJob: Job? = null
    private var loadEpisodeJob: Job? = null
    private var requestedEpisodeKey = initialEpisodeKey
    private var attemptedFreshGate: Boolean = false
    private var mirrorAttemptStartedAtMs: Long = 0L
    private var hasRecordedStartForAttempt: Boolean = false
    private var hasPreloadedNextForEpisode: Boolean = false
    private var isUserPriorityActive: Boolean = false
    private var hasEscalatedNextEpisode: Boolean = false
    private var progressWrite: Job? = null
    private data class ProgressSave(val key: String, val position: Long, val duration: Long, val watched: Boolean)
    private var lastProgressSave: ProgressSave? = null

    private val _uiState: MutableStateFlow<PlayerUiState>
    val uiState: StateFlow<PlayerUiState>

    init {
        val warmState = buildWarmStateIfAvailable(
            episodeKey = initialEpisodeKey,
            baseAnimeId = initialAnimeId,
            baseAnimeTitle = initialAnimeTitle,
            baseCoverUrl = initialCoverUrl,
            resumeOverrideMs = null
        )
        _uiState = MutableStateFlow(
            warmState ?: PlayerUiState(
                animeId = initialAnimeId,
                animeTitle = initialAnimeTitle,
                coverUrl = initialCoverUrl
            )
        )
        uiState = _uiState.asStateFlow()

        if (warmState != null) {
            mirrorAttemptStartedAtMs = System.currentTimeMillis()
            val warmStream = warmState.stream!!
            videoCacheManager?.probeMirrorsInBackground(warmStream.videoSources)
            fetchSkipTimesAsync(warmState.animeTitle, warmStream.episodeLabel, warmStream.episodeKey)
            refreshStaleGateInBackgroundIfNeeded(warmStream.episodeKey, warmState.animeId)
        } else {
            loadEpisode(initialEpisodeKey)
        }
    }

    private fun buildWarmStateIfAvailable(
        episodeKey: String,
        baseAnimeId: String,
        baseAnimeTitle: String,
        baseCoverUrl: String,
        resumeOverrideMs: Long?
    ): PlayerUiState? {
        val vcm = videoCacheManager ?: return null
        val cachedStream = repository.peekCachedStream(episodeKey) ?: return null
        if (cachedStream.videoSources.isEmpty()) return null
        val ranked = vcm.mirrorHealthTracker.rankMirrors(cachedStream.videoSources)
        val primaryUrl = ranked.firstOrNull() ?: return null
        val cacheKey = vcm.resolveCacheKey(episodeKey, primaryUrl)
        val savedProgress = repository.peekSavedProgress(episodeKey)
        val resumeMs = resumeOverrideMs ?: savedProgress?.let {
            AnimeHeavenRepository.resolveResumePositionMs(it.positionMs, it.durationMs)
        } ?: 0L
        val durMs = savedProgress?.durationMs?.takeIf { it > 0L }
            ?: vcm.getVideoIndex(episodeKey)?.durationMs?.takeIf { it > 0L }
            ?: 0L
        if (!vcm.hasPlayableHotWindow(episodeKey, cacheKey, resumeMs, durMs)) return null

        val resolvedTitle = cachedStream.animeTitle.ifBlank { baseAnimeTitle }
        val resolvedAnimeId = cachedStream.animeId?.takeIf { it.isNotBlank() } ?: baseAnimeId
        return PlayerUiState(
            isLoading = false,
            errorMessage = null,
            stream = cachedStream,
            mirrorState = MirrorQueueState(sources = ranked),
            activeCacheKey = cacheKey,
            initialResumePositionMs = resumeMs,
            initialDurationMs = durMs,
            isDiskCacheWarm = true,
            animeId = resolvedAnimeId,
            animeTitle = resolvedTitle,
            coverUrl = baseCoverUrl,
            skipTimes = EpisodeSkipTimes(),
            skipTimesResolved = false,
            preparedNextEpisode = null
        )
    }

    private fun refreshStaleGateInBackgroundIfNeeded(episodeKey: String, animeId: String) {
        if (!repository.isCachedStreamStale(episodeKey)) return
        viewModelScope.launch {
            runCatchingCancellable {
                val fresh = repository.resolveEpisodeStream(
                    episodeKey = episodeKey,
                    animeId = animeId.ifBlank { null },
                    forceRefresh = true
                )
                if (fresh.videoSources.isNotEmpty()) {
                    videoCacheManager?.probeMirrorsInBackground(fresh.videoSources)
                }
            }
        }
    }

    fun loadEpisode(
        episodeKey: String,
        forceRefreshGate: Boolean = false,
        resumeOverrideMs: Long? = null
    ) {
        requestedEpisodeKey = episodeKey
        val currentState = _uiState.value
        attemptedFreshGate = forceRefreshGate
        hasRecordedStartForAttempt = false
        hasPreloadedNextForEpisode = false
        hasEscalatedNextEpisode = false
        videoCacheManager?.stopPlaybackBooster()
        skipTimesJob?.cancel()
        loadEpisodeJob?.cancel()

        // Zero-frame synchronous fast path when stream & playable hot window are already warm on disk
        if (!forceRefreshGate) {
            val warmState = buildWarmStateIfAvailable(
                episodeKey = episodeKey,
                baseAnimeId = currentState.animeId,
                baseAnimeTitle = currentState.animeTitle,
                baseCoverUrl = currentState.coverUrl,
                resumeOverrideMs = resumeOverrideMs
            )
            if (warmState != null) {
                mirrorAttemptStartedAtMs = System.currentTimeMillis()
                _uiState.value = warmState
                val warmStream = warmState.stream!!
                videoCacheManager?.probeMirrorsInBackground(warmStream.videoSources)
                fetchSkipTimesAsync(warmState.animeTitle, warmStream.episodeLabel, warmStream.episodeKey)
                refreshStaleGateInBackgroundIfNeeded(warmStream.episodeKey, warmState.animeId)
                return
            }
        }

        setPlaybackBufferingActive(true)
        loadEpisodeJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    errorMessage = null,
                    mirrorState = MirrorQueueState(),
                    activeCacheKey = null,
                    initialResumePositionMs = resumeOverrideMs ?: 0L,
                    initialDurationMs = 0L,
                    isDiskCacheWarm = false,
                    skipTimes = EpisodeSkipTimes(),
                    skipTimesResolved = false,
                    preparedNextEpisode = null
                )
            }
            runCatchingCancellable {
                if (forceRefreshGate) repository.invalidateGateStream(episodeKey)
                val stream = repository.resolveEpisodeStream(
                    episodeKey = episodeKey,
                    animeId = currentState.animeId.ifBlank { null },
                    forceRefresh = forceRefreshGate
                )
                val savedProgress = repository.getSavedProgress(episodeKey)
                val resumeMs = resumeOverrideMs ?: savedProgress?.let {
                    AnimeHeavenRepository.resolveResumePositionMs(it.positionMs, it.durationMs)
                } ?: 0L
                val knownDurMs = savedProgress?.durationMs?.takeIf { it > 0L }
                    ?: videoCacheManager?.getVideoIndex(episodeKey)?.durationMs?.takeIf { it > 0L }
                    ?: 0L

                val preRanked = videoCacheManager?.mirrorHealthTracker?.rankMirrors(stream.videoSources)
                    ?: stream.videoSources
                val prePrimary = preRanked.firstOrNull()
                val preCacheKey = prePrimary?.let { videoCacheManager?.resolveCacheKey(stream.episodeKey, it) }
                val alreadyWarmOnDisk = preCacheKey != null &&
                    videoCacheManager?.hasPlayableHotWindow(stream.episodeKey, preCacheKey, resumeMs, knownDurMs) == true

                if (stream.videoSources.isNotEmpty()) {
                    if (alreadyWarmOnDisk) {
                        // Start playback immediately from local disk cache; probe mirrors asynchronously!
                        videoCacheManager?.probeMirrorsInBackground(stream.videoSources)
                    } else {
                        videoCacheManager?.probeMirrorsForPlayback(stream.videoSources, maxWaitMs = 900L)
                    }
                }
                Triple(stream, resumeMs, Pair(knownDurMs, alreadyWarmOnDisk))
            }.onSuccess { (stream, resumeMs, durAndWarm) ->
                coroutineContext.ensureActive()
                val (knownDurMs, diskWarm) = durAndWarm
                if (stream.videoSources.isEmpty()) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            stream = stream,
                            errorMessage = "No playable video mirrors found for this episode.",
                            skipTimesResolved = true
                        )
                    }
                } else {
                    val resolvedTitle = stream.animeTitle.ifBlank { _uiState.value.animeTitle }
                    val rankedMirrors = videoCacheManager?.mirrorHealthTracker
                        ?.rankMirrors(stream.videoSources)
                        ?: stream.videoSources
                    val primaryUrl = rankedMirrors.first()
                    val cacheKey = videoCacheManager?.resolveCacheKey(stream.episodeKey, primaryUrl)
                        ?: primaryUrl
                    val finalDurMs = knownDurMs.takeIf { it > 0L }
                        ?: videoCacheManager?.getVideoIndex(stream.episodeKey)?.durationMs?.takeIf { it > 0L }
                        ?: 0L
                    val isWarm = diskWarm ||
                        videoCacheManager?.hasPlayableHotWindow(stream.episodeKey, cacheKey, resumeMs, finalDurMs) == true
                    mirrorAttemptStartedAtMs = System.currentTimeMillis()
                    hasRecordedStartForAttempt = false
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            stream = stream,
                            animeId = stream.animeId?.takeIf { id -> id.isNotBlank() } ?: it.animeId,
                            animeTitle = resolvedTitle,
                            mirrorState = MirrorQueueState(sources = rankedMirrors),
                            activeCacheKey = cacheKey,
                            initialResumePositionMs = resumeMs,
                            initialDurationMs = finalDurMs,
                            isDiskCacheWarm = isWarm,
                            errorMessage = null,
                            preparedNextEpisode = null
                        )
                    }
                    fetchSkipTimesAsync(resolvedTitle, stream.episodeLabel, stream.episodeKey)
                }
            }.onFailure { err ->
                coroutineContext.ensureActive()
                setPlaybackBufferingActive(false)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = err.message ?: "Failed to load episode stream",
                        skipTimesResolved = true
                    )
                }
            }
        }
    }

    /**
     * Atomically promotes the pre-buffered [PreparedNextEpisode] to active playback state with
     * `isLoading = false` and without clearing `activeUrl`, enabling 1-frame gapless switching
     * via `ExoPlayer.seekToNextMediaItem()`.
     */
    fun promotePreparedNextEpisode(): PreparedNextEpisode? {
        val snapshot = _uiState.value
        val next = snapshot.preparedNextEpisode ?: return null
        if (next.primaryUrl.isNullOrBlank()) return null

        val oldEpKey = snapshot.stream?.episodeKey
        val oldCacheKey = snapshot.activeCacheKey
        videoCacheManager?.stopPlaybackBooster()
        videoCacheManager?.markEpisodeCompleted(oldEpKey, oldCacheKey)

        requestedEpisodeKey = next.episodeKey
        attemptedFreshGate = false
        hasRecordedStartForAttempt = false
        hasPreloadedNextForEpisode = false
        hasEscalatedNextEpisode = false
        mirrorAttemptStartedAtMs = System.currentTimeMillis()
        skipTimesJob?.cancel()
        loadEpisodeJob?.cancel()

        val resolvedTitle = next.stream.animeTitle.ifBlank { snapshot.animeTitle }
        val hasSkip = next.skipTimes.op != null || next.skipTimes.ed != null
        _uiState.update {
            it.copy(
                isLoading = false,
                errorMessage = null,
                stream = next.stream,
                animeId = next.stream.animeId?.takeIf { id -> id.isNotBlank() } ?: it.animeId,
                animeTitle = resolvedTitle,
                mirrorState = MirrorQueueState(sources = next.rankedMirrors),
                activeCacheKey = next.activeCacheKey,
                initialResumePositionMs = 0L,
                initialDurationMs = next.durationMs,
                isDiskCacheWarm = true,
                skipTimes = next.skipTimes,
                skipTimesResolved = hasSkip,
                preparedNextEpisode = null
            )
        }
        if (!hasSkip) {
            fetchSkipTimesAsync(resolvedTitle, next.stream.episodeLabel, next.episodeKey)
        }
        return next
    }

    private fun onNextEpisodePrepared(warm: PreparedEpisodeWarmup) {
        val currentNextKey = _uiState.value.stream?.nextEpisodeKey
        if (currentNextKey != warm.episodeKey || warm.rankedMirrors.isEmpty()) return
        _uiState.update { state ->
            if (state.stream?.nextEpisodeKey != warm.episodeKey) state
            else state.copy(
                preparedNextEpisode = PreparedNextEpisode(
                    episodeKey = warm.episodeKey,
                    stream = warm.stream,
                    rankedMirrors = warm.rankedMirrors,
                    activeCacheKey = warm.cacheKey,
                    skipTimes = warm.skipTimes,
                    durationMs = warm.durationMs
                )
            )
        }
    }

    private fun fetchSkipTimesAsync(
        animeTitle: String,
        episodeLabel: String,
        episodeKey: String
    ) {
        skipTimesJob?.cancel()
        skipTimesJob = viewModelScope.launch {
            val epNum = SkipTimesParser.parseEpisodeNumber(episodeLabel)
            val result = if (epNum != null && animeTitle.isNotBlank()) {
                skipTimesClient.fetchSkipTimes(animeTitle, epNum)
            } else {
                EpisodeSkipTimes()
            }
            if (_uiState.value.stream?.episodeKey == episodeKey) {
                _uiState.update {
                    it.copy(skipTimes = result, skipTimesResolved = true)
                }
            }
        }
    }

    fun onMirrorPlaybackError(currentPositionMs: Long) {
        videoCacheManager?.stopPlaybackBooster()
        val snapshot = _uiState.value
        if (snapshot.isLoading || snapshot.mirrorState.exhausted || snapshot.mirrorState.currentUrl == null) return
        snapshot.mirrorState.currentUrl?.let { failedUrl ->
            videoCacheManager?.mirrorHealthTracker?.recordFailure(failedUrl)
        }
        val nextMirrors = snapshot.mirrorState.advanceOnError()
        val episodeKey = snapshot.stream?.episodeKey
        if (nextMirrors.exhausted && !attemptedFreshGate && !episodeKey.isNullOrBlank()) {
            loadEpisode(episodeKey, forceRefreshGate = true, resumeOverrideMs = currentPositionMs.coerceAtLeast(0L))
            return
        }

        _uiState.update { state ->
            val updated = state.mirrorState.advanceOnError()
            if (updated.exhausted) {
                state.copy(
                    mirrorState = updated,
                    errorMessage = "All ${updated.totalCount} video mirrors failed to play."
                )
            } else {
                val nextUrl = updated.currentUrl
                val nextCacheKey = nextUrl?.let {
                    videoCacheManager?.resolveCacheKey(state.stream?.episodeKey, it) ?: it
                }
                mirrorAttemptStartedAtMs = System.currentTimeMillis()
                hasRecordedStartForAttempt = false
                state.copy(
                    mirrorState = updated,
                    activeCacheKey = nextCacheKey,
                    initialResumePositionMs = currentPositionMs.coerceAtLeast(0L),
                    errorMessage = null
                )
            }
        }
    }

    fun onPlaybackStarted() {
        setPlaybackBufferingActive(false)
        val state = _uiState.value

        if (!hasRecordedStartForAttempt) {
            hasRecordedStartForAttempt = true
            val activeUrl = state.mirrorState.currentUrl
            if (!activeUrl.isNullOrBlank()) {
                val latencyMs = if (mirrorAttemptStartedAtMs > 0L) {
                    (System.currentTimeMillis() - mirrorAttemptStartedAtMs).coerceIn(1L, 15_000L)
                } else {
                    100L
                }
                videoCacheManager?.mirrorHealthTracker?.recordSuccess(activeUrl, latencyMs)
            }
        }

        if (!hasPreloadedNextForEpisode) {
            val stream = state.stream ?: return
            val nextKey = stream.nextEpisodeKey
            if (!nextKey.isNullOrBlank()) {
                hasPreloadedNextForEpisode = true
                preloader?.preloadNextEpisode(
                    nextEpisodeKey = nextKey,
                    animeId = state.animeId.ifBlank { stream.animeId },
                    animeTitle = state.animeTitle.ifBlank { stream.animeTitle },
                    nextEpisodeLabel = stream.nextEpisodeLabel,
                    onPrepared = ::onNextEpisodePrepared
                )
            }
        }
    }

    fun setPlaybackBufferingActive(active: Boolean) {
        if (active == isUserPriorityActive) return
        isUserPriorityActive = active
        if (active) {
            preloader?.beginUserPriorityWork()
        } else {
            preloader?.endUserPriorityWork()
        }
    }

    fun onPlaybackHealthyBufferAhead() {
        if (hasEscalatedNextEpisode) return
        val state = _uiState.value
        val nextKey = state.stream?.nextEpisodeKey?.takeIf { it.isNotBlank() } ?: return
        hasEscalatedNextEpisode = true
        preloader?.escalateNextEpisodeBuffer(
            nextEpisodeKey = nextKey,
            animeId = state.animeId.ifBlank { state.stream.animeId },
            animeTitle = state.animeTitle.ifBlank { state.stream.animeTitle },
            nextEpisodeLabel = state.stream.nextEpisodeLabel,
            onPrepared = ::onNextEpisodePrepared
        )
    }

    fun startStreamingBoost(
        activeUrl: String,
        activeCacheKey: String,
        initialPositionMs: Long,
        initialDurationMs: Long
    ) {
        val state = _uiState.value
        val sources = state.mirrorState.sources.ifEmpty { listOf(activeUrl) }
        val opEndMs = state.skipTimes.op?.endMs
            ?: (settingsStore.settings.value.skipOpSeconds * 1000L)
        val resolvedDuration = initialDurationMs.takeIf { it > 0L } ?: state.initialDurationMs
        videoCacheManager?.startPlaybackBooster(
            activeUrl = activeUrl,
            allSources = sources,
            initialPositionMs = initialPositionMs,
            initialDurationMs = resolvedDuration,
            opEndMs = opEndMs,
            episodeKey = state.stream?.episodeKey,
            explicitCacheKey = activeCacheKey
        )
    }

    fun updateBoosterProgress(positionMs: Long, durationMs: Long, bufferedMs: Long = positionMs) {
        val state = _uiState.value
        val opEndMs = state.skipTimes.op?.endMs
            ?: (settingsStore.settings.value.skipOpSeconds * 1000L)
        videoCacheManager?.updatePlaybackBoosterPosition(positionMs, durationMs, opEndMs, bufferedMs)
    }

    fun stopStreamingBoost() {
        videoCacheManager?.stopPlaybackBooster()
        setPlaybackBufferingActive(false)
    }

    fun onBufferingStallDetected(currentPositionMs: Long) {
        val snapshot = _uiState.value
        if (snapshot.mirrorState.totalCount <= 1) return
        onMirrorPlaybackError(currentPositionMs)
    }

    override fun onCleared() {
        stopStreamingBoost()
        setPlaybackBufferingActive(false)
        super.onCleared()
    }

    fun switchMirrorManually(currentPositionMs: Long) {
        mirrorAttemptStartedAtMs = System.currentTimeMillis()
        hasRecordedStartForAttempt = false
        _uiState.update { state ->
            val nextMirrors = state.mirrorState.cycleManual()
            val nextUrl = nextMirrors.currentUrl
            val nextCacheKey = nextUrl?.let {
                videoCacheManager?.resolveCacheKey(state.stream?.episodeKey, it) ?: it
            }
            state.copy(
                mirrorState = nextMirrors,
                activeCacheKey = nextCacheKey,
                initialResumePositionMs = currentPositionMs,
                errorMessage = null
            )
        }
    }

    fun retryCurrentEpisode() {
        loadEpisode(requestedEpisodeKey, forceRefreshGate = true)
    }

    fun persistProgress(
        positionMs: Long,
        durationMs: Long,
        forceWatched: Boolean = false
    ) {
        val state = _uiState.value
        val stream = state.stream ?: return
        val animeId = state.animeId.ifBlank { stream.animeId.orEmpty() }
        if (animeId.isBlank() || durationMs <= 0L) return
        val position = positionMs.coerceIn(0L, durationMs)
        if (!forceWatched && lastProgressSave?.let {
                it.key == stream.episodeKey && it.watched && it.position == position && it.duration == durationMs
            } == true) return
        val save = ProgressSave(stream.episodeKey, position, durationMs, forceWatched)
        if (lastProgressSave == save && progressWrite?.isCancelled != true) return
        lastProgressSave = save
        if (forceWatched) {
            videoCacheManager?.markEpisodeCompleted(stream.episodeKey, state.activeCacheKey)
        }
        if (!state.isLoading) _uiState.update { it.copy(initialResumePositionMs = position) }

        val previousWrite = progressWrite
        progressWrite = progressScope.launch {
            previousWrite?.join()
            runCatchingCancellable {
                repository.saveWatchProgress(
                    episodeKey = stream.episodeKey,
                    animeId = animeId,
                    animeTitle = state.animeTitle.ifBlank { stream.animeTitle },
                    coverUrl = state.coverUrl,
                    episodeLabel = stream.episodeLabel.removePrefix("Episode").trim().ifBlank { stream.episodeLabel },
                    positionMs = position,
                    durationMs = durationMs,
                    nextEpisodeKey = stream.nextEpisodeKey,
                    nextEpisodeLabel = stream.nextEpisodeLabel?.removePrefix("Episode")?.trim(),
                    forceWatched = forceWatched
                )
            }.onFailure { android.util.Log.w("PlayerViewModel", "Could not save playback position", it) }
        }
    }
}
