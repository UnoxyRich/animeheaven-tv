package me.animeheaven.tv.data.cache

import android.content.Context
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.allowRgb565
import coil3.size.Precision
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import me.animeheaven.tv.data.local.BookmarkEntity
import me.animeheaven.tv.data.model.AnimeCard
import me.animeheaven.tv.data.model.AnimeDetail
import me.animeheaven.tv.data.model.ContinueWatchingItem
import me.animeheaven.tv.data.model.EpisodeStream
import me.animeheaven.tv.data.model.FastSearchItem
import me.animeheaven.tv.data.model.HomeFeed
import me.animeheaven.tv.data.model.PreloadMode
import me.animeheaven.tv.data.network.EpisodeSkipTimes
import me.animeheaven.tv.data.network.NetworkPolicy
import me.animeheaven.tv.data.network.NetworkPreconnector
import me.animeheaven.tv.data.network.SkipTimesClient
import me.animeheaven.tv.data.network.SkipTimesParser
import me.animeheaven.tv.data.repository.AnimeHeavenRepository
import me.animeheaven.tv.data.runCatchingCancellable
import okhttp3.OkHttpClient

/**
 * Snapshot emitted when a Watch Next episode has its stream, ranked mirrors, `moov` header,
 * and playable opening bytes committed to `SimpleCache` so the player can append it seamlessly.
 */
data class PreparedEpisodeWarmup(
    val episodeKey: String,
    val stream: EpisodeStream,
    val rankedMirrors: List<String>,
    val cacheKey: String,
    val skipTimes: EpisodeSkipTimes,
    val durationMs: Long
)

/**
 * 4-Wave predictive preloader with origin rate-limit protection, reactive user-priority yielding,
 * resume-aware episode resolution, and tiered hot-window video warming (strictly unmetered Wi-Fi/LAN):
 * - Wave 0 (0 ms): Visible Home images, CW #1 (8 MB + Warm MediaSource), CW #2-#3 (6 MB), Hero #1 (6 MB)
 * - Wave 1 (350 ms): Remaining CW #4-#15 (5 MB), remaining Banners (5 MB), Bookmarks #1-#15 (4.5 MB)
 * - Wave 2 (paced): Popular strip (12 shows, 4 MB), Home Schedule (24 shows, 4 MB), Catalog tabs
 * - Wave 3 (interactive): Card focus, Detail screen (8 MB + Warm MediaSource), Episode hover (5 MB), Watch Next (6 -> 20 MB)
 */
