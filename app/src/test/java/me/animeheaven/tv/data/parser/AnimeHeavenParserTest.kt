package me.animeheaven.tv.data.parser

import me.animeheaven.tv.data.model.RankDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimeHeavenParserTest {

    @Test
    fun `parseHome extracts banners, dynamic season link, schedule and popular strip`() {
        val html = """
            <!DOCTYPE html>
            <html><body>
            <div class='header bc1 c2'>
              <a href='new.php'><div class='headeritem ac'>New</div></a>
              <a class='headeritem2' href='2025fall.php' title='2025 Fall Shows'><div class='headeritem ac'>Fall</div></a>
              <a class='headeritem2' href='random.php' title='Random Show'><div class='headeritem ac'>Random</div></a>
            </div>
            <div class='banner slide c2'>
              <div id='slide' class='slide1 ani'>
                <a href='anime.php?0pqfl'><img class='bannerimg' src='image.php?g9lr9' alt='Black Clover 2nd Season'></a>
                <a href='anime.php?ugyek'><img class='bannerimg' src='image.php?vjl82' alt='Dandadan 2nd Season'></a>
              </div>
            </div>
            <div class='boldtext'>
              <div class='linetitle c'>Subbed Anime Schedule</div>
              <div class='chart bc1'>
                <div class='chartimg'><a href='anime.php?6zcxf'><img class='coverimg' src='image.php?abc12' alt='Ace of the Diamond'><div class='chartepm bc2 c1'>7</div></a></div>
                <div class='chartep c2'>7</div>
                <div class='chartinfo'>
                  <div class='charttitle c'><a href='anime.php?6zcxf' class='c'>Ace of the Diamond: Act II 2nd Season</a></div>
                  <div class='charttitlejp c'>Diamond no Ace</div>
                  <div class='charttimer c2'>18 Hours 38 Min</div>
                </div>
              </div>
              <div class='popularbox bc1'>
                <a href='popular.php'><div class='boxitem2 boxitemv bc2 c1 center'>POPULAR</div></a>
                <div class='popularbox2'><a href='anime.php?kybuw'><img class='coverimg popularimg' src='image.php?puty1' alt='Tokyo Revengers'></a></div>
                <div class='popularbox2'><a href='anime.php?igk5o'><img class='coverimg popularimg' src='image.php?tv772' alt='The Apothecary Diaries 3rd Season'></a></div>
              </div>
              <div class='chart bc1'>
                <a href='2024summer.php'><div class='boxitem2 bc2 c1 center'>2024 Summer Anime</div></a>
              </div>
            </div>
            </body></html>
        """.trimIndent()

        val feed = AnimeHeavenParser.parseHome(html)

        assertEquals(2, feed.banners.size)
        assertEquals("0pqfl", feed.banners[0].animeId)
        assertEquals("https://animeheaven.me/image.php?g9lr9", feed.banners[0].bannerImageUrl)
        assertEquals("2025fall.php", feed.seasonPath)
        assertEquals("Fall", feed.seasonLabel)

        // Footer button-only .chart wrapper should be ignored
        assertEquals(1, feed.scheduleItems.size)
        val item = feed.scheduleItems[0]
        assertEquals("6zcxf", item.id)
        assertEquals("Ace of the Diamond: Act II 2nd Season", item.title)
        assertEquals("Diamond no Ace", item.japaneseTitle)
        assertEquals("7", item.episodeLabel)
        assertEquals("18 Hours 38 Min", item.timerOrStatus)

        assertEquals(2, feed.popularItems.size)
        assertEquals("kybuw", feed.popularItems[0].id)
        assertEquals("Tokyo Revengers", feed.popularItems[0].title)
    }

    @Test
    fun `parseChartPage with popular ranks parses rank arrows and deltas`() {
        val html = """
            <html><body>
            <div class='boldtext'>
              <div class='linetitle c'>Popular Today</div>
              <div class='chart bc1'>
                <div class='chartimg'><a href='anime.php?kybuw'><img class='coverimg' src='image.php?puty1' alt='Tokyo Revengers'></a></div>
                <div class='chartep c2'>1</div>
                <div class='chartinfo'>
                  <div class='charttitle c'><a href='anime.php?kybuw'>Tokyo Revengers</a></div>
                  <div class='charttitlejp c'>-</div>
                  <div class='charttimer c2'><div class='popi bc2 c1'><img src='arrowu.svg' alt=''></div><div class='popi popt c'>+2</div></div>
                </div>
              </div>
              <div class='chart bc1'>
                <div class='chartimg'><a href='anime.php?6uopw'><img class='coverimg' src='image.php?3ks09' alt='Heavy Knight'></a></div>
                <div class='chartep c2'>5</div>
                <div class='chartinfo'>
                  <div class='charttitle c'><a href='anime.php?6uopw'>Heavy Knight</a></div>
                  <div class='charttitlejp c'>Tsuihou</div>
                  <div class='charttimer c2'><div class='popi bc3 c1'><img src='arrowd.svg' alt=''></div><div class='popi popt c'>-4</div></div>
                </div>
              </div>
            </div>
            </body></html>
        """.trimIndent()

        val (title, items) = AnimeHeavenParser.parseChartPage(html, isPopularRank = true)
        assertEquals("Popular Today", title)
        assertEquals(2, items.size)
        assertEquals("1", items[0].episodeLabel)
        assertEquals(RankDirection.UP, items[0].rankDirection)
        assertEquals("+2", items[0].rankDelta)
        assertEquals("", items[0].japaneseTitle)
        assertEquals(RankDirection.DOWN, items[1].rankDirection)
        assertEquals("-4", items[1].rankDelta)
    }

    @Test
    fun `parseFastSearch and parseSimilarGrid extract items cleanly`() {
        val fastHtml = """
            <a class='ac' href='/anime.php?0pqfl'><div class='fastitem bc1 ac'><div class='fastimg'><img class='coverimg' src='/image.php?82gob' alt='Black Clover 2nd Season'></div><div class='fastname'>Black Clover 2nd Season</div></div></a>
            <a class='ac' href='/anime.php?7tq84'><div class='fastitem bc1 ac'><div class='fastimg'><img class='coverimg' src='/image.php?xq99f' alt='Black Torch'></div><div class='fastname'>Black Torch</div></div></a>
        """.trimIndent()

        val fastItems = AnimeHeavenParser.parseFastSearch(fastHtml)
        assertEquals(2, fastItems.size)
        assertEquals("0pqfl", fastItems[0].animeId)
        assertEquals("Black Clover 2nd Season", fastItems[0].title)
        assertEquals("https://animeheaven.me/image.php?82gob", fastItems[0].coverUrl)

        val gridHtml = """
            <html><body><div class='boldtext'>
              <div class='linetitle c'>black clover</div>
              <div class='info3 bc1'>
                <div class='similarimg'><div class='p1'><a href='anime.php?l1cen'><img class='coverimg' src='image.php?mg1oq' alt='Black Clover'></a><div class='similarname c'><a href='anime.php?l1cen' class='c'>Black Clover</a></div></div></div>
              </div>
            </div></body></html>
        """.trimIndent()

        val (queryTitle, gridItems) = AnimeHeavenParser.parseSimilarGrid(gridHtml)
        assertEquals("black clover", queryTitle)
        assertEquals(1, gridItems.size)
        assertEquals("l1cen", gridItems[0].id)
    }

    @Test
    fun `parseAnimeDetail extracts metadata, episodes, related and similar shows`() {
        val html = """
            <html><body>
            <div class='boldtext'>
              <div class='banner'><img class='bannerimg' src='image.php?g9lr9'></div>
              <div class='info bc1'>
                <div class='infoimg'><img class='posterimg' src='https://animeheaven.me/image.php?xnpn4'></div>
                <div class='infodiv'>
                  <div class='infotitle c'>Black Clover 2nd Season</div>
                  <div class='infodes c'>The second season of Black Clover.</div>
                  <div class='infotags c'>Tags: <a href='tags.php?tag=Action'><div class='boxitem bc2 c1'>Action</div></a><a href='tags.php?tag=Magic'><div class='boxitem bc2 c1'>Magic</div></a></div>
                  <div class='infoyear c'>Episodes: <div class='inline c2'>7+</div> Year: <div class='inline c2'>2026</div> Score: <div class='inline c2'>8.5/10</div></div>
                </div>
              </div>
            </div>
            <div class='boldtext'>
              <div class='info2 bc1'><div class='inline c'>Countdown to Episode 8 :</div> <div class='inline c2'>58 Min</div></div>
              <div class='linetitle2 c2'>
                <a class='c' id="4414ad9ad0c6a87e2f17358bd3cef408" onclick='gatea("4414ad9ad0c6a87e2f17358bd3cef408")' href='gate.php'>
                  <div class='trackep0 watch bc2'><div class='trackep watchb bc'><div class='watch1 bc c'>Episode</div><div class='watch2 bc'>7</div><div class='watch1 bc c'>6 d ago</div></div></div>
                </a>
                <a class='c' id="3e6a76712d6b4ec2dac680bb40f307ad" onclick='gatea("3e6a76712d6b4ec2dac680bb40f307ad")' href='gate.php'>
                  <div class='trackep0 watch bc2'><div class='trackep watchb bc'><div class='watch1 bc c'>Episode</div><div class='watch2 bc'>6</div><div class='watch1 bc c'>13 d ago</div></div></div>
                </a>
              </div>
            </div>
            <div class='boldtext'>
              <div class='linetitle c'>Related Shows</div>
              <div class='info3 bc1'>
                <div class='similarimg'><div class='p1 asp'><a href='anime.php?l1cen'><img class='coverimg' src='image.php?mg1oq'></a></div><div class='similarname c'><a href='anime.php?l1cen'>Black Clover</a></div></div>
              </div>
              <div class='linetitle c'>Similar Shows</div>
              <div class='info3 bc1'>
                <div class='similarimg'><div class='p1 asp'><a href='anime.php?og14w'><img class='coverimg' src='image.php?6ux41'></a></div><div class='similarname c'><a href='anime.php?og14w'>Fairy Tail</a></div></div>
              </div>
            </div>
            </body></html>
        """.trimIndent()

        val detail = AnimeHeavenParser.parseAnimeDetail("0pqfl", html)

        assertEquals("Black Clover 2nd Season", detail.title)
        assertEquals("https://animeheaven.me/image.php?xnpn4", detail.posterUrl)
        assertEquals(listOf("Action", "Magic"), detail.tags)
        assertEquals("7+", detail.episodesLabel)
        assertEquals("2026", detail.year)
        assertEquals("8.5/10", detail.score)
        assertTrue(detail.countdownLabel?.contains("58 Min") == true)
        assertEquals(2, detail.episodes.size)
        assertEquals("4414ad9ad0c6a87e2f17358bd3cef408", detail.episodes[0].key)
        assertEquals("7", detail.episodes[0].numberLabel)
        assertEquals("6 d ago", detail.episodes[0].releaseAge)
        assertEquals(1, detail.relatedShows.size)
        assertEquals("l1cen", detail.relatedShows[0].id)
        assertEquals(1, detail.similarShows.size)
        assertEquals("og14w", detail.similarShows[0].id)
    }

    @Test
    fun `parseGatePage ignores commented sources, deduplicates mirrors, and resolves prev and next`() {
        val html = """
            <html><body><div class='boldtext'>
              <!-- <source src='https://ax.animeheaven.me/video.mp4?old' type='video/mp4'> -->
              <h1><div class='linetitle3 c'><a href='anime.php?0pqfl' class='c2 ac2'>Black Clover 2nd Season</a> Episode 6</div></h1>
              <div class='linetitle2 c'>
                <a href='gate.php' onclick='gate("d86a7cd633657bc94b11d53ba35445ea")'><div class='boxitem bc2 c1'>Episode 5</div></a>
                <a href='gate.php' onclick='gate("4414ad9ad0c6a87e2f17358bd3cef408")'><div class='boxitem bc2 c1'>Episode 7</div></a>
              </div>
              <div class='info2 bc1'>
                <video id='vid'>
                  <source src='https://sr.animeheaven.me/video.mp4?ep6' type='video/mp4'>
                  <source src='https://ct.animeheaven.me/video.mp4?ep6&error' type='video/mp4'>
                  <source src='https://ck.animeheaven.me/video.mp4?ep6&error2' type='video/mp4'>
                  <source src='https://sr.animeheaven.me/video.mp4?ep6' type='video/mp4'>
                </video>
              </div>
              <div class='linetitle2 c'><a href='https://sr.animeheaven.me/video.mp4?ep6&d'><div class='boxitem'>Download Episode 6</div></a></div>
            </div></body></html>
        """.trimIndent()

        val stream = AnimeHeavenParser.parseGatePage("3e6a76712d6b4ec2dac680bb40f307ad", html)

        assertEquals("0pqfl", stream.animeId)
        assertEquals("Black Clover 2nd Season", stream.animeTitle)
        assertEquals("Episode 6", stream.episodeLabel)
        assertEquals(
            listOf(
                "https://sr.animeheaven.me/video.mp4?ep6",
                "https://ct.animeheaven.me/video.mp4?ep6&error",
                "https://ck.animeheaven.me/video.mp4?ep6&error2"
            ),
            stream.videoSources
        )
        assertEquals("d86a7cd633657bc94b11d53ba35445ea", stream.prevEpisodeKey)
        assertEquals("Episode 5", stream.prevEpisodeLabel)
        assertEquals("4414ad9ad0c6a87e2f17358bd3cef408", stream.nextEpisodeKey)
        assertEquals("Episode 7", stream.nextEpisodeLabel)
    }

    @Test
    fun `parseGatePage with single nav link distinguishes prev vs next correctly`() {
        val latestEpHtml = """
            <html><body>
              <div class='linetitle3'><a href='anime.php?0pqfl'>Show</a> Episode 7</div>
              <div class='linetitle2'><a href='gate.php' onclick='gate("prev6key")'><div class='boxitem'>Episode 6</div></a></div>
              <video id='vid'><source src='https://f1.animeheaven.me/video.mp4?ep7'></video>
            </body></html>
        """.trimIndent()
        val latest = AnimeHeavenParser.parseGatePage("ep7key", latestEpHtml)
        assertEquals("prev6key", latest.prevEpisodeKey)
        assertNull(latest.nextEpisodeKey)

        val firstEpHtml = """
            <html><body>
              <div class='linetitle3'><a href='anime.php?0pqfl'>Show</a> Episode 1</div>
              <div class='linetitle2'><a href='gate.php' onclick='gate("next2key")'><div class='boxitem'>Episode 2</div></a></div>
              <video id='vid'><source src='https://f1.animeheaven.me/video.mp4?ep1'></video>
            </body></html>
        """.trimIndent()
        val first = AnimeHeavenParser.parseGatePage("ep1key", firstEpHtml)
        assertNull(first.prevEpisodeKey)
        assertEquals("next2key", first.nextEpisodeKey)
    }
}
