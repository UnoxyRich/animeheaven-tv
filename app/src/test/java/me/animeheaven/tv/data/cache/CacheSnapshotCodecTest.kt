package me.animeheaven.tv.data.cache

import me.animeheaven.tv.data.model.AnimeCard
import me.animeheaven.tv.data.model.AnimeDetail
import me.animeheaven.tv.data.model.BannerItem
import me.animeheaven.tv.data.model.EpisodeItem
import me.animeheaven.tv.data.model.EpisodeStream
import me.animeheaven.tv.data.model.FastSearchItem
import me.animeheaven.tv.data.model.HomeFeed
import me.animeheaven.tv.data.model.RankDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CacheSnapshotCodecTest {

    @Test
    fun `homeFeed JSON round-trip preserves unicode, quotes, and nulls`() {
        val original = HomeFeed(
            banners = listOf(
                BannerItem("b1", "Dandadan \"2nd\" Season", "https://animeheaven.me/b1.jpg")
            ),
            scheduleItems = listOf(
                AnimeCard(
                    id = "s1",
                    title = "Kimi to Idol Pretty Cure♪",
                    japaneseTitle = "キミとアイドルプリキュア♪",
                    coverUrl = "https://animeheaven.me/c1.jpg",
                    episodeLabel = "49",
                    timerOrStatus = "2d ago"
                )
            ),
            popularItems = listOf(
                AnimeCard(
                    id = "p1",
                    title = "Overgeared",
                    coverUrl = "https://animeheaven.me/p1.jpg",
                    rankDelta = "+1",
                    rankDirection = RankDirection.UP
                )
            ),
            seasonPath = "2025fall.php",
            seasonLabel = "Fall"
        )

        val encoded = CacheSnapshotCodec.encodeHomeFeed(original)
        val decoded = CacheSnapshotCodec.decodeHomeFeed(encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `chart and fastSearch round-trip accurately`() {
        val chart = "Popular Today" to listOf(
            AnimeCard("id1", "Show One", "日本語", "https://img/1.jpg", "12", "+2", "+2", RankDirection.UP),
            AnimeCard("id2", "Show Two", "", "https://img/2.jpg", "3", "-1", "-1", RankDirection.DOWN)
        )
        assertEquals(chart, CacheSnapshotCodec.decodeChart(CacheSnapshotCodec.encodeChart(chart)))

        val fast = listOf(
            FastSearchItem("a1", "Naruto: Shippuden", "https://img/n.jpg")
        )
        assertEquals(fast, CacheSnapshotCodec.decodeFastSearch(CacheSnapshotCodec.encodeFastSearch(fast)))
    }

    @Test
    fun `animeDetail and episodeStream JSON round-trip handles multiline text`() {
        val detail = AnimeDetail(
            id = "d1",
            title = "From Far Away",
            japaneseTitle = "彼方から",
            bannerUrl = null,
            posterUrl = "https://img/poster.jpg",
            synopsis = "Line 1\n\"Quoted\" \\ text",
            tags = listOf("Fantasy", "Isekai"),
            episodesLabel = "8+",
            year = "2026-?",
            score = "?",
            countdownLabel = "6 Days",
            episodes = listOf(EpisodeItem("ep8key", "8", "10 h ago")),
            relatedShows = listOf(AnimeCard("r1", "Related")),
            similarShows = emptyList()
        )
        assertEquals(detail, CacheSnapshotCodec.decodeAnimeDetail(CacheSnapshotCodec.encodeAnimeDetail(detail)))

        val stream = EpisodeStream(
            episodeKey = "ep8key",
            animeId = "d1",
            animeTitle = "From Far Away",
            episodeLabel = "Episode 8",
            videoSources = listOf("https://cdn1/video.mp4", "https://cdn2/video.mp4"),
            prevEpisodeKey = "ep7key",
            prevEpisodeLabel = "7",
            nextEpisodeKey = null,
            nextEpisodeLabel = null
        )
        assertEquals(stream, CacheSnapshotCodec.decodeEpisodeStream(CacheSnapshotCodec.encodeEpisodeStream(stream)))
    }

    @Test
    fun `compressText shrinks HTML losslessly and keeps short or uncompressed text intact`() {
        val shortText = "<html>hi</html>"
        assertEquals(shortText, CacheSnapshotCodec.decompressText(CacheSnapshotCodec.compressText(shortText)))

        val repeatedHtml = buildString {
            append("<html><body>")
            repeat(200) { idx ->
                append("<div class='chart'>Anime Title #$idx <a href='anime.php?id$idx'>Watch</a></div>\n")
            }
            append("</body></html>")
        }
        val compressed = CacheSnapshotCodec.compressText(repeatedHtml)
        assertTrue(compressed.startsWith("GZ:"))
        assertTrue(compressed.length < repeatedHtml.length / 3)
        assertEquals(repeatedHtml, CacheSnapshotCodec.decompressText(compressed))
    }

    @Test
    fun `malformed JSON returns null cleanly without throwing`() {
        assertNull(CacheSnapshotCodec.decodeHomeFeed("{bad json"))
        assertNull(CacheSnapshotCodec.decodeAnimeDetail("[]"))
        assertNull(CacheSnapshotCodec.decodeFastSearch("{not: array}"))
        assertNull(CacheSnapshotCodec.decodeHomeFeed("""{"banners":[],"schedule":[],"popular":[]} trailing"""))
    }

    @Test
    fun `streaming decoder skips unknown future fields and decodes unicode escapes`() {
        val json = """
            {
              "seasonPath": "2026spring.php",
              "seasonLabel": "Spring \u2605",
              "futureFlag": true,
              "futureMeta": {"nested": [1, 2, 3]},
              "banners": [{"id": "b1", "title": "Line1\nLine2", "img": "https://img/b.jpg", "extra": 42}],
              "schedule": [],
              "popular": []
            }
        """.trimIndent()
        val decoded = CacheSnapshotCodec.decodeHomeFeed(json)
        assertEquals("2026spring.php", decoded?.seasonPath)
        assertEquals("Spring ★", decoded?.seasonLabel)
        assertEquals(1, decoded?.banners?.size)
        assertEquals("Line1\nLine2", decoded?.banners?.first()?.title)
    }
}
