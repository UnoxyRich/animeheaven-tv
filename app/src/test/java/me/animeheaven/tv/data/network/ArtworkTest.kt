package me.animeheaven.tv.data.network

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.animeheaven.tv.data.local.WebCacheDao
import me.animeheaven.tv.data.local.WebCacheEntity
import me.animeheaven.tv.data.model.ArtworkImageHeaders
import me.animeheaven.tv.data.model.ArtworkLookupRequest
import me.animeheaven.tv.data.model.ArtworkSlot
import me.animeheaven.tv.data.model.ArtworkSmartFit
import me.animeheaven.tv.data.parser.AnimeHeavenParser
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class ArtworkTest {
    @Test fun aliasedBatchSelectsCorrectSeasonAndIgnoresFailedAliases() {
        val requests = listOf(
            ArtworkLookupRequest("frieren", "Frieren: Beyond Journey's End", "Sousou no Frieren"),
            ArtworkLookupRequest("black-clover", "Black Clover 2nd Season", year = 2026),
            ArtworkLookupRequest("other", "Escaped \"title\"\n試験")
        )
        val body = Json.parseToJsonElement(ArtworkProviders.aniListBody(requests)).jsonObject
        assertEquals(requests[2].title, body.getValue("variables").jsonObject.getValue("s2").jsonPrimitive.content)
        assertTrue(body.getValue("query").jsonPrimitive.content.contains("a2:Page(perPage:5)"))
        assertThrows(IllegalArgumentException::class.java) { ArtworkProviders.aniListBody(List(9) { requests[0] }) }
        val results = ArtworkProviders.aniList(
            """{"data":{"a0":{"media":[
             {"id":189513,"title":{"english":"Frieren: Beyond Journey’s End Mini Anime Part 2"},"format":"ONA","popularity":100000},
             {"id":154587,"idMal":52991,"title":{"english":"Frieren: Beyond Journey’s End","romaji":"Sousou no Frieren"},"format":"TV"}]},
             "a1":{"media":[{"id":97940,"title":{"english":"Black Clover"},"format":"TV","popularity":1000000},
             {"id":195604,"title":{"english":"Black Clover Season 2"},"seasonYear":2026,"format":"TV"}]},
             "a2":null},"errors":[{"path":["a2"]}]}""", requests
        )
        assertEquals(listOf(154587, 195604, null), results.map { it?.anilistId })
        assertNull(ArtworkTitleMatcher.bestIndex(requests[1], listOf(ArtworkTitleMatcher.Candidate(listOf("Black Lagoon"), 2026, "TV", 100_000))))
        assertEquals("pokemon", ArtworkTitleMatcher.normalize("Pokémon"))
    }

    @Test fun providersPopulateOnlyTheirSlotsAndRetainNativeFallback() {
        val request = ArtworkLookupRequest("a", "Example Title")
        val zip = ArtworkProviders.aniZip(
            """{"images":[{"coverType":"Fanart","url":"https://example.test/fanart"},
                {"coverType":"Poster","url":"https://example.test/portrait"},{"coverType":"Banner","url":"https://example.test/banner"}],
                "episodes":{"1":{"image":"https://example.test/episode"},"1.5":{"image":"https://example.test/not-integer"}}}"""
        )!!
        val kitsu = ArtworkProviders.kitsu(
            """{"data":[{"attributes":{"canonicalTitle":"Example Title","subtype":"TV","posterImage":{"large":"https://example.test/kitsu-poster"},
                  "coverImage":{"large":"https://example.test/kitsu-banner"}}}]}""", request
        )!!
        val jikan = ArtworkProviders.jikan(
            """{"data":{"mal_id":12,"images":{"webp":{"large_image_url":"https://example.test/mal"}},
                "trailer":{"images":{"maximum_image_url":"https://example.test/trailer"}}}}""", request
        )!!
        val full = zip.merge(kitsu).merge(jikan)
        assertEquals("https://example.test/episode", full.bestUrl(ArtworkSlot.LANDSCAPE_16_9, 1, "native"))
        assertEquals("https://example.test/fanart", full.bestUrl(ArtworkSlot.LANDSCAPE_16_9, 2, "native"))
        assertEquals("https://example.test/portrait", full.bestUrl(ArtworkSlot.PORTRAIT_2_3, fallbackUrl = "native"))
        assertEquals("https://example.test/kitsu-banner", full.bestUrl(ArtworkSlot.WIDE_BANNER, fallbackUrl = "native"))
        assertEquals("https://example.test/mal", jikan.bestUrl(ArtworkSlot.PORTRAIT_2_3, fallbackUrl = "native"))
        assertEquals("https://example.test/trailer", jikan.bestUrl(ArtworkSlot.LANDSCAPE_16_9, fallbackUrl = "native"))
        assertEquals("native", jikan.bestUrl(ArtworkSlot.LANDSCAPE_16_9, 2, "native"))
        assertEquals(full, ArtworkProviders.decode(ArtworkProviders.encode(full)))
    }

    @Test fun smartFitAndNativeHostIsolation() {
        assertFalse(ArtworkSmartFit.needsAmbient(1.55f, 1f))
        assertFalse(ArtworkSmartFit.needsAmbient(16f / 9, 16f / 10))
        assertTrue(ArtworkSmartFit.needsAmbient(8f, 16f / 10))
        assertTrue(ArtworkSmartFit.needsAmbient(16f / 9, 2f / 3))
        assertTrue(ArtworkSmartFit.needsAmbient(2f / 3, 16f / 9))
        assertEquals("https://animeheaven.me/", ArtworkImageHeaders.referer("cdn.animeheaven.me"))
        assertNull(ArtworkImageHeaders.referer("fakeanimeheaven.me"))
        assertNull(ArtworkImageHeaders.referer("animeheaven.me.example.test"))
        assertNull(ArtworkImageHeaders.referer("s4.anilist.co"))
        val detail = AnimeHeavenParser.parseAnimeDetail("id", """
            <meta property="og:image" content="/image.php?cover">
            <div class="banner"><img class="bannerimg" src="/image.php?wide"></div>
            <div class="infoimg"><img class="posterimg" src="/image.php?portrait"></div>
        """.trimIndent())
        assertEquals("https://animeheaven.me/image.php?cover", detail.landscapeCoverUrl)
    }

    @Test fun rateLimitPausesAniListWithoutPersistingTransientMiss() = runTest {
        val calls = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val rateLimited = request.url.host == "graphql.anilist.co"
            if (rateLimited) calls.incrementAndGet()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(if (rateLimited) 429 else 200).message("fixture")
                .header("Retry-After", "40")
                .body("""{"data":[]}""".toResponseBody()).build()
        }.build()
        val database = TestCache()
        val resolver = AnimeArtworkResolver(client, database, backgroundScope)
        assertNull(resolver.resolveMalId("First Test Show"))
        assertNull(resolver.resolveMalId("Second Test Show"))
        assertEquals(1, calls.get())
        assertTrue(database.rows.isEmpty())
        assertEquals(40_000L, AnimeArtworkResolver.retryMillis("40", now = 0))
    }

    private class TestCache : WebCacheDao {
        val rows = ConcurrentHashMap<String, WebCacheEntity>()
        override suspend fun get(cacheKey: String) = rows[cacheKey]
        override suspend fun getByKeys(keys: List<String>) = keys.mapNotNull(rows::get)
        override suspend fun getRecentDetails(limit: Int) = emptyList<WebCacheEntity>()
        override suspend fun getRecentStreams(limit: Int) = emptyList<WebCacheEntity>()
        override suspend fun getRecentVideoMeta(limit: Int) = emptyList<WebCacheEntity>()
        override suspend fun getRecentArtwork(since: Long, limit: Int) = rows.values.filter { it.fetchedAt >= since }.take(limit)
        override suspend fun upsert(entity: WebCacheEntity) { rows[entity.cacheKey] = entity }
        override suspend fun delete(cacheKey: String) { rows.remove(cacheKey) }
        override suspend fun pruneOlderThan(cutoffMs: Long) { rows.entries.removeIf { it.value.fetchedAt < cutoffMs } }
        override suspend fun clearAll() = rows.clear()
        override suspend fun countCachedPages() = rows.size
        override suspend fun totalPayloadBytes() = rows.values.sumOf { it.payload.length.toLong() }
    }
}
