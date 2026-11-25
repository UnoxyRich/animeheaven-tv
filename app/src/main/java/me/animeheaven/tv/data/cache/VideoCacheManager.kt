package me.animeheaven.tv.data.cache

import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.text.SubtitleParser
import java.io.File
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.animeheaven.tv.data.network.AnimeHeavenClient
import me.animeheaven.tv.data.network.awaitResponse
import me.animeheaven.tv.data.runCatchingCancellable
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request

data class VideoProbeMeta(
    val url: String,
    val contentLength: Long,
    val supportsRanges: Boolean,
    val latencyMs: Long
)

/**
 * Tracks host reliability, RTT latency, EWMA streaming throughput (bytes/sec), and stub-video
 * detection so dead, throttled, or tiny stub (< 1 MB) mirrors are automatically demoted below
 * healthy fast full-episode mirrors. Persists learned CDN rankings across cold restarts.
 */
class MirrorHealthTracker(
    private val prefs: SharedPreferences? = null,
    private val clockMs: () -> Long = { System.currentTimeMillis() }
) {
    private data class HostStats(
        val lastLatencyMs: Long = Long.MAX_VALUE,
        val failureCount: Int = 0,
        val lastFailureAtMs: Long = 0L,
        val isStubVideo: Boolean = false,
        val avgBytesPerSec: Long = 0L
    ) {
        fun encode(): String = "$lastLatencyMs|$failureCount|$lastFailureAtMs|$isStubVideo|$avgBytesPerSec"

        companion object {
            fun decode(raw: String): HostStats? {
                val parts = raw.split('|')
                if (parts.size != 4 && parts.size != 5) return null
                return HostStats(
                    lastLatencyMs = parts[0].toLongOrNull() ?: return null,
                    failureCount = parts[1].toIntOrNull() ?: return null,
                    lastFailureAtMs = parts[2].toLongOrNull() ?: return null,
                    isStubVideo = parts[3].toBooleanStrictOrNull() ?: false,
                    avgBytesPerSec = parts.getOrNull(4)?.toLongOrNull() ?: 0L
                )
            }
        }
    }

    private val statsByHost = ConcurrentHashMap<String, HostStats>().apply {
        prefs?.all?.forEach { (host, value) ->
            val raw = value as? String ?: return@forEach
            HostStats.decode(raw)?.let { put(host, it) }
        }
    }

    private val lastPersistedAtMs = ConcurrentHashMap<String, Long>()

    private fun persistHost(host: String, stats: HostStats, forcePersist: Boolean) {
        if (prefs == null) return
        val now = clockMs()
        val prevPersist = lastPersistedAtMs[host] ?: 0L
        if (forcePersist || (now - prevPersist) >= PERSIST_THROTTLE_MS) {
            lastPersistedAtMs[host] = now
            prefs.edit().putString(host, stats.encode()).apply()
        }
    }

    private fun updateHost(host: String, forcePersist: Boolean = true, update: (HostStats?) -> HostStats) {
        statsByHost.compute(host) { _, old ->
            update(old).also { persistHost(host, it, forcePersist) }
        }
    }

    fun recordSuccess(url: String, latencyMs: Long = 100L) {
        val host = extractHost(url) ?: return
        updateHost(host) { prev ->
            HostStats(
                lastLatencyMs = latencyMs.coerceAtLeast(1L),
                failureCount = 0,
                lastFailureAtMs = 0L,
                isStubVideo = false,
                avgBytesPerSec = prev?.avgBytesPerSec ?: 0L
            )
        }
    }

    fun recordProbeSuccess(url: String, latencyMs: Long, contentLength: Long) {
        val host = extractHost(url) ?: return
        val stub = contentLength in 0L until MIN_VALID_VIDEO_BYTES
        updateHost(host) { prev ->
            HostStats(
                lastLatencyMs = latencyMs.coerceAtLeast(1L),
                failureCount = 0,
                lastFailureAtMs = 0L,
                isStubVideo = stub,
                avgBytesPerSec = if (stub) 0L else (prev?.avgBytesPerSec ?: 0L)
            )
        }
    }

    fun recordChunkThroughput(url: String, bytes: Long, elapsedMs: Long) {
        if (bytes <= 0L || elapsedMs <= 0L) return
        val host = extractHost(url) ?: return
        val sampleBps = ((bytes * 1000L) / elapsedMs).coerceAtLeast(1L)
        updateHost(host, forcePersist = false) { prev ->
            val prevBps = prev?.avgBytesPerSec ?: 0L
            val blendedBps = if (prevBps > 0L) ((prevBps * 65L) + (sampleBps * 35L)) / 100L else sampleBps
            HostStats(
                lastLatencyMs = prev?.lastLatencyMs?.takeIf { it < Long.MAX_VALUE } ?: elapsedMs,
                failureCount = 0,
                lastFailureAtMs = 0L,
                isStubVideo = false,
                avgBytesPerSec = blendedBps
            )
        }
    }

    fun recordFailure(url: String) {
        val host = extractHost(url) ?: return
        updateHost(host) { old ->
            val prev = old ?: HostStats()
            prev.copy(
                failureCount = prev.failureCount.coerceAtMost(Int.MAX_VALUE - 1) + 1,
                lastFailureAtMs = clockMs(),
                avgBytesPerSec = 0L
            )
        }
    }

    fun hasFreshStats(url: String): Boolean {
        val host = extractHost(url) ?: return false
        val stats = statsByHost[host] ?: return false
        if (stats.failureCount == 0 && stats.lastLatencyMs < Long.MAX_VALUE) return true
        return stats.failureCount > 0 && (clockMs() - stats.lastFailureAtMs) < FAILURE_COOLDOWN_MS
    }

    fun topHealthyHosts(limit: Int = 3): List<String> {
        val now = clockMs()
        return statsByHost.entries
            .asSequence()
            .filter { (_, s) ->
                !s.isStubVideo &&
                    (s.failureCount == 0 || (now - s.lastFailureAtMs) >= FAILURE_COOLDOWN_MS)
            }
            .sortedBy { (_, s) -> scalarPerformanceScore(s) }
            .take(limit)
            .map { it.key }
            .toList()
    }

    fun rankMirrors(urls: List<String>): List<String> {
        if (urls.size <= 1) return urls
        val now = clockMs()
        val entries = urls.mapIndexed { idx, url ->
            val stats = extractHost(url)?.let { statsByHost[it] }
            val recentlyFailed = stats != null &&
                stats.failureCount > 0 &&
                (now - stats.lastFailureAtMs) < FAILURE_COOLDOWN_MS
            RankedMirrorEntry(
                url = url,
                index = idx,
                failedTier = if (recentlyFailed) 1 else 0,
                stubTier = if (stats != null && stats.isStubVideo) 1 else 0,
                perfScore = scalarPerformanceScore(stats)
            )
        }
        return entries
            .sortedWith(
                compareBy<RankedMirrorEntry>(
                    { it.failedTier },
                    { it.stubTier },
                    { it.perfScore },
                    { it.index }
                )
            )
            .map { it.url }
    }

    private data class RankedMirrorEntry(
        val url: String,
        val index: Int,
        val failedTier: Int,
        val stubTier: Int,
        val perfScore: Long
    )

    private fun scalarPerformanceScore(s: HostStats?): Long {
        if (s == null || s.failureCount > 0) return Long.MAX_VALUE / 2
        val bps = s.avgBytesPerSec
        return when {
            bps >= FAST_THROUGHPUT_BPS -> -bps
            bps in THROTTLED_THROUGHPUT_BPS until FAST_THROUGHPUT_BPS -> 1_000_000L - bps
            bps in 1L until THROTTLED_THROUGHPUT_BPS -> 10_000_000L + (10_000_000_000L / bps)
            else -> s.lastLatencyMs.coerceAtMost(999_999L)
        }
    }

    private fun extractHost(url: String): String? =
        runCatching { URI(url).host?.lowercase() }.getOrNull()

    companion object {
        const val FAILURE_COOLDOWN_MS = 10 * 60_000L
        const val MIN_VALID_VIDEO_BYTES = 1_000_000L
        const val FAST_THROUGHPUT_BPS = 300_000L
        const val THROTTLED_THROUGHPUT_BPS = 180_000L
        private const val PERSIST_THROTTLE_MS = 2_500L
    }
}

