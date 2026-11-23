package me.animeheaven.tv.data.parser

import me.animeheaven.tv.data.model.AnimeCard
import me.animeheaven.tv.data.model.AnimeDetail
import me.animeheaven.tv.data.model.BannerItem
import me.animeheaven.tv.data.model.EpisodeItem
import me.animeheaven.tv.data.model.EpisodeStream
import me.animeheaven.tv.data.model.FastSearchItem
import me.animeheaven.tv.data.model.HomeFeed
import me.animeheaven.tv.data.model.RankDirection
import me.animeheaven.tv.data.network.AnimeHeavenClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.select.Evaluator
import org.jsoup.select.QueryParser

object AnimeHeavenParser {

    private val ANIME_ID_REGEX = Regex("""anime\.php\?([a-zA-Z0-9_-]+)""")
    private val GATE_KEY_REGEX = Regex("""gate(?:a|h)?\s*\(\s*["']([a-zA-Z0-9_-]+)["']""")
    private val EPISODE_NUMBER_REGEX = Regex("""(\d+(?:\.\d+)?)""")

    // Pre-compiled Jsoup Evaluators eliminate ~450 CSS selector parses per page
    private val EVAL_BANNER_LINKS: Evaluator = QueryParser.parse("div.banner #slide a[href*=anime.php]")
    private val EVAL_IMG: Evaluator = QueryParser.parse("img")
    private val EVAL_HEADER_ITEM2: Evaluator = QueryParser.parse("div.header a.headeritem2")
    private val EVAL_CHART: Evaluator = QueryParser.parse("div.chart")
    private val EVAL_CHART_ALL: Evaluator = QueryParser.parse("div.chart, div.chart2g, div.chart2ls")
    private val EVAL_POPULAR_LINKS: Evaluator = QueryParser.parse("div.popularbox div.popularbox2 a[href*=anime.php]")
    private val EVAL_LINE_TITLE: Evaluator = QueryParser.parse("div.linetitle")
    private val EVAL_CHART_TITLE_LINK: Evaluator = QueryParser.parse(
        ".charttitle a[href*=anime.php], .charttitle2g a[href*=anime.php], .charttitle2ls a[href*=anime.php]"
    )
    private val EVAL_CHART_COVER_LINK: Evaluator = QueryParser.parse(
        ".chartimg a[href*=anime.php], .chartimg2g a[href*=anime.php], .chartimg2ls a[href*=anime.php]"
    )
    private val EVAL_COVER_IMG: Evaluator = QueryParser.parse("img.coverimg")
    private val EVAL_CHART_TITLE_JP: Evaluator = QueryParser.parse(".charttitlejp, .charttitlejp2g, .charttitlejp2ls")
    private val EVAL_CHART_EP: Evaluator = QueryParser.parse(".chartep, .chartep2g, .chartep2ls")
    private val EVAL_CHART_EPM: Evaluator = QueryParser.parse(".chartepm, .chartepm2g, .chartepm2ls")
    private val EVAL_CHART_TIMER: Evaluator = QueryParser.parse(".charttimer, .charttimer2g, .charttimer2ls")
    private val EVAL_POPT: Evaluator = QueryParser.parse(".popt")
    private val EVAL_ANIME_LINKS: Evaluator = QueryParser.parse("a[href*=anime.php]")
    private val EVAL_FAST_IMG: Evaluator = QueryParser.parse(".fastimg img, img.coverimg")
    private val EVAL_FAST_NAME: Evaluator = QueryParser.parse(".fastname")
    private val EVAL_INFO3: Evaluator = QueryParser.parse("div.info3")
    private val EVAL_SIMILAR_IMG: Evaluator = QueryParser.parse("div.similarimg")
    private val EVAL_SIMILAR_NAME: Evaluator = QueryParser.parse(".similarname a, .similarname")
    private val EVAL_DETAIL_BANNER: Evaluator = QueryParser.parse("div.banner img.bannerimg")
    private val EVAL_DETAIL_POSTER: Evaluator = QueryParser.parse("div.infoimg img.posterimg")
    private val EVAL_INFO_TITLE: Evaluator = QueryParser.parse("div.infotitle")
    private val EVAL_INFO_TITLE_JP: Evaluator = QueryParser.parse("div.infotitlejp")
    private val EVAL_INFO_DES: Evaluator = QueryParser.parse("div.infodes")
    private val EVAL_INFO_TAGS: Evaluator = QueryParser.parse("div.infotags a .boxitem")
    private val EVAL_INFO_YEAR: Evaluator = QueryParser.parse("div.infoyear")
    private val EVAL_INLINE_C2: Evaluator = QueryParser.parse("div.inline.c2")
    private val EVAL_INFO2: Evaluator = QueryParser.parse("div.info2")
    private val EVAL_GATE_LINKS: Evaluator = QueryParser.parse("div.linetitle2 a[href*=gate.php]")
    private val EVAL_WATCH2: Evaluator = QueryParser.parse(".watch2")
    private val EVAL_WATCH1: Evaluator = QueryParser.parse(".watch1")
    private val EVAL_LINE_TITLE3: Evaluator = QueryParser.parse("div.linetitle3")
    private val EVAL_VIDEO_SOURCES: Evaluator = QueryParser.parse("video#vid source[src]")
    private val EVAL_BOX_ITEM: Evaluator = QueryParser.parse(".boxitem")

