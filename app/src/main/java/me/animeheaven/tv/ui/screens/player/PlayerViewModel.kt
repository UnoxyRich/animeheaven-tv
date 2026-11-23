package me.animeheaven.tv.ui.screens.player

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.animeheaven.tv.data.cache.AnimePreloader
import me.animeheaven.tv.data.cache.VideoCacheManager
import me.animeheaven.tv.data.local.AppSettingsStore
import me.animeheaven.tv.data.model.EpisodeStream
import me.animeheaven.tv.data.network.EpisodeSkipTimes
import me.animeheaven.tv.data.network.SkipTimesClient
import me.animeheaven.tv.data.network.SkipTimesParser
import me.animeheaven.tv.data.repository.AnimeHeavenRepository

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
data class PlayerUiState(
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val stream: EpisodeStream? = null,
    val mirrorState: MirrorQueueState = MirrorQueueState(),
    val activeCacheKey: String? = null,
    val initialResumePositionMs: Long = 0L,
    val animeId: String = "",
    val animeTitle: String = "",
    val coverUrl: String = "",
    val skipTimes: EpisodeSkipTimes = EpisodeSkipTimes(),
    val skipTimesResolved: Boolean = false
)

class PlayerViewModel(
    private val repository: AnimeHeavenRepository,
    val settingsStore: AppSettingsStore,
    private val skipTimesClient: SkipTimesClient,
    initialEpisodeKey: String,
    initialAnimeId: String,
    initialAnimeTitle: String,
    initialCoverUrl: String,
    private val videoCacheManager: VideoCacheManager? = null,
    private val preloader: AnimePreloader? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        PlayerUiState(
            animeId = initialAnimeId,
            animeTitle = initialAnimeTitle,
            coverUrl = initialCoverUrl
        )
    )
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private var skipTimesJob: Job? = null
    private var loadEpisodeJob: Job? = null
    private var attemptedFreshGate: Boolean = false
    private var mirrorAttemptStartedAtMs: Long = 0L
    private var hasRecordedStartForAttempt: Boolean = false
    private var hasPreloadedNextForEpisode: Boolean = false
    private var isUserPriorityActive: Boolean = false
    private var hasEscalatedNextEpisode: Boolean = false

    init {
        loadEpisode(initialEpisodeKey)
    }

    fun loadEpisode(
        episodeKey: String,
        forceRefreshGate: Boolean = false,
        resumeOverrideMs: Long? = null
    ) {
        val currentState = _uiState.value
        attemptedFreshGate = forceRefreshGate
        hasRecordedStartForAttempt = false
        hasPreloadedNextForEpisode = false
        hasEscalatedNextEpisode = false
        videoCacheManager?.stopPlaybackBooster()
        setPlaybackBufferingActive(true)
        skipTimesJob?.cancel()
        loadEpisodeJob?.cancel()
        loadEpisodeJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    errorMessage = null,
                    mirrorState = MirrorQueueState(),
                    activeCacheKey = null,
                    initialResumePositionMs = resumeOverrideMs ?: 0L,
                    skipTimes = EpisodeSkipTimes(),
                    skipTimesResolved = false
                )
            }
            runCatching {
                val stream = repository.resolveEpisodeStream(
                    episodeKey = episodeKey,
                    animeId = currentState.animeId.ifBlank { null },
                    forceRefresh = forceRefreshGate
                )
                // Race for the FIRST valid full-length mirror (~80-150ms) instead of stalling up to 950ms
                if (stream.videoSources.isNotEmpty()) {
                    videoCacheManager?.probeMirrorsForPlayback(stream.videoSources, maxWaitMs = 900L)
                }
                val resumeMs = resumeOverrideMs ?: repository.getSavedPositionMs(episodeKey)
                stream to resumeMs
            }.onSuccess { (stream, resumeMs) ->
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
                            errorMessage = null
                        )
                    }
                    fetchSkipTimesAsync(resolvedTitle, stream.episodeLabel, stream.episodeKey)
                }
            }.onFailure { err ->
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
        snapshot.mirrorState.currentUrl?.let { failedUrl ->
            videoCacheManager?.mirrorHealthTracker?.recordFailure(failedUrl)
        }
        val nextMirrors = snapshot.mirrorState.advanceOnError()
        val episodeKey = snapshot.stream?.episodeKey
        if (nextMirrors.exhausted && !attemptedFreshGate && !episodeKey.isNullOrBlank()) {
            val savedResumeMs = currentPositionMs.coerceAtLeast(snapshot.initialResumePositionMs)
            viewModelScope.launch {
                repository.invalidateGateStream(episodeKey)
                loadEpisode(
                    episodeKey = episodeKey,
                    forceRefreshGate = true,
                    resumeOverrideMs = savedResumeMs
                )
            }
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
                    initialResumePositionMs = currentPositionMs.coerceAtLeast(state.initialResumePositionMs),
                    errorMessage = null
                )
            }
        }
    }

    fun onPlaybackStarted() {
        setPlaybackBufferingActive(false)
        val state = _uiState.value

        // Record startup latency ONCE per mirror attempt (prevents seeks/pauses 10 min later
        // from recording 600,000ms latency and poisoning MirrorHealthTracker!)
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
                    nextEpisodeLabel = stream.nextEpisodeLabel
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
            animeId = state.animeId.ifBlank { state.stream.animeId }
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
        videoCacheManager?.startPlaybackBooster(
            activeUrl = activeUrl,
            allSources = sources,
            initialPositionMs = initialPositionMs,
            initialDurationMs = initialDurationMs,
            opEndMs = opEndMs,
            episodeKey = state.stream?.episodeKey,
            explicitCacheKey = activeCacheKey
        )
    }

    fun updateBoosterProgress(positionMs: Long, durationMs: Long) {
        val state = _uiState.value
        val opEndMs = state.skipTimes.op?.endMs
            ?: (settingsStore.settings.value.skipOpSeconds * 1000L)
        videoCacheManager?.updatePlaybackBoosterPosition(positionMs, durationMs, opEndMs)
    }

    fun stopStreamingBoost() {
        videoCacheManager?.stopPlaybackBooster()
    }

    fun onBufferingStallDetected(currentPositionMs: Long) {
        val snapshot = _uiState.value
        if (snapshot.mirrorState.totalCount <= 1) return
        val activeUrl = snapshot.mirrorState.currentUrl ?: return
        videoCacheManager?.mirrorHealthTracker?.recordFailure(activeUrl)
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
        val key = _uiState.value.stream?.episodeKey ?: return
        viewModelScope.launch {
            repository.invalidateGateStream(key)
            loadEpisode(key, forceRefreshGate = true)
        }
    }

    fun persistProgress(
        positionMs: Long,
        durationMs: Long,
        forceWatched: Boolean = false
    ) {
        val state = _uiState.value
        val stream = state.stream ?: return
        val animeId = state.animeId.ifBlank { stream.animeId.orEmpty() }
        if (animeId.isBlank()) return

        viewModelScope.launch {
            repository.saveWatchProgress(
                episodeKey = stream.episodeKey,
                animeId = animeId,
                animeTitle = state.animeTitle.ifBlank { stream.animeTitle },
                coverUrl = state.coverUrl,
                episodeLabel = stream.episodeLabel.removePrefix("Episode").trim().ifBlank { stream.episodeLabel },
                positionMs = positionMs,
                durationMs = durationMs,
                nextEpisodeKey = stream.nextEpisodeKey,
                nextEpisodeLabel = stream.nextEpisodeLabel?.removePrefix("Episode")?.trim(),
                forceWatched = forceWatched
            )
        }
    }
}