@OptIn(UnstableApi::class)
class VideoCacheManager(
    context: Context,
    baseOkHttpClient: OkHttpClient,
    val mirrorHealthTracker: MirrorHealthTracker = MirrorHealthTracker(
        prefs = context.applicationContext.getSharedPreferences("ah_mirror_health", Context.MODE_PRIVATE)
    )
) {
    private val appContext = context.applicationContext
    private val isLowRamDevice: Boolean = run {
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        am?.isLowRamDevice == true || (am?.memoryClass ?: 256) <= 192
    }
    private val cacheDir = File(appContext.cacheDir, "video_cache").apply { mkdirs() }
    private val boosterLock = Any()
    private var boosterStartJob: Job? = null
    private val databaseProvider = StandaloneDatabaseProvider(appContext)
    val cacheEvictor = PinnedPriorityCacheEvictor(
        maxBytes = calculateVideoCacheMaxBytes(appContext.cacheDir.usableSpace)
    )
    val simpleCache: SimpleCache = SimpleCache(
        cacheDir,
        cacheEvictor,
        databaseProvider
    )

    val videoHttpClient: OkHttpClient = baseOkHttpClient.newBuilder()
        .cache(null)
        .dispatcher(
            Dispatcher().apply {
                maxRequests = 32
                maxRequestsPerHost = 10
            }
        )
        .connectionPool(ConnectionPool(20, 5, TimeUnit.MINUTES))
        .retryOnConnectionFailure(true)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val probeClient: OkHttpClient = videoHttpClient.newBuilder()
        .connectTimeout(2000, TimeUnit.MILLISECONDS)
        .readTimeout(2000, TimeUnit.MILLISECONDS)
        .build()

    private val upstreamFactory = OkHttpDataSource.Factory(videoHttpClient)
        .setUserAgent(AnimeHeavenClient.USER_AGENT)
        .setDefaultRequestProperties(mapOf("Referer" to "${AnimeHeavenClient.BASE_URL}/"))

    private val cacheSinkFactory = CacheDataSink.Factory()
        .setCache(simpleCache)
        .setFragmentSize(CACHE_SINK_FRAGMENT_BYTES)

    val cacheDataSourceFactory: CacheDataSource.Factory = CacheDataSource.Factory()
        .setCache(simpleCache)
        .setUpstreamDataSourceFactory(upstreamFactory)
        .setCacheWriteDataSinkFactory(cacheSinkFactory)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    private val leanExtractorsFactory: ExtractorsFactory = ExtractorsFactory {
        arrayOf(
            Mp4Extractor(SubtitleParser.Factory.UNSUPPORTED),
            FragmentedMp4Extractor(SubtitleParser.Factory.UNSUPPORTED),
            MatroskaExtractor(SubtitleParser.Factory.UNSUPPORTED)
        )
    }

    fun buildProgressiveMediaSourceFactory(): MediaSource.Factory =
        ProgressiveMediaSource.Factory(cacheDataSourceFactory, leanExtractorsFactory)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activePreloadJobs = ConcurrentHashMap<String, Job>()
    private val activeWriters = ConcurrentHashMap<String, CacheWriter>()
    private val probeMetaByUrl = LruCacheMap<String, VideoProbeMeta>(MAX_PROBE_CACHE)
    private val videoIndexByEpisode = LruCacheMap<String, EpisodeVideoIndex>(MAX_VIDEO_INDEX_CACHE)
    private val diskLengthByEpisode = ConcurrentHashMap<String, Long>()

    @Volatile
    private var videoIndexPersistence: (suspend (EpisodeVideoIndex) -> Unit)? = null

    val warmupPool: PlayerWarmupPool by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        PlayerWarmupPool(appContext, buildProgressiveMediaSourceFactory())
    }

    val chunkBooster = StreamingChunkBooster(
        simpleCache = simpleCache,
        cacheSinkFactory = cacheSinkFactory,
        videoHttpClient = videoHttpClient,
        mirrorHealthTracker = mirrorHealthTracker,
        cacheEvictor = cacheEvictor,
        lowRamDevice = isLowRamDevice,
        onVideoIndexReady = { index ->
            recordVideoIndex(index)
            videoIndexPersistence?.invoke(index)
        }
    )

    init {
        scanExistingDiskKeys()
    }

    fun setVideoIndexPersistence(callback: suspend (EpisodeVideoIndex) -> Unit) {
        videoIndexPersistence = callback
    }

    fun hydrateVideoIndices(indices: List<EpisodeVideoIndex>) {
        for (idx in indices) {
            if (idx.episodeKey.isBlank() || idx.contentLength < MirrorHealthTracker.MIN_VALID_VIDEO_BYTES) continue
            videoIndexByEpisode[idx.episodeKey] = idx
            diskLengthByEpisode[idx.episodeKey] = idx.contentLength
            val key = buildEpisodeCacheKey(idx.episodeKey, idx.contentLength, "")
            if (idx.moovOffset >= 0L && idx.moovSize > 0L) {
                cacheEvictor.markHotWindow(
                    key,
                    StreamingChunkBooster.alignChunkStart(idx.moovOffset),
                    minOf(idx.contentLength, idx.moovOffset + idx.moovSize)
                )
            }
            cacheEvictor.markHotWindow(key, 0L, minOf(idx.contentLength, CHUNK_SIZE_BYTES))
        }
    }

    fun scanExistingDiskKeys() {
        runCatching {
            for (key in simpleCache.keys) {
                if (!key.startsWith("ep:")) continue
                val lastColon = key.lastIndexOf(':')
                if (lastColon <= 3) continue
                val epKey = key.substring(3, lastColon)
                val len = key.substring(lastColon + 1).toLongOrNull() ?: continue
                if (epKey.isNotBlank() && len >= MirrorHealthTracker.MIN_VALID_VIDEO_BYTES) {
                    diskLengthByEpisode[epKey] = len
                }
            }
        }
    }

    private fun recordVideoIndex(index: EpisodeVideoIndex) {
        videoIndexByEpisode[index.episodeKey] = index
        if (index.contentLength >= MirrorHealthTracker.MIN_VALID_VIDEO_BYTES) {
            diskLengthByEpisode[index.episodeKey] = index.contentLength
        }
    }

    fun getVideoIndex(episodeKey: String?): EpisodeVideoIndex? =
        episodeKey?.takeIf { it.isNotBlank() }?.let { videoIndexByEpisode[it] }

    fun resolveKnownContentLength(episodeKey: String?, url: String? = null): Long {
        if (!url.isNullOrBlank()) {
            val probeLen = probeMetaByUrl[url]?.contentLength ?: -1L
            if (probeLen >= MirrorHealthTracker.MIN_VALID_VIDEO_BYTES) return probeLen
        }
        if (!episodeKey.isNullOrBlank()) {
            videoIndexByEpisode[episodeKey]?.contentLength?.takeIf {
                it >= MirrorHealthTracker.MIN_VALID_VIDEO_BYTES
            }?.let { return it }
            diskLengthByEpisode[episodeKey]?.takeIf {
                it >= MirrorHealthTracker.MIN_VALID_VIDEO_BYTES
            }?.let { return it }
        }
        return -1L
    }

    fun buildFastLoadControl(): LoadControl {
        val minBufMs = if (isLowRamDevice) 20_000 else 30_000
        val maxBufMs = if (isLowRamDevice) 40_000 else 60_000
        val backBufMs = if (isLowRamDevice) 10_000 else 15_000
        val targetBytes = if (isLowRamDevice) 24 * 1024 * 1024 else 48 * 1024 * 1024

        return DefaultLoadControl.Builder()
            .setAllocator(DefaultAllocator(true, 64 * 1024))
            .setBufferDurationsMs(
                /* minBufferMs = */ minBufMs,
                /* maxBufferMs = */ maxBufMs,
                /* bufferForPlaybackMs = */ 200,
                /* bufferForPlaybackAfterRebufferMs = */ 500
            )
            .setTargetBufferBytes(targetBytes)
            .setBackBuffer(backBufMs, true)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
    }

    fun resolveCacheKey(episodeKey: String?, url: String): String {
        val contentLength = resolveKnownContentLength(episodeKey, url)
        return buildEpisodeCacheKey(episodeKey, contentLength, url)
    }

    /**
     * Checks whether [cacheKey] already holds enough local bytes on disk (header + tail moov if
     * non-faststart + at least [minBytes] at [resumePositionMs]) to begin playback immediately at
     * 0 ms without blocking on network probes.
     */
    fun hasPlayableHotWindow(
        episodeKey: String?,
        cacheKey: String,
        resumePositionMs: Long,
        estimatedDurationMs: Long = 0L,
        minBytes: Long = MIN_PLAYABLE_HOT_BYTES
    ): Boolean {
        val totalLen = resolveKnownContentLength(episodeKey)
        if (totalLen < MirrorHealthTracker.MIN_VALID_VIDEO_BYTES) return false
        val index = getVideoIndex(episodeKey)
        val headCheckLen = minOf(128L * 1024L, totalLen)
        val hasHead = runCatching {
            simpleCache.getCachedBytes(cacheKey, 0L, headCheckLen) >= headCheckLen
        }.getOrDefault(false)
        if (!hasHead) return false

        if (index != null && index.isMoovAtTail && index.moovSize > 0L) {
            val moovLen = minOf(index.moovSize, totalLen - index.moovOffset)
            val hasMoov = runCatching {
                simpleCache.getCachedBytes(cacheKey, index.moovOffset, moovLen) >= moovLen
            }.getOrDefault(false)
            if (!hasMoov) return false
        }

        val durMs = when {
            index != null && index.durationMs > 0L -> index.durationMs
            else -> estimatedDurationMs
        }
        val targetStart = if (resumePositionMs > 10_000L && durMs > 0L) {
            StreamingChunkBooster.alignChunkStart(
                StreamingChunkBooster.resolveByteOffset(index, resumePositionMs, durMs, totalLen)
            )
        } else {
            0L
        }
        val windowLen = minOf(minBytes, (totalLen - targetStart).coerceAtLeast(0L))
        if (windowLen <= 0L) return false
        val cachedAtTarget = runCatching {
            simpleCache.getCachedBytes(cacheKey, targetStart, windowLen)
        }.getOrDefault(0L)
        return cachedAtTarget >= (windowLen * 3L) / 4L
    }

    fun getCachedBytesAtWindow(cacheKey: String, startByte: Long, windowBytes: Long): Long =
        runCatching {
            simpleCache.getCachedBytes(cacheKey, startByte.coerceAtLeast(0L), windowBytes.coerceAtLeast(0L))
        }.getOrDefault(0L)

    suspend fun probeMirrorsSpeed(urls: List<String>) {
        if (urls.isEmpty()) return
        val needsProbe = urls.filter { url ->
            probeMetaByUrl[url] == null || !mirrorHealthTracker.hasFreshStats(url)
        }.distinct()
        if (needsProbe.isEmpty()) return

        withContext(Dispatchers.IO) {
            withTimeoutOrNull(1800L) {
                coroutineScope {
                    needsProbe.map { url ->
                        async { probeSingleMirror(url) }
                    }.awaitAll()
                }
            }
        }
    }

    suspend fun probeMirrorsForPlayback(urls: List<String>, maxWaitMs: Long = 900L) {
        if (urls.isEmpty()) return
        val alreadyHaveValid = urls.any { url ->
            val meta = probeMetaByUrl[url]
            meta != null && meta.contentLength >= MirrorHealthTracker.MIN_VALID_VIDEO_BYTES
        }
        val needsProbe = urls.filter { url ->
            probeMetaByUrl[url] == null || !mirrorHealthTracker.hasFreshStats(url)
        }.distinct()
        if (needsProbe.isEmpty()) return

        if (alreadyHaveValid) {
            scope.launch {
                needsProbe.forEach { url -> launch { probeSingleMirror(url) } }
            }
            return
        }

        val firstValidReady = CompletableDeferred<Unit>()
        val remaining = AtomicInteger(needsProbe.size)
        for (url in needsProbe) {
            scope.launch {
                val meta = probeSingleMirror(url)
                if (meta != null && meta.contentLength >= MirrorHealthTracker.MIN_VALID_VIDEO_BYTES) {
                    firstValidReady.complete(Unit)
                }
                if (remaining.decrementAndGet() == 0) {
                    firstValidReady.complete(Unit)
                }
            }
        }
        withTimeoutOrNull(maxWaitMs) {
            firstValidReady.await()
        }
    }

    fun probeMirrorsInBackground(urls: List<String>) {
        val needsProbe = urls.filter { url ->
            probeMetaByUrl[url] == null || !mirrorHealthTracker.hasFreshStats(url)
        }.distinct()
        if (needsProbe.isEmpty()) return
        scope.launch {
            needsProbe.forEach { url -> launch { probeSingleMirror(url) } }
        }
    }

    private suspend fun probeSingleMirror(url: String): VideoProbeMeta? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", AnimeHeavenClient.USER_AGENT)
            .header("Referer", "${AnimeHeavenClient.BASE_URL}/")
            .header("Range", "bytes=0-0")
            .build()
        val startNs = System.nanoTime()
        return try {
            probeClient.awaitResponse(req).use { resp ->
                if (resp.isSuccessful || resp.code == 206) {
                    val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
                    val totalLen = parseTotalLengthFromHeaders(
                        contentRange = resp.header("Content-Range"),
                        contentLength = resp.header("Content-Length"),
                        statusCode = resp.code
                    )
                    val supportsRanges = resp.code == 206 ||
                        resp.header("Accept-Ranges")?.contains("bytes", ignoreCase = true) == true
                    val meta = VideoProbeMeta(
                        url = url,
                        contentLength = totalLen,
                        supportsRanges = supportsRanges,
                        latencyMs = elapsedMs
                    )
                    probeMetaByUrl[url] = meta
                    mirrorHealthTracker.recordProbeSuccess(url, elapsedMs, totalLen)
                    meta
                } else {
                    mirrorHealthTracker.recordFailure(url)
                    null
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            mirrorHealthTracker.recordFailure(url)
            null
        }
    }

    fun getProbeMeta(url: String): VideoProbeMeta? = probeMetaByUrl[url]

    fun getVerifiedRangeMirrors(primaryUrl: String, candidates: List<String>, knownLength: Long = -1L): List<String> {
        val primaryMeta = probeMetaByUrl[primaryUrl]
        val expectedLen = when {
            primaryMeta != null && primaryMeta.supportsRanges && primaryMeta.contentLength >= MirrorHealthTracker.MIN_VALID_VIDEO_BYTES ->
                primaryMeta.contentLength
            knownLength >= MirrorHealthTracker.MIN_VALID_VIDEO_BYTES -> knownLength
            else -> return if (primaryMeta?.supportsRanges == true) listOf(primaryUrl) else emptyList()
        }
        val matching = candidates
            .distinct()
            .filter { url ->
                val meta = probeMetaByUrl[url]
                if (meta != null) {
                    meta.supportsRanges && meta.contentLength == expectedLen
                } else {
                    knownLength >= MirrorHealthTracker.MIN_VALID_VIDEO_BYTES
                }
            }
            .ifEmpty { listOf(primaryUrl) }
        return mirrorHealthTracker.rankMirrors(matching)
    }

    fun preloadVideoHead(
        url: String,
        bytesToCache: Long = DEFAULT_PRELOAD_BYTES,
        allSources: List<String> = listOf(url),
        resumePositionMs: Long = 0L,
        estimatedDurationMs: Long = 0L,
        opEndMs: Long = 0L,
        episodeKey: String? = null
    ): Job? {
        if (url.isBlank()) return null
        val jobKey = episodeKey?.takeIf { it.isNotBlank() } ?: url
        activePreloadJobs[jobKey]?.takeIf { it.isActive }?.let { return it }

        val job = scope.launch {
            try {
                executePreloadVideoHead(
                    url = url,
                    bytesToCache = bytesToCache,
                    allSources = allSources,
                    resumePositionMs = resumePositionMs,
                    estimatedDurationMs = estimatedDurationMs,
                    opEndMs = opEndMs,
                    episodeKey = episodeKey,
                    jobKey = jobKey
                )
            } finally {
                activePreloadJobs.remove(jobKey)
            }
        }
        activePreloadJobs[jobKey] = job
        return job
    }

    /**
     * Suspends until the requested preload window has been committed to [simpleCache],
     * returning the resolved `cacheKey` if playable bytes are now on disk.
     */
    suspend fun preloadVideoHeadAndAwait(
        url: String,
        bytesToCache: Long = DEFAULT_PRELOAD_BYTES,
        allSources: List<String> = listOf(url),
        resumePositionMs: Long = 0L,
        estimatedDurationMs: Long = 0L,
        opEndMs: Long = 0L,
        episodeKey: String? = null
    ): String? {
        if (url.isBlank()) return null
        val jobKey = episodeKey?.takeIf { it.isNotBlank() } ?: url
        activePreloadJobs[jobKey]?.takeIf { it.isActive }?.join()
        val job = preloadVideoHead(
            url = url,
            bytesToCache = bytesToCache,
            allSources = allSources,
            resumePositionMs = resumePositionMs,
            estimatedDurationMs = estimatedDurationMs,
            opEndMs = opEndMs,
            episodeKey = episodeKey
        )
        job?.join()
        val cacheKey = resolveCacheKey(episodeKey, url)
        return if (hasPlayableHotWindow(episodeKey, cacheKey, resumePositionMs, estimatedDurationMs)) {
            cacheKey
        } else {
            null
        }
    }

    private suspend fun executePreloadVideoHead(
        url: String,
        bytesToCache: Long,
        allSources: List<String>,
        resumePositionMs: Long,
        estimatedDurationMs: Long,
        opEndMs: Long,
        episodeKey: String?,
        jobKey: String
    ) {
        try {
            if (allSources.size > 1 && probeMetaByUrl[url] == null) {
                probeMirrorsSpeed(allSources)
            } else if (probeMetaByUrl[url] == null) {
                probeMirrorsSpeed(listOf(url))
            }

            val meta = probeMetaByUrl[url]
            val knownLen = meta?.contentLength?.takeIf { it >= MirrorHealthTracker.MIN_VALID_VIDEO_BYTES }
                ?: resolveKnownContentLength(episodeKey, url)
            val verifiedMirrors = getVerifiedRangeMirrors(url, allSources, knownLen)
            val cacheKey = buildEpisodeCacheKey(episodeKey, knownLen, url)
            if (knownLen >= MirrorHealthTracker.MIN_VALID_VIDEO_BYTES && verifiedMirrors.isNotEmpty()) {
                if (!episodeKey.isNullOrBlank()) {
                    diskLengthByEpisode[episodeKey] = knownLen
                }
                applyContentLengthMetadata(cacheKey, knownLen)
                val existingIdx = getVideoIndex(episodeKey)
                val parsed = chunkBooster.preloadInitialWindows(
                    cacheKey = cacheKey,
                    mirrors = verifiedMirrors,
                    totalLength = knownLen,
                    targetBytes = bytesToCache,
                    resumePositionMs = resumePositionMs,
                    estimatedDurationMs = estimatedDurationMs,
                    opEndMs = opEndMs,
                    episodeKey = episodeKey,
                    existingIndex = existingIdx
                )
                if (parsed == null && existingIdx == null && !episodeKey.isNullOrBlank()) {
                    // Still persist contentLength so cold starts resolve cacheKey in 0 ms!
                    val basicIdx = EpisodeVideoIndex(
                        episodeKey = episodeKey,
                        contentLength = knownLen,
                        durationMs = estimatedDurationMs.coerceAtLeast(0L),
                        updatedAtMs = System.currentTimeMillis()
                    )
                    recordVideoIndex(basicIdx)
                    runCatchingCancellable { videoIndexPersistence?.invoke(basicIdx) }
                }
                if (meta != null) {
                    mirrorHealthTracker.recordSuccess(url, meta.latencyMs)
                }
                return
            }

            val alreadyCached = runCatching {
                simpleCache.getCachedBytes(cacheKey, 0L, bytesToCache)
            }.getOrDefault(0L)
            if (alreadyCached >= bytesToCache / 2L) {
                mirrorHealthTracker.recordSuccess(url, latencyMs = 20L)
                return
            }

            val dataSpec = DataSpec.Builder()
                .setUri(Uri.parse(url))
                .setKey(cacheKey)
                .setPosition(0L)
                .setLength(bytesToCache)
                .build()

            val dataSource = cacheDataSourceFactory.createDataSourceForDownloading()
            val writer = CacheWriter(dataSource, dataSpec, ByteArray(DEFAULT_BUFFER_SIZE), null)
            activeWriters[jobKey] = writer
            val startNs = System.nanoTime()
            try {
                writer.cache()
                val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
                mirrorHealthTracker.recordSuccess(url, elapsedMs)
            } finally {
                activeWriters.remove(jobKey)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            mirrorHealthTracker.recordFailure(url)
        }
    }

    fun startPlaybackBooster(
        activeUrl: String,
        allSources: List<String>,
        initialPositionMs: Long,
        initialDurationMs: Long,
        opEndMs: Long,
        episodeKey: String? = null,
        explicitCacheKey: String? = null
    ) = synchronized(boosterLock) {
        stopPlaybackBooster()
        boosterStartJob = scope.launch {
            var knownLen = resolveKnownContentLength(episodeKey, activeUrl)
            if (probeMetaByUrl[activeUrl] == null) {
                if (knownLen >= MirrorHealthTracker.MIN_VALID_VIDEO_BYTES) {
                    probeMirrorsInBackground(allSources.ifEmpty { listOf(activeUrl) })
                } else {
                    probeMirrorsSpeed(allSources.ifEmpty { listOf(activeUrl) })
                    knownLen = resolveKnownContentLength(episodeKey, activeUrl)
                }
            }
            if (knownLen < MirrorHealthTracker.MIN_VALID_VIDEO_BYTES) {
                return@launch
            }
            val mirrors = getVerifiedRangeMirrors(activeUrl, allSources, knownLen)
            if (mirrors.isEmpty()) return@launch
            val cacheKey = explicitCacheKey?.takeIf { it.isNotBlank() }
                ?: buildEpisodeCacheKey(episodeKey, knownLen, activeUrl)
            val index = getVideoIndex(episodeKey)
            synchronized(boosterLock) {
                coroutineContext.ensureActive()
                applyContentLengthMetadata(cacheKey, knownLen)
                chunkBooster.startStreamingBoost(
                    cacheKey = cacheKey,
                    mirrors = mirrors,
                    totalLength = knownLen,
                    initialPositionMs = initialPositionMs,
                    initialDurationMs = initialDurationMs,
                    opEndMs = opEndMs,
                    videoIndex = index
                )
            }
        }
    }

    fun updatePlaybackBoosterPosition(
        positionMs: Long,
        durationMs: Long,
        opEndMs: Long = 0L,
        bufferedMs: Long = positionMs
    ) {
        chunkBooster.updatePlayhead(positionMs, durationMs, opEndMs, bufferedMs)
    }

    fun stopPlaybackBooster() = synchronized(boosterLock) {
        boosterStartJob?.cancel()
        boosterStartJob = null
        chunkBooster.stopStreamingBoost()
    }

    fun markEpisodeCompleted(episodeKey: String?, cacheKey: String?) {
        if (!cacheKey.isNullOrBlank()) {
            cacheEvictor.unpinKey(cacheKey)
        }
        if (!episodeKey.isNullOrBlank()) {
            warmupPool.releaseEpisode(episodeKey)
        }
    }

    fun trimMemory() {
        probeMetaByUrl.trimToSize(32)
        videoIndexByEpisode.trimToSize(48)
        chunkBooster.trimBuffers()
        warmupPool.releaseAll()
    }

    private fun applyContentLengthMetadata(cacheKey: String, totalLength: Long) {
        if (totalLength <= 0L) return
        runCatching {
            val existing = ContentMetadata.getContentLength(simpleCache.getContentMetadata(cacheKey))
            if (existing <= 0L) {
                val mutations = ContentMetadataMutations()
                ContentMetadataMutations.setContentLength(mutations, totalLength)
                simpleCache.applyContentMetadataMutations(cacheKey, mutations)
            }
        }
    }

    fun cancelPreloadsExcept(activeKeyOrUrl: String?) {
        cancelPreloadsExcept(if (activeKeyOrUrl.isNullOrBlank()) emptySet() else setOf(activeKeyOrUrl))
    }

    fun cancelPreloadsExcept(keepKeys: Set<String>) {
        val iterator = activePreloadJobs.entries.iterator()
        while (iterator.hasNext()) {
            val (key, job) = iterator.next()
            if (key !in keepKeys) {
                activeWriters[key]?.cancel()
                job.cancel()
                iterator.remove()
            }
        }
    }

    fun cachedBytesSize(): Long = runCatching { simpleCache.cacheSpace }.getOrDefault(0L)

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        stopPlaybackBooster()
        cancelPreloadsExcept(emptySet())
        warmupPool.releaseAll()
        probeMetaByUrl.clear()
        videoIndexByEpisode.clear()
        diskLengthByEpisode.clear()
        cacheEvictor.clearPins()
        runCatching {
            simpleCache.keys.toList().forEach { key ->
                simpleCache.removeResource(key)
            }
        }
    }

    companion object {
        const val CHUNK_SIZE_BYTES = 512L * 1024L
        private const val CACHE_SINK_FRAGMENT_BYTES = 2L * 1024L * 1024L
        const val DEFAULT_PRELOAD_BYTES = 4L * 1024L * 1024L
        const val MIN_PLAYABLE_HOT_BYTES = 1536L * 1024L
        private const val VIDEO_CACHE_BASE_BYTES = 512L * 1024L * 1024L
        private const val VIDEO_CACHE_LARGE_BYTES = 1024L * 1024L * 1024L
        private const val VIDEO_CACHE_XLARGE_BYTES = 2L * 1024L * 1024L * 1024L
        private const val LARGE_DISK_THRESHOLD_BYTES = 4L * 1024L * 1024L * 1024L
        private const val XLARGE_DISK_THRESHOLD_BYTES = 12L * 1024L * 1024L * 1024L
        private const val DEFAULT_BUFFER_SIZE = 64 * 1024
        private const val MAX_PROBE_CACHE = 128
        private const val MAX_VIDEO_INDEX_CACHE = 160

        fun buildEpisodeCacheKey(
            episodeKey: String?,
            contentLength: Long,
            fallbackUrl: String
        ): String {
            if (!episodeKey.isNullOrBlank() && contentLength >= MirrorHealthTracker.MIN_VALID_VIDEO_BYTES) {
                return "ep:$episodeKey:$contentLength"
            }
            return fallbackUrl
        }

        fun calculateVideoCacheMaxBytes(usableSpaceBytes: Long): Long {
            val target = when {
                usableSpaceBytes >= XLARGE_DISK_THRESHOLD_BYTES -> VIDEO_CACHE_XLARGE_BYTES
                usableSpaceBytes >= LARGE_DISK_THRESHOLD_BYTES -> VIDEO_CACHE_LARGE_BYTES
                else -> VIDEO_CACHE_BASE_BYTES
            }
            return minOf(target, usableSpaceBytes.coerceAtLeast(0L) / 4)
        }

        fun parseTotalLengthFromHeaders(
            contentRange: String?,
            contentLength: String?,
            statusCode: Int
        ): Long {
            if (!contentRange.isNullOrBlank()) {
                val slashIdx = contentRange.lastIndexOf('/')
                if (slashIdx >= 0) {
                    val total = contentRange.substring(slashIdx + 1).trim().toLongOrNull()
                    if (total != null && total > 0L) return total
                }
            }
            if (statusCode == 200 && !contentLength.isNullOrBlank()) {
                return contentLength.trim().toLongOrNull() ?: -1L
            }
            return -1L
        }
    }
}