    fun extractAnimeId(href: String?): String? {
        if (href.isNullOrBlank()) return null
        return ANIME_ID_REGEX.find(href)?.groupValues?.getOrNull(1)
    }

    fun normalizeUrl(url: String?): String {
        val trimmed = url?.trim().orEmpty()
        if (trimmed.isEmpty()) return ""
        return when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("/") -> "${AnimeHeavenClient.BASE_URL}$trimmed"
            else -> "${AnimeHeavenClient.BASE_URL}/$trimmed"
        }
    }

    fun parseHome(html: String): HomeFeed {
        val doc = Jsoup.parse(html, AnimeHeavenClient.BASE_URL)

        val banners = doc.select(EVAL_BANNER_LINKS).mapNotNull { link ->
            val id = extractAnimeId(link.attr("href")) ?: return@mapNotNull null
            val img = link.selectFirst(EVAL_IMG)
            val imageUrl = normalizeUrl(img?.attr("src"))
            if (imageUrl.isBlank()) return@mapNotNull null
            val title = img?.attr("alt").orEmpty().ifBlank { "Featured Anime" }
            BannerItem(animeId = id, title = title, bannerImageUrl = imageUrl)
        }

        val seasonLink = doc.select(EVAL_HEADER_ITEM2).firstOrNull { link ->
            val href = link.attr("href")
            href.isNotBlank() && !href.contains("random.php", ignoreCase = true)
        }
        val seasonPath = seasonLink?.attr("href")?.trimStart('/')?.takeIf { it.isNotBlank() }
        val seasonLabel = seasonLink?.text()?.trim()?.takeIf { it.isNotBlank() }

        val scheduleItems = parseChartElements(doc.select(EVAL_CHART), isPopularRank = false)

        val popularItems = doc.select(EVAL_POPULAR_LINKS)
            .mapNotNull { link ->
                val id = extractAnimeId(link.attr("href")) ?: return@mapNotNull null
                val img = link.selectFirst(EVAL_IMG)
                val coverUrl = normalizeUrl(img?.attr("src"))
                val title = img?.attr("alt").orEmpty().trim()
                AnimeCard(
                    id = id,
                    title = title,
                    coverUrl = coverUrl
                )
            }

        return HomeFeed(
            banners = banners,
            scheduleItems = scheduleItems,
            popularItems = popularItems,
            seasonPath = seasonPath,
            seasonLabel = seasonLabel
        )
    }

    fun parseChartPage(
        html: String,
        isPopularRank: Boolean = false
    ): Pair<String, List<AnimeCard>> {
        val doc = Jsoup.parse(html, AnimeHeavenClient.BASE_URL)
        val title = doc.selectFirst(EVAL_LINE_TITLE)?.ownText()?.trim().orEmpty()
        val charts = doc.select(EVAL_CHART_ALL)
        val items = parseChartElements(charts, isPopularRank = isPopularRank)
        return title to items
    }

    private fun parseChartElements(
        elements: Iterable<Element>,
        isPopularRank: Boolean
    ): List<AnimeCard> {
        return elements.mapNotNull { chart ->
            val titleLink = chart.selectFirst(EVAL_CHART_TITLE_LINK)
            val coverLink = chart.selectFirst(EVAL_CHART_COVER_LINK)
            val id = extractAnimeId(titleLink?.attr("href"))
                ?: extractAnimeId(coverLink?.attr("href"))
                ?: return@mapNotNull null

            val coverImg = chart.selectFirst(EVAL_COVER_IMG)
            val title = titleLink?.text()?.trim().orEmpty()
                .ifBlank { coverImg?.attr("alt").orEmpty().trim() }
            if (title.isBlank()) return@mapNotNull null

            val japaneseTitle = chart.selectFirst(EVAL_CHART_TITLE_JP)
                ?.text()?.trim().orEmpty()
                .let { if (it == "-") "" else it }

            val coverUrl = normalizeUrl(coverImg?.attr("src"))
            val epText = chart.selectFirst(EVAL_CHART_EP)?.text()?.trim().orEmpty()
                .ifBlank { chart.selectFirst(EVAL_CHART_EPM)?.text()?.trim().orEmpty() }

            val timerEl = chart.selectFirst(EVAL_CHART_TIMER)
            var rankDelta: String? = null
            var rankDirection: RankDirection? = null
            val timerOrStatus: String

            if (isPopularRank && timerEl != null) {
                val imgSrc = timerEl.selectFirst(EVAL_IMG)?.attr("src").orEmpty()
                rankDirection = when {
                    imgSrc.contains("arrowu", ignoreCase = true) -> RankDirection.UP
                    imgSrc.contains("arrowd", ignoreCase = true) -> RankDirection.DOWN
                    imgSrc.contains("arrowb", ignoreCase = true) -> RankDirection.SAME
                    else -> null
                }
                rankDelta = timerEl.selectFirst(EVAL_POPT)?.text()?.trim()?.takeIf { it.isNotBlank() }
                timerOrStatus = rankDelta ?: ""
            } else {
                timerOrStatus = timerEl?.text()?.trim().orEmpty()
            }

            AnimeCard(
                id = id,
                title = title,
                japaneseTitle = japaneseTitle,
                coverUrl = coverUrl,
                episodeLabel = epText,
                timerOrStatus = timerOrStatus,
                rankDelta = rankDelta,
                rankDirection = rankDirection
            )
        }
    }

    fun parseFastSearch(html: String): List<FastSearchItem> {
        if (html.isBlank()) return emptyList()
        val doc = Jsoup.parseBodyFragment(html, AnimeHeavenClient.BASE_URL)
        return doc.select(EVAL_ANIME_LINKS).mapNotNull { link ->
            val id = extractAnimeId(link.attr("href")) ?: return@mapNotNull null
            val img = link.selectFirst(EVAL_FAST_IMG)
            val coverUrl = normalizeUrl(img?.attr("src"))
            val title = link.selectFirst(EVAL_FAST_NAME)?.text()?.trim().orEmpty()
                .ifBlank { img?.attr("alt").orEmpty().trim() }
            if (title.isBlank()) return@mapNotNull null
            FastSearchItem(
                animeId = id,
                title = title,
                coverUrl = coverUrl
            )
        }
    }

    fun parseSimilarGrid(html: String): Pair<String, List<AnimeCard>> {
        val doc = Jsoup.parse(html, AnimeHeavenClient.BASE_URL)
        val title = doc.selectFirst(EVAL_LINE_TITLE)?.ownText()?.trim().orEmpty()
        val items = parseSimilarContainers(doc.select(EVAL_INFO3))
        return title to items
    }

    private fun parseSimilarContainers(containers: Iterable<Element>): List<AnimeCard> {
        return containers.flatMap { container ->
            container.select(EVAL_SIMILAR_IMG).mapNotNull { card ->
                val link = card.selectFirst(EVAL_ANIME_LINKS) ?: return@mapNotNull null
                val id = extractAnimeId(link.attr("href")) ?: return@mapNotNull null
                val img = card.selectFirst(EVAL_COVER_IMG)
                val coverUrl = normalizeUrl(img?.attr("src"))
                val title = card.selectFirst(EVAL_SIMILAR_NAME)?.text()?.trim().orEmpty()
                    .ifBlank { img?.attr("alt").orEmpty().trim() }
                if (title.isBlank()) return@mapNotNull null
                AnimeCard(
                    id = id,
                    title = title,
                    coverUrl = coverUrl
                )
            }
        }
    }

    fun parseAnimeDetail(animeId: String, html: String): AnimeDetail {
        val doc = Jsoup.parse(html, AnimeHeavenClient.BASE_URL)

        val bannerUrl = normalizeUrl(doc.selectFirst(EVAL_DETAIL_BANNER)?.attr("src"))
            .takeIf { it.isNotBlank() }
        val posterUrl = normalizeUrl(doc.selectFirst(EVAL_DETAIL_POSTER)?.attr("src"))
        val title = doc.selectFirst(EVAL_INFO_TITLE)?.text()?.trim().orEmpty()
        val japaneseTitle = doc.selectFirst(EVAL_INFO_TITLE_JP)?.text()?.trim().orEmpty()
            .let { if (it == "-") "" else it }
        val synopsis = doc.selectFirst(EVAL_INFO_DES)?.text()?.trim().orEmpty()
        val tags = doc.select(EVAL_INFO_TAGS).mapNotNull {
            it.text().trim().takeIf { t -> t.isNotBlank() }
        }

        val infoYearEl = doc.selectFirst(EVAL_INFO_YEAR)
        val metaValues = infoYearEl?.select(EVAL_INLINE_C2)?.map { it.text().trim() }.orEmpty()
        val episodesLabel = metaValues.getOrElse(0) { "?" }
        val year = metaValues.getOrElse(1) { "?" }
        val score = metaValues.getOrElse(2) { "?" }

        val countdownLabel = doc.select(EVAL_INFO2)
            .firstOrNull { it.text().contains("Countdown", ignoreCase = true) }
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        val episodes = doc.select(EVAL_GATE_LINKS).mapNotNull { epLink ->
            val key = epLink.attr("id").trim().ifBlank {
                GATE_KEY_REGEX.find(epLink.attr("onclick"))?.groupValues?.getOrNull(1).orEmpty()
            }
            if (key.isBlank()) return@mapNotNull null

            val numberLabel = epLink.selectFirst(EVAL_WATCH2)?.text()?.trim().orEmpty()
            val watch1s = epLink.select(EVAL_WATCH1)
            val releaseAge = if (watch1s.size > 1) watch1s.last()?.text()?.trim().orEmpty() else ""

            EpisodeItem(
                key = key,
                numberLabel = numberLabel,
                releaseAge = releaseAge
            )
        }

        var relatedShows = emptyList<AnimeCard>()
        var similarShows = emptyList<AnimeCard>()
        doc.select(EVAL_LINE_TITLE).forEach { header ->
            val label = header.text().trim()
            val nextInfo3 = header.nextElementSibling()?.takeIf { it.hasClass("info3") }
            if (nextInfo3 != null) {
                when {
                    label.contains("Related Shows", ignoreCase = true) -> {
                        relatedShows = parseSimilarContainers(listOf(nextInfo3))
                    }
                    label.contains("Similar Shows", ignoreCase = true) -> {
                        similarShows = parseSimilarContainers(listOf(nextInfo3))
                    }
                }
            }
        }

        return AnimeDetail(
            id = animeId,
            title = title,
            japaneseTitle = japaneseTitle,
            bannerUrl = bannerUrl,
            posterUrl = posterUrl,
            synopsis = synopsis,
            tags = tags,
            episodesLabel = episodesLabel,
            year = year,
            score = score,
            countdownLabel = countdownLabel,
            episodes = episodes,
            relatedShows = relatedShows,
            similarShows = similarShows
        )
    }

    fun parseGatePage(episodeKey: String, html: String): EpisodeStream {
        val doc = Jsoup.parse(html, AnimeHeavenClient.BASE_URL)

        val headerEl = doc.selectFirst(EVAL_LINE_TITLE3)
        val animeLink = headerEl?.selectFirst(EVAL_ANIME_LINKS)
        val animeId = extractAnimeId(animeLink?.attr("href"))
        val animeTitle = animeLink?.text()?.trim().orEmpty()
        val fullHeaderText = headerEl?.text()?.trim().orEmpty()
        val episodeLabel = if (animeTitle.isNotBlank() && fullHeaderText.startsWith(animeTitle)) {
            fullHeaderText.removePrefix(animeTitle).trim()
        } else {
            fullHeaderText
        }

        val videoSources = doc.select(EVAL_VIDEO_SOURCES)
            .mapNotNull { srcEl ->
                normalizeUrl(srcEl.attr("src")).takeIf { it.isNotBlank() }
            }
            .distinct()

        val navLinks = doc.select(EVAL_GATE_LINKS).mapNotNull { link ->
            val key = GATE_KEY_REGEX.find(link.attr("onclick"))?.groupValues?.getOrNull(1)
                ?: link.attr("id").trim().takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val label = link.selectFirst(EVAL_BOX_ITEM)?.text()?.trim().orEmpty()
                .ifBlank { link.text().trim() }
            key to label
        }

        val currentEpNum = extractLeadingNumber(episodeLabel)
        var prevKey: String? = null
        var prevLabel: String? = null
        var nextKey: String? = null
        var nextLabel: String? = null

        when (navLinks.size) {
            1 -> {
                val (key, label) = navLinks[0]
                val navNum = extractLeadingNumber(label)
                if (currentEpNum != null && navNum != null && navNum > currentEpNum) {
                    nextKey = key
                    nextLabel = label
                } else {
                    prevKey = key
                    prevLabel = label
                }
            }
            else -> if (navLinks.size >= 2) {
                val first = navLinks[0]
                val second = navLinks[1]
                val firstNum = extractLeadingNumber(first.second)
                val secondNum = extractLeadingNumber(second.second)
                if (firstNum != null && secondNum != null && firstNum > secondNum) {
                    prevKey = second.first
                    prevLabel = second.second
                    nextKey = first.first
                    nextLabel = first.second
                } else {
                    prevKey = first.first
                    prevLabel = first.second
                    nextKey = second.first
                    nextLabel = second.second
                }
            }
        }

        return EpisodeStream(
            episodeKey = episodeKey,
            animeId = animeId,
            animeTitle = animeTitle,
            episodeLabel = episodeLabel,
            videoSources = videoSources,
            prevEpisodeKey = prevKey,
            prevEpisodeLabel = prevLabel,
            nextEpisodeKey = nextKey,
            nextEpisodeLabel = nextLabel
        )
    }

    private fun extractLeadingNumber(text: String): Double? =
        EPISODE_NUMBER_REGEX.find(text)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
}
