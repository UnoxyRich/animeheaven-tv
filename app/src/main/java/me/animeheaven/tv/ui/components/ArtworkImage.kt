package me.animeheaven.tv.ui.components

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.allowRgb565
import coil3.size.Precision
import coil3.size.Scale
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import me.animeheaven.tv.data.cache.ImageTargetSize
import me.animeheaven.tv.data.model.AnimeArtwork
import me.animeheaven.tv.data.model.ArtworkLookupRequest
import me.animeheaven.tv.data.model.ArtworkPriority
import me.animeheaven.tv.data.model.ArtworkSlot
import me.animeheaven.tv.data.model.ArtworkSmartFit
import me.animeheaven.tv.data.network.AnimeArtworkResolver
import me.animeheaven.tv.ui.theme.LocalAhPalette

val LocalArtworkResolver = staticCompositionLocalOf<AnimeArtworkResolver?> { null }

@Composable
fun AhCoverImage(
    url: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    targetSize: ImageTargetSize = ImageTargetSize.STANDARD_CARD,
    artwork: ArtworkLookupRequest? = null,
    slot: ArtworkSlot = ArtworkSlot.LANDSCAPE_16_9,
    episodeNumber: Int? = null,
    nativeLandscapeFirst: Boolean = false
) {
    val resolver = LocalArtworkResolver.current
    var resolved by remember(artwork?.animeId, artwork?.title, resolver) { mutableStateOf(artwork?.let { resolver?.memory(it) }) }
    LaunchedEffect(artwork, resolver, slot) {
        if (resolver == null || artwork == null) return@LaunchedEffect
        coroutineScope {
            launch {
                resolver.updates.collect { key ->
                    if (key.isEmpty() || key == artwork.animeKey || key == artwork.titleKey) {
                        resolved = resolver.cached(artwork)
                    }
                }
            }
            resolved = resolver.cached(artwork)
            if (artwork.priority != ArtworkPriority.CACHED_ONLY) resolved = resolver.resolve(artwork, slot)
        }
    }
    val candidates = remember(resolved, slot, episodeNumber, url, nativeLandscapeFirst) {
        (resolved ?: AnimeArtwork()).urls(slot, episodeNumber, url, nativeLandscapeFirst)
    }
    var candidateIndex by remember(candidates) { mutableIntStateOf(0) }
    val candidate = candidates.getOrNull(candidateIndex)
    val context = LocalContext.current
    val request = remember(candidate, targetSize, context) {
        ImageRequest.Builder(context)
            .data(candidate)
            .size(targetSize.widthPx, targetSize.heightPx)
            .scale(Scale.FIT)
            .precision(Precision.INEXACT)
            .allowRgb565(true)
            .build()
    }
    val painter = rememberAsyncImagePainter(request, filterQuality = FilterQuality.Medium)
    val state by painter.state.collectAsStateWithLifecycle()
    var displayed by remember(url, artwork?.animeId, targetSize) { mutableStateOf<AsyncImagePainter.State.Success?>(null) }
    LaunchedEffect(state, candidate, candidates) {
        when (val value = state) {
            is AsyncImagePainter.State.Success -> if (value.result.request.data == candidate) {
                displayed = value
                AhImageLoadTracker.loadedCount.incrementAndGet()
            }
            is AsyncImagePainter.State.Error -> if (value.result.request.data == candidate && candidateIndex < candidates.lastIndex) {
                candidateIndex++
            }
            else -> Unit
        }
    }
    val palette = LocalAhPalette.current
    var frameRatio by remember { mutableStateOf(0f) }
    Box(modifier.background(palette.surfaceElevated).clipToBounds().onSizeChanged { size ->
        if (size.width > 0 && size.height > 0) frameRatio = size.width.toFloat() / size.height
    }) {
        val image = displayed
        if (image != null) {
            val source = image.result.image
            val ambient = ArtworkSmartFit.needsAmbient(source.width.toFloat() / source.height, frameRatio)
            if (ambient) {
                if (Build.VERSION.SDK_INT >= 31) {
                    Image(image.painter, null, Modifier.fillMaxSize().blur(18.dp), contentScale = ContentScale.Crop)
                }
                val accent = resolved?.accentColorHex?.removePrefix("#")?.takeIf { it.length == 6 }
                    ?.toLongOrNull(16)?.let { Color(0xFF000000L or it) } ?: palette.surfaceElevated
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.56f), Color.Black.copy(alpha = 0.72f)))))
            }
            Image(
                painter = image.painter,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = if (ambient) ContentScale.Fit else ContentScale.Crop,
                alignment = if (slot == ArtworkSlot.PORTRAIT_2_3 && !ambient) BiasAlignment(0f, -0.2f) else BiasAlignment(0f, 0f)
            )
        }
    }
}
