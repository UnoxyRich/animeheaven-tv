package me.animeheaven.tv.data.cache

import android.content.Context
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.allowRgb565
import coil3.size.Precision
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import me.animeheaven.tv.data.local.BookmarkEntity
import me.animeheaven.tv.data.model.AnimeCard
import me.animeheaven.tv.data.model.AnimeDetail
import me.animeheaven.tv.data.model.ContinueWatchingItem
import me.animeheaven.tv.data.model.FastSearchItem
import me.animeheaven.tv.data.model.HomeFeed
import me.animeheaven.tv.data.model.PreloadMode
import me.animeheaven.tv.data.network.NetworkPolicy
import me.animeheaven.tv.data.network.NetworkPreconnector
import me.animeheaven.tv.data.network.SkipTimesClient
import me.animeheaven.tv.data.network.SkipTimesParser
import me.animeheaven.tv.data.repository.AnimeHeavenRepository
import me.animeheaven.tv.data.runCatchingCancellable
import okhttp3.OkHttpClient

/**
 * 3-Band predictive preloader with reactive user-priority yield gate, canonical Coil image
 * target sizes, Band-1 congestion pacing, and shared-key video warming:
 * - Band 0: Immediate visible Home artwork (exact UI pixel sizes), Hero banners, Continue Watching #1-3
 * - Band 1: Paced background warming after visible image grace delay (Popular, Schedule, Catalog tabs, Bookmarks)
 * - Band 2: Screen lookahead (D-pad neighbors, live Search top matches, Detail Next-Ep, Player 8 MB escalation)
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
    private val band0Gate = Semaphore(3)
    private val band1Gate = Semaphore(2)
    private val imageGate = Semaphore(4)
    private var suspended = false

    private fun preloadAllowed(): Boolean = networkPolicy?.effectivePreloadMode() != PreloadMode.DATA_SAVER

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
    private val warmedSearchQueries = ConcurrentHashMap.newKeySet<String>()

    private val foregroundUserWorkCount = MutableStateFlow(0)

    private var focusPreloadJob: Job? = null
    private var homeEcosystemJob: Job? = null
    private var detailPreloadJob: Job? = null

    @Volatile
    private var lastHomeEcosystemSignature: String? = null

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
        if (suspended || !preloadAllowed()) return
        // Deduplicate by feed & target episode keys so 5s/15s playback progress updates never re-trigger storms
        val signature = buildString(128) {
            append(feed.banners.firstOrNull()?.animeId.orEmpty())
            append('|')
            append(feed.scheduleItems.size)
            append('|')
            append(feed.popularItems.size)
            append('|')
            continueItems.take(3).forEach {
                append(it.targetEpisodeKey)
                append(',')
            }
        }
        if (signature == lastHomeEcosystemSignature) return
        lastHomeEcosystemSignature = signature

        // Band 0: Immediate visible + near-viewport images at EXACT UI target sizes (0ms RAM hits)
        preloadImages(feed.banners.map { it.bannerImageUrl }, maxCount = 3, targetSize = ImageTargetSize.HERO_BANNER)
        preloadImages(continueItems.map { it.coverUrl }, maxCount = 4, targetSize = ImageTargetSize.CONTINUE_CARD)
        preloadImages(feed.popularItems.map { it.coverUrl }, maxCount = 6, targetSize = ImageTargetSize.POPULAR_CARD)
        preloadImages(feed.scheduleItems.map { it.coverUrl }, maxCount = 8, targetSize = ImageTargetSize.STANDARD_CARD)

        // Preconnect learned video CDNs & skip APIs only when Continue Watching shows playback is likely
        if (continueItems.isNotEmpty()) {
            scope.launch {
                val vcm = videoCacheManagerProvider()
                val topHosts = vcm.mirrorHealthTracker.topHealthyHosts(limit = 2)
                if (topHosts.isNotEmpty()) {
                    NetworkPreconnector.preconnectCdnHostsAsync(vcm.videoHttpClient, topHosts)
                }
                okHttpClient?.let { NetworkPreconnector.preconnectSkipApisAsync(it) }
            }
        }

        val topContinue = continueItems.firstOrNull()
        var launchedTopContinueNow = false
        if (topContinue != null && topContinue.targetEpisodeKey != lastWarmedTopContinueKey) {
            launchedTopContinueNow = true
            lastWarmedTopContinueKey = topContinue.targetEpisodeKey
            scope.launch {
                val cwBytes = networkPolicy?.continuePrimaryVideoBytes()
                    ?: VideoCacheManager.DEFAULT_PRELOAD_BYTES
                preloadEpisodeBundle(
                    episodeKey = topContinue.targetEpisodeKey,
                    animeId = topContinue.animeId,
                    animeTitle = topContinue.animeTitle,
                    episodeLabel = topContinue.targetEpisodeLabel,
                    videoBytes = cwBytes,
                    resumePositionMs = topContinue.resumePositionMs,
                    estimatedDurationMs = topContinue.durationMs
                )
            }
        }

        if (homeEcosystemJob?.isActive == true) return
        homeEcosystemJob = scope.launch {
            // Band 0: Continue Watching details + secondary items (skip duplicate #0 video bundle!)
            val cwCount = networkPolicy?.maxContinueWatchingPreloadCount() ?: 3
            continueItems.take(cwCount).forEachIndexed { idx, cw ->
                launch {
                    warmAnimeDetail(cw.animeId, highPriority = true)
                    if (idx == 0 && launchedTopContinueNow) return@launch
                    val bytes = when (idx) {
                        0 -> networkPolicy?.continuePrimaryVideoBytes() ?: VideoCacheManager.DEFAULT_PRELOAD_BYTES
                        1 -> if (networkPolicy?.shouldPreloadVideo() == true) 1536L * 1024L else 0L
                        else -> 0L
                    }
                    preloadEpisodeBundle(
                        episodeKey = cw.targetEpisodeKey,
                        animeId = cw.animeId,
                        animeTitle = cw.animeTitle,
                        episodeLabel = cw.targetEpisodeLabel,
                        videoBytes = bytes,
                        resumePositionMs = cw.resumePositionMs,
                        estimatedDurationMs = cw.durationMs
                    )
                }
            }

            // Grace delay so Band-0 visible Home thumbnails never fight Band-1 HTML scraping on animeheaven.me
            delay(BAND1_STARTUP_GRACE_MS)

            feed.banners.take(2).forEachIndexed { idx, banner ->
                launch {
                    if (idx > 0) yieldIfUserActive()
                    val detail = warmAnimeDetail(banner.animeId, highPriority = idx == 0)
                    if (idx == 0 && detail != null) {
                        val firstEp = detail.episodes.lastOrNull()
                        if (firstEp != null) {
                            preloadEpisodeBundle(
                                episodeKey = firstEp.key,
                                animeId = detail.id,
                                animeTitle = detail.title,
                                episodeLabel = firstEp.numberLabel,
                                videoBytes = 0L
                            )
                        }
                    }
                }
            }

            // Band 1: Catalog tabs (New, Popular, Seasonal) + top details inside tabs
            val catalogDetailCap = networkPolicy?.catalogDetailCount() ?: 6
            launch {
                yieldIfUserActive()
                if (warmedCatalogTabs.add("new")) {
                    val items = if (repository.hasFreshChartInMemory("chart:new")) {
                        runCatchingCancellable { repository.getNewEpisodes().second }.getOrDefault(emptyList())
                    } else {
                        band1Gate.withPermit {
                            runCatchingCancellable { repository.getNewEpisodes().second }.getOrDefault(emptyList())
                        }
                    }
                    preloadImages(items.map { it.coverUrl }, maxCount = 12, targetSize = ImageTargetSize.STANDARD_CARD)
                    items.take(catalogDetailCap).forEach { card ->
                        yieldIfUserActive()
                        warmAnimeDetail(card.id, highPriority = false)
                    }
                }
            }
            launch {
                yieldIfUserActive()
                if (warmedCatalogTabs.add("popular")) {
                    val items = if (repository.hasFreshChartInMemory("chart:popular")) {
                        runCatchingCancellable { repository.getPopularToday().second }.getOrDefault(emptyList())
                    } else {
                        band1Gate.withPermit {
                            runCatchingCancellable { repository.getPopularToday().second }.getOrDefault(emptyList())
                        }
                    }
                    preloadImages(items.map { it.coverUrl }, maxCount = 12, targetSize = ImageTargetSize.STANDARD_CARD)
                    items.take(catalogDetailCap).forEach { card ->
                        yieldIfUserActive()
                        warmAnimeDetail(card.id, highPriority = false)
                    }
                }
            }
            feed.seasonPath?.takeIf { it.isNotBlank() }?.let { path ->
                launch {
                    yieldIfUserActive()
                    if (warmedCatalogTabs.add(path)) {
                        val items = if (repository.hasFreshChartInMemory("chart:$path", AnimeHeavenRepository.TTL_SEASONAL_MS)) {
                            runCatchingCancellable { repository.getSeasonal(path).second }.getOrDefault(emptyList())
                        } else {
                            band1Gate.withPermit {
                                runCatchingCancellable { repository.getSeasonal(path).second }.getOrDefault(emptyList())
                            }
                        }
                        preloadImages(items.map { it.coverUrl }, maxCount = 10, targetSize = ImageTargetSize.STANDARD_CARD)
                    }
                }
            }

            // Band 1: Visible Popular strip + Top Schedule + Bookmark details
            if (networkPolicy?.shouldPreloadHomeDetails() != false) {
                val popularCap = networkPolicy?.homePopularDetailCount() ?: 10
                val scheduleCap = networkPolicy?.homeScheduleDetailCount() ?: 12

                launch {
                    feed.popularItems.take(popularCap).forEach { card ->
                        yieldIfUserActive()
                        warmAnimeDetail(card.id, highPriority = false)
                    }
                }
                launch {
                    feed.scheduleItems.take(scheduleCap).forEach { card ->
                        yieldIfUserActive()
                        warmAnimeDetail(card.id, highPriority = false)
                    }
                }
                launch {
                    yieldIfUserActive()
                    val bookmarkIds = runCatchingCancellable { repository.getBookmarkIdsSnapshot() }
                        .getOrDefault(emptyList())
                    val bmCap = if (networkPolicy?.effectivePreloadMode() == PreloadMode.BALANCED) 3 else 8
                    bookmarkIds.take(bmCap).forEach { id ->
                        yieldIfUserActive()
                        warmAnimeDetail(id, highPriority = false)
                    }
                }
            }
        }
    }

    fun preloadCatalogPage(items: List<AnimeCard>) {
        preloadImages(items.map { it.coverUrl }, maxCount = 20, targetSize = ImageTargetSize.STANDARD_CARD)
        val detailCount = networkPolicy?.catalogDetailCount() ?: 6
        if (detailCount <= 0) return
        scope.launch {
            items.take(detailCount).forEach { card ->
                yieldIfUserActive()
                warmAnimeDetail(card.id, highPriority = false)
            }
        }
    }

    fun preloadBookmarksEcosystem(
        bookmarks: List<BookmarkEntity>,
        continueItems: List<ContinueWatchingItem>
    ) {
        preloadImages(bookmarks.map { it.coverUrl }, maxCount = 16, targetSize = ImageTargetSize.STANDARD_CARD)
        preloadImages(continueItems.map { it.coverUrl }, maxCount = 8, targetSize = ImageTargetSize.CONTINUE_CARD)
        if (networkPolicy?.shouldPreloadHomeDetails() == false) return
        scope.launch {
            bookmarks.take(8).forEach { bm ->
                yieldIfUserActive()
                warmAnimeDetail(bm.animeId, highPriority = false)
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
                    band1Gate.withPermit {
                        runCatchingCancellable { repository.fastSearch(query) }.onSuccess { matches ->
                            preloadImages(
                                matches.map { it.coverUrl },
                                maxCount = 4,
                                targetSize = ImageTargetSize.SEARCH_THUMB
                            )
                            matches.firstOrNull()?.let { top -> warmAnimeDetail(top.animeId, highPriority = false) }
                        }
                    }
                }
            }
        }
    }

    fun preloadSearchMatches(items: List<FastSearchItem>) {
        if (items.isEmpty()) return
        preloadImages(items.map { it.coverUrl }, maxCount = 8, targetSize = ImageTargetSize.SEARCH_THUMB)
        if (networkPolicy?.shouldPreloadHomeDetails() == false) return
        scope.launch {
            items.take(3).forEach { item ->
                yieldIfUserActive()
                warmAnimeDetail(item.animeId, highPriority = false)
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
            if (animeId !in warmedAnimeDetails) {
                warmAnimeDetail(animeId, highPriority = true)
            }
            for (nextId in neighborIds.take(2)) {
                if (nextId.isNotBlank() && nextId !in warmedAnimeDetails) {
                    yieldIfUserActive()
                    warmAnimeDetail(nextId, highPriority = false)
                }
            }
        }
    }

    @Synchronized
    fun preloadAnimeDetail(
        detail: AnimeDetail,
        primaryEpisodeKey: String?,
        primaryEpisodeLabel: String?
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

        // Warm video CDNs & skip APIs right when user enters Detail (seconds before pressing Watch)
        detailPreloadJob?.cancel()
        detailPreloadJob = scope.launch {
            okHttpClient?.let { NetworkPreconnector.preconnectSkipApisAsync(it) }
            val vcm = videoCacheManagerProvider()
            val topHosts = vcm.mirrorHealthTracker.topHealthyHosts(limit = 2)
            if (topHosts.isNotEmpty()) {
                NetworkPreconnector.preconnectCdnHostsAsync(vcm.videoHttpClient, topHosts)
            }

            if (!primaryEpisodeKey.isNullOrBlank()) {
                val primaryBytes = if (networkPolicy?.shouldPreloadVideo() != false) {
                    3072L * 1024L
                } else {
                    0L
                }
                val savedResumeMs = runCatchingCancellable {
                    repository.getSavedPositionMs(primaryEpisodeKey)
                }.getOrDefault(0L)
                preloadEpisodeBundle(
                    episodeKey = primaryEpisodeKey,
                    animeId = detail.id,
                    animeTitle = detail.title,
                    episodeLabel = primaryEpisodeLabel.orEmpty(),
                    videoBytes = primaryBytes,
                    resumePositionMs = savedResumeMs
                )

                val epIdx = detail.episodes.indexOfFirst { it.key == primaryEpisodeKey }
                if (epIdx > 0) {
                    val followEp = detail.episodes[epIdx - 1]
                    yieldIfUserActive()
                    preloadEpisodeBundle(
                        episodeKey = followEp.key,
                        animeId = detail.id,
                        animeTitle = detail.title,
                        episodeLabel = followEp.numberLabel,
                        videoBytes = 0L
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
        nextEpisodeLabel: String?
    ) {
        if (nextEpisodeKey.isBlank()) return
        val bytes = networkPolicy?.nextEpisodeInitialBytes() ?: VideoCacheManager.DEFAULT_PRELOAD_BYTES
        scope.launch {
            preloadEpisodeBundle(
                episodeKey = nextEpisodeKey,
                animeId = animeId,
                animeTitle = animeTitle,
                episodeLabel = nextEpisodeLabel.orEmpty(),
                videoBytes = bytes
            )
        }
    }

    fun escalateNextEpisodeBuffer(
        nextEpisodeKey: String,
        animeId: String?
    ) {
        val targetBytes = networkPolicy?.nextEpisodeEscalatedBytes() ?: (8L * 1024L * 1024L)
        if (nextEpisodeKey.isBlank() || targetBytes <= 0L) return
        scope.launch {
            runCatchingCancellable {
                val vcm = videoCacheManagerProvider()
                val stream = repository.resolveEpisodeStream(nextEpisodeKey, animeId)
                if (stream.videoSources.isNotEmpty()) {
                    vcm.probeMirrorsSpeed(stream.videoSources)
                    val ranked = vcm.mirrorHealthTracker.rankMirrors(stream.videoSources)
                    ranked.firstOrNull()?.let { primaryUrl ->
                        vcm.preloadVideoHead(
                            url = primaryUrl,
                            bytesToCache = targetBytes,
                            allSources = ranked,
                            opEndMs = DEFAULT_OP_END_MS,
                            episodeKey = nextEpisodeKey
                        )
                    }
                }
            }
        }
    }

    private suspend fun warmAnimeDetail(animeId: String, highPriority: Boolean): AnimeDetail? {
        if (!preloadAllowed()) return null
        if (animeId.isBlank() || !warmedAnimeDetails.add(animeId)) return null
        if (repository.hasFreshDetailInMemory(animeId)) {
            return runCatchingCancellable { repository.getAnimeDetail(animeId, forceRefresh = false) }.getOrNull()
        }
        val gate = if (highPriority) band0Gate else band1Gate
        return gate.withPermit {
            runCatchingCancellable { repository.getAnimeDetail(animeId, forceRefresh = false) }
                .onSuccess { detail ->
                    // Match EXACT AnimeDetailScreen decode dimensions (eliminates 100% RAM cache miss!)
                    detail.bannerUrl?.let {
                        preloadImages(listOf(it), maxCount = 1, targetSize = ImageTargetSize.HERO_BANNER)
                    }
                    if (detail.posterUrl.isNotBlank()) {
                        preloadImages(listOf(detail.posterUrl), maxCount = 1, targetSize = ImageTargetSize.DETAIL_POSTER)
                    }
                }
                .onFailure {
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
        estimatedDurationMs: Long = 0L
    ) {
        if (episodeKey.isBlank()) return
        val firstEpisodeWarm = warmedEpisodeStreams.add(episodeKey)
        if (!firstEpisodeWarm && videoBytes <= 0L) return

        band0Gate.withPermit {
            runCatchingCancellable {
                val vcm = videoCacheManagerProvider()
                val stream = repository.resolveEpisodeStream(episodeKey, animeId)
                val title = stream.animeTitle.ifBlank { animeTitle }
                val label = stream.episodeLabel.ifBlank { episodeLabel }
                val epNum = SkipTimesParser.parseEpisodeNumber(label)
                if (epNum != null && title.isNotBlank()) {
                    scope.launch {
                        runCatchingCancellable { skipTimesClientProvider().fetchSkipTimes(title, epNum) }
                    }
                }
                if (stream.videoSources.isNotEmpty()) {
                    vcm.probeMirrorsSpeed(stream.videoSources)
                    if (videoBytes > 0L) {
                        val ranked = vcm.mirrorHealthTracker.rankMirrors(stream.videoSources)
                        ranked.firstOrNull()?.let { primaryUrl ->
                            val durMs = if (estimatedDurationMs > 0L) estimatedDurationMs else DEFAULT_EP_DURATION_MS
                            vcm.preloadVideoHead(
                                url = primaryUrl,
                                bytesToCache = videoBytes,
                                allSources = ranked,
                                resumePositionMs = resumePositionMs,
                                estimatedDurationMs = durMs,
                                opEndMs = DEFAULT_OP_END_MS,
                                episodeKey = episodeKey
                            )
                        }
                    }
                }
            }.onFailure {
                warmedEpisodeStreams.remove(episodeKey)
            }
        }
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
                warmedSearchQueries.clear()
                lastHomeEcosystemSignature = null
                lastWarmedTopContinueKey = null
                focusPreloadJob = null
                detailPreloadJob = null
                homeEcosystemJob = null
                suspended = false
            }
        }
    }

    companion object {
        private const val DEFAULT_EP_DURATION_MS = 24L * 60_000L
        private const val DEFAULT_OP_END_MS = 85_000L
        private const val BAND1_STARTUP_GRACE_MS = 450L
        private const val USER_YIELD_MAX_WAIT_MS = 6_000L
    }
}
