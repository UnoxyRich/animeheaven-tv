package me.animeheaven.tv.ui.screens.detail

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import me.animeheaven.tv.data.cache.ImageTargetSize
import me.animeheaven.tv.data.local.WatchProgressEntity
import me.animeheaven.tv.data.model.AnimeCard
import me.animeheaven.tv.data.model.EpisodeItem
import me.animeheaven.tv.ui.components.AhCoverImage
import me.animeheaven.tv.ui.components.AhErrorBox
import me.animeheaven.tv.ui.components.AhFocusableBox
import me.animeheaven.tv.ui.components.AhIcons
import me.animeheaven.tv.ui.components.AhLoadingBox
import me.animeheaven.tv.ui.components.AhOverscanHorizontal
import me.animeheaven.tv.ui.components.AhOverscanVertical
import me.animeheaven.tv.ui.components.AhShape2
import me.animeheaven.tv.ui.components.AhShape20
import me.animeheaven.tv.ui.components.AhShapeRect
import me.animeheaven.tv.ui.components.AhStaticFocusableBox
import me.animeheaven.tv.ui.components.SimilarShowCard
import me.animeheaven.tv.ui.components.toGridRows
import me.animeheaven.tv.ui.theme.LocalAhPalette

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun AnimeDetailScreen(
    viewModel: DetailViewModel,
    onBack: () -> Unit,
    onPlayEpisode: (episodeKey: String, animeId: String, animeTitle: String, coverUrl: String) -> Unit,
    onOpenTag: (String) -> Unit,
    onOpenAnime: (String) -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val palette = LocalAhPalette.current
    val primaryFocusRequester = remember { FocusRequester() }

    val detail = state.detail
    LaunchedEffect(detail?.id) {
        if (detail != null) {
            delay(80L)
            runCatching { primaryFocusRequester.requestFocus() }
        }
    }

    when {
        state.isLoading && detail == null -> AhLoadingBox()
        state.errorMessage != null && detail == null -> {
            AhErrorBox(
                errorMessage = state.errorMessage ?: "Unable to load anime",
                onRetry = { viewModel.load(forceRefresh = true) }
            )
        }
        detail != null -> {
            val orderedEpisodes = remember(detail.episodes, state.sortOldestFirst) {
                if (state.sortOldestFirst) detail.episodes.reversed() else detail.episodes
            }
            val epRows = remember(orderedEpisodes) { orderedEpisodes.toGridRows(8) { it.key } }
            val bannerScrim = remember(palette.background) {
                Brush.verticalGradient(
                    listOf(Color(0x66000000), Color(0x88000000), palette.background)
                )
            }
            val coverForPlayer = detail.bannerUrl ?: detail.posterUrl
            val listState = rememberLazyListState(
                prefetchStrategy = remember { LazyListPrefetchStrategy(nestedPrefetchItemCount = 4) }
            )

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 28.dp)
            ) {
                // Hero Banner + Back Bar
                item(key = "banner", contentType = "detail_banner") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(185.dp)
                    ) {
                        if (!detail.bannerUrl.isNullOrBlank()) {
                            AhCoverImage(
                                url = detail.bannerUrl,
                                contentDescription = detail.title,
                                targetSize = ImageTargetSize.HERO_BANNER,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(bannerScrim)
                        )

                        AhStaticFocusableBox(
                            onClick = onBack,
                            modifier = Modifier
                                .padding(horizontal = AhOverscanHorizontal, vertical = AhOverscanVertical)
                                .align(Alignment.TopStart),
                            containerColor = Color(0xCC121212),
                            focusedContainerColor = palette.crimson,
                            unfocusedBorderColor = palette.borderSubtle,
                            focusedBorderColor = Color.White
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                    contentDescription = "Back",
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "Back",
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }

                // Main Info Card matching .info.bc1
                item(key = "info_card") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = AhOverscanHorizontal)
                            .background(palette.surface, AhShape2)
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        AhCoverImage(
                            url = detail.posterUrl,
                            contentDescription = detail.title,
                            targetSize = ImageTargetSize.DETAIL_POSTER,
                            modifier = Modifier
                                .width(176.dp)
                                .aspectRatio(2f / 3f)
                                .border(1.dp, palette.borderSubtle)
                        )

                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = detail.title,
                                color = palette.textPrimary,
                                fontSize = 25.sp,
                                fontWeight = FontWeight.Bold
                            )
                            if (detail.japaneseTitle.isNotBlank()) {
                                Text(
                                    text = detail.japaneseTitle,
                                    color = palette.textMuted,
                                    fontSize = 15.sp
                                )
                            }
                            if (detail.synopsis.isNotBlank()) {
                                Text(
                                    text = detail.synopsis,
                                    color = palette.textSecondary,
                                    fontSize = 14.sp,
                                    lineHeight = 20.sp,
                                    maxLines = 4,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            // Metadata Row: Episodes / Year / Score
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                MetaStat("Episodes", detail.episodesLabel)
                                MetaStat("Year", detail.year)
                                MetaStat("Score", detail.score)
                            }

                            // Clickable Tags (.boxitem.bc2)
                            if (detail.tags.isNotEmpty()) {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    detail.tags.forEach { tag ->
                                        AhFocusableBox(
                                            onClick = { onOpenTag(tag) },
                                            containerColor = palette.crimson,
                                            focusedContainerColor = Color.White,
                                            focusedBorderColor = palette.crimson,
                                            focusScale = 1.05f
                                        ) { isFocused ->
                                            Text(
                                                text = tag,
                                                color = if (isFocused) palette.crimson else Color.White,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(2.dp))

                            // Action Buttons: Watch / Resume / Next + Bookmark
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val action = state.primaryAction
                                if (action != null) {
                                    AhFocusableBox(
                                        onClick = {
                                            onPlayEpisode(
                                                action.episodeKey,
                                                detail.id,
                                                detail.title,
                                                coverForPlayer
                                            )
                                        },
                                        modifier = Modifier.focusRequester(primaryFocusRequester),
                                        containerColor = palette.crimson,
                                        focusedContainerColor = Color.White,
                                        focusedBorderColor = palette.crimson,
                                        focusScale = 1.04f
                                    ) { isFocused ->
                                        Row(
                                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.PlayArrow,
                                                contentDescription = null,
                                                tint = if (isFocused) palette.crimson else Color.White,
                                                modifier = Modifier.size(22.dp)
                                            )
                                            Text(
                                                text = action.buttonLabel,
                                                color = if (isFocused) palette.crimson else Color.White,
                                                fontSize = 16.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }

                                AhFocusableBox(
                                    onClick = viewModel::toggleBookmark,
                                    modifier = if (state.primaryAction == null) {
                                        Modifier.focusRequester(primaryFocusRequester)
                                    } else {
                                        Modifier
                                    },
                                    shape = AhShape20,
                                    containerColor = if (state.isBookmarked) palette.crimson else palette.surfaceElevated,
                                    focusedContainerColor = Color.White,
                                    unfocusedBorderColor = palette.crimson,
                                    focusedBorderColor = palette.crimson,
                                    borderWidth = 1.5.dp
                                ) { isFocused ->
                                    Row(
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (state.isBookmarked) {
                                                AhIcons.Bookmark
                                            } else {
                                                AhIcons.BookmarkBorder
                                            },
                                            contentDescription = null,
                                            tint = if (isFocused) palette.crimson else Color.White,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Text(
                                            text = if (state.isBookmarked) "Bookmarked" else "Bookmark",
                                            color = if (isFocused) palette.crimson else Color.White,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Countdown Banner (.info2.bc1)
                if (!detail.countdownLabel.isNullOrBlank()) {
                    item(key = "countdown") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = AhOverscanHorizontal)
                                .padding(top = 10.dp)
                                .background(palette.surface)
                                .padding(vertical = 10.dp, horizontal = 14.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = detail.countdownLabel,
                                color = palette.crimson,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Episodes Header + Sort Toggle
                if (orderedEpisodes.isNotEmpty()) {
                    item(key = "episodes_header") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = AhOverscanHorizontal)
                                .padding(top = 16.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Episodes (${orderedEpisodes.size})",
                                color = palette.textPrimary,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold
                            )
                            AhStaticFocusableBox(
                                onClick = viewModel::toggleSortOrder,
                                containerColor = palette.surface,
                                focusedContainerColor = palette.crimson,
                                unfocusedBorderColor = palette.crimson,
                                focusedBorderColor = Color.White,
                                borderWidth = 1.dp
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = AhIcons.SwapVert,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = if (state.sortOldestFirst) "Ep 1 First" else "Newest First",
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }

                    // Square Crimson-Bordered Episode Grid (.watch.bc2 / .watchb.bc)
                    items(
                        items = epRows,
                        key = { row -> row.key },
                        contentType = { "episode_row" }
                    ) { row ->
                        val rowEpisodes = row.items
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = AhOverscanHorizontal, vertical = 5.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            rowEpisodes.forEach { ep ->
                                EpisodeSquareTile(
                                    episode = ep,
                                    progress = state.progressMap[ep.key],
                                    onClick = {
                                        onPlayEpisode(
                                            ep.key,
                                            detail.id,
                                            detail.title,
                                            coverForPlayer
                                        )
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            repeat(8 - rowEpisodes.size) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }

                if (detail.relatedShows.isNotEmpty()) {
                    item(key = "related_shows", contentType = "show_row") {
                        RelatedOrSimilarRow(
                            title = "Related Shows",
                            shows = detail.relatedShows,
                            onOpenAnime = onOpenAnime,
                            onCardFocused = { id, cover, nIds, nCovers ->
                                viewModel.onCardFocused(id, cover, nIds, nCovers)
                            }
                        )
                    }
                }

                if (detail.similarShows.isNotEmpty()) {
                    item(key = "similar_shows", contentType = "show_row") {
                        RelatedOrSimilarRow(
                            title = "Similar Shows",
                            shows = detail.similarShows,
                            onOpenAnime = onOpenAnime,
                            onCardFocused = { id, cover, nIds, nCovers ->
                                viewModel.onCardFocused(id, cover, nIds, nCovers)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MetaStat(label: String, value: String) {
    val palette = LocalAhPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "$label: ",
            color = palette.textSecondary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = value,
            color = palette.crimson,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun EpisodeSquareTile(
    episode: EpisodeItem,
    progress: WatchProgressEntity?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalAhPalette.current
    val isWatched = progress?.isWatched == true
    val progressFraction = if (progress != null && progress.durationMs > 0L) {
        (progress.positionMs.toFloat() / progress.durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    AhFocusableBox(
        onClick = onClick,
        modifier = modifier.height(92.dp),
        shape = AhShapeRect,
        containerColor = if (palette.isLight) Color(0xFFFDFDFF) else Color.Black,
        focusedContainerColor = palette.crimson,
        unfocusedBorderColor = palette.crimson,
        focusedBorderColor = Color.White,
        borderWidth = 2.dp,
        focusedBorderWidth = 2.5.dp,
        focusScale = 1.06f
    ) { isFocused ->
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(4.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (isWatched) "Watched" else "Episode",
                    color = if (isFocused) Color.White else palette.textSecondary,
                    fontSize = 11.sp,
                    maxLines = 1
                )
                Text(
                    text = episode.numberLabel,
                    color = when {
                        isFocused -> Color.White
                        isWatched -> palette.crimson
                        else -> palette.textPrimary
                    },
                    fontSize = if (episode.numberLabel.length >= 4) 20.sp else 27.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                Text(
                    text = episode.releaseAge.ifBlank { " " },
                    color = if (isFocused) Color(0xFFEAEAEA) else palette.textMuted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    textAlign = TextAlign.Center
                )
            }

            if (progressFraction > 0.02f && !isWatched) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .align(Alignment.BottomCenter)
                        .background(Color(0x55FFFFFF))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progressFraction)
                            .fillMaxHeight()
                            .background(if (isFocused) Color.White else palette.crimson)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RelatedOrSimilarRow(
    title: String,
    shows: List<AnimeCard>,
    onOpenAnime: (String) -> Unit,
    onCardFocused: (animeId: String, coverUrl: String, neighborIds: List<String>, neighborCovers: List<String>) -> Unit
) {
    val palette = LocalAhPalette.current
    val rowState = rememberLazyListState(
        prefetchStrategy = remember { LazyListPrefetchStrategy(nestedPrefetchItemCount = 4) }
    )
    Column(modifier = Modifier.padding(top = 18.dp)) {
        Text(
            text = title,
            color = palette.textPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = AhOverscanHorizontal, vertical = 8.dp)
        )
        LazyRow(
            state = rowState,
            contentPadding = PaddingValues(horizontal = AhOverscanHorizontal),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            itemsIndexed(
                items = shows,
                key = { _, it -> it.id },
                contentType = { _, _ -> "show_card" }
            ) { index, show ->
                SimilarShowCard(
                    item = show,
                    onClick = { onOpenAnime(show.id) },
                    onItemFocused = { id, cover ->
                        val n1 = shows.getOrNull(index + 1)
                        val n2 = shows.getOrNull(index + 2)
                        onCardFocused(
                            id,
                            cover,
                            listOfNotNull(n1?.id, n2?.id),
                            listOfNotNull(n1?.coverUrl, n2?.coverUrl)
                        )
                    },
                    modifier = Modifier.width(210.dp)
                )
            }
        }
    }
}
