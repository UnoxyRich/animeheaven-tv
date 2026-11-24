package me.animeheaven.tv.ui.screens.search

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListPrefetchStrategy
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridPrefetchStrategy
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import me.animeheaven.tv.data.cache.ImageTargetSize
import me.animeheaven.tv.ui.components.AhCoverImage
import me.animeheaven.tv.ui.components.AhErrorBox
import me.animeheaven.tv.ui.components.AhFocusableBox
import me.animeheaven.tv.ui.components.AhIcons
import me.animeheaven.tv.ui.components.AhOverscanHorizontal
import me.animeheaven.tv.ui.components.AhOverscanVertical
import me.animeheaven.tv.ui.components.AhShape16
import me.animeheaven.tv.ui.components.AhShape2
import me.animeheaven.tv.ui.components.AhShape3
import me.animeheaven.tv.ui.components.AhShape4
import me.animeheaven.tv.ui.components.AhStaticFocusableBox
import me.animeheaven.tv.ui.components.SimilarShowCard
import me.animeheaven.tv.ui.components.TvCustomKeyboard
import me.animeheaven.tv.ui.theme.LocalAhPalette

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    onBack: () -> Unit,
    onOpenAnime: (String) -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val palette = LocalAhPalette.current

    val firstKeyFocusRequester = remember { FocusRequester() }
    val resultsFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(80L)
        runCatching { firstKeyFocusRequester.requestFocus() }
    }

    val hasAnyResults = state.errorMessage == null &&
        if (state.isFullSearchMode) state.fullResults.isNotEmpty() else state.fastResults.isNotEmpty()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = AhOverscanHorizontal, vertical = AhOverscanVertical)
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (keyEvent.key) {
                    Key.Backspace, Key.Delete, Key.MediaRewind -> {
                        viewModel.backspace()
                        true
                    }
                    Key.MediaFastForward -> {
                        viewModel.appendSpace()
                        true
                    }
                    else -> {
                        val unicode = keyEvent.nativeKeyEvent.unicodeChar
                        if (unicode > 32 && unicode < 127) {
                            viewModel.appendChar(unicode.toChar().toString())
                            true
                        } else {
                            false
                        }
                    }
                }
            },
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Top Netflix TV Search Query Display Bar (Never invokes system IME)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.surface, AhShape4)
                .border(1.dp, palette.borderSubtle, AhShape4)
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            AhFocusableBox(
                onClick = onBack,
                modifier = Modifier.size(42.dp),
                shape = AhShape4,
                containerColor = palette.surfaceElevated,
                focusedContainerColor = Color.White,
                focusedBorderColor = Color.White
            ) { isFocused ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back",
                        tint = if (isFocused) Color.Black else Color.White
                    )
                }
            }

            // Sleek Netflix Search Query Box
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .border(1.5.dp, Color(0x38FFFFFF), AhShape4)
                    .background(if (palette.isLight) Color.White else Color(0xFF0E0E0E), AhShape4)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (state.query.isEmpty()) {
                    Text(
                        text = "Type anime title using TV keyboard...",
                        color = palette.textMuted,
                        fontSize = 18.sp,
                        fontStyle = FontStyle.Italic,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = state.query,
                            color = palette.textPrimary,
                            fontSize = 19.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        // Isolated GPU-layer blinking caret: 0 recompositions of SearchScreen/Keyboard!
                        BlinkingCaret(color = palette.crimson)
                    }
                }

                if (state.isLoading) {
                    CircularProgressIndicator(
                        color = palette.crimson,
                        strokeWidth = 2.5.dp,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            if (state.query.isNotEmpty()) {
                AhStaticFocusableBox(
                    onClick = {
                        viewModel.clearQuery()
                        runCatching { firstKeyFocusRequester.requestFocus() }
                    },
                    modifier = Modifier.size(42.dp),
                    containerColor = palette.surfaceElevated,
                    focusedContainerColor = palette.crimson,
                    focusedBorderColor = Color.White
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Clear Query",
                            tint = Color.White
                        )
                    }
                }
            }

            AhFocusableBox(
                onClick = viewModel::submitFullSearch,
                modifier = Modifier.height(42.dp),
                containerColor = palette.crimson,
                focusedContainerColor = Color.White,
                focusedBorderColor = palette.crimson
            ) { isFocused ->
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = "Search",
                        tint = if (isFocused) palette.crimson else Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "Search",
                        color = if (isFocused) palette.crimson else Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Split Screen: Left Custom TV Keyboard + History | Right Live / Full Search Results
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .weight(0.45f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TvCustomKeyboard(
                    mode = state.keyboardMode,
                    firstKeyFocusRequester = firstKeyFocusRequester,
                    resultsFocusRequester = resultsFocusRequester,
                    hasResults = hasAnyResults,
                    onChar = viewModel::appendChar,
                    onSpace = viewModel::appendSpace,
                    onBackspace = viewModel::backspace,
                    onClear = viewModel::clearQuery,
                    onSearch = viewModel::submitFullSearch,
                    onChangeMode = viewModel::setKeyboardMode
                )

                if (state.recentSearches.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(palette.surface, AhShape4)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = AhIcons.History,
                                contentDescription = null,
                                tint = palette.crimson,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Recent Searches",
                                color = palette.textSecondary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(
                                items = state.recentSearches,
                                key = { it },
                                contentType = { "recent_chip" }
                            ) { recent ->
                                AhFocusableBox(
                                    onClick = { viewModel.applyRecentQuery(recent) },
                                    shape = AhShape16,
                                    containerColor = palette.surfaceElevated,
                                    focusedContainerColor = palette.crimson,
                                    unfocusedBorderColor = palette.borderSubtle,
                                    focusedBorderColor = Color.White
                                ) { isFocused ->
                                    Text(
                                        text = recent,
                                        color = if (isFocused) Color.White else palette.textSecondary,
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Right Results Panel
            Column(
                modifier = Modifier
                    .weight(0.55f)
                    .fillMaxHeight()
                    .focusRequester(resultsFocusRequester)
                    .focusGroup()
                    .background(palette.surface, AhShape4)
                    .border(1.dp, palette.borderSubtle, AhShape4)
                    .padding(14.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = when {
                            state.isFullSearchMode -> "Results for \"${state.fullResultsTitle}\""
                            state.query.trim().length >= 2 -> "Live Matches"
                            else -> "Start typing to search AnimeHeaven"
                        },
                        color = palette.textPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (state.isFullSearchMode && state.fastResults.isNotEmpty()) {
                        AhStaticFocusableBox(
                            onClick = viewModel::showLiveSuggestions,
                            containerColor = palette.surfaceElevated,
                            focusedContainerColor = palette.crimson,
                            focusedBorderColor = Color.White
                        ) {
                            Text(
                                text = "Live List",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                    }
                }

                when {
                    state.errorMessage != null -> {
                        AhErrorBox(
                            errorMessage = state.errorMessage ?: "Search error",
                            onRetry = viewModel::submitFullSearch
                        )
                    }
                    state.isFullSearchMode -> {
                        if (state.fullResults.isEmpty() && !state.isLoading) {
                            EmptySearchMessage("No anime found for \"${state.query.trim()}\"")
                        } else {
                            val gridState = rememberLazyGridState(
                                prefetchStrategy = remember { LazyGridPrefetchStrategy(nestedPrefetchItemCount = 4) }
                            )
                            LazyVerticalGrid(
                                state = gridState,
                                columns = GridCells.Fixed(3),
                                contentPadding = PaddingValues(4.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                itemsIndexed(
                                    items = state.fullResults,
                                    key = { index, it -> "full_${index}_${it.id}" },
                                    contentType = { _, _ -> "search_grid_card" }
                                ) { index, item ->
                                    val isLeftCol = index % 3 == 0
                                    SimilarShowCard(
                                        item = item,
                                        onClick = { onOpenAnime(item.id) },
                                        onItemFocused = { id, cover ->
                                            val n1 = state.fullResults.getOrNull(index + 1)
                                            val n2 = state.fullResults.getOrNull(index + 3)
                                            viewModel.onCardFocused(
                                                animeId = id,
                                                coverUrl = cover,
                                                neighborIds = listOfNotNull(n1?.id, n2?.id),
                                                neighborCovers = listOfNotNull(n1?.coverUrl, n2?.coverUrl)
                                            )
                                        },
                                        modifier = Modifier
                                            .then(
                                                if (isLeftCol) {
                                                    Modifier.focusProperties { left = firstKeyFocusRequester }
                                                } else {
                                                    Modifier
                                                }
                                            )
                                    )
                                }
                            }
                        }
                    }
                    state.query.trim().length < 2 -> {
                        EmptySearchMessage(
                            "Use the custom D-pad keyboard on the left or your remote keys.\nLive suggestions appear after 2 characters."
                        )
                    }
                    state.fastResults.isEmpty() && !state.isLoading -> {
                        EmptySearchMessage("No instant matches. Press SEARCH for full site search.")
                    }
                    else -> {
                        val fastListState = rememberLazyListState(
                            prefetchStrategy = remember { LazyListPrefetchStrategy(nestedPrefetchItemCount = 4) }
                        )
                        LazyColumn(
                            state = fastListState,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(bottom = 8.dp)
                        ) {
                            itemsIndexed(
                                items = state.fastResults,
                                key = { index, it -> "fast_${index}_${it.animeId}" },
                                contentType = { _, _ -> "fast_search_row" }
                            ) { index, item ->
                                AhFocusableBox(
                                    onClick = { onOpenAnime(item.animeId) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .focusProperties { left = firstKeyFocusRequester },
                                    containerColor = palette.surfaceElevated,
                                    focusScale = 1.015f,
                                    onFocusStateChanged = { focused ->
                                        if (focused) {
                                            val n1 = state.fastResults.getOrNull(index + 1)
                                            val n2 = state.fastResults.getOrNull(index + 2)
                                            viewModel.onCardFocused(
                                                animeId = item.animeId,
                                                coverUrl = item.coverUrl,
                                                neighborIds = listOfNotNull(n1?.animeId, n2?.animeId),
                                                neighborCovers = listOfNotNull(n1?.coverUrl, n2?.coverUrl)
                                            )
                                        }
                                    }
                                ) { isFocused ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        AhCoverImage(
                                            url = item.coverUrl,
                                            contentDescription = item.title,
                                            targetSize = ImageTargetSize.SEARCH_THUMB,
                                            modifier = Modifier
                                                .width(128.dp)
                                                .aspectRatio(16f / 9f)
                                        )
                                        Text(
                                            text = item.title,
                                            color = if (isFocused) Color.White else palette.textSecondary,
                                            fontSize = 17.sp,
                                            fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Medium,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }

                            item(key = "show_all_btn", contentType = "show_all_btn") {
                                AhFocusableBox(
                                    onClick = viewModel::submitFullSearch,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .focusProperties { left = firstKeyFocusRequester },
                                    containerColor = palette.crimson,
                                    focusedContainerColor = Color.White,
                                    focusedBorderColor = palette.crimson
                                ) { isFocused ->
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 10.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "Show all results for \"${state.query.trim()}\"",
                                            color = if (isFocused) palette.crimson else Color.White,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BlinkingCaret(color: Color) {
    val alphaAnim = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(530L)
            alphaAnim.snapTo(if (alphaAnim.value > 0.5f) 0f else 1f)
        }
    }
    Box(
        modifier = Modifier
            .padding(start = 2.dp)
            .width(2.5.dp)
            .height(22.dp)
            .graphicsLayer { alpha = alphaAnim.value }
            .background(color)
    )
}

@Composable
private fun EmptySearchMessage(text: String) {
    val palette = LocalAhPalette.current
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = palette.textMuted,
            fontSize = 15.sp,
            lineHeight = 22.sp,
            modifier = Modifier.padding(24.dp)
        )
    }
}
