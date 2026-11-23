package me.animeheaven.tv.ui.screens.catalog

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.animeheaven.tv.data.cache.AnimePreloader
import me.animeheaven.tv.data.local.AppSettingsStore
import me.animeheaven.tv.data.model.AnimeCard
import me.animeheaven.tv.data.model.FeedViewMode
import me.animeheaven.tv.data.repository.AnimeHeavenRepository
import me.animeheaven.tv.data.repository.CachedResource

enum class CatalogKind {
    NEW,
    POPULAR,
    SEASONAL,
    TAG
}

@Immutable
data class CatalogUiState(
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val title: String = "",
    val items: List<AnimeCard> = emptyList(),
    val bookmarkedIds: Set<String> = emptySet(),
    val viewMode: FeedViewMode = FeedViewMode.LIST,
    val seasonLabel: String? = null,
    val seasonPath: String? = null
)

private data class CatalogBaseState(
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val title: String = "",
    val items: List<AnimeCard> = emptyList(),
    val viewMode: FeedViewMode = FeedViewMode.LIST
)

class CatalogViewModel(
    private val repository: AnimeHeavenRepository,
    private val settingsStore: AppSettingsStore,
    val kind: CatalogKind,
    val param: String,
    initialTitle: String,
    private val preloader: AnimePreloader? = null
) : ViewModel() {

    private val baseState = MutableStateFlow(
        CatalogBaseState(
            title = initialTitle,
            viewMode = settingsStore.settings.value.defaultViewMode
        )
    )

    val uiState: StateFlow<CatalogUiState> = combine(
        baseState,
        repository.observeBookmarkedIds(),
        repository.seasonHeader
    ) { base, bookmarkedIds, header ->
        CatalogUiState(
            isLoading = base.isLoading,
            errorMessage = base.errorMessage,
            title = base.title,
            items = base.items,
            bookmarkedIds = bookmarkedIds,
            viewMode = base.viewMode,
            seasonLabel = header?.first,
            seasonPath = header?.second
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = CatalogUiState(
            title = initialTitle,
            viewMode = settingsStore.settings.value.defaultViewMode,
            seasonLabel = repository.seasonHeader.value?.first,
            seasonPath = repository.seasonHeader.value?.second
        )
    )

    private var loadJob: Job? = null

    init {
        if (repository.seasonHeader.value == null) {
            viewModelScope.launch {
                runCatching { repository.getHomeFeed(forceRefresh = false) }
            }
        }
        load(forceRefresh = false)
    }

    fun load(forceRefresh: Boolean = true) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val flow = when (kind) {
                CatalogKind.NEW -> repository.observeNewEpisodes(forceRefresh = forceRefresh)
                CatalogKind.POPULAR -> repository.observePopularToday(forceRefresh = forceRefresh)
                CatalogKind.SEASONAL -> repository.observeSeasonal(param, forceRefresh = forceRefresh)
                CatalogKind.TAG -> repository.observeByTag(param, forceRefresh = forceRefresh)
            }
            flow.collect { resource ->
                when (resource) {
                    CachedResource.Loading -> {
                        baseState.update { state ->
                            state.copy(
                                isLoading = state.items.isEmpty(),
                                errorMessage = null
                            )
                        }
                    }
                    is CachedResource.Data -> {
                        val (parsedTitle, list) = resource.data
                        baseState.update {
                            it.copy(
                                isLoading = false,
                                title = parsedTitle.ifBlank { it.title },
                                items = list,
                                errorMessage = null
                            )
                        }
                        preloader?.preloadCatalogPage(list)
                    }
                    is CachedResource.Error -> {
                        baseState.update { state ->
                            state.copy(
                                isLoading = false,
                                errorMessage = if (state.items.isEmpty()) resource.message else null
                            )
                        }
                    }
                }
            }
        }
    }

    fun onCardFocused(
        animeId: String,
        coverUrl: String,
        neighborIds: List<String> = emptyList(),
        neighborCovers: List<String> = emptyList()
    ) {
        preloader?.onCardFocusedWithNeighbors(animeId, coverUrl, neighborIds, neighborCovers)
    }

    fun setViewMode(mode: FeedViewMode) {
        settingsStore.setDefaultViewMode(mode)
        baseState.update { it.copy(viewMode = mode) }
    }

    fun toggleBookmark(card: AnimeCard) {
        viewModelScope.launch {
            repository.toggleBookmark(
                animeId = card.id,
                title = card.title,
                coverUrl = card.coverUrl,
                lastEpisodeLabel = card.episodeLabel
            )
        }
    }
}
