package me.animeheaven.tv.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyListPrefetchStrategy
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowRgb565
import coil3.size.Precision
import kotlinx.coroutines.delay
import me.animeheaven.tv.data.cache.ImageTargetSize
import me.animeheaven.tv.data.model.AnimeCard
import me.animeheaven.tv.data.model.BannerItem
import me.animeheaven.tv.data.model.ContinueWatchingItem
import me.animeheaven.tv.data.model.FeedViewMode
import me.animeheaven.tv.data.model.RankDirection
import me.animeheaven.tv.ui.theme.LocalAhPalette

private val HERO_BANNER_SCRIM = Brush.verticalGradient(
    listOf(Color.Transparent, Color(0xCC000000), Color(0xEE000000))
)
private val RANK_BADGE_SHAPE = RoundedCornerShape(6.dp)

@Immutable
data class GridRow<T>(
    val key: String,
    val items: List<T>
)

fun <T> List<T>.toGridRows(columns: Int, keyOf: (T) -> String): List<GridRow<T>> {
    if (isEmpty()) return emptyList()
    val rows = ArrayList<GridRow<T>>((size + columns - 1) / columns)
    var i = 0
    while (i < size) {
        val end = minOf(i + columns, size)
        val slice = subList(i, end)
        val rowKey = buildString(columns * 8) {
            for (j in slice.indices) {
                if (j > 0) append('_')
                append(keyOf(slice[j]))
            }
        }
        rows.add(GridRow(key = rowKey, items = slice))
        i = end
    }
    return rows
}

@Composable
fun AhCoverImage(
    url: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    targetSize: ImageTargetSize = ImageTargetSize.STANDARD_CARD
) {
    val context = LocalContext.current
    val request = remember(url, targetSize) {
        ImageRequest.Builder(context)
            .data(url)
            .size(targetSize.widthPx, targetSize.heightPx)
            .precision(Precision.INEXACT)
            .allowRgb565(true)
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        contentScale = ContentScale.Crop,
        filterQuality = FilterQuality.Medium,
        modifier = modifier
    )
}

