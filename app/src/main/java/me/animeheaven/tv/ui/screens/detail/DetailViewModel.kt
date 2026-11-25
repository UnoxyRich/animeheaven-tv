package me.animeheaven.tv.ui.screens.detail

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.animeheaven.tv.data.cache.AnimePreloader
import me.animeheaven.tv.data.local.WatchProgressEntity
import me.animeheaven.tv.data.model.AnimeDetail
import me.animeheaven.tv.data.model.ArtworkLookupRequest
import me.animeheaven.tv.data.network.AnimeArtworkResolver
import me.animeheaven.tv.data.model.EpisodeItem
import me.animeheaven.tv.data.repository.AnimeHeavenRepository
import me.animeheaven.tv.data.repository.CachedResource
import me.animeheaven.tv.ui.components.formatDurationMs

@Immutable
data class DetailActionTarget(
    val episodeKey: String,
    val episodeLabel: String,
    val buttonLabel: String
)

@Immutable
data class DetailUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
    val detail: AnimeDetail? = null,
    val isBookmarked: Boolean = false,
    val progressMap: Map<String, WatchProgressEntity> = emptyMap(),
    val primaryAction: DetailActionTarget? = null,
    val sortOldestFirst: Boolean = false
)

private data class DetailBaseState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
    val detail: AnimeDetail? = null,
    val sortOldestFirst: Boolean = false
)

