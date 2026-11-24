package me.animeheaven.tv.data.network

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import me.animeheaven.tv.data.cache.LruCacheMap
import me.animeheaven.tv.data.local.WebCacheDao
import me.animeheaven.tv.data.local.WebCacheEntity
import me.animeheaven.tv.data.model.AnimeArtwork
import me.animeheaven.tv.data.model.ArtworkLookupRequest
import me.animeheaven.tv.data.model.ArtworkPriority
import me.animeheaven.tv.data.model.ArtworkSlot
import me.animeheaven.tv.data.runCatchingCancellable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class AnimeArtworkResolver(
    baseClient: OkHttpClient,
    private val database: WebCacheDao,
    private val scope: CoroutineScope,
    private val dataSaver: () -> Boolean = { false }
) {
    private val client = baseClient.newBuilder().cache(null)
        .callTimeout(9, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS).build()
    private data class Cached(val artwork: AnimeArtwork, val fetchedAt: Long)
    private data class Lookup(val request: ArtworkLookupRequest, val epoch: Long, val result: CompletableDeferred<AnimeArtwork?>)
    private val cache = LruCacheMap<String, Cached>(512)
    private val queue = Channel<Lookup>(128)
    private val baseFlights = ConcurrentHashMap<String, Deferred<AnimeArtwork?>>()
    private val providerFlights = ConcurrentHashMap<String, Deferred<AnimeArtwork?>>()
    private val persistence = Mutex()
    private val zipPermits = Semaphore(3)
    private val _updates = MutableSharedFlow<String>(extraBufferCapacity = 64)
    internal val updates = _updates.asSharedFlow()
    @Volatile private var generation = 0L
    @Volatile private var clearing = false

    private enum class Provider(val spacing: Long) { ANI_LIST(2050), ANI_ZIP(0), KITSU(1100), JIKAN(1100) }
    private class Limiter {
        val mutex = Mutex()
        var next = 0L
        var pausedUntil = 0L
    }
    private val limiters = Provider.entries.associateWith { Limiter() }

    init {
        scope.launch {
            for (first in queue) {
                delay(120)
                val group = buildList {
                    add(first)
                    repeat(7) { add(queue.tryReceive().getOrNull() ?: return@buildList) }
                }
                val active = group.filter { it.epoch == generation && !clearing }
                val matches = if (active.isNotEmpty()) {
                    val lookups = active.map { it.request }
                    fetch(Provider.ANI_LIST, "https://graphql.anilist.co", body = ArtworkProviders.aniListBody(lookups))
                        ?.let { ArtworkProviders.aniList(it, lookups) }.orEmpty()
                } else emptyList()
                active.forEachIndexed { index, lookup -> lookup.result.complete(matches.getOrNull(index)) }
                group.forEach { it.result.complete(null) }
            }
        }
    }

    fun memory(request: ArtworkLookupRequest): AnimeArtwork? =
        if (clearing) null else listOf(request.animeKey, request.titleKey).firstNotNullOfOrNull { key ->
            cache[key]?.takeIf { fresh(it.fetchedAt) }?.artwork
        }

    suspend fun cached(request: ArtworkLookupRequest): AnimeArtwork? {
        memory(request)?.let { return it }
        val epoch = generation
        for (key in setOf(request.animeKey, request.titleKey)) {
            val row = runCatchingCancellable { database.get(key) }.getOrNull()
            if (generation != epoch || clearing) return null
            if (row != null && fresh(row.fetchedAt)) {
                val decoded = ArtworkProviders.decode(row.payload) ?: continue
                cache[key] = Cached(decoded, row.fetchedAt)
                return decoded
            }
        }
        return null
    }

    suspend fun hydrateRecentFromDisk() {
        val epoch = generation
        val rows = runCatchingCancellable { database.getRecentArtwork(System.currentTimeMillis() - TTL) }.getOrNull().orEmpty()
        persistence.withLock {
            if (epoch != generation || clearing) return
            for (row in rows.asReversed()) {
                if (cache[row.cacheKey] == null) {
                    ArtworkProviders.decode(row.payload)?.let { cache[row.cacheKey] = Cached(it, row.fetchedAt) }
                }
            }
        }
    }

    fun trimMemory() = cache.trimToSize(64)

    suspend fun withCacheCleared(clear: suspend () -> Unit) {
        persistence.withLock {
            generation++
            clearing = true
            cache.clear()
            try { clear() } finally { clearing = false }
        }
        _updates.emit("")
    }

    suspend fun resolveMalId(title: String): Int? {
        val request = ArtworkLookupRequest("", title)
        cached(request)?.malId?.let { return it }
        val epoch = generation
        if (clearing || ArtworkTitleMatcher.normalize(title).isBlank()) return null
        val match = aniList(request, epoch) ?: return null
        persist(request, match, epoch)
        return match.malId.takeIf { epoch == generation }
    }

    suspend fun resolve(request: ArtworkLookupRequest, slot: ArtworkSlot): AnimeArtwork? {
        val epoch = generation
        val previous = cached(request)
        if (clearing || epoch != generation || request.priority == ArtworkPriority.CACHED_ONLY ||
            dataSaver() && request.priority < ArtworkPriority.FEATURED || ArtworkTitleMatcher.normalize(request.title).isBlank()
        ) return previous
        var art = previous ?: AnimeArtwork()
        if (art.anilistId == null) aniList(request, epoch)?.let { art = it.merge(art) }
        if (epoch != generation) return null

        val anilistId = art.anilistId
        if (request.priority == ArtworkPriority.HIGH && !art.aniZipChecked && anilistId != null &&
            (slot != ArtworkSlot.WIDE_BANNER || art.aniListBanner == null)
        ) {
            shared(providerFlights, "$epoch:zip:$anilistId") {
                zipPermits.withPermit {
                    fetch(Provider.ANI_ZIP, "https://api.ani.zip/mappings", mapOf("anilist_id" to "$anilistId"))
                }?.let(ArtworkProviders::aniZip)
            }?.let { art = art.merge(it) }
        }
        if (epoch != generation) return null
        val missingPortrait = slot == ArtworkSlot.PORTRAIT_2_3 && art.aniZipPoster == null && art.aniListPoster == null
        val missingWide = slot == ArtworkSlot.WIDE_BANNER && art.aniListBanner == null
        val missingLandscape = slot == ArtworkSlot.LANDSCAPE_16_9 && art.landscapeFanartUrl == null && art.trailerId == null
        if (!art.kitsuChecked && (missingPortrait || missingWide || missingLandscape && art.aniListBanner == null)) {
            shared(providerFlights, "$epoch:kitsu:${request.titleKey}") {
                fetch(Provider.KITSU, "https://kitsu.app/api/edge/anime", mapOf("filter[text]" to request.title, "page[limit]" to "5"))
                    ?.let { ArtworkProviders.kitsu(it, request) }
            }?.let { art = art.merge(it) }
        }
        if (epoch != generation) return null
        if (!art.jikanChecked && (missingPortrait && art.kitsuPoster == null || missingLandscape)) {
            val id = art.malId
            shared(providerFlights, "$epoch:jikan:${id ?: request.titleKey}") {
                val url = "https://api.jikan.moe/v4/anime"
                (if (id != null) fetch(Provider.JIKAN, "$url/$id")
                else fetch(Provider.JIKAN, url, mapOf("q" to request.title, "limit" to "5")))
                    ?.let { ArtworkProviders.jikan(it, request) }
            }?.let { art = art.merge(it) }
        }
        if (epoch != generation || !art.hasArtwork()) return previous
        return if (art != previous) persist(request, art, epoch) else art
    }

    private suspend fun persist(request: ArtworkLookupRequest, value: AnimeArtwork, epoch: Long): AnimeArtwork? =
        persistence.withLock {
            if (clearing || epoch != generation) return@withLock null
            val merged = cached(request)?.let(value::merge) ?: value
            val payload = ArtworkProviders.encode(merged)
            val now = System.currentTimeMillis()
            for (key in setOf(request.animeKey, request.titleKey)) {
                cache[key] = Cached(merged, now)
                runCatchingCancellable { database.upsert(WebCacheEntity(key, payload, now)) }
                _updates.tryEmit(key)
            }
            merged
        }

    private suspend fun aniList(request: ArtworkLookupRequest, epoch: Long): AnimeArtwork? =
        shared(baseFlights, "$epoch:${request.titleKey}:${ArtworkTitleMatcher.normalize(request.japaneseTitle)}") {
            val result = CompletableDeferred<AnimeArtwork?>()
            if (queue.trySend(Lookup(request, epoch, result)).isSuccess) result.await() else null
        }

    private suspend fun shared(
        flights: ConcurrentHashMap<String, Deferred<AnimeArtwork?>>,
        key: String,
        operation: suspend () -> AnimeArtwork?
    ): AnimeArtwork? {
        val task = flights.computeIfAbsent(key) {
            scope.async(start = CoroutineStart.LAZY) { operation() }
        }
        task.invokeOnCompletion { flights.remove(key, task) }
        return task.await()
    }

    private suspend fun fetch(
        provider: Provider,
        endpoint: String,
        params: Map<String, String> = emptyMap(),
        body: String? = null
    ): String? {
        val limiter = limiters.getValue(provider)
        val wait = limiter.mutex.withLock {
            val now = monotonicMs()
            if (clearing || limiter.pausedUntil > now) return null
            val start = maxOf(limiter.next, now)
            limiter.next = start + provider.spacing
            start - now
        }
        if (wait > 0) delay(wait)
        if (limiter.mutex.withLock { clearing || limiter.pausedUntil > monotonicMs() }) return null
        val url = endpoint.toHttpUrl().newBuilder().apply { params.forEach { (key, value) -> addQueryParameter(key, value) } }.build()
        val request = Request.Builder().url(url).header("Accept", "application/json").apply {
            if (body != null) post(body.toRequestBody(JSON))
        }.build()
        val response = runCatchingCancellable {
            client.readResponse(request) {
                val limited = it.code == 429 || provider == Provider.ANI_LIST && it.header("X-RateLimit-Remaining") == "0"
                val data = when {
                    it.isSuccessful -> it.body?.string()
                    it.code == 404 && provider == Provider.ANI_ZIP -> """{"images":[]}"""
                    else -> null
                }
                (if (limited) retryMillis(it.header("Retry-After")) else 0L) to data
            }
        }.getOrNull() ?: return null
        if (response.first > 0) limiter.mutex.withLock {
            limiter.pausedUntil = maxOf(limiter.pausedUntil, monotonicMs() + response.first)
        }
        return response.second
    }

    private fun fresh(timestamp: Long) = System.currentTimeMillis() - timestamp < TTL

    companion object {
        private val JSON = "application/json".toMediaType()
        private const val TTL = 14 * 86_400_000L
        private fun monotonicMs() = TimeUnit.NANOSECONDS.toMillis(System.nanoTime())
        internal fun retryMillis(header: String?, now: Long = System.currentTimeMillis()): Long {
            header?.toLongOrNull()?.let { return it.coerceIn(1, Long.MAX_VALUE / 2000) * 1000 }
            if (header != null) {
                val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }
                runCatching { format.parse(header)?.time }.getOrNull()?.let { return (it - now).coerceAtLeast(1000) }
            }
            return 60_000
        }
    }
}
