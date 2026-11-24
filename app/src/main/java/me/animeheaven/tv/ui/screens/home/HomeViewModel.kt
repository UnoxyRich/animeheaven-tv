package me.animeheaven.tv.ui.screens.home

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
import me.animeheaven.tv.data.local.AppSettingsStore
import me.animeheaven.tv.data.model.AnimeCard
import me.animeheaven.tv.data.model.ContinueWatchingItem
import me.animeheaven.tv.data.model.FeedViewMode
import me.animeheaven.tv.data.model.HomeFeed
import me.animeheaven.tv.data.repository.AnimeHeavenRepository
import me.animeheaven.tv.data.repository.CachedResource

@Immutable
data class HomeUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
    val feed: HomeFeed? = null,
    val continueWatching: List<ContinueWatchingItem> = emptyList(),
    val bookmarkedIds: Set<String> = emptySet(),
    val viewMode: FeedViewMode = FeedViewMode.LIST
)

private data class HomeFeedState(
    val isLoading: Boolean,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
    val feed: HomeFeed? = null,
    val viewMode: FeedViewMode
)

class HomeViewModel(
    private val repository: AnimeHeavenRepository,
    private val settingsStore: AppSettingsStore,
    private val preloader: AnimePreloader? = null
) : ViewModel() {

    private val initialFeed = repository.peekCachedHomeFeed()
    private val feedState = MutableStateFlow(
        HomeFeedState(
            isLoading = initialFeed == null,
            feed = initialFeed,
            viewMode = settingsStore.settings.value.defaultViewMode
        )
    )

    // SharingStarted.WhileSubscribed unsubscribes Room observers whenever HomeScreen is stopped
    // on the NavHost backstack (e.g. during video playback), completely preventing 5s/15s DB storms.
    val uiState: StateFlow<HomeUiState> = combine(
        feedState,
        repository.observeContinueWatching(),
        repository.observeBookmarkedIds()
    ) { fs, continueItems, bookmarkedIds ->
        HomeUiState(
            isLoading = fs.isLoading,
            isRefreshing = fs.isRefreshing,
            errorMessage = fs.errorMessage,
            feed = fs.feed,
            continueWatching = continueItems,
            bookmarkedIds = bookmarkedIds,
            viewMode = fs.viewMode
        )
    }.onEach { state ->
        state.feed?.let { feed ->
            preloader?.preloadHomeEcosystem(feed, state.continueWatching)
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = HomeUiState(
            isLoading = initialFeed == null,
            feed = initialFeed,
            viewMode = settingsStore.settings.value.defaultViewMode
        )
    )

    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            settingsStore.settings.map { it.defaultViewMode }.distinctUntilChanged().collect(::setViewMode)
        }
        loadHome(forceRefresh = false)
    }

    fun loadHome(forceRefresh: Boolean = true) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            repository.observeHomeFeed(forceRefresh = forceRefresh).collect { resource ->
                when (resource) {
                    CachedResource.Loading -> {
                        feedState.update { state ->
                            state.copy(
                                isLoading = state.feed == null,
                                isRefreshing = state.feed != null,
                                errorMessage = null
                            )
                        }
                    }
                    is CachedResource.Data -> {
                        feedState.update {
                            it.copy(
                                isLoading = false,
                                isRefreshing = resource.isRefreshing,
                                feed = resource.data,
                                errorMessage = null
                            )
                        }
                    }
                    is CachedResource.Error -> {
                        feedState.update { state ->
                            state.copy(
                                isLoading = false,
                                isRefreshing = false,
                                errorMessage = if (state.feed == null) resource.message else null
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
        feedState.update { it.copy(viewMode = mode) }
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
