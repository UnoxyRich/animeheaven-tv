package me.animeheaven.tv.ui.screens.home

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
import me.animeheaven.tv.data.model.ContinueWatchingItem
import me.animeheaven.tv.data.model.FeedViewMode
import me.animeheaven.tv.ui.components.AhErrorBox
import me.animeheaven.tv.ui.components.AhLoadingBox
import me.animeheaven.tv.ui.components.AhOverscanHorizontal
import me.animeheaven.tv.ui.components.AnimeHeavenFooter
import me.animeheaven.tv.ui.components.AnimeHeavenHeader
import me.animeheaven.tv.ui.components.ContinueWatchingRow
import me.animeheaven.tv.ui.components.HeaderDestination
import me.animeheaven.tv.ui.components.HeroBannerCarousel
import me.animeheaven.tv.ui.components.PopularHorizontalStrip
import me.animeheaven.tv.ui.components.ScheduleGridCard
import me.animeheaven.tv.ui.components.ScheduleListCard
import me.animeheaven.tv.ui.components.SectionHeaderWithToggle
import me.animeheaven.tv.ui.components.toGridRows

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenAnime: (String) -> Unit,
    onPlayContinueItem: (ContinueWatchingItem) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenNew: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onOpenPopular: () -> Unit,
    onOpenSeasonal: (path: String, label: String) -> Unit,
    onOpenRandom: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val feed = state.feed

    Column(modifier = Modifier.fillMaxSize()) {
        AnimeHeavenHeader(
            activeDestination = HeaderDestination.HOME,
            seasonLabel = feed?.seasonLabel,
            onSelectHome = { viewModel.loadHome(forceRefresh = true) },
            onSelectSearch = onOpenSearch,
            onSelectNew = onOpenNew,
            onSelectBookmarks = onOpenBookmarks,
            onSelectPopular = onOpenPopular,
            onSelectSeasonal = {
                val path = feed?.seasonPath ?: "2025fall.php"
                val label = feed?.seasonLabel ?: "Season"
                onOpenSeasonal(path, label)
            },
            onSelectRandom = onOpenRandom,
            onSelectSettings = onOpenSettings
        )

        when {
            state.isLoading && feed == null -> AhLoadingBox()
            state.errorMessage != null && feed == null -> {
                AhErrorBox(
                    errorMessage = state.errorMessage ?: "Network error",
                    onRetry = { viewModel.loadHome(forceRefresh = true) }
                )
            }
            feed != null -> {
                val listState = rememberLazyListState(
                    prefetchStrategy = remember { LazyListPrefetchStrategy(nestedPrefetchItemCount = 4) }
                )
                val gridRows = remember(feed.scheduleItems) {
                    feed.scheduleItems.toGridRows(4) { it.id }
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 6.dp, bottom = 16.dp)
                ) {
                    if (feed.banners.isNotEmpty()) {
                        item(key = "hero_banner", contentType = "hero_banner") {
                            HeroBannerCarousel(
                                banners = feed.banners,
                                onBannerClick = onOpenAnime,
                                onBannerFocused = viewModel::onCardFocused
                            )
                        }
                    }

                    if (state.continueWatching.isNotEmpty()) {
                        item(key = "continue_watching", contentType = "continue_watching") {
                            ContinueWatchingRow(
                                items = state.continueWatching,
                                onPlayItem = onPlayContinueItem,
                                onItemFocused = viewModel::onCardFocused
                            )
                        }
                    }

                    if (feed.popularItems.isNotEmpty()) {
                        item(key = "popular_strip", contentType = "popular_strip") {
                            PopularHorizontalStrip(
                                items = feed.popularItems,
                                onAnimeClick = onOpenAnime,
                                onMoreClick = onOpenPopular,
                                onItemFocused = viewModel::onCardFocused
                            )
                        }
                    }

                    item(key = "schedule_header", contentType = "section_header") {
                        SectionHeaderWithToggle(
                            title = "Subbed Anime Schedule",
                            viewMode = state.viewMode,
                            onChangeViewMode = viewModel::setViewMode
                        )
                    }

                    if (state.viewMode == FeedViewMode.LIST) {
                        itemsIndexed(
                            items = feed.scheduleItems,
                            key = { _, it -> it.id },
                            contentType = { _, _ -> "schedule_list_card" }
                        ) { index, card ->
                            ScheduleListCard(
                                item = card,
                                isBookmarked = state.bookmarkedIds.contains(card.id),
                                onClick = { onOpenAnime(card.id) },
                                onToggleBookmark = { viewModel.toggleBookmark(card) },
                                onItemFocused = { id, cover ->
                                    val n1 = feed.scheduleItems.getOrNull(index + 1)
                                    val n2 = feed.scheduleItems.getOrNull(index + 2)
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
                            contentType = { _, _ -> "schedule_grid_row" }
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