@Composable
fun HeroBannerCarousel(
    banners: List<BannerItem>,
    onBannerClick: (String) -> Unit,
    onBannerFocused: ((animeId: String, imageUrl: String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    if (banners.isEmpty()) return
    val palette = LocalAhPalette.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var currentIndex by remember(banners) { mutableIntStateOf(0) }
    var isCarouselFocused by remember { mutableStateOf(false) }

    // Pause banner rotation whenever the screen is not RESUMED (e.g. during video playback)
    LaunchedEffect(banners, isCarouselFocused, lifecycleOwner) {
        if (banners.size <= 1 || isCarouselFocused) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(7000L)
                currentIndex = (currentIndex + 1) % banners.size
            }
        }
    }

    val safeIndex = currentIndex.coerceIn(0, banners.lastIndex)
    val current = banners[safeIndex]

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = AhOverscanHorizontal)
            .height(215.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (banners.size > 1) {
            AhStaticFocusableBox(
                onClick = {
                    currentIndex = if (safeIndex == 0) banners.lastIndex else safeIndex - 1
                },
                modifier = Modifier
                    .width(42.dp)
                    .fillMaxHeight(),
                containerColor = palette.surface,
                focusedContainerColor = palette.crimson,
                focusedBorderColor = Color.White,
                onFocusStateChanged = { isCarouselFocused = it }
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                        contentDescription = "Previous Banner",
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }

        AhStaticFocusableBox(
            onClick = { onBannerClick(current.animeId) },
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            containerColor = palette.surface,
            focusedBorderWidth = 3.dp,
            focusScale = 1.015f,
            onFocusStateChanged = { focused ->
                isCarouselFocused = focused
                if (focused) onBannerFocused?.invoke(current.animeId, current.bannerImageUrl)
            }
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Crossfade(
                    targetState = current,
                    animationSpec = tween(durationMillis = 220),
                    label = "heroBannerCrossfade"
                ) { slide ->
                    AhCoverImage(
                        url = slide.bannerImageUrl,
                        contentDescription = slide.title,
                        targetSize = ImageTargetSize.HERO_BANNER,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(HERO_BANNER_SCRIM)
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Box(
                                modifier = Modifier
                                    .background(palette.crimson, AhShape2)
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "FEATURED",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = current.title,
                                color = Color.White,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (banners.size > 1) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                banners.forEachIndexed { idx, _ ->
                                    Box(
                                        modifier = Modifier
                                            .size(if (idx == safeIndex) 10.dp else 7.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (idx == safeIndex) palette.crimson else Color(0x88FFFFFF)
                                            )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (banners.size > 1) {
            AhStaticFocusableBox(
                onClick = { currentIndex = (safeIndex + 1) % banners.size },
                modifier = Modifier
                    .width(42.dp)
                    .fillMaxHeight(),
                containerColor = palette.surface,
                focusedContainerColor = palette.crimson,
                focusedBorderColor = Color.White,
                onFocusStateChanged = { isCarouselFocused = it }
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = "Next Banner",
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ContinueWatchingRow(
    items: List<ContinueWatchingItem>,
    onPlayItem: (ContinueWatchingItem) -> Unit,
    onItemFocused: ((animeId: String, coverUrl: String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) return
    val palette = LocalAhPalette.current
    val rowState = rememberLazyListState(
        prefetchStrategy = remember { LazyListPrefetchStrategy(nestedPrefetchItemCount = 4) }
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 14.dp)
    ) {
        Text(
            text = "Continue Watching",
            color = palette.textPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = AhOverscanHorizontal, vertical = 6.dp)
        )

        LazyRow(
            state = rowState,
            contentPadding = PaddingValues(horizontal = AhOverscanHorizontal),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(
                items = items,
                key = { "${it.animeId}_${it.targetEpisodeKey}" },
                contentType = { "continue_card" }
            ) { item ->
                AhStaticFocusableBox(
                    onClick = { onPlayItem(item) },
                    modifier = Modifier.width(250.dp),
                    containerColor = palette.surface,
                    focusScale = 1.04f,
                    onFocusStateChanged = { focused ->
                        if (focused) onItemFocused?.invoke(item.animeId, item.coverUrl)
                    }
                ) {
                    Column {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(16f / 9f)
                        ) {
                            AhCoverImage(
                                url = item.coverUrl,
                                contentDescription = item.animeTitle,
                                targetSize = ImageTargetSize.CONTINUE_CARD,
                                modifier = Modifier.fillMaxSize()
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color(0x33000000))
                            )
                            Row(
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(6.dp)
                                    .background(
                                        color = if (item.isNextEpisode) palette.crimson else Color(0xDD121212),
                                        shape = AhShape2
                                    )
                                    .then(
                                        if (!item.isNextEpisode) {
                                            Modifier.border(1.dp, palette.crimson, AhShape2)
                                        } else {
                                            Modifier
                                        }
                                    )
                                    .padding(horizontal = 7.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.PlayArrow,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = if (item.isNextEpisode) {
                                        "NEXT • ${item.targetEpisodeLabel}"
                                    } else {
                                        "RESUME • ${item.targetEpisodeLabel}"
                                    },
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            if (!item.isNextEpisode && item.durationMs > 0L) {
                                val fraction = (item.resumePositionMs.toFloat() / item.durationMs.toFloat())
                                    .coerceIn(0.03f, 1f)
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(4.dp)
                                        .align(Alignment.BottomCenter)
                                        .background(Color(0x88000000))
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(fraction)
                                            .fillMaxHeight()
                                            .background(palette.crimson)
                                    )
                                }
                            }
                        }

                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = item.animeTitle,
                                color = palette.textPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (item.isNextEpisode) {
                                    "Finished ${item.lastWatchedLabel} • Auto-advance"
                                } else {
                                    "${formatDurationMs(item.resumePositionMs)} / ${formatDurationMs(item.durationMs)}"
                                },
                                color = palette.crimson,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PopularHorizontalStrip(
    items: List<AnimeCard>,
    onAnimeClick: (String) -> Unit,
    onMoreClick: () -> Unit,
    onItemFocused: ((animeId: String, coverUrl: String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) return
    val palette = LocalAhPalette.current
    val rowState = rememberLazyListState(
        prefetchStrategy = remember { LazyListPrefetchStrategy(nestedPrefetchItemCount = 5) }
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = AhOverscanHorizontal)
            .padding(top = 14.dp)
            .background(palette.surface, AhShape2)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        VerticalCrimsonTab(
            text = "POPULAR",
            onClick = onMoreClick
        )

        LazyRow(
            state = rowState,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(
                items = items,
                key = { it.id },
                contentType = { "popular_card" }
            ) { anime ->
                AhFocusableBox(
                    onClick = { onAnimeClick(anime.id) },
                    modifier = Modifier
                        .width(172.dp)
                        .height(154.dp),
                    containerColor = palette.surfaceElevated,
                    focusScale = 1.04f,
                    onFocusStateChanged = { focused ->
                        if (focused) onItemFocused?.invoke(anime.id, anime.coverUrl)
                    }
                ) { isFocused ->
                    Box(modifier = Modifier.fillMaxSize()) {
                        AhCoverImage(
                            url = anime.coverUrl,
                            contentDescription = anime.title,
                            targetSize = ImageTargetSize.POPULAR_CARD,
                            modifier = Modifier.fillMaxSize()
                        )
                        if (isFocused || anime.title.isNotBlank()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .align(Alignment.BottomCenter)
                                    .background(
                                        if (isFocused) Color(0xE6DD0021) else Color(0xB3000000)
                                    )
                                    .padding(horizontal = 6.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = anime.title,
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }

        VerticalCrimsonTab(
            text = "MORE",
            onClick = onMoreClick
        )
    }
}

@Composable
private fun VerticalCrimsonTab(
    text: String,
    onClick: () -> Unit
) {
    val palette = LocalAhPalette.current
    AhFocusableBox(
        onClick = onClick,
        modifier = Modifier
            .width(38.dp)
            .height(154.dp),
        containerColor = palette.crimson,
        focusedContainerColor = Color.White,
        focusedBorderColor = palette.crimson,
        focusScale = 1.04f
    ) { isFocused ->
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            text.forEach { ch ->
                Text(
                    text = ch.toString(),
                    color = if (isFocused) palette.crimson else Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold,
                    lineHeight = 14.sp
                )
            }
        }
    }
}

@Composable
fun SectionHeaderWithToggle(
    title: String,
    viewMode: FeedViewMode?,
    onChangeViewMode: ((FeedViewMode) -> Unit)?,
    modifier: Modifier = Modifier
) {
    val palette = LocalAhPalette.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = AhOverscanHorizontal, vertical = 12.dp)
    ) {
        Text(
            text = title,
            color = palette.textSecondary,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.Center)
        )

        if (viewMode != null && onChangeViewMode != null) {
            Row(
                modifier = Modifier.align(Alignment.CenterEnd),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircleToggleButton(
                    selected = viewMode == FeedViewMode.LIST,
                    onClick = { onChangeViewMode(FeedViewMode.LIST) }
                ) { tint ->
                    Icon(
                        imageVector = AhIcons.FormatListBulleted,
                        contentDescription = "List View",
                        tint = tint,
                        modifier = Modifier.size(20.dp)
                    )
                }
                CircleToggleButton(
                    selected = viewMode == FeedViewMode.GRID,
                    onClick = { onChangeViewMode(FeedViewMode.GRID) }
                ) { tint ->
                    Icon(
                        imageVector = AhIcons.GridView,
                        contentDescription = "Grid View",
                        tint = tint,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun CircleToggleButton(
    selected: Boolean,
    onClick: () -> Unit,
    icon: @Composable (Color) -> Unit
) {
    val palette = LocalAhPalette.current
    AhFocusableBox(
        onClick = onClick,
        modifier = Modifier.size(36.dp),
        shape = CircleShape,
        containerColor = if (selected) palette.crimson else palette.surfaceElevated,
        focusedContainerColor = palette.crimson,
        unfocusedBorderColor = palette.crimson,
        focusedBorderColor = Color.White,
        borderWidth = 1.5.dp,
        focusScale = 1.08f
    ) { isFocused ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            icon(if (selected || isFocused) Color.White else palette.textSecondary)
        }
    }
}

@Composable
fun ScheduleListCard(
    item: AnimeCard,
    isBookmarked: Boolean,
    onClick: () -> Unit,
    onToggleBookmark: () -> Unit,
    onItemFocused: ((animeId: String, coverUrl: String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val palette = LocalAhPalette.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = AhOverscanHorizontal, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AhFocusableBox(
            onClick = onClick,
            modifier = Modifier.weight(1f),
            containerColor = palette.surface,
            focusScale = 1.015f,
            onFocusStateChanged = { focused ->
                if (focused) onItemFocused?.invoke(item.id, item.coverUrl)
            }
        ) { isFocused ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .width(208.dp)
                        .aspectRatio(16f / 9f)
                ) {
                    AhCoverImage(
                        url = item.coverUrl,
                        contentDescription = item.title,
                        targetSize = ImageTargetSize.STANDARD_CARD,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                if (item.episodeLabel.isNotBlank()) {
                    Text(
                        text = item.episodeLabel,
                        color = palette.crimson,
                        fontSize = if (item.episodeLabel.length > 3) 26.sp else 36.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier.width(120.dp)
                    )
                } else {
                    Spacer(modifier = Modifier.width(20.dp))
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = item.title,
                        color = if (isFocused) palette.textPrimary else palette.textSecondary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                    if (item.japaneseTitle.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = item.japaneseTitle,
                            color = palette.textMuted,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center
                        )
                    }
                    if (item.rankDirection != null || item.timerOrStatus.isNotBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        RankOrTimerBadge(item = item)
                    }
                }
            }
        }

        // Zero-recomposition focus button matching AnimeHeaven's + / X overlay
        AhStaticFocusableBox(
            onClick = onToggleBookmark,
            modifier = Modifier.size(44.dp),
            shape = CircleShape,
            containerColor = if (isBookmarked) palette.crimson else palette.surface,
            focusedContainerColor = palette.crimson,
            unfocusedBorderColor = palette.crimson,
            focusedBorderColor = Color.White,
            borderWidth = 1.5.dp,
            focusScale = 1.1f
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (isBookmarked) AhIcons.Bookmark else AhIcons.BookmarkBorder,
                    contentDescription = if (isBookmarked) "Remove Bookmark" else "Bookmark",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

@Composable
fun ScheduleGridCard(
    item: AnimeCard,
    isBookmarked: Boolean,
    onClick: () -> Unit,
    onItemFocused: ((animeId: String, coverUrl: String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val palette = LocalAhPalette.current
    AhFocusableBox(
        onClick = onClick,
        modifier = modifier,
        containerColor = palette.surface,
        focusScale = 1.03f,
        onFocusStateChanged = { focused ->
            if (focused) onItemFocused?.invoke(item.id, item.coverUrl)
        }
    ) { isFocused ->
        Column(modifier = Modifier.padding(8.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
            ) {
                AhCoverImage(
                    url = item.coverUrl,
                    contentDescription = item.title,
                    targetSize = ImageTargetSize.STANDARD_CARD,
                    modifier = Modifier.fillMaxSize()
                )
                if (item.episodeLabel.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .background(palette.crimson)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = item.episodeLabel,
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                if (isBookmarked) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(5.dp)
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(palette.crimson),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = AhIcons.Bookmark,
                            contentDescription = "Bookmarked",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = item.title,
                color = if (isFocused) palette.textPrimary else palette.textSecondary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            if (item.japaneseTitle.isNotBlank()) {
                Text(
                    text = item.japaneseTitle,
                    color = palette.textMuted,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (item.rankDirection != null || item.timerOrStatus.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    RankOrTimerBadge(item = item)
                }
            }
        }
    }
}

@Composable
fun SimilarShowCard(
    item: AnimeCard,
    onClick: () -> Unit,
    onItemFocused: ((animeId: String, coverUrl: String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val palette = LocalAhPalette.current
    AhFocusableBox(
        onClick = onClick,
        modifier = modifier,
        containerColor = palette.surface,
        focusScale = 1.03f,
        onFocusStateChanged = { focused ->
            if (focused) onItemFocused?.invoke(item.id, item.coverUrl)
        }
    ) { isFocused ->
        Column(modifier = Modifier.padding(6.dp)) {
            AhCoverImage(
                url = item.coverUrl,
                contentDescription = item.title,
                targetSize = ImageTargetSize.STANDARD_CARD,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = item.title,
                color = if (isFocused) palette.textPrimary else palette.textSecondary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp)
            )
        }
    }
}

@Composable
private fun RankOrTimerBadge(item: AnimeCard) {
    val palette = LocalAhPalette.current
    if (item.rankDirection != null) {
        val badgeColor = when (item.rankDirection) {
            RankDirection.UP -> palette.crimson
            RankDirection.SAME -> palette.crimson
            RankDirection.DOWN -> palette.surfaceElevated
        }
        val arrow = when (item.rankDirection) {
            RankDirection.UP -> "▲"
            RankDirection.DOWN -> "▼"
            RankDirection.SAME -> "•"
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .background(badgeColor, RANK_BADGE_SHAPE),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = arrow,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = item.rankDelta.orEmpty(),
                color = palette.textSecondary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    } else if (item.timerOrStatus.isNotBlank()) {
        Text(
            text = item.timerOrStatus,
            color = palette.crimson,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun AnimeHeavenFooter(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalAhPalette.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = AhOverscanHorizontal)
            .padding(top = 16.dp, bottom = 24.dp)
            .background(palette.surface, AhShape2)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "AnimeHeaven.me TV • Pure Ad-Free 1080p Streaming",
            color = palette.textSecondary,
            fontSize = 14.sp
        )
        AhFocusableBox(
            onClick = onOpenSettings,
            shape = CircleShape,
            containerColor = palette.crimson,
            focusedContainerColor = Color.White,
            focusedBorderColor = palette.crimson,
            focusScale = 1.06f
        ) { isFocused ->
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = AhIcons.Tune,
                    contentDescription = null,
                    tint = if (isFocused) palette.crimson else Color.White,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "Settings & Theme",
                    color = if (isFocused) palette.crimson else Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun AhLoadingBox(
    message: String = "Loading AnimeHeaven...",
    modifier: Modifier = Modifier
) {
    val palette = LocalAhPalette.current
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = palette.crimson)
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = message,
                color = palette.textSecondary,
                fontSize = 16.sp
            )
        }
    }
}

@Composable
fun AhErrorBox(
    errorMessage: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalAhPalette.current
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .background(palette.surface, AhShape4)
                .border(1.dp, palette.crimson, AhShape4)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Unable to load content",
                color = palette.textPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = errorMessage,
                color = palette.textSecondary,
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            AhFocusableBox(
                onClick = onRetry,
                containerColor = palette.crimson,
                focusedContainerColor = Color.White,
                focusedBorderColor = palette.crimson
            ) { isFocused ->
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = null,
                        tint = if (isFocused) palette.crimson else Color.White
                    )
                    Text(
                        text = "Retry",
                        color = if (isFocused) palette.crimson else Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

fun formatDurationMs(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSec = ms / 1000L
    val hours = totalSec / 3600L
    val minutes = (totalSec % 3600L) / 60L
    val seconds = totalSec % 60L
    return buildString(8) {
        if (hours > 0) {
            append(hours)
            append(':')
            if (minutes < 10) append('0')
            append(minutes)
            append(':')
            if (seconds < 10) append('0')
            append(seconds)
        } else {
            append(minutes)
            append(':')
            if (seconds < 10) append('0')
            append(seconds)
        }
    }
}