class DetailViewModel(
    private val repository: AnimeHeavenRepository,
    val animeId: String,
    private val preloader: AnimePreloader? = null,
    private val artworkResolver: AnimeArtworkResolver? = null
) : ViewModel() {

    private val baseState = MutableStateFlow(DetailBaseState())
    private var lastPreloadedKey: String? = null

    val uiState: StateFlow<DetailUiState> = combine(
        baseState,
        repository.observeIsBookmarked(animeId),
        repository.observeAnimeProgressMap(animeId)
    ) { base, isBookmarked, progressMap ->
        val action = base.detail?.let { computePrimaryAction(it.episodes, progressMap) }
        DetailUiState(
            isLoading = base.isLoading,
            isRefreshing = base.isRefreshing,
            errorMessage = base.errorMessage,
            detail = base.detail,
            isBookmarked = isBookmarked,
            progressMap = progressMap,
            primaryAction = action,
            sortOldestFirst = base.sortOldestFirst
        )
    }.onEach { state ->
        val detail = state.detail ?: return@onEach
        val primaryEpKey = state.primaryAction?.episodeKey
        val primaryProg = primaryEpKey?.let { state.progressMap[it] }
        val preloadKey = "${detail.id}:$primaryEpKey:${primaryProg?.positionMs ?: 0L}"
        if (preloadKey != lastPreloadedKey) {
            lastPreloadedKey = preloadKey
            val resumeMs = primaryProg?.let {
                AnimeHeavenRepository.resolveResumePositionMs(it.positionMs, it.durationMs)
            } ?: 0L
            preloader?.preloadAnimeDetail(
                detail = detail,
                primaryEpisodeKey = primaryEpKey,
                primaryEpisodeLabel = state.primaryAction?.episodeLabel,
                primaryResumeMs = resumeMs,
                primaryDurationMs = primaryProg?.durationMs ?: 0L
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = DetailUiState()
    )

    private var loadJob: Job? = null

    init {
        load(forceRefresh = false)
    }

    fun load(forceRefresh: Boolean = true) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            repository.observeAnimeDetail(animeId, forceRefresh = forceRefresh).collect { resource ->
                when (resource) {
                    CachedResource.Loading -> {
                        baseState.update { state ->
                            state.copy(
                                isLoading = state.detail == null,
                                isRefreshing = state.detail != null,
                                errorMessage = null
                            )
                        }
                    }
                    is CachedResource.Data -> {
                        baseState.update { state ->
                            state.copy(
                                isLoading = false,
                                isRefreshing = resource.isRefreshing,
                                detail = resource.data,
                                errorMessage = null
                            )
                        }
                    }
                    is CachedResource.Error -> {
                        baseState.update { state ->
                            state.copy(
                                isLoading = false,
                                isRefreshing = false,
                                errorMessage = if (state.detail == null) resource.message else null
                            )
                        }
                    }
                }
            }
        }
    }

    fun onCardFocused(
        targetAnimeId: String,
        coverUrl: String,
        neighborIds: List<String> = emptyList(),
        neighborCovers: List<String> = emptyList()
    ) {
        preloader?.onCardFocusedWithNeighbors(targetAnimeId, coverUrl, neighborIds, neighborCovers)
    }

    fun onEpisodeFocused(
        detail: AnimeDetail,
        episode: EpisodeItem,
        progress: WatchProgressEntity?
    ) {
        val resumeMs = progress?.let {
            AnimeHeavenRepository.resolveResumePositionMs(it.positionMs, it.durationMs)
        } ?: 0L
        preloader?.onEpisodeFocused(
            animeId = detail.id,
            animeTitle = detail.title,
            episodeKey = episode.key,
            episodeLabel = episode.numberLabel,
            resumePositionMs = resumeMs,
            durationMs = progress?.durationMs ?: 0L
        )
    }

    fun toggleSortOrder() {
        baseState.update { it.copy(sortOldestFirst = !it.sortOldestFirst) }
    }

    fun landscapeCover(detail: AnimeDetail): String =
        artworkResolver?.memory(ArtworkLookupRequest(detail.id, detail.title, detail.japaneseTitle))?.landscapeFanartUrl
            ?: detail.landscapeCoverUrl.orEmpty()

    fun toggleBookmark() {
        val detail = baseState.value.detail ?: return
        viewModelScope.launch {
            repository.toggleBookmark(
                animeId = detail.id,
                title = detail.title,
                coverUrl = landscapeCover(detail),
                lastEpisodeLabel = detail.episodes.firstOrNull()?.numberLabel ?: detail.episodesLabel
            )
        }
    }

    companion object {
        /**
         * AnimeHeaven orders episodes newest-first ([Ep N, Ep N-1, ..., Ep 1]).
         * - Mid-episode progress -> Resume current episode
         * - Finished episode (isWatched) + newer episode exists -> Play Next episode
         * - Otherwise -> Watch earliest episode (last in list)
         */
        fun computePrimaryAction(
            episodes: List<EpisodeItem>,
            progressMap: Map<String, WatchProgressEntity>
        ): DetailActionTarget? {
            if (episodes.isEmpty()) return null
            val latestProgress = episodes
                .mapNotNull { progressMap[it.key] }
                .maxByOrNull { it.updatedAt }

            if (latestProgress != null) {
                if (!latestProgress.isWatched &&
                    latestProgress.positionMs > AnimeHeavenRepository.MIN_RESUME_THRESHOLD_MS
                ) {
                    return DetailActionTarget(
                        episodeKey = latestProgress.episodeKey,
                        episodeLabel = latestProgress.episodeLabel,
                        buttonLabel = "Resume Ep ${latestProgress.episodeLabel} (${formatDurationMs(latestProgress.positionMs)})"
                    )
                }
                if (latestProgress.isWatched) {
                    val idx = episodes.indexOfFirst { it.key == latestProgress.episodeKey }
                    if (idx > 0) {
                        val nextEp = episodes[idx - 1]
                        return DetailActionTarget(
                            episodeKey = nextEp.key,
                            episodeLabel = nextEp.numberLabel,
                            buttonLabel = "Play Next: Ep ${nextEp.numberLabel}"
                        )
                    }
                    return DetailActionTarget(
                        episodeKey = latestProgress.episodeKey,
                        episodeLabel = latestProgress.episodeLabel,
                        buttonLabel = "Rewatch Ep ${latestProgress.episodeLabel}"
                    )
                }
            }

            val firstEp = episodes.last()
            return DetailActionTarget(
                episodeKey = firstEp.key,
                episodeLabel = firstEp.numberLabel,
                buttonLabel = "Watch Episode ${firstEp.numberLabel}"
            )
        }
    }
}
