package me.animeheaven.tv.ui.screens.bookmarks

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.animeheaven.tv.data.cache.AnimePreloader
import me.animeheaven.tv.data.local.BookmarkEntity
import me.animeheaven.tv.data.model.ContinueWatchingItem
import me.animeheaven.tv.data.repository.AnimeHeavenRepository

@Immutable
data class BookmarksUiState(
    val bookmarks: List<BookmarkEntity> = emptyList(),
    val continueWatching: List<ContinueWatchingItem> = emptyList(),
    val seasonLabel: String? = null,
    val seasonPath: String? = null
)

class BookmarksViewModel(
    private val repository: AnimeHeavenRepository,
    private val preloader: AnimePreloader? = null
) : ViewModel() {

    private var lastPreloadedSignature: String? = null

    val uiState: StateFlow<BookmarksUiState> = combine(
        repository.observeBookmarks(),
        repository.observeContinueWatching(),
        repository.seasonHeader
    ) { bookmarks, cw, header ->
        BookmarksUiState(
            bookmarks = bookmarks,
            continueWatching = cw,
            seasonLabel = header?.first,
            seasonPath = header?.second
        )
    }.onEach { state ->
        val sig = "${state.bookmarks.size}:${state.bookmarks.firstOrNull()?.animeId}:${state.continueWatching.firstOrNull()?.targetEpisodeKey}"
        if (sig != lastPreloadedSignature && (state.bookmarks.isNotEmpty() || state.continueWatching.isNotEmpty())) {
            lastPreloadedSignature = sig
            preloader?.preloadBookmarksEcosystem(state.bookmarks, state.continueWatching)
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = BookmarksUiState(
            seasonLabel = repository.seasonHeader.value?.first,
            seasonPath = repository.seasonHeader.value?.second
        )
    )

    init {
        if (repository.seasonHeader.value == null) {
            viewModelScope.launch {
                runCatching { repository.getHomeFeed(forceRefresh = false) }
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

    fun removeBookmark(animeId: String) {
        viewModelScope.launch {
            repository.removeBookmark(animeId)
        }
    }
}
