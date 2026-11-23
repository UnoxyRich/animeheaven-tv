package me.animeheaven.tv.data.network

import androidx.compose.runtime.Immutable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import me.animeheaven.tv.data.cache.LruCacheMap
import me.animeheaven.tv.data.local.WebCacheDao
import me.animeheaven.tv.data.local.WebCacheEntity
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

enum class SkipSegmentType {
    OP,
    ED
}

@Immutable
data class SkipSegment(
    val type: SkipSegmentType,
    val startMs: Long,
    val endMs: Long,
    val isExact: Boolean
)

@Immutable
data class EpisodeSkipTimes(
    val op: SkipSegment? = null,
    val ed: SkipSegment? = null
)

object SkipTimesParser {

    private val EP_INT_REGEX = Regex("""(\d+)""")
    private val ID_MAL_REGEX = Regex(""""idMal"\s*:\s*(\d+)""")
    private val FOUND_TRUE_REGEX = Regex(""""found"\s*:\s*true""", RegexOption.IGNORE_CASE)
    private val START_TIME_REGEX = Regex(""""startTime"\s*:\s*([0-9]+(?:\.[0-9]+)?)""")
    private val END_TIME_REGEX = Regex(""""endTime"\s*:\s*([0-9]+(?:\.[0-9]+)?)""")
    private val SKIP_TYPE_REGEX = Regex(""""skipType"\s*:\s*"([^"]+)"""")

    fun parseEpisodeNumber(episodeLabel: String): Int? =
        EP_INT_REGEX.find(episodeLabel)?.groupValues?.getOrNull(1)?.toIntOrNull()

    fun parseMalIdFromAniList(json: String): Int? =
        ID_MAL_REGEX.find(json)?.groupValues?.getOrNull(1)?.toIntOrNull()

    fun parseAniSkipResponse(json: String): EpisodeSkipTimes {
        if (!FOUND_TRUE_REGEX.containsMatchIn(json)) return EpisodeSkipTimes()
        val objects = extractResultObjects(json)

        var exactOp: SkipSegment? = null
        var mixedOp: SkipSegment? = null
        var exactEd: SkipSegment? = null
        var mixedEd: SkipSegment? = null

        for (obj in objects) {
            val startSec = START_TIME_REGEX.find(obj)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
                ?: continue
            val endSec = END_TIME_REGEX.find(obj)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
                ?: continue
            val skipType = SKIP_TYPE_REGEX.find(obj)?.groupValues?.getOrNull(1)?.lowercase()
                ?: continue

            val startMs = (startSec * 1000.0).toLong().coerceAtLeast(0L)
            val endMs = (endSec * 1000.0).toLong()
            if (endMs <= startMs) continue

            when (skipType) {
                "op" -> if (exactOp == null) {
                    exactOp = SkipSegment(SkipSegmentType.OP, startMs, endMs, isExact = true)
                }
                "mixed-op" -> if (mixedOp == null) {
                    mixedOp = SkipSegment(SkipSegmentType.OP, startMs, endMs, isExact = true)
                }
                "ed" -> if (exactEd == null) {
                    exactEd = SkipSegment(SkipSegmentType.ED, startMs, endMs, isExact = true)
                }
                "mixed-ed" -> if (mixedEd == null) {
                    mixedEd = SkipSegment(SkipSegmentType.ED, startMs, endMs, isExact = true)
                }
            }
        }

        return EpisodeSkipTimes(
            op = exactOp ?: mixedOp,
            ed = exactEd ?: mixedEd
        )
    }

    private fun extractResultObjects(json: String): List<String> {
        val resultsIdx = json.indexOf("\"results\"")
        if (resultsIdx < 0) return emptyList()
        val arrayStart = json.indexOf('[', startIndex = resultsIdx)
        if (arrayStart < 0) return emptyList()

        val objects = mutableListOf<String>()
        var depth = 0
        var objStart = -1
        var inString = false
        var escaped = false

        for (i in (arrayStart + 1) until json.length) {
            val ch = json[i]
            if (escaped) {
                escaped = false
                continue
            }
            if (ch == '\\' && inString) {
                escaped = true
                continue
            }
            if (ch == '"') {
                inString = !inString
                continue
            }
            if (inString) continue

            when (ch) {
                '{' -> {
                    if (depth == 0) objStart = i
                    depth++
                }
                '}' -> {
                    if (depth > 0) {
                        depth--
                        if (depth == 0 && objStart >= 0) {
                            objects.add(json.substring(objStart, i + 1))
                            objStart = -1
                        }
                    }
                }
                ']' -> if (depth == 0) break
            }
        }
        return objects
    }
}

class SkipTimesClient(
    baseOkHttpClient: OkHttpClient,
    private val webCacheDao: WebCacheDao? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Room owns durable skip-time caching, so disable OkHttp's HTTP disk cache here
    private val fastClient: OkHttpClient = baseOkHttpClient.newBuilder()
        .cache(null)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    // Bounded LRU caches: MAL ID by normalized anime title (-1 for confirmed misses)
    private val malIdCache = LruCacheMap<String, Int>(MAX_MAL_ID_CACHE)
    private val skipTimesMemCache = LruCacheMap<String, EpisodeSkipTimes>(MAX_SKIP_CACHE)
    private val inFlightMalIds = ConcurrentHashMap<String, Deferred<Int?>>()

    fun clearMemoryCache() {
        malIdCache.clear()
        skipTimesMemCache.clear()
    }

    fun trimMemory(aggressive: Boolean) {
        if (aggressive) {
            skipTimesMemCache.clear()
            malIdCache.trimToSize(64)
        } else {
            skipTimesMemCache.trimToSize(48)
            malIdCache.trimToSize(128)
        }
    }

    suspend fun fetchSkipTimes(animeTitle: String, episodeNumber: Int): EpisodeSkipTimes {
        if (animeTitle.isBlank() || episodeNumber <= 0) return EpisodeSkipTimes()
        val normalized = animeTitle.trim().lowercase()
        val memKey = "$normalized#$episodeNumber"
        skipTimesMemCache[memKey]?.let { return it }

        return runCatching {
            val malId = resolveMalIdCoalesced(animeTitle, normalized)
                ?: return EpisodeSkipTimes()
            val cacheKey = "aniskip:$malId:$episodeNumber"
            val now = System.currentTimeMillis()
            val cachedRow = withContext(Dispatchers.IO) { webCacheDao?.get(cacheKey) }
            if (cachedRow != null && now - cachedRow.fetchedAt < ANISKIP_TTL_MS) {
                val parsed = SkipTimesParser.parseAniSkipResponse(cachedRow.payload)
                skipTimesMemCache[memKey] = parsed
                return parsed
            }

            val url =
                "https://api.aniskip.com/v2/skip-times/$malId/$episodeNumber" +
                    "?types[]=op&types[]=mixed-op&types[]=ed&types[]=mixed-ed&episodeLength=0"
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .build()

            fastClient.awaitResponse(request).use { resp ->
                val body = withContext(Dispatchers.IO) { resp.body?.string().orEmpty() }
                if (resp.isSuccessful && body.isNotBlank()) {
                    withContext(Dispatchers.IO) {
                        webCacheDao?.upsert(WebCacheEntity(cacheKey, body, now))
                    }
                    val parsed = SkipTimesParser.parseAniSkipResponse(body)
                    skipTimesMemCache[memKey] = parsed
                    return parsed
                }
                if (resp.code == 404) {
                    val emptyPayload = """{"found":false,"results":[]}"""
                    withContext(Dispatchers.IO) {
                        webCacheDao?.upsert(WebCacheEntity(cacheKey, emptyPayload, now))
                    }
                    val empty = EpisodeSkipTimes()
                    skipTimesMemCache[memKey] = empty
                    return empty
                }
                EpisodeSkipTimes()
            }
        }.getOrDefault(EpisodeSkipTimes())
    }

    private suspend fun resolveMalIdCoalesced(rawTitle: String, normalized: String): Int? {
        val memCached = malIdCache[normalized]
        if (memCached != null) {
            return if (memCached > 0) memCached else null
        }

        val deferred = inFlightMalIds.computeIfAbsent(normalized) {
            scope.async {
                try {
                    resolveMalIdInternal(rawTitle, normalized)
                } finally {
                    inFlightMalIds.remove(normalized)
                }
            }
        }
        return deferred.await()
    }

    private suspend fun resolveMalIdInternal(rawTitle: String, normalized: String): Int? {
        val memCached = malIdCache[normalized]
        if (memCached != null) {
            return if (memCached > 0) memCached else null
        }

        val dbKey = "anilist:$normalized"
        val now = System.currentTimeMillis()
        val dbCached = webCacheDao?.get(dbKey)
        if (dbCached != null && now - dbCached.fetchedAt < ANILIST_TTL_MS) {
            val id = dbCached.payload.toIntOrNull() ?: -1
            malIdCache[normalized] = id
            return if (id > 0) id else null
        }

        val escapedTitle = escapeJsonString(rawTitle.trim())
        val payload =
            """{"query":"query(${"$"}s:String){Media(search:${"$"}s,type:ANIME){id idMal}}","variables":{"s":"$escapedTitle"}}"""
        val request = Request.Builder()
            .url("https://graphql.anilist.co")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .build()

        val malId = runCatching {
            fastClient.awaitResponse(request).use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string().orEmpty()
                SkipTimesParser.parseMalIdFromAniList(body)
            }
        }.getOrNull()

        val storedVal = malId ?: -1
        malIdCache[normalized] = storedVal
        webCacheDao?.upsert(WebCacheEntity(dbKey, storedVal.toString(), now))
        return malId
    }

    private fun escapeJsonString(raw: String): String = buildString(raw.length) {
        for (ch in raw) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val ANILIST_TTL_MS = TimeUnit.DAYS.toMillis(30)
        private val ANISKIP_TTL_MS = TimeUnit.DAYS.toMillis(7)
        private const val MAX_MAL_ID_CACHE = 256
        private const val MAX_SKIP_CACHE = 128
    }
}
