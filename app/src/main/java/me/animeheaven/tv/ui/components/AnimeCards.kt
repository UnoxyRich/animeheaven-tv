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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListPrefetchStrategy
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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

private val HERO_LEFT_SCRIM = Brush.horizontalGradient(
    0.0f to Color(0xF00B0B0B),
    0.45f to Color(0xB80B0B0B),
    0.78f to Color(0x330B0B0B),
    1.0f to Color.Transparent
)
private val HERO_BOTTOM_SCRIM = Brush.verticalGradient(
    0.0f to Color.Transparent,
    0.58f to Color(0x440B0B0B),
    1.0f to Color(0xF50B0B0B)
)
private val CARD_BOTTOM_SCRIM = Brush.verticalGradient(
    0.0f to Color.Transparent,
    0.45f to Color(0x44000000),
    1.0f to Color(0xE8000000)
)
private val RANK_BADGE_SHAPE = RoundedCornerShape(5.dp)
private val TOP10_BADGE_SHAPE = RoundedCornerShape(bottomStart = 6.dp)

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

/**
 * 2025/2026 Netflix TV Spotlight Hero Billboard:
 * Full-width cinematic stage with editorial left overlay, A SERIES eyebrow, contextual badges,
 * signature white "Watch Now" CTA + frosted "More Info" CTA, and subtle bottom-right controls.
 */
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

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = AhOverscanHorizontal)
            .height(312.dp)
            .clip(AhShape12)
            .background(palette.surface)
            .border(1.dp, Color(0x26FFFFFF), AhShape12)
    ) {
        // Full-bleed crossfading backdrop
        Crossfade(
            targetState = current,
            animationSpec = tween(durationMillis = 320),
            label = "heroBannerCrossfade"
        ) { slide ->
            AhCoverImage(
                url = slide.bannerImageUrl,
                contentDescription = slide.title,
                targetSize = ImageTargetSize.HERO_BANNER,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Cinema vignettes
        Box(modifier = Modifier.fillMaxSize().background(HERO_LEFT_SCRIM))
        Box(modifier = Modifier.fillMaxSize().background(HERO_BOTTOM_SCRIM))

        // Left Editorial Spotlight Overlay
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth(0.64f)
                .padding(horizontal = 28.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Netflix-style "A  S E R I E S" eyebrow
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "A",
                    color = palette.crimson,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black
                )
                Text(
                    text = "S E R I E S",
                    color = Color(0xFFD9D9D9),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.4.sp
                )
                Box(
                    modifier = Modifier
                        .clip(AhShape2)
                        .background(palette.crimson)
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "#${safeIndex + 1} FEATURED",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.8.sp
                    )
                }
            }

            Text(
                text = current.title,
                color = Color.White,
                fontSize = 30.sp,
                lineHeight = 34.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            // Contextual metadata pills
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Trending Now",
                    color = palette.positiveGreen,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(text = "•", color = Color(0x88FFFFFF), fontSize = 13.sp)
                Text(
                    text = "Subbed Anime",
                    color = Color(0xFFE0E0E0),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
                Box(
                    modifier = Modifier
                        .border(1.dp, Color(0x80FFFFFF), AhShape2)
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = "HD",
                        color = Color(0xFFE0E0E0),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(2.dp))

            // Signature Netflix White Play + Frosted More Info CTAs
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AhFocusableBox(
                    onClick = { onBannerClick(current.animeId) },
                    shape = AhShape3,
                    containerColor = Color.White,
                    focusedContainerColor = Color.White,
                    focusedBorderColor = palette.crimson,
                    focusedBorderWidth = 3.dp,
                    focusScale = 1.06f,
                    onFocusStateChanged = { focused ->
                        isCarouselFocused = focused
                        if (focused) onBannerFocused?.invoke(current.animeId, current.bannerImageUrl)
                    }
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
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = "Watch Now",
                            color = Color.Black,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }

                AhFocusableBox(
                    onClick = { onBannerClick(current.animeId) },
                    shape = AhShape3,
                    containerColor = Color(0x99333333),
                    focusedContainerColor = Color(0xE64A4A4A),
                    unfocusedBorderColor = Color(0x33FFFFFF),
                    focusedBorderColor = Color.White,
                    focusedBorderWidth = 2.5.dp,
                    focusScale = 1.05f,
                    onFocusStateChanged = { focused ->
                        isCarouselFocused = focused
                        if (focused) onBannerFocused?.invoke(current.animeId, current.bannerImageUrl)
                    }
                ) { _ ->
                    Text(
                        text = "More Info",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 11.dp)
                    )
                }
            }
        }

        // Bottom-right subtle carousel controls & progress pills
        if (banners.size > 1) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(horizontal = 24.dp, vertical = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AhStaticFocusableBox(
                    onClick = {
                        currentIndex = if (safeIndex == 0) banners.lastIndex else safeIndex - 1
                    },
                    modifier = Modifier.size(34.dp),
                    shape = CircleShape,
                    containerColor = Color(0x88181818),
                    focusedContainerColor = Color.White,
                    unfocusedBorderColor = Color(0x44FFFFFF),
                    focusedBorderColor = Color.White,
                    focusScale = 1.1f,
                    onFocusStateChanged = { isCarouselFocused = it }
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                            contentDescription = "Previous Banner",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    banners.forEachIndexed { idx, _ ->
                        Box(
                            modifier = Modifier
                                .width(if (idx == safeIndex) 20.dp else 7.dp)
                                .height(4.dp)
                                .clip(CircleShape)
                                .background(
                                    if (idx == safeIndex) palette.crimson else Color(0x66FFFFFF)
                                )
                        )
                    }
                }

                AhStaticFocusableBox(
                    onClick = { currentIndex = (safeIndex + 1) % banners.size },
                    modifier = Modifier.size(34.dp),
                    shape = CircleShape,
                    containerColor = Color(0x88181818),
                    focusedContainerColor = Color.White,
                    unfocusedBorderColor = Color(0x44FFFFFF),
                    focusedBorderColor = Color.White,
                    focusScale = 1.1f,
                    onFocusStateChanged = { isCarouselFocused = it }
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            contentDescription = "Next Banner",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
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
            .padding(top = 18.dp)
    ) {
        Text(
            text = "Continue Watching",
            color = palette.textPrimary,
            fontSize = 21.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = AhOverscanHorizontal, vertical = 8.dp)
        )

        LazyRow(
            state = rowState,
            contentPadding = PaddingValues(horizontal = AhOverscanHorizontal, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(
                items = items,
                key = { "${it.animeId}_${it.targetEpisodeKey}" },
                contentType = { "continue_card" }
            ) { item ->
                AhStaticFocusableBox(
                    onClick = { onPlayItem(item) },
                    modifier = Modifier.width(268.dp),
                    shape = AhShape10,
                    containerColor = palette.surface,
                    unfocusedBorderColor = Color(0x1FFFFFFF),
                    focusedBorderColor = Color.White,
                    focusedBorderWidth = 2.5.dp,
                    focusScale = 1.05f,
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
                                    .background(CARD_BOTTOM_SCRIM)
                            )

                            // Netflix centered frosted play circle
                            Box(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xA6000000))
                                    .border(1.5.dp, Color.White, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.PlayArrow,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(26.dp)
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(8.dp)
                                    .clip(AhShape2)
                                    .background(if (item.isNextEpisode) palette.crimson else Color(0xCC141414))
                                    .padding(horizontal = 7.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = if (item.isNextEpisode) {
                                        "UP NEXT • ${item.targetEpisodeLabel}"
                                    } else {
                                        "EP ${item.targetEpisodeLabel}"
                                    },
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }

                            // Signature Netflix bottom red progress bar
                            val fraction = if (!item.isNextEpisode && item.durationMs > 0L) {
                                (item.resumePositionMs.toFloat() / item.durationMs.toFloat())
                                    .coerceIn(0.04f, 1f)
                            } else {
                                0f
                            }
                            if (fraction > 0f) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(3.5.dp)
                                        .align(Alignment.BottomCenter)
                                        .background(Color(0x55FFFFFF))
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

                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                            Text(
                                text = item.animeTitle,
                                color = palette.textPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (item.isNextEpisode) {
                                    "Finished ${item.lastWatchedLabel} • Ready to play"
                                } else {
                                    "${formatDurationMs(item.resumePositionMs)} of ${formatDurationMs(item.durationMs)}"
                                },
                                color = palette.textMuted,
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

/**
 * Iconic Netflix "Top 10 Popular Today" swimlane:
 * Large stylized rank numerals peeking beside 2:3 poster cards with red TOP 10 corner ribbons.
 */
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

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 18.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AhOverscanHorizontal, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .clip(AhShape2)
                        .background(palette.crimson)
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "TOP 10",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 0.6.sp
                    )
                }
                Text(
                    text = "Popular in Anime Today",
                    color = palette.textPrimary,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            AhFocusableBox(
                onClick = onMoreClick,
                shape = AhShape20,
                containerColor = Color(0x26FFFFFF),
                focusedContainerColor = Color.White,
                focusedBorderColor = Color.White,
                focusScale = 1.05f
            ) { isFocused ->
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "Explore All",
                        color = if (isFocused) Color.Black else palette.textPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = "Explore Popular",
                        tint = if (isFocused) Color.Black else palette.textPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        LazyRow(
            state = rowState,
            contentPadding = PaddingValues(horizontal = AhOverscanHorizontal, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            itemsIndexed(
                items = items,
                key = { _, it -> it.id },
                contentType = { _, _ -> "popular_card" }
            ) { index, anime ->
                val rank = index + 1
                Row(
                    verticalAlignment = Alignment.Bottom
                ) {
                    if (rank <= 10) {
                        Text(
                            text = rank.toString(),
                            color = if (palette.isLight) Color(0x38000000) else Color(0x52FFFFFF),
                            fontSize = if (rank == 10) 76.sp else 92.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = (-4).sp,
                            modifier = Modifier
                                .offset(x = 12.dp, y = 12.dp)
                        )
                    }

                    AhFocusableBox(
                        onClick = { onAnimeClick(anime.id) },
                        modifier = Modifier
                            .width(142.dp)
                            .height(202.dp),
                        shape = AhShape10,
                        containerColor = palette.surfaceElevated,
                        unfocusedBorderColor = Color(0x1FFFFFFF),
                        focusedBorderColor = Color.White,
                        focusedBorderWidth = 2.5.dp,
                        focusScale = 1.06f,
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

                            // Netflix Red TOP 10 corner badge
                            if (rank <= 10) {
                                Column(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .clip(TOP10_BADGE_SHAPE)
                                        .background(palette.crimson)
                                        .padding(horizontal = 5.dp, vertical = 3.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = "TOP",
                                        color = Color.White,
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.Black,
                                        lineHeight = 9.sp
                                    )
                                    Text(
                                        text = "10",
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Black,
                                        lineHeight = 11.sp
                                    )
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .align(Alignment.BottomCenter)
                                    .background(CARD_BOTTOM_SCRIM)
                                    .padding(horizontal = 8.dp, vertical = 7.dp)
                            ) {
                                Text(
                                    text = anime.title,
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = if (isFocused) FontWeight.ExtraBold else FontWeight.SemiBold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
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
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = AhOverscanHorizontal)
            .padding(top = 18.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            color = palette.textPrimary,
            fontSize = 21.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        if (viewMode != null && onChangeViewMode != null) {
            Row(
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
                        modifier = Modifier.size(18.dp)
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
                        modifier = Modifier.size(17.dp)
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
        containerColor = if (selected) Color.White else palette.surfaceElevated,
        focusedContainerColor = Color.White,
        unfocusedBorderColor = if (selected) Color.White else Color(0x33FFFFFF),
        focusedBorderColor = palette.crimson,
        borderWidth = 1.dp,
        focusedBorderWidth = 2.dp,
        focusScale = 1.08f
    ) { isFocused ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            icon(if (selected || isFocused) Color.Black else palette.textSecondary)
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
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AhFocusableBox(
            onClick = onClick,
            modifier = Modifier.weight(1f),
            shape = AhShape10,
            containerColor = palette.surface,
            focusedContainerColor = palette.surfaceElevated,
            unfocusedBorderColor = Color(0x18FFFFFF),
            focusedBorderColor = Color.White,
            focusedBorderWidth = 2.5.dp,
            focusScale = 1.015f,
            onFocusStateChanged = { focused ->
                if (focused) onItemFocused?.invoke(item.id, item.coverUrl)
            }
        ) { isFocused ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .width(196.dp)
                        .aspectRatio(16f / 9f)
                        .clip(AhShape8)
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
                                .align(Alignment.BottomStart)
                                .padding(6.dp)
                                .clip(AhShape2)
                                .background(palette.crimson)
                                .padding(horizontal = 7.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "NEW EP ${item.episodeLabel}",
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = item.title,
                        color = if (isFocused) Color.White else palette.textPrimary,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (item.japaneseTitle.isNotBlank()) {
                        Text(
                            text = item.japaneseTitle,
                            color = palette.textMuted,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (item.rankDirection != null || item.timerOrStatus.isNotBlank()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        RankOrTimerBadge(item = item)
                    }
                }
            }
        }

        // Netflix "+ My List" circular action
        AhFocusableBox(
            onClick = onToggleBookmark,
            modifier = Modifier.size(44.dp),
            shape = CircleShape,
            containerColor = if (isBookmarked) palette.crimson else palette.surfaceElevated,
            focusedContainerColor = Color.White,
            unfocusedBorderColor = if (isBookmarked) palette.crimson else Color(0x38FFFFFF),
            focusedBorderColor = Color.White,
            borderWidth = 1.5.dp,
            focusScale = 1.1f
        ) { isFocused ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (isBookmarked) AhIcons.Bookmark else AhIcons.BookmarkBorder,
                    contentDescription = if (isBookmarked) "Remove Bookmark" else "Bookmark",
                    tint = if (isFocused) Color.Black else Color.White,
                    modifier = Modifier.size(21.dp)
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
        shape = AhShape10,
        containerColor = palette.surface,
        focusedContainerColor = palette.surfaceElevated,
        unfocusedBorderColor = Color(0x18FFFFFF),
        focusedBorderColor = Color.White,
        focusedBorderWidth = 2.5.dp,
        focusScale = 1.05f,
        onFocusStateChanged = { focused ->
            if (focused) onItemFocused?.invoke(item.id, item.coverUrl)
        }
    ) { isFocused ->
        Column {
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
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(CARD_BOTTOM_SCRIM)
                )
                if (item.episodeLabel.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(8.dp)
                            .clip(AhShape2)
                            .background(palette.crimson)
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "EP ${item.episodeLabel}",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
                if (isBookmarked) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(Color(0xCC000000))
                            .border(1.dp, Color(0x66FFFFFF), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = AhIcons.Bookmark,
                            contentDescription = "Bookmarked",
                            tint = palette.crimson,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = item.title,
                    color = if (isFocused) Color.White else palette.textPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.rankDirection != null || item.timerOrStatus.isNotBlank()) {
                    RankOrTimerBadge(item = item)
                } else if (item.japaneseTitle.isNotBlank()) {
                    Text(
                        text = item.japaneseTitle,
                        color = palette.textMuted,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
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
        shape = AhShape10,
        containerColor = palette.surface,
        focusedContainerColor = palette.surfaceElevated,
        unfocusedBorderColor = Color(0x18FFFFFF),
        focusedBorderColor = Color.White,
        focusedBorderWidth = 2.5.dp,
        focusScale = 1.05f,
        onFocusStateChanged = { focused ->
            if (focused) onItemFocused?.invoke(item.id, item.coverUrl)
        }
    ) { isFocused ->
        Column {
            AhCoverImage(
                url = item.coverUrl,
                contentDescription = item.title,
                targetSize = ImageTargetSize.STANDARD_CARD,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
            )
            Text(
                text = item.title,
                color = if (isFocused) Color.White else palette.textSecondary,
                fontSize = 13.sp,
                fontWeight = if (isFocused) FontWeight.Bold else FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .height(34.dp)
            )
        }
    }
}

@Composable
private fun RankOrTimerBadge(item: AnimeCard) {
    val palette = LocalAhPalette.current
    if (item.rankDirection != null) {
        val badgeColor = when (item.rankDirection) {
            RankDirection.UP -> palette.positiveGreen
            RankDirection.SAME -> palette.textMuted
            RankDirection.DOWN -> palette.crimson
        }
        val arrow = when (item.rankDirection) {
            RankDirection.UP -> "▲"
            RankDirection.DOWN -> "▼"
            RankDirection.SAME -> "•"
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .background(badgeColor.copy(alpha = 0.2f), RANK_BADGE_SHAPE),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = arrow,
                    color = badgeColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = item.rankDelta.orEmpty(),
                color = palette.textSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    } else if (item.timerOrStatus.isNotBlank()) {
        Text(
            text = item.timerOrStatus,
            color = palette.positiveGreen,
            fontSize = 12.sp,
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
            .padding(top = 20.dp, bottom = 28.dp)
            .clip(AhShape10)
            .background(palette.surface)
            .border(1.dp, palette.borderSubtle, AhShape10)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "A",
                color = palette.crimson,
                fontSize = 20.sp,
                fontWeight = FontWeight.Black
            )
            Text(
                text = "AnimeHeaven TV • Ad-Free 1080p Cinema",
                color = palette.textMuted,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        }
        AhFocusableBox(
            onClick = onOpenSettings,
            shape = AhShape20,
            containerColor = palette.surfaceElevated,
            focusedContainerColor = Color.White,
            unfocusedBorderColor = Color(0x33FFFFFF),
            focusedBorderColor = Color.White,
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
                    tint = if (isFocused) Color.Black else Color.White,
                    modifier = Modifier.size(17.dp)
                )
                Text(
                    text = "Settings & Theme",
                    color = if (isFocused) Color.Black else Color.White,
                    fontSize = 13.sp,
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
                .clip(AhShape12)
                .background(palette.surface)
                .border(1.dp, palette.borderSubtle, AhShape12)
                .padding(28.dp),
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
            Spacer(modifier = Modifier.height(18.dp))
            AhFocusableBox(
                onClick = onRetry,
                shape = AhShape3,
                containerColor = Color.White,
                focusedContainerColor = Color.White,
                focusedBorderColor = palette.crimson,
                focusedBorderWidth = 3.dp
            ) { _ ->
                Row(
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = null,
                        tint = Color.Black
                    )
                    Text(
                        text = "Try Again",
                        color = Color.Black,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.ExtraBold
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
