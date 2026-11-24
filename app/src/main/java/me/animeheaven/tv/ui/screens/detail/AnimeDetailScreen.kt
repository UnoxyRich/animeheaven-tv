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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import me.animeheaven.tv.ui.components.AhShape10
import me.animeheaven.tv.ui.components.AhShape2
import me.animeheaven.tv.ui.components.AhShape20
import me.animeheaven.tv.ui.components.AhShape3
import me.animeheaven.tv.ui.components.AhShape8
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
    var hasFocusedPrimary by rememberSaveable { mutableStateOf(false) }

    val detail = state.detail
    LaunchedEffect(detail?.id) {
        if (detail != null && !hasFocusedPrimary) {
            delay(80L)
            hasFocusedPrimary = runCatching { primaryFocusRequester.requestFocus() }.getOrDefault(false)
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
            val verticalScrim = remember(palette.background) {
                Brush.verticalGradient(
                    0.0f to Color(0x660B0B0B),
                    0.45f to Color(0x990B0B0B),
                    1.0f to palette.background
                )
            }
            val horizontalScrim = remember(palette.background) {
                Brush.horizontalGradient(
                    0.0f to palette.background.copy(alpha = 0.94f),
                    0.55f to palette.background.copy(alpha = 0.72f),
                    1.0f to Color.Transparent
                )
            }
            val coverForPlayer = detail.bannerUrl ?: detail.posterUrl
            val listState = rememberLazyListState(
                prefetchStrategy = remember { LazyListPrefetchStrategy(nestedPrefetchItemCount = 4) }
            )

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 32.dp)
            ) {
                // Full-Bleed Cinematic Netflix TV Hero + Editorial Info
                item(key = "hero_detail", contentType = "detail_hero") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(380.dp)
                    ) {
                        AhCoverImage(
                            url = detail.bannerUrl?.takeIf { it.isNotBlank() } ?: detail.posterUrl,
                            contentDescription = detail.title,
                            targetSize = ImageTargetSize.HERO_BANNER,
                            modifier = Modifier.fillMaxSize()
                        )
                        Box(modifier = Modifier.fillMaxSize().background(horizontalScrim))
                        Box(modifier = Modifier.fillMaxSize().background(verticalScrim))

                        // Top Back Pill
                        AhFocusableBox(
                            onClick = onBack,
                            modifier = Modifier
                                .padding(horizontal = AhOverscanHorizontal, vertical = AhOverscanVertical)
                                .align(Alignment.TopStart),
                            shape = AhShape20,
                            containerColor = Color(0xB3141414),
                            focusedContainerColor = Color.White,
                            unfocusedBorderColor = Color(0x33FFFFFF),
                            focusedBorderColor = Color.White,
                            focusScale = 1.06f
                        ) { isFocused ->
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                    contentDescription = "Back",
                                    tint = if (isFocused) Color.Black else Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "Back",
                                    color = if (isFocused) Color.Black else Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        // Overlaid Editorial Detail Header
                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .fillMaxWidth()
                                .padding(horizontal = AhOverscanHorizontal)
                                .padding(bottom = 18.dp),
                            horizontalArrangement = Arrangement.spacedBy(24.dp),
                            verticalAlignment = Alignment.Bottom
                        ) {
                            AhCoverImage(
                                url = detail.posterUrl,
                                contentDescription = detail.title,
                                targetSize = ImageTargetSize.DETAIL_POSTER,
                                modifier = Modifier
                                    .width(156.dp)
                                    .aspectRatio(2f / 3f)
                                    .clip(AhShape10)
                                    .border(1.dp, Color(0x33FFFFFF), AhShape10)
                            )

                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = "A",
                                        color = palette.crimson,
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Black
                                    )
                                    Text(
                                        text = "S E R I E S",
                                        color = Color(0xFFD6D6D6),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 2.4.sp
                                    )
                                }

                                Text(
                                    text = detail.title,
                                    color = palette.textPrimary,
                                    fontSize = 30.sp,
                                    lineHeight = 34.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )

                                // Netflix Metadata Strip: Score (Green), Year, Episodes, HD
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    if (detail.score.isNotBlank()) {
                                        Text(
                                            text = "Score ${detail.score}",
                                            color = palette.positiveGreen,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    if (detail.year.isNotBlank()) {
                                        Text(
                                            text = detail.year,
                                            color = palette.textSecondary,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                    if (detail.episodesLabel.isNotBlank()) {
                                        Text(
                                            text = "${detail.episodesLabel} Episodes",
                                            color = palette.textSecondary,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .border(1.dp, Color(0x88FFFFFF), AhShape2)
                                            .padding(horizontal = 5.dp, vertical = 1.dp)
                                    ) {
                                        Text(
                                            text = "HD",
                                            color = palette.textPrimary,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                if (detail.synopsis.isNotBlank()) {
                                    Text(
                                        text = detail.synopsis,
                                        color = palette.textSecondary,
                                        fontSize = 14.sp,
                                        lineHeight = 20.sp,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                if (detail.tags.isNotEmpty()) {
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        detail.tags.take(6).forEach { tag ->
                                            AhFocusableBox(
                                                onClick = { onOpenTag(tag) },
                                                shape = AhShape20,
                                                containerColor = Color(0x33FFFFFF),
                                                focusedContainerColor = Color.White,
                                                focusedBorderColor = Color.White,
                                                focusScale = 1.05f
                                            ) { isFocused ->
                                                Text(
                                                    text = tag,
                                                    color = if (isFocused) Color.Black else Color.White,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                                )
                                            }
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(2.dp))

                                // Signature Netflix TV CTAs: White Play + Frosted My List
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
                                            shape = AhShape3,
                                            containerColor = Color.White,
                                            focusedContainerColor = Color.White,
                                            focusedBorderColor = palette.crimson,
                                            focusedBorderWidth = 3.dp,
                                            focusScale = 1.05f
                                        ) { _ ->
                                            Row(
                                                modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.PlayArrow,
                                                    contentDescription = null,
                                                    tint = Color.Black,
                                                    modifier = Modifier.size(22.dp)
                                                )
                                                Text(
                                                    text = action.buttonLabel,
                                                    color = Color.Black,
                                                    fontSize = 16.sp,
                                                    fontWeight = FontWeight.ExtraBold
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
                                        shape = AhShape3,
                                        containerColor = if (state.isBookmarked) palette.crimson else Color(0x99333333),
                                        focusedContainerColor = Color.White,
                                        unfocusedBorderColor = Color(0x33FFFFFF),
                                        focusedBorderColor = Color.White,
                                        borderWidth = 1.dp,
                                        focusScale = 1.05f
                                    ) { isFocused ->
                                        Row(
                                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
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
                                                tint = if (isFocused) Color.Black else Color.White,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Text(
                                                text = if (state.isBookmarked) "In My List" else "My List",
                                                color = if (isFocused) Color.Black else Color.White,
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

                // Countdown Banner
                if (!detail.countdownLabel.isNullOrBlank()) {
                    item(key = "countdown") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = AhOverscanHorizontal)
                                .padding(top = 6.dp)
                                .clip(AhShape8)
                                .background(palette.surface)
                                .border(1.dp, palette.crimson.copy(alpha = 0.45f), AhShape8)
                                .padding(vertical = 10.dp, horizontal = 14.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = detail.countdownLabel,
                                color = palette.positiveGreen,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Episodes Section with Netflix Red Tab Accent
                if (orderedEpisodes.isNotEmpty()) {
                    item(key = "episodes_header") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = AhOverscanHorizontal)
                                .padding(top = 18.dp, bottom = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Box(
                                    modifier = Modifier
                                        .width(54.dp)
                                        .height(3.dp)
                                        .clip(CircleShape)
                                        .background(palette.crimson)
                                )
                                Text(
                                    text = "Episodes (${orderedEpisodes.size})",
                                    color = palette.textPrimary,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            AhFocusableBox(
                                onClick = viewModel::toggleSortOrder,
                                shape = AhShape20,
                                containerColor = palette.surfaceElevated,
                                focusedContainerColor = Color.White,
                                unfocusedBorderColor = Color(0x33FFFFFF),
                                focusedBorderColor = Color.White,
                                borderWidth = 1.dp
                            ) { isFocused ->
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = AhIcons.SwapVert,
                                        contentDescription = null,
                                        tint = if (isFocused) Color.Black else Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = if (state.sortOldestFirst) "Ep 1 First" else "Newest First",
                                        color = if (isFocused) Color.Black else Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    // Modern Rounded Netflix Episode Tiles
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
                                EpisodeNetflixTile(
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
                            title = "More Like This",
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
private fun EpisodeNetflixTile(
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
        shape = AhShape8,
        containerColor = if (isWatched) palette.surface else palette.surfaceElevated,
        focusedContainerColor = Color.White,
        unfocusedBorderColor = if (isWatched) palette.positiveGreen.copy(alpha = 0.45f) else Color(0x26FFFFFF),
        focusedBorderColor = Color.White,
        borderWidth = 1.dp,
        focusedBorderWidth = 2.5.dp,
        focusScale = 1.06f
    ) { isFocused ->
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(6.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (isWatched) "WATCHED" else "EPISODE",
                    color = when {
                        isFocused -> Color(0xFF444444)
                        isWatched -> palette.positiveGreen
                        else -> palette.textMuted
                    },
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                Text(
                    text = episode.numberLabel,
                    color = if (isFocused) Color.Black else palette.textPrimary,
                    fontSize = if (episode.numberLabel.length >= 4) 20.sp else 26.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1
                )
                Text(
                    text = episode.releaseAge.ifBlank { " " },
                    color = if (isFocused) Color(0xFF555555) else palette.textMuted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    textAlign = TextAlign.Center
                )
            }

            if (progressFraction > 0.02f && !isWatched) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.5.dp)
                        .align(Alignment.BottomCenter)
                        .background(Color(0x44FFFFFF))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progressFraction)
                            .fillMaxHeight()
                            .background(palette.crimson)
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
    Column(modifier = Modifier.padding(top = 20.dp)) {
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
                key = { index, it -> "anime_${index}_${it.id}" },
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
                    modifier = Modifier.width(214.dp)
                )
            }
        }
    }
}
