package me.animeheaven.tv.data.cache

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRendererCapabilitiesList
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.preload.PreloadException
import androidx.media3.exoplayer.source.preload.PreloadMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import kotlin.math.abs

/**
 * Lightweight Media3 1.5.1 [PreloadMediaSource] pool that pre-demuxes `moov` metadata, selects
 * tracks, and fills ~6 seconds of encoded samples from `SimpleCache` on a shared playback looper
 * WITHOUT allocating any extra hardware `MediaCodec` video decoders (safe for low-RAM TV sticks).
 */
@OptIn(UnstableApi::class)
class PlayerWarmupPool(
    context: Context,
    private val mediaSourceFactory: MediaSource.Factory
) {
    private val appContext = context.applicationContext
    private val playbackThread = HandlerThread("ah-exo-playback", Process.THREAD_PRIORITY_AUDIO).apply {
        start()
    }
    val playbackLooper: Looper = playbackThread.looper
    private val preloadHandler = Handler(playbackLooper)

    private val lock = Any()

    private data class WarmedEntry(
        val episodeKey: String,
        val cacheKey: String,
        val resumePositionMs: Long,
        val source: PreloadMediaSource
    )

    private val entries = LinkedHashMap<String, WarmedEntry>(4, 0.75f, true)

    private val preloadFactory: PreloadMediaSource.Factory by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val bandwidthMeter = DefaultBandwidthMeter.getSingletonInstance(appContext)
        val trackSelector = DefaultTrackSelector(appContext).apply {
            init({}, bandwidthMeter)
        }
        val renderersFactory = DefaultRenderersFactory(appContext)
            .setEnableDecoderFallback(true)
            .forceEnableMediaCodecAsynchronousQueueing()
        val capabilities = DefaultRendererCapabilitiesList.Factory(renderersFactory)
            .createRendererCapabilitiesList()
            .rendererCapabilities
        val allocator = DefaultAllocator(true, 64 * 1024)
        val control = object : PreloadMediaSource.PreloadControl {
            override fun onSourcePrepared(mediaSource: PreloadMediaSource): Boolean = true

            override fun onTracksSelected(mediaSource: PreloadMediaSource): Boolean = true

            override fun onContinueLoadingRequested(
                mediaSource: PreloadMediaSource,
                bufferedDurationUs: Long
            ): Boolean = bufferedDurationUs < TARGET_PRELOAD_DURATION_US

            override fun onUsedByPlayer(mediaSource: PreloadMediaSource) = Unit

            override fun onPreloadError(error: PreloadException, mediaSource: PreloadMediaSource) {
                releaseSourceOnLooper(mediaSource)
            }
        }
        PreloadMediaSource.Factory(
            mediaSourceFactory,
            control,
            trackSelector,
            bandwidthMeter,
            capabilities,
            allocator,
            playbackLooper
        )
    }

    fun preloadEpisode(
        episodeKey: String,
        url: String,
        cacheKey: String,
        resumePositionMs: Long = 0L
    ) {
        if (episodeKey.isBlank() || url.isBlank() || cacheKey.isBlank()) return
        synchronized(lock) {
            val existing = entries[episodeKey]
            if (existing != null &&
                existing.cacheKey == cacheKey &&
                abs(existing.resumePositionMs - resumePositionMs) <= RESUME_TOLERANCE_MS
            ) {
                return
            }
        }
        preloadHandler.post {
            runCatching {
                val mediaItem = MediaItem.Builder()
                    .setUri(url)
                    .setMediaId(episodeKey)
                    .setCustomCacheKey(cacheKey)
                    .build()
                val source = preloadFactory.createMediaSource(mediaItem)
                val evicted = mutableListOf<PreloadMediaSource>()
                synchronized(lock) {
                    entries.remove(episodeKey)?.let { evicted.add(it.source) }
                    while (entries.size >= MAX_WARMED_SOURCES) {
                        val oldestKey = entries.keys.firstOrNull() ?: break
                        entries.remove(oldestKey)?.let { evicted.add(it.source) }
                    }
                    entries[episodeKey] = WarmedEntry(episodeKey, cacheKey, resumePositionMs.coerceAtLeast(0L), source)
                }
                evicted.forEach { runCatching { it.releasePreloadMediaSource() } }
                source.preload(resumePositionMs.coerceAtLeast(0L) * 1000L)
            }
        }
    }

    fun takePreparedSource(
        episodeKey: String,
        cacheKey: String,
        resumePositionMs: Long
    ): PreloadMediaSource? {
        if (episodeKey.isBlank()) return null
        val entry = synchronized(lock) { entries.remove(episodeKey) } ?: return null
        val matches = entry.cacheKey == cacheKey &&
            abs(entry.resumePositionMs - resumePositionMs) <= RESUME_TOLERANCE_MS
        if (matches) {
            return entry.source
        }
        releaseSourceOnLooper(entry.source)
        return null
    }

    fun releaseEpisode(episodeKey: String) {
        val entry = synchronized(lock) { entries.remove(episodeKey) } ?: return
        releaseSourceOnLooper(entry.source)
    }

    fun releaseAll() {
        val toRelease = synchronized(lock) {
            entries.values.map { it.source }.also { entries.clear() }
        }
        if (toRelease.isNotEmpty()) {
            preloadHandler.post {
                toRelease.forEach { runCatching { it.releasePreloadMediaSource() } }
            }
        }
    }

    private fun releaseSourceOnLooper(source: PreloadMediaSource) {
        preloadHandler.post {
            runCatching { source.releasePreloadMediaSource() }
        }
    }

    companion object {
        private const val TARGET_PRELOAD_DURATION_US = 6_000_000L
        private const val RESUME_TOLERANCE_MS = 5_000L
        private const val MAX_WARMED_SOURCES = 2
    }
}