class AnimePreloader(
    context: Context,
    private val repository: AnimeHeavenRepository,
    private val skipTimesClientProvider: () -> SkipTimesClient,
    private val imageLoaderProvider: () -> ImageLoader,
    private val videoCacheManagerProvider: () -> VideoCacheManager,
    private val okHttpClient: OkHttpClient? = null,
    private val networkPolicy: NetworkPolicy? = null
) {
    private val appContext = context.applicationContext
    private val supervisor = SupervisorJob()
    private val workerScope = CoroutineScope(supervisor + Dispatchers.IO)
    private val scope = SpeculativeJobs()

    // Strict origin concurrency cap protects animeheaven.me from 429/503 throttling.
    private val originHtmlGate = Semaphore(2)
    private val videoWarmupGate = Semaphore(2)
    private val imageGate = Semaphore(4)
    private var suspended = false

    @Volatile
    private var originBackoffUntilMs: Long = 0L

    @Volatile
    private var originBackoffDurationMs: Long = 0L

    private fun preloadAllowed(): Boolean = networkPolicy?.effectivePreloadMode() != PreloadMode.DATA_SAVER

    private fun videoPreloadAllowed(): Boolean = networkPolicy?.shouldPreloadVideo() != false

    private inner class SpeculativeJobs {
        fun launch(block: suspend CoroutineScope.() -> Unit): Job = synchronized(this@AnimePreloader) {
            if (suspended || !preloadAllowed()) return@synchronized Job().apply { cancel() }
            workerScope.launch {
                if (preloadAllowed()) block()
            }
        }
    }

    private val warmedAnimeDetails = ConcurrentHashMap.newKeySet<String>()
    private val warmedCatalogTabs = ConcurrentHashMap.newKeySet<String>()
    private val warmedEpisodeStreams = ConcurrentHashMap.newKeySet<String>()
    private val warmedVideoBytesByEpisode = ConcurrentHashMap<String, Long>()
    private val warmedSearchQueries = ConcurrentHashMap.newKeySet<String>()

    private val foregroundUserWorkCount = MutableStateFlow(0)

    private var focusPreloadJob: Job? = null
    private var episodeFocusPreloadJob: Job? = null
    private var homeEcosystemJob: Job? = null
    private var detailPreloadJob: Job? = null

    @Volatile
    private var lastHomeEcosystemSignature: String? = null

    @Volatile
    private var lastHomeFeed: HomeFeed? = null

    @Volatile
    private var lastContinueItems: List<ContinueWatchingItem> = emptyList()

    @Volatile
    private var lastWarmedTopContinueKey: String? = null

    fun beginUserPriorityWork() {
        foregroundUserWorkCount.update { it + 1 }
    }

    fun endUserPriorityWork() {
        foregroundUserWorkCount.update { cur -> if (cur > 0) cur - 1 else 0 }
    }

    private suspend fun yieldIfUserActive() {
        if (!preloadAllowed()) throw CancellationException("Speculative preload disabled")
        if (foregroundUserWorkCount.value <= 0) return
        withTimeoutOrNull(USER_YIELD_MAX_WAIT_MS) {
            foregroundUserWorkCount.first { it <= 0 }
        }
        if (!preloadAllowed()) throw CancellationException("Speculative preload disabled")
    }

    private suspend fun awaitOriginBackoffIfNeeded() {
        val waitMs = originBackoffUntilMs - System.currentTimeMillis()
        if (waitMs > 0L) {
            delay(waitMs.coerceAtMost(MAX_ORIGIN_BACKOFF_MS))
        }
    }

    private fun recordOriginOutcome(error: Throwable?) {
        val msg = error?.message.orEmpty()
        val throttled = error is IOException && ("HTTP 429" in msg || "HTTP 503" in msg)
        if (throttled) {
            val next = when {
                originBackoffDurationMs <= 0L -> 2_000L
                originBackoffDurationMs < 5_000L -> 5_000L
                else -> MAX_ORIGIN_BACKOFF_MS
            }
            originBackoffDurationMs = next
            originBackoffUntilMs = System.currentTimeMillis() + next
        } else if (error == null) {
            originBackoffDurationMs = 0L
        }
    }

    /**
     * Called when connectivity transitions from cellular/offline onto unmetered Wi-Fi/LAN
     * so startup preloading runs automatically without requiring an app restart.
     */
    fun onUnmeteredNetworkAvailable() {
        val feed = lastHomeFeed ?: return
        lastHomeEcosystemSignature = null
        preloadHomeEcosystem(feed, lastContinueItems)
    }

    fun preloadImages(
        urls: List<String>,
        maxCount: Int = 12,
        targetSize: ImageTargetSize = ImageTargetSize.STANDARD_CARD
    ) {
        preloadImages(
            urls = urls,
            maxCount = maxCount,
            widthPx = targetSize.widthPx,
            heightPx = targetSize.heightPx
        )
    }

    fun preloadImages(
        urls: List<String>,
        maxCount: Int = 12,
        widthPx: Int = ImageTargetSize.STANDARD_CARD.widthPx,
        heightPx: Int = ImageTargetSize.STANDARD_CARD.heightPx
    ) {
        if (urls.isEmpty() || maxCount <= 0 || !preloadAllowed()) return
        urls.asSequence()
            .filter { it.isNotBlank() }
            .distinct()
            .take(maxCount)
            .forEach { url ->
                val request = ImageRequest.Builder(appContext)
                    .data(url)
                    .size(widthPx, heightPx)
                    .precision(Precision.INEXACT)
                    .allowRgb565(true)
                    .build()
                scope.launch {
                    imageGate.withPermit {
                        if (preloadAllowed()) imageLoaderProvider().execute(request)
                    }
                }
            }
    }

    @Synchronized
    fun preloadHomeEcosystem(
        feed: HomeFeed,
        continueItems: List<ContinueWatchingItem>
    ) {
        lastHomeFeed = feed
        lastContinueItems = continueItems
        if (suspended || !preloadAllowed()) return

        val signature = buildString(160) {
            append(feed.banners.firstOrNull()?.animeId.orEmpty())
            append('|')
            append(feed.scheduleItems.size)
            append('|')
            append(feed.popularItems.size)
            append('|')
            append(networkPolicy?.effectivePreloadMode()?.name.orEmpty())
            append('|')
            continueItems.take(5).forEach {
                append(it.targetEpisodeKey)
                append(',')
            }
        }
        if (signature == lastHomeEcosystemSignature) return
        lastHomeEcosystemSignature = signature

        // Wave 0: Immediate visible + near-viewport images at exact UI pixel sizes
        preloadImages(feed.banners.map { it.bannerImageUrl }, maxCount = 5, targetSize = ImageTargetSize.HERO_BANNER)
        preloadImages(continueItems.map { it.coverUrl }, maxCount = 8, targetSize = ImageTargetSize.CONTINUE_CARD)
        preloadImages(feed.popularItems.map { it.coverUrl }, maxCount = 12, targetSize = ImageTargetSize.POPULAR_CARD)
        preloadImages(feed.scheduleItems.map { it.coverUrl }, maxCount = 16, targetSize = ImageTargetSize.STANDARD_CARD)

        // Preconnect animeheaven.me, learned top 3 video CDNs, and Skip APIs immediately
        scope.launch {
            okHttpClient?.let {
                NetworkPreconnector.preconnectAsync(it)
                NetworkPreconnector.preconnectSkipApisAsync(it)
            }
            val vcm = videoCacheManagerProvider()
            val topHosts = vcm.mirrorHealthTracker.topHealthyHosts(limit = 3)
            if (topHosts.isNotEmpty()) {
                NetworkPreconnector.preconnectCdnHostsAsync(vcm.videoHttpClient, topHosts)
            }
        }

        // Wave 0 Critical Path: Launch CW #1 immediately at 0 ms with warm MediaSource pre-prep
        val topContinue = continueItems.firstOrNull()
        var launchedTopContinueNow = false
        if (topContinue != null && topContinue.targetEpisodeKey != lastWarmedTopContinueKey) {
            launchedTopContinueNow = true
            lastWarmedTopContinueKey = topContinue.targetEpisodeKey
            scope.launch {
                val cwBytes = networkPolicy?.continueVideoBytes(0) ?: (8L * 1024L * 1024L)
                preloadEpisodeBundle(
                    episodeKey = topContinue.targetEpisodeKey,
                    animeId = topContinue.animeId,
                    animeTitle = topContinue.animeTitle,
                    episodeLabel = topContinue.targetEpisodeLabel,
                    videoBytes = cwBytes,
                    resumePositionMs = topContinue.resumePositionMs,
                    estimatedDurationMs = topContinue.durationMs,
                    highPriority = true,
                    warmMediaSource = true
                )
            }
        }

        homeEcosystemJob?.cancel()
        homeEcosystemJob = scope.launch {
            val warmedAnimeVideoIds = HashSet<String>(64)

            // Wave 0: Top 3 Continue Watching items + Hero Banner #1
            val cwMax = networkPolicy?.maxContinueWatchingPreloadCount() ?: 15
            continueItems.take(minOf(3, cwMax)).forEachIndexed { idx, cw ->
                warmedAnimeVideoIds.add(cw.animeId)
                launch {
                    warmAnimeDetail(cw.animeId, highPriority = true)
                    if (idx == 0 && launchedTopContinueNow) return@launch
                    val bytes = networkPolicy?.continueVideoBytes(idx) ?: VideoCacheManager.DEFAULT_PRELOAD_BYTES
                    preloadEpisodeBundle(
                        episodeKey = cw.targetEpisodeKey,
                        animeId = cw.animeId,
                        animeTitle = cw.animeTitle,
                        episodeLabel = cw.targetEpisodeLabel,
                        videoBytes = bytes,
                        resumePositionMs = cw.resumePositionMs,
                        estimatedDurationMs = cw.durationMs,
                        highPriority = true,
                        warmMediaSource = idx == 0
                    )
                }
            }

            // Hero Banner #1 (focused by default on TV Home launch)
            feed.banners.firstOrNull()?.let { hero ->
                if (warmedAnimeVideoIds.add(hero.animeId)) {
                    launch {
                        val heroBytes = networkPolicy?.bannerVideoBytes(0) ?: (6L * 1024L * 1024L)
                        warmAnimeWithPrimaryVideo(
                            animeId = hero.animeId,
                            videoBytes = heroBytes,
                            highPriority = true,
                            warmMediaSource = continueItems.isEmpty()
                        )
                    }
                }
            }

            // Short startup grace delay so Frame 1 and CW #1 settle before Wave 1 begins
            delay(WAVE1_STARTUP_GRACE_MS)

            // Wave 1: Remaining Continue Watching (#4-#15)
            continueItems.drop(3).take((cwMax - 3).coerceAtLeast(0)).forEachIndexed { offsetIdx, cw ->
                yieldIfUserActive()
                warmedAnimeVideoIds.add(cw.animeId)
                val cwIndex = offsetIdx + 3
                val bytes = networkPolicy?.continueVideoBytes(cwIndex) ?: (5L * 1024L * 1024L)
                preloadEpisodeBundle(
                    episodeKey = cw.targetEpisodeKey,
                    animeId = cw.animeId,
                    animeTitle = cw.animeTitle,
                    episodeLabel = cw.targetEpisodeLabel,
                    videoBytes = bytes,
                    resumePositionMs = cw.resumePositionMs,
                    estimatedDurationMs = cw.durationMs,
                    highPriority = false
                )
            }

            // Wave 1: Remaining Hero Banners (#2-#5)
            val bannerCap = networkPolicy?.bannerVideoCount() ?: 5
            feed.banners.drop(1).take((bannerCap - 1).coerceAtLeast(0)).forEachIndexed { offsetIdx, banner ->
                yieldIfUserActive()
                if (warmedAnimeVideoIds.add(banner.animeId)) {
                    val bytes = networkPolicy?.bannerVideoBytes(offsetIdx + 1) ?: (5L * 1024L * 1024L)
                    warmAnimeWithPrimaryVideo(
                        animeId = banner.animeId,
                        videoBytes = bytes,
                        highPriority = false
                    )
                }
            }

            // Wave 1: Top Bookmarks (up to 15) with opening/resume video seconds
            if (networkPolicy?.shouldPreloadHomeDetails() != false) {
                val bookmarkIds = runCatchingCancellable { repository.getBookmarkIdsSnapshot() }
                    .getOrDefault(emptyList())
                val bmDetailCap = networkPolicy?.bookmarkDetailCount() ?: 15
                val bmVideoCap = networkPolicy?.bookmarkVideoCount() ?: 15
                val bmBytes = networkPolicy?.bookmarkVideoBytes() ?: (4608L * 1024L)
                bookmarkIds.take(bmDetailCap).forEachIndexed { idx, animeId ->
                    yieldIfUserActive()
                    val vBytes = if (idx < bmVideoCap && warmedAnimeVideoIds.add(animeId)) bmBytes else 0L
                    warmAnimeWithPrimaryVideo(
                        animeId = animeId,
                        videoBytes = vBytes,
                        highPriority = false,
                        paced = true
                    )
                }
            }

            // Wave 2: Home Popular Strip + Subbed Anime Schedule (every show's start/resume seconds, paced!)
            if (networkPolicy?.shouldPreloadHomeDetails() != false) {
                val popularCap = networkPolicy?.homePopularDetailCount() ?: 12
                val scheduleCap = networkPolicy?.homeScheduleDetailCount() ?: 24
                val showVideoBytes = networkPolicy?.homeShowVideoBytes() ?: VideoCacheManager.DEFAULT_PRELOAD_BYTES

                feed.popularItems.take(popularCap).forEach { card ->
                    yieldIfUserActive()
                    val vBytes = if (warmedAnimeVideoIds.add(card.id)) showVideoBytes else 0L
                    warmAnimeWithPrimaryVideo(
                        animeId = card.id,
                        videoBytes = vBytes,
                        highPriority = false,
                        paced = true
                    )
                }

                feed.scheduleItems.take(scheduleCap).forEach { card ->
                    yieldIfUserActive()
                    val vBytes = if (warmedAnimeVideoIds.add(card.id)) showVideoBytes else 0L
                    warmAnimeWithPrimaryVideo(
                        animeId = card.id,
                        videoBytes = vBytes,
                        highPriority = false,
                        paced = true
                    )
                }
            }

            // Wave 2b: Background catalog tabs (HTML + thumbnails)
            warmCatalogTabsInBackground(feed)
        }
    }

    private suspend fun warmCatalogTabsInBackground(feed: HomeFeed) {
        val catalogDetailCap = networkPolicy?.catalogDetailCount() ?: 10
        if (warmedCatalogTabs.add("new")) {
            yieldIfUserActive()
            val items = fetchChartWithPacing("chart:new") { repository.getNewEpisodes().second }
            preloadImages(items.map { it.coverUrl }, maxCount = 16, targetSize = ImageTargetSize.STANDARD_CARD)
            items.take(minOf(6, catalogDetailCap)).forEach { card ->
                yieldIfUserActive()
                warmAnimeDetail(card.id, highPriority = false, paced = true)
            }
        }
        if (warmedCatalogTabs.add("popular")) {
            yieldIfUserActive()
            val items = fetchChartWithPacing("chart:popular") { repository.getPopularToday().second }
            preloadImages(items.map { it.coverUrl }, maxCount = 16, targetSize = ImageTargetSize.STANDARD_CARD)
            items.take(minOf(6, catalogDetailCap)).forEach { card ->
                yieldIfUserActive()
                warmAnimeDetail(card.id, highPriority = false, paced = true)
            }
        }
        feed.seasonPath?.takeIf { it.isNotBlank() }?.let { path ->
            if (warmedCatalogTabs.add(path)) {
                yieldIfUserActive()
                val items = fetchChartWithPacing("chart:$path", AnimeHeavenRepository.TTL_SEASONAL_MS) {
                    repository.getSeasonal(path).second
                }
                preloadImages(items.map { it.coverUrl }, maxCount = 12, targetSize = ImageTargetSize.STANDARD_CARD)
            }
        }
    }

    private suspend fun fetchChartWithPacing(
        cacheKey: String,
        ttlMs: Long = AnimeHeavenRepository.TTL_CHART_MS,
        fetch: suspend () -> List<AnimeCard>
    ): List<AnimeCard> {
        if (repository.hasFreshChartInMemory(cacheKey, ttlMs)) {
            return runCatchingCancellable { fetch() }.getOrDefault(emptyList())
        }
        awaitOriginBackoffIfNeeded()
        return originHtmlGate.withPermit {
            runCatchingCancellable { fetch() }
                .onSuccess { recordOriginOutcome(null) }
                .onFailure { recordOriginOutcome(it) }
                .getOrDefault(emptyList())
        }
    }

    fun preloadCatalogPage(items: List<AnimeCard>) {
        preloadImages(items.map { it.coverUrl }, maxCount = 24, targetSize = ImageTargetSize.STANDARD_CARD)
        val detailCount = networkPolicy?.catalogDetailCount() ?: 10
        val videoCount = networkPolicy?.catalogVideoCount() ?: 8
        val videoBytes = networkPolicy?.homeShowVideoBytes() ?: VideoCacheManager.DEFAULT_PRELOAD_BYTES
        if (detailCount <= 0) return
        scope.launch {
            items.take(detailCount).forEachIndexed { idx, card ->
                yieldIfUserActive()
                val vBytes = if (idx < videoCount) videoBytes else 0L
                warmAnimeWithPrimaryVideo(card.id, videoBytes = vBytes, highPriority = false, paced = true)
            }
        }
    }

    fun preloadBookmarksEcosystem(
        bookmarks: List<BookmarkEntity>,
        continueItems: List<ContinueWatchingItem>
    ) {
        preloadImages(bookmarks.map { it.coverUrl }, maxCount = 20, targetSize = ImageTargetSize.STANDARD_CARD)
        preloadImages(continueItems.map { it.coverUrl }, maxCount = 10, targetSize = ImageTargetSize.CONTINUE_CARD)
        if (networkPolicy?.shouldPreloadHomeDetails() == false) return
        val bmVideoCap = networkPolicy?.bookmarkVideoCount() ?: 15
        val bmBytes = networkPolicy?.bookmarkVideoBytes() ?: (4608L * 1024L)
        scope.launch {
            bookmarks.take(15).forEachIndexed { idx, bm ->
                yieldIfUserActive()
                val vBytes = if (idx < bmVideoCap) bmBytes else 0L
                warmAnimeWithPrimaryVideo(bm.animeId, videoBytes = vBytes, highPriority = false, paced = true)
            }
        }
    }

    fun preloadSearchEcosystem(recentQueries: List<String>) {
        if (recentQueries.isEmpty()) return
        scope.launch {
            recentQueries.take(3).forEach { query ->
                val normalized = query.trim().lowercase()
                if (normalized.length >= 2 && warmedSearchQueries.add(normalized)) {
                    yieldIfUserActive()
                    awaitOriginBackoffIfNeeded()
                    originHtmlGate.withPermit {
                        runCatchingCancellable { repository.fastSearch(query) }
                            .onSuccess { matches ->
                                recordOriginOutcome(null)
                                preloadImages(
                                    matches.map { it.coverUrl },
                                    maxCount = 4,
                                    targetSize = ImageTargetSize.SEARCH_THUMB
                                )
                                matches.firstOrNull()?.let { top ->
                                    warmAnimeDetail(top.animeId, highPriority = false)
                                }
                            }
                            .onFailure { recordOriginOutcome(it) }
                    }
                }
            }
        }
    }

    fun preloadSearchMatches(items: List<FastSearchItem>) {
        if (items.isEmpty()) return
        preloadImages(items.map { it.coverUrl }, maxCount = 8, targetSize = ImageTargetSize.SEARCH_THUMB)
        if (networkPolicy?.shouldPreloadHomeDetails() == false) return
        val hoverBytes = networkPolicy?.focusHoverVideoBytes() ?: 0L
        scope.launch {
            items.take(3).forEachIndexed { idx, item ->
                yieldIfUserActive()
                warmAnimeWithPrimaryVideo(
                    animeId = item.animeId,
                    videoBytes = if (idx == 0) hoverBytes else 0L,
                    highPriority = false
                )
            }
        }
    }

    fun onCardFocused(animeId: String, coverUrl: String = "") {
        onCardFocusedWithNeighbors(animeId, coverUrl)
    }

    @Synchronized
    fun onCardFocusedWithNeighbors(
        animeId: String,
        coverUrl: String = "",
        neighborIds: List<String> = emptyList(),
        neighborCovers: List<String> = emptyList(),
        targetSize: ImageTargetSize = ImageTargetSize.STANDARD_CARD
    ) {
        if (animeId.isBlank() || suspended || !preloadAllowed()) return
        if (coverUrl.isNotBlank()) {
            preloadImages(listOf(coverUrl), maxCount = 1, targetSize = targetSize)
        }
        if (neighborCovers.isNotEmpty()) {
            preloadImages(neighborCovers, maxCount = 3, targetSize = targetSize)
        }

        focusPreloadJob?.cancel()
        focusPreloadJob = scope.launch {
            delay(120L)
            val focusBytes = networkPolicy?.focusHoverVideoBytes() ?: 0L
            warmAnimeWithPrimaryVideo(
                animeId = animeId,
                videoBytes = focusBytes,
                highPriority = true,
                warmMediaSource = false
            )
            for (nextId in neighborIds.take(2)) {
                if (nextId.isNotBlank() && nextId !in warmedAnimeDetails) {
                    yieldIfUserActive()
                    warmAnimeDetail(nextId, highPriority = false)
                }
            }
        }
    }

    /**
     * TV Detail Episode Tile D-pad focus lookahead: after a 200 ms dwell, warms that specific
     * episode's gate, exact resume/start 5 MB video window, and pre-prepared MediaSource!
     */
    @Synchronized
    fun onEpisodeFocused(
        animeId: String,
        animeTitle: String,
        episodeKey: String,
        episodeLabel: String,
        resumePositionMs: Long = 0L,
        durationMs: Long = 0L
    ) {
        if (episodeKey.isBlank() || suspended || !preloadAllowed()) return
        val bytes = networkPolicy?.focusHoverVideoBytes() ?: 0L
        if (bytes <= 0L) return
        episodeFocusPreloadJob?.cancel()
        episodeFocusPreloadJob = scope.launch {
            delay(200L)
            preloadEpisodeBundle(
                episodeKey = episodeKey,
                animeId = animeId,
                animeTitle = animeTitle,
                episodeLabel = episodeLabel,
                videoBytes = bytes,
                resumePositionMs = resumePositionMs,
                estimatedDurationMs = durationMs,
                highPriority = true,
                warmMediaSource = true
            )
        }
    }

    @Synchronized
    fun preloadAnimeDetail(
        detail: AnimeDetail,
        primaryEpisodeKey: String?,
        primaryEpisodeLabel: String?,
        primaryResumeMs: Long = -1L,
        primaryDurationMs: Long = 0L
    ) {
        if (suspended || !preloadAllowed()) return
        detail.bannerUrl?.let {
            preloadImages(listOf(it), maxCount = 1, targetSize = ImageTargetSize.HERO_BANNER)
        }
        preloadImages(listOf(detail.posterUrl), maxCount = 1, targetSize = ImageTargetSize.DETAIL_POSTER)
        preloadImages(
            urls = (detail.relatedShows + detail.similarShows).map { it.coverUrl },
            maxCount = 12,
            targetSize = ImageTargetSize.STANDARD_CARD
        )

        detailPreloadJob?.cancel()
        detailPreloadJob = scope.launch {
            okHttpClient?.let { NetworkPreconnector.preconnectSkipApisAsync(it) }
            val vcm = videoCacheManagerProvider()
            val topHosts = vcm.mirrorHealthTracker.topHealthyHosts(limit = 3)
            if (topHosts.isNotEmpty()) {
                NetworkPreconnector.preconnectCdnHostsAsync(vcm.videoHttpClient, topHosts)
            }

            if (!primaryEpisodeKey.isNullOrBlank()) {
                val primaryBytes = networkPolicy?.detailPrimaryVideoBytes() ?: (8L * 1024L * 1024L)
                val savedProgress = if (primaryResumeMs < 0L || primaryDurationMs <= 0L) {
                    runCatchingCancellable { repository.getSavedProgress(primaryEpisodeKey) }.getOrNull()
                } else {
                    null
                }
                val resolvedResumeMs = if (primaryResumeMs >= 0L) {
                    primaryResumeMs
                } else {
                    savedProgress?.let {
                        AnimeHeavenRepository.resolveResumePositionMs(it.positionMs, it.durationMs)
                    } ?: 0L
                }
                val resolvedDurMs = primaryDurationMs.takeIf { it > 0L }
                    ?: savedProgress?.durationMs?.takeIf { it > 0L }
                    ?: 0L

                preloadEpisodeBundle(
                    episodeKey = primaryEpisodeKey,
                    animeId = detail.id,
                    animeTitle = detail.title,
                    episodeLabel = primaryEpisodeLabel.orEmpty(),
                    videoBytes = primaryBytes,
                    resumePositionMs = resolvedResumeMs,
                    estimatedDurationMs = resolvedDurMs,
                    highPriority = true,
                    warmMediaSource = true
                )

                val epIdx = detail.episodes.indexOfFirst { it.key == primaryEpisodeKey }
                if (epIdx > 0) {
                    val followEp = detail.episodes[epIdx - 1]
                    yieldIfUserActive()
                    val followupBytes = networkPolicy?.detailFollowupVideoBytes() ?: 0L
                    preloadEpisodeBundle(
                        episodeKey = followEp.key,
                        animeId = detail.id,
                        animeTitle = detail.title,
                        episodeLabel = followEp.numberLabel,
                        videoBytes = followupBytes,
                        highPriority = false
                    )
                }
            }

            if (networkPolicy?.shouldPreloadHomeDetails() != false) {
                (detail.relatedShows + detail.similarShows).take(2).forEach { show ->
                    yieldIfUserActive()
                    warmAnimeDetail(show.id, highPriority = false)
                }
            }
        }
    }

    fun preloadNextEpisode(
        nextEpisodeKey: String,
        animeId: String?,
        animeTitle: String,
        nextEpisodeLabel: String?,
        onPrepared: ((PreparedEpisodeWarmup) -> Unit)? = null
    ) {
        if (nextEpisodeKey.isBlank()) return
        val bytes = networkPolicy?.nextEpisodeInitialBytes() ?: (6L * 1024L * 1024L)
        scope.launch {
            val prepared = preloadEpisodeBundle(
                episodeKey = nextEpisodeKey,
                animeId = animeId,
                animeTitle = animeTitle,
                episodeLabel = nextEpisodeLabel.orEmpty(),
                videoBytes = bytes,
                highPriority = true,
                warmMediaSource = false
            )
            if (prepared != null) {
                onPrepared?.invoke(prepared)
            }
        }
    }

    fun escalateNextEpisodeBuffer(
        nextEpisodeKey: String,
        animeId: String?,
        animeTitle: String = "",
        nextEpisodeLabel: String? = null,
        onPrepared: ((PreparedEpisodeWarmup) -> Unit)? = null
    ) {
        val targetBytes = networkPolicy?.nextEpisodeEscalatedBytes() ?: (20L * 1024L * 1024L)
        if (nextEpisodeKey.isBlank() || targetBytes <= 0L) return
        scope.launch {
            val prepared = preloadEpisodeBundle(
                episodeKey = nextEpisodeKey,
                animeId = animeId,
                animeTitle = animeTitle,
                episodeLabel = nextEpisodeLabel.orEmpty(),
                videoBytes = targetBytes,
                highPriority = true,
                warmMediaSource = false
            )
            if (prepared != null) {
                onPrepared?.invoke(prepared)
            }
        }
    }

    private data class ResolvedEpisodeTarget(
        val episodeKey: String,
        val episodeLabel: String,
        val resumePositionMs: Long,
        val durationMs: Long
    )

    private fun resolveTargetEpisodeForAnime(detail: AnimeDetail): ResolvedEpisodeTarget? {
        if (detail.episodes.isEmpty()) return null
        val latest = repository.peekLatestProgressForAnime(detail.id)
        if (latest != null) {
            if (!latest.isWatched && latest.positionMs > AnimeHeavenRepository.MIN_RESUME_THRESHOLD_MS) {
                val resumeMs = AnimeHeavenRepository.resolveResumePositionMs(latest.positionMs, latest.durationMs)
                return ResolvedEpisodeTarget(
                    episodeKey = latest.episodeKey,
                    episodeLabel = latest.episodeLabel,
                    resumePositionMs = resumeMs,
                    durationMs = latest.durationMs
                )
            }
            if (latest.isWatched) {
                val idx = detail.episodes.indexOfFirst { it.key == latest.episodeKey }
                if (idx > 0) {
                    val nextEp = detail.episodes[idx - 1]
                    return ResolvedEpisodeTarget(nextEp.key, nextEp.numberLabel, 0L, 0L)
                }
                val nextKey = latest.nextEpisodeKey?.takeIf { it.isNotBlank() }
                if (nextKey != null) {
                    return ResolvedEpisodeTarget(nextKey, latest.nextEpisodeLabel.orEmpty(), 0L, 0L)
                }
            }
        }
        val firstEp = detail.episodes.last()
        return ResolvedEpisodeTarget(firstEp.key, firstEp.numberLabel, 0L, 0L)
    }

    private suspend fun warmAnimeWithPrimaryVideo(
        animeId: String,
        videoBytes: Long,
        highPriority: Boolean,
        warmMediaSource: Boolean = false,
        paced: Boolean = false
    ) {
        if (!preloadAllowed() || animeId.isBlank()) return
        val detail = repository.peekCachedDetail(animeId)
            ?: warmAnimeDetail(animeId, highPriority = highPriority, paced = paced)
            ?: return
        if (videoBytes <= 0L || !videoPreloadAllowed()) return
        val target = resolveTargetEpisodeForAnime(detail) ?: return
        preloadEpisodeBundle(
            episodeKey = target.episodeKey,
            animeId = detail.id,
            animeTitle = detail.title,
            episodeLabel = target.episodeLabel,
            videoBytes = videoBytes,
            resumePositionMs = target.resumePositionMs,
            estimatedDurationMs = target.durationMs,
            highPriority = highPriority,
            warmMediaSource = warmMediaSource,
            paced = paced
        )
    }

    private suspend fun warmAnimeDetail(
        animeId: String,
        highPriority: Boolean,
        paced: Boolean = false
    ): AnimeDetail? {
        if (!preloadAllowed() || animeId.isBlank()) return null
        if (repository.hasFreshDetailInMemory(animeId)) {
            warmedAnimeDetails.add(animeId)
            return repository.peekCachedDetail(animeId)
        }
        if (!warmedAnimeDetails.add(animeId)) {
            return repository.peekCachedDetail(animeId)
        }
        if (!highPriority) awaitOriginBackoffIfNeeded()
        return originHtmlGate.withPermit {
            if (paced) delay(WAVE2_ORIGIN_PACING_MS)
            runCatchingCancellable { repository.getAnimeDetail(animeId, forceRefresh = false) }
                .onSuccess { detail ->
                    recordOriginOutcome(null)
                    detail.bannerUrl?.let {
                        preloadImages(listOf(it), maxCount = 1, targetSize = ImageTargetSize.HERO_BANNER)
                    }
                    if (detail.posterUrl.isNotBlank()) {
                        preloadImages(listOf(detail.posterUrl), maxCount = 1, targetSize = ImageTargetSize.DETAIL_POSTER)
                    }
                }
                .onFailure { err ->
                    recordOriginOutcome(err)
                    warmedAnimeDetails.remove(animeId)
                }
                .getOrNull()
        }
    }

    private suspend fun preloadEpisodeBundle(
        episodeKey: String,
        animeId: String?,
        animeTitle: String,
        episodeLabel: String,
        videoBytes: Long,
        resumePositionMs: Long = 0L,
        estimatedDurationMs: Long = 0L,
        highPriority: Boolean = true,
        warmMediaSource: Boolean = false,
        paced: Boolean = false
    ): PreparedEpisodeWarmup? {
        if (episodeKey.isBlank() || !preloadAllowed()) return null
        val effectiveVideoBytes = if (videoPreloadAllowed()) videoBytes else 0L
        val alreadyWarmedBytes = warmedVideoBytesByEpisode[episodeKey] ?: 0L
        val firstEpisodeWarm = warmedEpisodeStreams.add(episodeKey)
        val needsMoreVideo = effectiveVideoBytes > alreadyWarmedBytes
        if (!firstEpisodeWarm && !needsMoreVideo && !warmMediaSource) {
            return buildPreparedSnapshotIfWarm(episodeKey, resumePositionMs, estimatedDurationMs)
        }

        val stream = repository.peekCachedStream(episodeKey)?.takeIf {
            !repository.isCachedStreamStale(episodeKey) && it.videoSources.isNotEmpty()
        } ?: run {
            if (!highPriority) awaitOriginBackoffIfNeeded()
            originHtmlGate.withPermit {
                if (paced) delay(WAVE2_ORIGIN_PACING_MS)
                runCatchingCancellable { repository.resolveEpisodeStream(episodeKey, animeId) }
                    .onSuccess { recordOriginOutcome(null) }
                    .onFailure { err ->
                        recordOriginOutcome(err)
                        warmedEpisodeStreams.remove(episodeKey)
                    }
                    .getOrNull()
            }
        } ?: return null

        val title = stream.animeTitle.ifBlank { animeTitle }
        val label = stream.episodeLabel.ifBlank { episodeLabel }
        val epNum = SkipTimesParser.parseEpisodeNumber(label)
        var resolvedSkipTimes = EpisodeSkipTimes()
        if (epNum != null && title.isNotBlank()) {
            if (highPriority) {
                scope.launch {
                    runCatchingCancellable { skipTimesClientProvider().fetchSkipTimes(title, epNum) }
                }
            }
        }

        if (stream.videoSources.isEmpty()) return null
        val vcm = videoCacheManagerProvider()

        return videoWarmupGate.withPermit {
            runCatchingCancellable {
                val ranked = vcm.mirrorHealthTracker.rankMirrors(stream.videoSources)
                val primaryUrl = ranked.firstOrNull() ?: return@runCatchingCancellable null

                if (effectiveVideoBytes > 0L) {
                    val existingIdx = vcm.getVideoIndex(episodeKey)
                    val durMs = when {
                        estimatedDurationMs > 0L -> estimatedDurationMs
                        existingIdx != null && existingIdx.durationMs > 0L -> existingIdx.durationMs
                        else -> DEFAULT_EP_DURATION_MS
                    }
                    val opEndMs = resolvedSkipTimes.op?.endMs ?: DEFAULT_OP_END_MS
                    val readyCacheKey = vcm.preloadVideoHeadAndAwait(
                        url = primaryUrl,
                        bytesToCache = effectiveVideoBytes,
                        allSources = ranked,
                        resumePositionMs = resumePositionMs,
                        estimatedDurationMs = durMs,
                        opEndMs = opEndMs,
                        episodeKey = episodeKey
                    )
                    if (readyCacheKey != null) {
                        warmedVideoBytesByEpisode.merge(episodeKey, effectiveVideoBytes) { old, incoming ->
                            maxOf(old, incoming)
                        }
                        if (warmMediaSource) {
                            vcm.warmupPool.preloadEpisode(
                                episodeKey = episodeKey,
                                url = primaryUrl,
                                cacheKey = readyCacheKey,
                                resumePositionMs = resumePositionMs
                            )
                        }
                        val refreshedRanked = vcm.mirrorHealthTracker.rankMirrors(stream.videoSources)
                        val refreshedDur = vcm.getVideoIndex(episodeKey)?.durationMs
                            ?.takeIf { it > 0L } ?: estimatedDurationMs
                        if (epNum != null && title.isNotBlank()) {
                            resolvedSkipTimes = runCatchingCancellable {
                                skipTimesClientProvider().fetchSkipTimes(title, epNum)
                            }.getOrDefault(EpisodeSkipTimes())
                        }
                        return@runCatchingCancellable PreparedEpisodeWarmup(
                            episodeKey = episodeKey,
                            stream = stream,
                            rankedMirrors = refreshedRanked,
                            cacheKey = readyCacheKey,
                            skipTimes = resolvedSkipTimes,
                            durationMs = refreshedDur
                        )
                    }
                } else {
                    vcm.probeMirrorsSpeed(stream.videoSources)
                }
                null
            }.getOrNull()
        }
    }

    private fun buildPreparedSnapshotIfWarm(
        episodeKey: String,
        resumePositionMs: Long,
        estimatedDurationMs: Long
    ): PreparedEpisodeWarmup? {
        val stream = repository.peekCachedStream(episodeKey) ?: return null
        if (stream.videoSources.isEmpty()) return null
        val vcm = videoCacheManagerProvider()
        val ranked = vcm.mirrorHealthTracker.rankMirrors(stream.videoSources)
        val primary = ranked.firstOrNull() ?: return null
        val cacheKey = vcm.resolveCacheKey(episodeKey, primary)
        if (!vcm.hasPlayableHotWindow(episodeKey, cacheKey, resumePositionMs, estimatedDurationMs)) {
            return null
        }
        val dur = vcm.getVideoIndex(episodeKey)?.durationMs?.takeIf { it > 0L } ?: estimatedDurationMs
        return PreparedEpisodeWarmup(
            episodeKey = episodeKey,
            stream = stream,
            rankedMirrors = ranked,
            cacheKey = cacheKey,
            skipTimes = EpisodeSkipTimes(),
            durationMs = dur
        )
    }

    suspend fun <T> withoutPreloading(block: suspend () -> T): T {
        val pending = synchronized(this) {
            suspended = true
            supervisor.children.toList().also { it.forEach { job -> job.cancel() } }
        }
        try {
            pending.joinAll()
            return block()
        } finally {
            synchronized(this) {
                warmedAnimeDetails.clear()
                warmedCatalogTabs.clear()
                warmedEpisodeStreams.clear()
                warmedVideoBytesByEpisode.clear()
                warmedSearchQueries.clear()
                lastHomeEcosystemSignature = null
                lastWarmedTopContinueKey = null
                focusPreloadJob = null
                episodeFocusPreloadJob = null
                detailPreloadJob = null
                homeEcosystemJob = null
                originBackoffUntilMs = 0L
                originBackoffDurationMs = 0L
                suspended = false
            }
        }
    }

    companion object {
        private const val DEFAULT_EP_DURATION_MS = 24L * 60_000L
        private const val DEFAULT_OP_END_MS = 85_000L
        private const val WAVE1_STARTUP_GRACE_MS = 350L
        private const val WAVE2_ORIGIN_PACING_MS = 150L
        private const val MAX_ORIGIN_BACKOFF_MS = 15_000L
        private const val USER_YIELD_MAX_WAIT_MS = 6_000L
    }
}
