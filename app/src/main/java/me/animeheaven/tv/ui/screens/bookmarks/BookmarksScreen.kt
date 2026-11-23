package me.animeheaven.tv.ui.screens.bookmarks

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListPrefetchStrategy
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.animeheaven.tv.data.cache.ImageTargetSize
import me.animeheaven.tv.data.model.ContinueWatchingItem
import me.animeheaven.tv.ui.components.AhCoverImage
import me.animeheaven.tv.ui.components.AhFocusableBox
import me.animeheaven.tv.ui.components.AhIcons
import me.animeheaven.tv.ui.components.AhOverscanHorizontal
import me.animeheaven.tv.ui.components.AhShape4
import me.animeheaven.tv.ui.components.AnimeHeavenFooter
import me.animeheaven.tv.ui.components.AnimeHeavenHeader
import me.animeheaven.tv.ui.components.ContinueWatchingRow
import me.animeheaven.tv.ui.components.HeaderDestination
import me.animeheaven.tv.ui.components.SectionHeaderWithToggle
import me.animeheaven.tv.ui.components.toGridRows
import me.animeheaven.tv.ui.theme.LocalAhPalette

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookmarksScreen(
    viewModel: BookmarksViewModel,
    onOpenAnime: (String) -> Unit,
    onPlayContinueItem: (ContinueWatchingItem) -> Unit,
    onOpenHome: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenNew: () -> Unit,
    onOpenPopular: () -> Unit,
    onOpenSeasonal: (path: String, label: String) -> Unit,
    onOpenRandom: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val palette = LocalAhPalette.current

    Column(modifier = Modifier.fillMaxSize()) {
        AnimeHeavenHeader(
            activeDestination = HeaderDestination.BOOKMARKS,
            seasonLabel = state.seasonLabel,
            onSelectHome = onOpenHome,
            onSelectSearch = onOpenSearch,
            onSelectNew = onOpenNew,
            onSelectBookmarks = {},
            onSelectPopular = onOpenPopular,
            onSelectSeasonal = {
                val path = state.seasonPath ?: "2025fall.php"
                val label = state.seasonLabel ?: "Season"
                onOpenSeasonal(path, label)
            },
            onSelectRandom = onOpenRandom,
            onSelectSettings = onOpenSettings
        )

        val listState = rememberLazyListState(
            prefetchStrategy = remember { LazyListPrefetchStrategy(nestedPrefetchItemCount = 4) }
        )
        val bookmarkRows = remember(state.bookmarks) { state.bookmarks.toGridRows(4) { it.animeId } }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            if (state.continueWatching.isNotEmpty()) {
                item(key = "continue_watching", contentType = "continue_watching") {
                    ContinueWatchingRow(
                        items = state.continueWatching,
                        onPlayItem = onPlayContinueItem,
                        onItemFocused = viewModel::onCardFocused
                    )
                }
            }

            item(key = "bookmarks_header", contentType = "section_header") {
                SectionHeaderWithToggle(
                    title = "My Bookmarks",
                    viewMode = null,
                    onChangeViewMode = null
                )
            }

            if (state.bookmarks.isEmpty()) {
                item(key = "empty", contentType = "empty") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = AhOverscanHorizontal, vertical = 32.dp)
                            .background(palette.surface, AhShape4)
                            .padding(28.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No bookmarks saved yet.\nOpen any anime or click the bookmark icon on a show to add it here.",
                            color = palette.textSecondary,
                            fontSize = 16.sp,
                            textAlign = TextAlign.Center,
                            lineHeight = 24.sp
                        )
                    }
                }
            } else {
                itemsIndexed(
                    items = bookmarkRows,
                    key = { _, row -> row.key },
                    contentType = { _, _ -> "bookmark_grid_row" }
                ) { rowIdx, row ->
                    val rowItems = row.items
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = AhOverscanHorizontal, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        rowItems.forEachIndexed { colIdx, bm ->
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                AhFocusableBox(
                                    onClick = { onOpenAnime(bm.animeId) },
                                    modifier = Modifier.fillMaxWidth(),
                                    containerColor = palette.surface,
                                    focusScale = 1.03f,
                                    onFocusStateChanged = { focused ->
                                        if (focused) {
                                            val nextInRow = rowItems.getOrNull(colIdx + 1)
                                            val belowCard = bookmarkRows.getOrNull(rowIdx + 1)?.items?.getOrNull(colIdx)
                                            viewModel.onCardFocused(
                                                animeId = bm.animeId,
                                                coverUrl = bm.coverUrl,
                                                neighborIds = listOfNotNull(nextInRow?.animeId, belowCard?.animeId),
                                                neighborCovers = listOfNotNull(nextInRow?.coverUrl, belowCard?.coverUrl)
                                            )
                                        }
                                    }
                                ) { isFocused ->
                                    Column(modifier = Modifier.padding(8.dp)) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .aspectRatio(16f / 9f)
                                        ) {
                                            AhCoverImage(
                                                url = bm.coverUrl,
                                                contentDescription = bm.title,
                                                targetSize = ImageTargetSize.STANDARD_CARD,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                            if (bm.lastEpisodeLabel.isNotBlank()) {
                                                Box(
                                                    modifier = Modifier
                                                        .align(Alignment.TopStart)
                                                        .background(palette.crimson)
                                                        .padding(horizontal = 7.dp, vertical = 3.dp)
                                                ) {
                                                    Text(
                                                        text = "Ep ${bm.lastEpisodeLabel}",
                                                        color = Color.White,
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = bm.title,
                                            color = if (isFocused) palette.textPrimary else palette.textSecondary,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(40.dp)
                                        )
                                    }
                                }

                                AhFocusableBox(
                                    onClick = { viewModel.removeBookmark(bm.animeId) },
                                    modifier = Modifier.fillMaxWidth(),
                                    containerColor = palette.surfaceElevated,
                                    focusedContainerColor = palette.crimson,
                                    focusedBorderColor = Color.White
                                ) { isFocused ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 6.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = AhIcons.DeleteOutline,
                                            contentDescription = "Remove",
                                            tint = if (isFocused) Color.White else palette.textSecondary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Text(
                                            text = " Remove",
                                            color = if (isFocused) Color.White else palette.textSecondary,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }
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
