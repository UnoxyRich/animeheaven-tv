package me.animeheaven.tv.data.repository

import java.io.File
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.animeheaven.tv.data.cache.CacheSnapshotCodec
import me.animeheaven.tv.data.cache.LruCacheMap
import me.animeheaven.tv.data.local.AppDatabase
import me.animeheaven.tv.data.local.BookmarkEntity
import me.animeheaven.tv.data.local.SearchHistoryEntity
import me.animeheaven.tv.data.local.WatchProgressEntity
import me.animeheaven.tv.data.local.WebCacheEntity
import me.animeheaven.tv.data.model.AnimeCard
import me.animeheaven.tv.data.model.AnimeDetail
import me.animeheaven.tv.data.model.ContinueWatchingItem
import me.animeheaven.tv.data.model.EpisodeStream
import me.animeheaven.tv.data.model.FastSearchItem
import me.animeheaven.tv.data.model.HomeFeed
import me.animeheaven.tv.data.network.AnimeHeavenClient
import me.animeheaven.tv.data.parser.AnimeHeavenParser

data class CachedEntry<T>(
    val value: T,
    val fetchedAt: Long
) {
    fun isStale(softTtlMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean =
        nowMs - fetchedAt >= softTtlMs
}

sealed interface CachedResource<out T> {
    data object Loading : CachedResource<Nothing>
    data class Data<T>(val data: T, val isRefreshing: Boolean = false) : CachedResource<T>
    data class Error(val message: String) : CachedResource<Nothing>
}

/**
 * Cancellation-safe request coalescer: launches shared fetches inside an independent [scope] so
 * cancelling one caller (e.g. a background preloader or D-pad focus job) never fails another
 * foreground screen awaiting the same key.
 */
class RequestCoalescer<V>(
    private val scope: CoroutineScope
) {
    private val inFlight = ConcurrentHashMap<String, Deferred<V>>()

    suspend fun getOrFetch(key: String, block: suspend () -> V): V {
        val deferred = inFlight.computeIfAbsent(key) {
            scope.async {
                try {
                    block()
                } finally {
                    inFlight.remove(key)
                }
            }
        }
        return deferred.await()
    }
}

class AnimeHeavenRepository(
    private val client: AnimeHeavenClient,
    private val database: AppDatabase,
    private val snapshotDir: File? = null
) {
    private val bookmarkDao = database.bookmarkDao()
    private val watchProgressDao = database.watchProgressDao()
    private val searchHistoryDao = database.searchHistoryDao()
    private val webCacheDao = database.webCacheDao()

    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requestCoalescer = RequestCoalescer<String>(repositoryScope)
    private val pendingPersistJobs = ConcurrentHashMap<String, Job>()

    @Volatile
    private var cachedHomeFeed: CachedEntry<HomeFeed>? = null
    private val cachedCharts = LruCacheMap<String, CachedEntry<Pair<String, List<AnimeCard>>>>(MAX_CHART_CACHE)
    private val cachedGrids = LruCacheMap<String, CachedEntry<Pair<String, List<AnimeCard>>>>(MAX_GRID_CACHE)
    private val cachedDetails = LruCacheMap<String, CachedEntry<AnimeDetail>>(MAX_DETAIL_CACHE)
    private val cachedStreams = LruCacheMap<String, CachedEntry<EpisodeStream>>(MAX_STREAM_CACHE)
    private val cachedFastSearch = LruCacheMap<String, CachedEntry<List<FastSearchItem>>>(MAX_FAST_SEARCH_CACHE)

    private val _seasonHeader = MutableStateFlow<Pair<String?, String?>?>(null)
    val seasonHeader: StateFlow<Pair<String?, String?>?> = _seasonHeader.asStateFlow()

    fun peekCachedHomeFeed(): HomeFeed? = cachedHomeFeed?.value

    fun hasFreshDetailInMemory(animeId: String): Boolean {
        val entry = cachedDetails[animeId] ?: return false
        return !entry.isStale(TTL_DETAIL_MS)
    }

    fun hasFreshChartInMemory(cacheKey: String, ttlMs: Long = TTL_CHART_MS): Boolean {
        val entry = cachedCharts[cacheKey] ?: return false
        return !entry.isStale(ttlMs)
    }

    fun hydrateHomeFastSync(): HomeFeed? {
        cachedHomeFeed?.value?.let { return it }
        val file = snapshotDir?.let { File(it, HOME_SNAPSHOT_FILENAME) } ?: return null
        if (!file.exists()) return null
        return runCatching {
            val content = file.readText()
            val nl = content.indexOf('\n')
            if (nl <= 0) return@runCatching null
            val fetchedAt = content.substring(0, nl).toLongOrNull() ?: return@runCatching null
            val json = content.substring(nl + 1)
            val feed = CacheSnapshotCodec.decodeHomeFeed(json) ?: return@runCatching null
            cachedHomeFeed = CachedEntry(feed, fetchedAt)
            updateSeasonHeader(feed)
            feed
        }.getOrNull()
    }

    private fun saveHomeFastSnapshotAtomic(fetchedAt: Long, json: String) {
        val dir = snapshotDir ?: return
        runCatching {
            val tmp = File(dir, "$HOME_SNAPSHOT_FILENAME.tmp")
            val target = File(dir, HOME_SNAPSHOT_FILENAME)
            tmp.writeText("$fetchedAt\n$json")
            if (!tmp.renameTo(target)) {
                target.writeText("$fetchedAt\n$json")
                tmp.delete()
            }
        }
    }

    fun trimMemory(aggressive: Boolean) {
        if (aggressive) {
            cachedDetails.trimToSize(8)
            cachedStreams.trimToSize(6)
            cachedCharts.trimToSize(4)
            cachedGrids.trimToSize(2)
            cachedFastSearch.trimToSize(8)
        } else {
            cachedDetails.trimToSize(24)
            cachedStreams.trimToSize(16)
            cachedCharts.trimToSize(10)
            cachedGrids.trimToSize(8)
            cachedFastSearch.trimToSize(24)
        }
    }

    fun observeHomeFeed(forceRefresh: Boolean = false): Flow<CachedResource<HomeFeed>> =
        observeWithCache(
            cacheKey = KEY_HOME,
            softTtlMs = TTL_HOME_MS,
            forceRefresh = forceRefresh,
            memoryGet = { cachedHomeFeed },
            memoryPut = {
                cachedHomeFeed = it
                updateSeasonHeader(it.value)
            },
            networkFetch = { client.fetchHtml("") },
            parser = { AnimeHeavenParser.parseHome(it) },
            serializer = CacheSnapshotCodec::encodeHomeFeed,
            deserializer = CacheSnapshotCodec::decodeHomeFeed
        )

    fun observeNewEpisodes(forceRefresh: Boolean = false): Flow<CachedResource<Pair<String, List<AnimeCard>>>> =
        observeChart("chart:new", "new.php", isPopularRank = false, softTtlMs = TTL_CHART_MS, forceRefresh = forceRefresh)

    fun observePopularToday(forceRefresh: Boolean = false): Flow<CachedResource<Pair<String, List<AnimeCard>>>> =
        observeChart("chart:popular", "popular.php", isPopularRank = true, softTtlMs = TTL_CHART_MS, forceRefresh = forceRefresh)

    fun observeSeasonal(path: String, forceRefresh: Boolean = false): Flow<CachedResource<Pair<String, List<AnimeCard>>>> =
        observeChart("chart:$path", path, isPopularRank = false, softTtlMs = TTL_SEASONAL_MS, forceRefresh = forceRefresh)

    fun observeByTag(tag: String, forceRefresh: Boolean = false): Flow<CachedResource<Pair<String, List<AnimeCard>>>> {
        val encoded = URLEncoder.encode(tag, "UTF-8")
        val key = "tag:${tag.lowercase()}"
        return observeWithCache(
            cacheKey = key,
            softTtlMs = TTL_SEASONAL_MS,
            forceRefresh = forceRefresh,
            memoryGet = { cachedGrids[key] },
            memoryPut = { cachedGrids[key] = it },
            networkFetch = { client.fetchHtml("tags.php?tag=$encoded") },
            parser = { AnimeHeavenParser.parseSimilarGrid(it) },
            serializer = CacheSnapshotCodec::encodeChart,
            deserializer = CacheSnapshotCodec::decodeChart
        )
    }

    fun observeAnimeDetail(
        animeId: String,
        forceRefresh: Boolean = false
    ): Flow<CachedResource<AnimeDetail>> =
        observeWithCache(
            cacheKey = "detail:$animeId",
            softTtlMs = TTL_DETAIL_MS,
            forceRefresh = forceRefresh,
            memoryGet = { cachedDetails[animeId] },
            memoryPut = { cachedDetails[animeId] = it },
            networkFetch = { client.fetchHtml("anime.php?$animeId") },
            parser = { AnimeHeavenParser.parseAnimeDetail(animeId, it) },
            serializer = CacheSnapshotCodec::encodeAnimeDetail,
            deserializer = CacheSnapshotCodec::decodeAnimeDetail
        )

    private fun observeChart(
        cacheKey: String,
        path: String,
        isPopularRank: Boolean,
        softTtlMs: Long,
        forceRefresh: Boolean
    ): Flow<CachedResource<Pair<String, List<AnimeCard>>>> =
        observeWithCache(
            cacheKey = cacheKey,
            softTtlMs = softTtlMs,
            forceRefresh = forceRefresh,
            memoryGet = { cachedCharts[cacheKey] },
            memoryPut = { cachedCharts[cacheKey] = it },
            networkFetch = { client.fetchHtml(path) },
            parser = { AnimeHeavenParser.parseChartPage(it, isPopularRank = isPopularRank) },
            serializer = CacheSnapshotCodec::encodeChart,
            deserializer = CacheSnapshotCodec::decodeChart
        )

    suspend fun getHomeFeed(forceRefresh: Boolean = false): HomeFeed =
        fetchOrCache(
            cacheKey = KEY_HOME,
            softTtlMs = TTL_HOME_MS,
            forceRefresh = forceRefresh,
            memoryGet = { cachedHomeFeed },
            memoryPut = {
                cachedHomeFeed = it
                updateSeasonHeader(it.value)
            },
            networkFetch = { client.fetchHtml("") },
            parser = { AnimeHeavenParser.parseHome(it) },
            serializer = CacheSnapshotCodec::encodeHomeFeed,
            deserializer = CacheSnapshotCodec::decodeHomeFeed
        )

    suspend fun getNewEpisodes(forceRefresh: Boolean = false): Pair<String, List<AnimeCard>> =
        fetchOrCache(
            cacheKey = "chart:new",
            softTtlMs = TTL_CHART_MS,
            forceRefresh = forceRefresh,
            memoryGet = { cachedCharts["chart:new"] },
            memoryPut = { cachedCharts["chart:new"] = it },
            networkFetch = { client.fetchHtml("new.php") },
            parser = { AnimeHeavenParser.parseChartPage(it, isPopularRank = false) },
            serializer = CacheSnapshotCodec::encodeChart,
            deserializer = CacheSnapshotCodec::decodeChart
        )

    suspend fun getPopularToday(forceRefresh: Boolean = false): Pair<String, List<AnimeCard>> =
        fetchOrCache(
            cacheKey = "chart:popular",
            softTtlMs = TTL_CHART_MS,
            forceRefresh = forceRefresh,
            memoryGet = { cachedCharts["chart:popular"] },
            memoryPut = { cachedCharts["chart:popular"] = it },
            networkFetch = { client.fetchHtml("popular.php") },
            parser = { AnimeHeavenParser.parseChartPage(it, isPopularRank = true) },
            serializer = CacheSnapshotCodec::encodeChart,
            deserializer = CacheSnapshotCodec::decodeChart
        )

    suspend fun getSeasonal(path: String, forceRefresh: Boolean = false): Pair<String, List<AnimeCard>> =
        fetchOrCache(
            cacheKey = "chart:$path",
            softTtlMs = TTL_SEASONAL_MS,
            forceRefresh = forceRefresh,
            memoryGet = { cachedCharts["chart:$path"] },
            memoryPut = { cachedCharts["chart:$path"] = it },
            networkFetch = { client.fetchHtml(path) },
            parser = { AnimeHeavenParser.parseChartPage(it, isPopularRank = false) },
            serializer = CacheSnapshotCodec::encodeChart,
            deserializer = CacheSnapshotCodec::decodeChart
        )

    suspend fun getByTag(tag: String, forceRefresh: Boolean = false): Pair<String, List<AnimeCard>> {
        val encoded = URLEncoder.encode(tag, "UTF-8")
        val key = "tag:${tag.lowercase()}"
        return fetchOrCache(
            cacheKey = key,
            softTtlMs = TTL_SEASONAL_MS,
            forceRefresh = forceRefresh,
            memoryGet = { cachedGrids[key] },
            memoryPut = { cachedGrids[key] = it },
            networkFetch = { client.fetchHtml("tags.php?tag=$encoded") },
            parser = { AnimeHeavenParser.parseSimilarGrid(it) },
            serializer = CacheSnapshotCodec::encodeChart,
            deserializer = CacheSnapshotCodec::decodeChart
        )
    }

    suspend fun fastSearch(query: String): List<FastSearchItem> {
        val trimmed = query.trim()
        val key = "fast:${trimmed.lowercase()}"
        return fetchOrCache(
            cacheKey = key,
            softTtlMs = TTL_SEARCH_MS,
            forceRefresh = false,
            memoryGet = { cachedFastSearch[key] },
            memoryPut = { cachedFastSearch[key] = it },
            networkFetch = { client.fetchFastSearch(trimmed) },
            parser = { AnimeHeavenParser.parseFastSearch(it) },
            serializer = CacheSnapshotCodec::encodeFastSearch,
            deserializer = CacheSnapshotCodec::decodeFastSearch
        )
    }

    suspend fun fullSearch(query: String): Pair<String, List<AnimeCard>> {
        val trimmed = query.trim()
        val encoded = URLEncoder.encode(trimmed, "UTF-8")
        val key = "search:${trimmed.lowercase()}"
        return fetchOrCache(
            cacheKey = key,
            softTtlMs = TTL_SEARCH_MS,
            forceRefresh = false,
            memoryGet = { cachedGrids[key] },
            memoryPut = { cachedGrids[key] = it },
            networkFetch = { client.fetchHtml("search.php?s=$encoded") },
            parser = { AnimeHeavenParser.parseSimilarGrid(it) },
            serializer = CacheSnapshotCodec::encodeChart,
            deserializer = CacheSnapshotCodec::decodeChart
        )
    }

    suspend fun getRandomAnimeId(): String = client.fetchRandomAnimeId()

    suspend fun getAnimeDetail(animeId: String, forceRefresh: Boolean = false): AnimeDetail =
        fetchOrCache(
            cacheKey = "detail:$animeId",
            softTtlMs = TTL_DETAIL_MS,
            forceRefresh = forceRefresh,
            memoryGet = { cachedDetails[animeId] },
            memoryPut = { cachedDetails[animeId] = it },
            networkFetch = { client.fetchHtml("anime.php?$animeId") },
            parser = { AnimeHeavenParser.parseAnimeDetail(animeId, it) },
            serializer = CacheSnapshotCodec::encodeAnimeDetail,
            deserializer = CacheSnapshotCodec::decodeAnimeDetail
        )

    suspend fun resolveEpisodeStream(
        episodeKey: String,
        animeId: String?,
        forceRefresh: Boolean = false
    ): EpisodeStream {
        val stream = fetchOrCache(
            cacheKey = "gate:$episodeKey",
            softTtlMs = TTL_GATE_MS,
            forceRefresh = forceRefresh,
            memoryGet = { cachedStreams[episodeKey] },
            memoryPut = { cachedStreams[episodeKey] = it },
            networkFetch = { client.fetchGateHtml(episodeKey, animeId) },
            parser = { AnimeHeavenParser.parseGatePage(episodeKey, it) },
            serializer = CacheSnapshotCodec::encodeEpisodeStream,
            deserializer = CacheSnapshotCodec::decodeEpisodeStream
        )
        // Never stick with empty sources from an invalid/expired cache entry
        if (stream.videoSources.isEmpty() && !forceRefresh) {
            invalidateGateStream(episodeKey)
            return resolveEpisodeStream(episodeKey, animeId, forceRefresh = true)
        }
        return stream
    }

    /**
     * Hydrates HomeFeed, catalog charts, recent anime details, and Continue Watching from Room
     * JSON snapshots immediately at startup so Frame 1 renders with 0ms Jsoup delay.
     */
    suspend fun warmUpFromDisk(): Pair<HomeFeed?, List<ContinueWatchingItem>> =
        withContext(Dispatchers.IO) {
            coroutineScope {
                val pagesDeferred = async {
                    webCacheDao.getByKeys(listOf(KEY_HOME, "chart:new", "chart:popular"))
                }
                val detailsDeferred = async {
                    webCacheDao.getRecentDetails(12)
                }
                val progressDeferred = async {
                    watchProgressDao.getAllOnce()
                }

                val pageRows = pagesDeferred.await()
                var warmedHome: HomeFeed? = cachedHomeFeed?.value
                for (row in pageRows) {
                    when (row.cacheKey) {
                        KEY_HOME -> {
                            if (cachedHomeFeed == null) {
                                val feed = decodeDbRow(
                                    row = row,
                                    deserializer = CacheSnapshotCodec::decodeHomeFeed,
                                    parser = AnimeHeavenParser::parseHome
                                )
                                if (feed != null) {
                                    warmedHome = feed
                                    cachedHomeFeed = CachedEntry(feed, row.fetchedAt)
                                    updateSeasonHeader(feed)
                                    if (snapshotDir != null && !File(snapshotDir, HOME_SNAPSHOT_FILENAME).exists()) {
                                        saveHomeFastSnapshotAtomic(
                                            row.fetchedAt,
                                            row.parsedJson ?: CacheSnapshotCodec.encodeHomeFeed(feed)
                                        )
                                    }
                                }
                            }
                        }
                        "chart:new" -> {
                            val chart = decodeDbRow(
                                row = row,
                                deserializer = CacheSnapshotCodec::decodeChart,
                                parser = { AnimeHeavenParser.parseChartPage(it, isPopularRank = false) }
                            )
                            if (chart != null) {
                                cachedCharts["chart:new"] = CachedEntry(chart, row.fetchedAt)
                            }
                        }
                        "chart:popular" -> {
                            val chart = decodeDbRow(
                                row = row,
                                deserializer = CacheSnapshotCodec::decodeChart,
                                parser = { AnimeHeavenParser.parseChartPage(it, isPopularRank = true) }
                            )
                            if (chart != null) {
                                cachedCharts["chart:popular"] = CachedEntry(chart, row.fetchedAt)
                            }
                        }
                    }
                }

                val seasonPath = warmedHome?.seasonPath?.takeIf { it.isNotBlank() }
                if (seasonPath != null) {
                    webCacheDao.get("chart:$seasonPath")?.let { row ->
                        val chart = decodeDbRow(
                            row = row,
                            deserializer = CacheSnapshotCodec::decodeChart,
                            parser = { AnimeHeavenParser.parseChartPage(it, isPopularRank = false) }
                        )
                        if (chart != null) {
                            cachedCharts["chart:$seasonPath"] = CachedEntry(chart, row.fetchedAt)
                        }
                    }
                }

                for (detailRow in detailsDeferred.await()) {
                    val animeId = detailRow.cacheKey.removePrefix("detail:")
                    if (animeId.isNotBlank() && !cachedDetails.containsKey(animeId)) {
                        decodeDbRow(
                            row = detailRow,
                            deserializer = CacheSnapshotCodec::decodeAnimeDetail,
                            parser = { AnimeHeavenParser.parseAnimeDetail(animeId, it) }
                        )?.let { detail ->
                            cachedDetails[animeId] = CachedEntry(detail, detailRow.fetchedAt)
                        }
                    }
                }

                val continueItems = computeContinueWatchingList(progressDeferred.await())
                warmedHome to continueItems
            }
        }

    suspend fun getBookmarkIdsSnapshot(): List<String> = withContext(Dispatchers.IO) {
        bookmarkDao.getAllIdsOnce()
    }

    suspend fun getCachedPageCount(): Int = withContext(Dispatchers.IO) {
        webCacheDao.countCachedPages()
    }

    private fun updateSeasonHeader(feed: HomeFeed) {
        val label = feed.seasonLabel
        val path = feed.seasonPath
        if (!label.isNullOrBlank() || !path.isNullOrBlank()) {
            _seasonHeader.value = label to path
        }
    }

    private fun computeContinueWatchingList(allRows: List<WatchProgressEntity>): List<ContinueWatchingItem> {
        val latestByAnime = linkedMapOf<String, WatchProgressEntity>()
        for (row in allRows) {
            if (row.animeId.isNotBlank() && !latestByAnime.containsKey(row.animeId)) {
                latestByAnime[row.animeId] = row
            }
        }
        return latestByAnime.values.mapNotNull { buildContinueWatchingItem(it) }
    }

    suspend fun invalidateGateStream(episodeKey: String) {
        cachedStreams.remove(episodeKey)
        withContext(Dispatchers.IO) {
            webCacheDao.delete("gate:$episodeKey")
        }
    }

    suspend fun getWebCacheBytes(): Long = withContext(Dispatchers.IO) {
        webCacheDao.totalPayloadBytes()
    }

    suspend fun clearWebPageCache() {
        cachedHomeFeed = null
        cachedCharts.clear()
        cachedGrids.clear()
        cachedDetails.clear()
        cachedStreams.clear()
        cachedFastSearch.clear()
        withContext(Dispatchers.IO) {
            runCatching { snapshotDir?.let { File(it, HOME_SNAPSHOT_FILENAME).delete() } }
            webCacheDao.clearAll()
        }
    }

    suspend fun pruneExpiredCache() = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - MAX_DISK_RETENTION_MS
        webCacheDao.pruneOlderThan(cutoff)
    }

    private fun <T : Any> persistCacheAsync(
        cacheKey: String,
        html: String,
        parsed: T,
        fetchedAt: Long,
        serializer: ((T) -> String)?
    ) {
        pendingPersistJobs[cacheKey]?.cancel()
        val job = repositoryScope.launch {
            runCatching {
                val json = serializer?.invoke(parsed)
                // When structured parsedJson exists, skip GZIP+Base64 raw HTML completely
                // (saves 100% of compression CPU and 60-80% of SQLite web_cache size).
                val storedPayload = if (json != null) "" else CacheSnapshotCodec.compressText(html)
                webCacheDao.upsert(WebCacheEntity(cacheKey, storedPayload, fetchedAt, json))
                if (cacheKey == KEY_HOME && json != null) {
                    saveHomeFastSnapshotAtomic(fetchedAt, json)
                }
            }
        }
        pendingPersistJobs[cacheKey] = job
        job.invokeOnCompletion { pendingPersistJobs.remove(cacheKey, job) }
    }

    private fun <T : Any> observeWithCache(
        cacheKey: String,
        softTtlMs: Long,
        forceRefresh: Boolean,
        memoryGet: () -> CachedEntry<T>?,
        memoryPut: (CachedEntry<T>) -> Unit,
        networkFetch: suspend () -> String,
        parser: (String) -> T,
        serializer: ((T) -> String)? = null,
        deserializer: ((String) -> T?)? = null
    ): Flow<CachedResource<T>> = flow {
        val now = System.currentTimeMillis()
        var emittedData: T? = null

        val memEntry = memoryGet()
        if (memEntry != null) {
            val stale = forceRefresh || memEntry.isStale(softTtlMs, now)
            emittedData = memEntry.value
            emit(CachedResource.Data(memEntry.value, isRefreshing = stale))
            if (!stale) return@flow
        } else {
            val dbRow = withContext(Dispatchers.IO) { webCacheDao.get(cacheKey) }
            if (dbRow != null) {
                val parsed = withContext(Dispatchers.Default) {
                    decodeDbRow(dbRow, deserializer, parser)
                }
                if (parsed != null) {
                    val entry = CachedEntry(parsed, dbRow.fetchedAt)
                    memoryPut(entry)
                    val stale = forceRefresh || entry.isStale(softTtlMs, now)
                    emittedData = parsed
                    emit(CachedResource.Data(parsed, isRefreshing = stale))
                    if (!stale) return@flow
                }
            }
        }

        if (emittedData == null) {
            emit(CachedResource.Loading)
        }

        runCatching {
            val html = requestCoalescer.getOrFetch(cacheKey, networkFetch)
            val parsed = withContext(Dispatchers.Default) { parser(html) }
            val fetchedAt = System.currentTimeMillis()
            memoryPut(CachedEntry(parsed, fetchedAt))
            persistCacheAsync(cacheKey, html, parsed, fetchedAt, serializer)
            parsed
        }.onSuccess { fresh ->
            emit(CachedResource.Data(fresh, isRefreshing = false))
        }.onFailure { err ->
            if (emittedData == null) {
                emit(CachedResource.Error(err.message ?: "Network error"))
            } else {
                emit(CachedResource.Data(emittedData, isRefreshing = false))
            }
        }
    }

    private suspend fun <T : Any> fetchOrCache(
        cacheKey: String,
        softTtlMs: Long,
        forceRefresh: Boolean,
        memoryGet: () -> CachedEntry<T>?,
        memoryPut: (CachedEntry<T>) -> Unit,
        networkFetch: suspend () -> String,
        parser: (String) -> T,
        serializer: ((T) -> String)? = null,
        deserializer: ((String) -> T?)? = null
    ): T {
        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            val mem = memoryGet()
            if (mem != null && !mem.isStale(softTtlMs, now)) {
                return mem.value
            }
            val dbRow = withContext(Dispatchers.IO) { webCacheDao.get(cacheKey) }
            if (dbRow != null && (now - dbRow.fetchedAt) < softTtlMs) {
                val parsed = withContext(Dispatchers.Default) {
                    decodeDbRow(dbRow, deserializer, parser)
                }
                if (parsed != null) {
                    memoryPut(CachedEntry(parsed, dbRow.fetchedAt))
                    return parsed
                }
            }
        }

        return try {
            val html = requestCoalescer.getOrFetch(cacheKey, networkFetch)
            val parsed = withContext(Dispatchers.Default) { parser(html) }
            val fetchedAt = System.currentTimeMillis()
            memoryPut(CachedEntry(parsed, fetchedAt))
            persistCacheAsync(cacheKey, html, parsed, fetchedAt, serializer)
            parsed
        } catch (err: Throwable) {
            // Fall back to stale memory/disk cache if offline
            memoryGet()?.let { return it.value }
            val dbRow = withContext(Dispatchers.IO) { webCacheDao.get(cacheKey) }
            if (dbRow != null) {
                val parsed = withContext(Dispatchers.Default) {
                    decodeDbRow(dbRow, deserializer, parser)
                }
                if (parsed != null) {
                    memoryPut(CachedEntry(parsed, dbRow.fetchedAt))
                    return parsed
                }
            }
            throw err
        }
    }

    private fun <T : Any> decodeDbRow(
        row: WebCacheEntity,
        deserializer: ((String) -> T?)?,
        parser: (String) -> T
    ): T? {
        val json = row.parsedJson
        if (!json.isNullOrBlank() && deserializer != null) {
            deserializer(json)?.let { return it }
        }
        if (row.payload.isBlank()) return null
        return runCatching {
            val html = CacheSnapshotCodec.decompressText(row.payload)
            parser(html)
        }.getOrNull()
    }

    // Bookmarks
    fun observeBookmarks(): Flow<List<BookmarkEntity>> = bookmarkDao.observeAll()

    fun observeBookmarkedIds(): Flow<Set<String>> =
        bookmarkDao.observeBookmarkedIds().map { it.toSet() }

    fun observeIsBookmarked(animeId: String): Flow<Boolean> =
        bookmarkDao.observeIsBookmarked(animeId)

    suspend fun toggleBookmark(
        animeId: String,
        title: String,
        coverUrl: String,
        lastEpisodeLabel: String
    ): Boolean {
        val exists = bookmarkDao.isBookmarked(animeId)
        if (exists) {
            bookmarkDao.deleteById(animeId)
            return false
        } else {
            bookmarkDao.upsert(
                BookmarkEntity(
                    animeId = animeId,
                    title = title,
                    coverUrl = coverUrl,
                    lastEpisodeLabel = lastEpisodeLabel,
                    addedAt = System.currentTimeMillis()
                )
            )
            return true
        }
    }

    suspend fun removeBookmark(animeId: String) {
        bookmarkDao.deleteById(animeId)
    }

    // Watch Progress & Continue -> Next Episode (SQLite-deduplicated per anime)
    fun observeContinueWatching(): Flow<List<ContinueWatchingItem>> {
        return watchProgressDao.observeLatestPerAnime().map(::computeContinueWatchingList)
    }

    fun observeAnimeProgressMap(animeId: String): Flow<Map<String, WatchProgressEntity>> {
        return watchProgressDao.observeByAnime(animeId).map { list ->
            list.associateBy { it.episodeKey }
        }
    }

    suspend fun getSavedPositionMs(episodeKey: String): Long {
        val saved = watchProgressDao.getByEpisodeKey(episodeKey) ?: return 0L
        return resolveResumePositionMs(saved.positionMs, saved.durationMs)
    }

    suspend fun saveWatchProgress(
        episodeKey: String,
        animeId: String,
        animeTitle: String,
        coverUrl: String,
        episodeLabel: String,
        positionMs: Long,
        durationMs: Long,
        nextEpisodeKey: String?,
        nextEpisodeLabel: String?,
        forceWatched: Boolean = false
    ) {
        if (animeId.isBlank()) return
        val watched = forceWatched || isEpisodeWatched(positionMs, durationMs)
        watchProgressDao.upsert(
            WatchProgressEntity(
                episodeKey = episodeKey,
                animeId = animeId,
                animeTitle = animeTitle,
                coverUrl = coverUrl,
                episodeLabel = episodeLabel,
                positionMs = positionMs.coerceAtLeast(0L),
                durationMs = durationMs.coerceAtLeast(0L),
                isWatched = watched,
                nextEpisodeKey = nextEpisodeKey,
                nextEpisodeLabel = nextEpisodeLabel,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun clearWatchHistory() {
        watchProgressDao.clearAll()
    }

    // Search History
    fun observeRecentSearches(): Flow<List<String>> =
        searchHistoryDao.observeRecent().map { list -> list.map { it.query } }

    suspend fun recordSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isNotEmpty()) {
            searchHistoryDao.upsert(
                SearchHistoryEntity(query = trimmed, searchedAt = System.currentTimeMillis())
            )
        }
    }

    suspend fun clearSearchHistory() {
        searchHistoryDao.clearAll()
    }

    companion object {
        const val MIN_RESUME_THRESHOLD_MS = 10_000L
        const val MAX_RESUME_RATIO = 0.92f
        const val WATCHED_RATIO = 0.85f

        private const val KEY_HOME = "home"
        private const val HOME_SNAPSHOT_FILENAME = "home_snapshot.v1.json"
        private const val MAX_CHART_CACHE = 24
        private const val MAX_GRID_CACHE = 24
        private const val MAX_DETAIL_CACHE = 64
        private const val MAX_STREAM_CACHE = 48
        private const val MAX_FAST_SEARCH_CACHE = 100

        val TTL_HOME_MS = TimeUnit.MINUTES.toMillis(5)
        val TTL_CHART_MS = TimeUnit.MINUTES.toMillis(5)
        val TTL_SEASONAL_MS = TimeUnit.MINUTES.toMillis(30)
        val TTL_SEARCH_MS = TimeUnit.MINUTES.toMillis(30)
        val TTL_DETAIL_MS = TimeUnit.MINUTES.toMillis(15)
        val TTL_GATE_MS = TimeUnit.MINUTES.toMillis(15)
        private val MAX_DISK_RETENTION_MS = TimeUnit.DAYS.toMillis(14)

        fun isEpisodeWatched(positionMs: Long, durationMs: Long): Boolean {
            if (durationMs <= 0L) return false
            return positionMs.toFloat() / durationMs.toFloat() >= WATCHED_RATIO
        }

        fun resolveResumePositionMs(positionMs: Long, durationMs: Long): Long {
            if (positionMs <= MIN_RESUME_THRESHOLD_MS) return 0L
            if (durationMs > 0L && positionMs.toFloat() / durationMs.toFloat() >= MAX_RESUME_RATIO) {
                return 0L
            }
            return positionMs
        }

        fun buildContinueWatchingItem(progress: WatchProgressEntity): ContinueWatchingItem? {
            return if (progress.isWatched) {
                val nextKey = progress.nextEpisodeKey?.takeIf { it.isNotBlank() } ?: return null
                ContinueWatchingItem(
                    animeId = progress.animeId,
                    animeTitle = progress.animeTitle,
                    coverUrl = progress.coverUrl,
                    targetEpisodeKey = nextKey,
                    targetEpisodeLabel = progress.nextEpisodeLabel ?: "Next Episode",
                    isNextEpisode = true,
                    resumePositionMs = 0L,
                    durationMs = 0L,
                    lastWatchedLabel = progress.episodeLabel
                )
            } else if (progress.positionMs > MIN_RESUME_THRESHOLD_MS) {
                ContinueWatchingItem(
                    animeId = progress.animeId,
                    animeTitle = progress.animeTitle,
                    coverUrl = progress.coverUrl,
                    targetEpisodeKey = progress.episodeKey,
                    targetEpisodeLabel = progress.episodeLabel,
                    isNextEpisode = false,
                    resumePositionMs = progress.positionMs,
                    durationMs = progress.durationMs,
                    lastWatchedLabel = progress.episodeLabel
                )
            } else {
                null
            }
        }
    }
}
