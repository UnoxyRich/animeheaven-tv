package me.animeheaven.tv.ui.screens.catalog

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListPrefetchStrategy
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.animeheaven.tv.data.model.FeedViewMode
import me.animeheaven.tv.ui.components.AhErrorBox
import me.animeheaven.tv.ui.components.AhLoadingBox
import me.animeheaven.tv.ui.components.AhOverscanHorizontal
import me.animeheaven.tv.ui.components.AnimeHeavenFooter
import me.animeheaven.tv.ui.components.AnimeHeavenHeader
import me.animeheaven.tv.ui.components.HeaderDestination
import me.animeheaven.tv.ui.components.ScheduleGridCard
import me.animeheaven.tv.ui.components.ScheduleListCard
import me.animeheaven.tv.ui.components.SectionHeaderWithToggle
import me.animeheaven.tv.ui.components.SimilarShowCard
import me.animeheaven.tv.ui.components.toGridRows

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CatalogScreen(
    viewModel: CatalogViewModel,
    onOpenAnime: (String) -> Unit,
    onOpenHome: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenNew: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onOpenPopular: () -> Unit,
    onOpenSeasonal: (path: String, label: String) -> Unit,
    onOpenRandom: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val activeDest = when (viewModel.kind) {
        CatalogKind.NEW -> HeaderDestination.NEW
        CatalogKind.POPULAR -> HeaderDestination.POPULAR
        CatalogKind.SEASONAL -> HeaderDestination.SEASONAL
        CatalogKind.TAG -> HeaderDestination.HOME
    }

    Column(modifier = Modifier.fillMaxSize()) {
        AnimeHeavenHeader(
            activeDestination = activeDest,
            seasonLabel = state.seasonLabel,
            onSelectHome = onOpenHome,
            onSelectSearch = onOpenSearch,
            onSelectNew = {
                if (viewModel.kind == CatalogKind.NEW) viewModel.load(true) else onOpenNew()
            },
            onSelectBookmarks = onOpenBookmarks,
            onSelectPopular = {
                if (viewModel.kind == CatalogKind.POPULAR) viewModel.load(true) else onOpenPopular()
            },
            onSelectSeasonal = {
                if (viewModel.kind == CatalogKind.SEASONAL) {
                    viewModel.load(true)
                } else {
                    val label = state.seasonLabel ?: "Season"
                    state.seasonPath?.takeIf { it.isNotBlank() }?.let { onOpenSeasonal(it, label) }
                }
            },
            onSelectRandom = onOpenRandom,
            onSelectSettings = onOpenSettings
        )

        when {
            state.isLoading && state.items.isEmpty() -> AhLoadingBox()
            state.errorMessage != null && state.items.isEmpty() -> {
                AhErrorBox(
                    errorMessage = state.errorMessage ?: "Failed to load",
                    onRetry = { viewModel.load(true) }
                )
            }
            else -> {
                val isTagGrid = viewModel.kind == CatalogKind.TAG
                val listState = rememberLazyListState(
                    prefetchStrategy = remember { LazyListPrefetchStrategy(nestedPrefetchItemCount = 4) }
                )
                val gridRows = remember(state.items) { state.items.toGridRows(4) { it.id } }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    item(key = "header", contentType = "section_header") {
                        SectionHeaderWithToggle(
                            title = state.title,
                            viewMode = if (isTagGrid) null else state.viewMode,
                            onChangeViewMode = if (isTagGrid) null else viewModel::setViewMode
                        )
                    }

                    if (isTagGrid) {
                        items(
                            items = gridRows,
                            key = { row -> row.key },
                            contentType = { "tag_grid_row" }
                        ) { row ->
                            val rowItems = row.items
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = AhOverscanHorizontal, vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                rowItems.forEach { card ->
                                    SimilarShowCard(
                                        item = card,
                                        onClick = { onOpenAnime(card.id) },
                                        onItemFocused = viewModel::onCardFocused,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                repeat(4 - rowItems.size) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    } else if (state.viewMode == FeedViewMode.LIST) {
                        itemsIndexed(
                            items = state.items,
                            key = { index, it -> "anime_${index}_${it.id}" },
                            contentType = { _, _ -> "catalog_list_card" }
                        ) { index, card ->
                            ScheduleListCard(
                                item = card,
                                isBookmarked = state.bookmarkedIds.contains(card.id),
                                onClick = { onOpenAnime(card.id) },
                                onToggleBookmark = { viewModel.toggleBookmark(card) },
                                onItemFocused = { id, cover ->
                                    val n1 = state.items.getOrNull(index + 1)
                                    val n2 = state.items.getOrNull(index + 2)
                                    viewModel.onCardFocused(
                                        animeId = id,
                                        coverUrl = cover,
                                        neighborIds = listOfNotNull(n1?.id, n2?.id),
                                        neighborCovers = listOfNotNull(n1?.coverUrl, n2?.coverUrl)
                                    )
                                }
                            )
                        }
                    } else {
                        itemsIndexed(
                            items = gridRows,
                            key = { _, row -> row.key },
                            contentType = { _, _ -> "catalog_grid_row" }
                        ) { rowIdx, row ->
                            val rowItems = row.items
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = AhOverscanHorizontal, vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                rowItems.forEachIndexed { colIdx, card ->
                                    ScheduleGridCard(
                                        item = card,
                                        isBookmarked = state.bookmarkedIds.contains(card.id),
                                        onClick = { onOpenAnime(card.id) },
                                        onItemFocused = { id, cover ->
                                            val nextInRow = rowItems.getOrNull(colIdx + 1)
                                            val belowCard = gridRows.getOrNull(rowIdx + 1)?.items?.getOrNull(colIdx)
                                            viewModel.onCardFocused(
                                                animeId = id,
                                                coverUrl = cover,
                                                neighborIds = listOfNotNull(nextInRow?.id, belowCard?.id),
                                                neighborCovers = listOfNotNull(nextInRow?.coverUrl, belowCard?.coverUrl)
                                            )
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                repeat(4 - rowItems.size) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }

                    item(key = "footer", contentType = "footer") {
                        AnimeHeavenFooter(onOpenSettings = onOpenSettings)
                    }
                }
            }
        }
    }
}
