package me.animeheaven.tv.data.cache

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import me.animeheaven.tv.data.model.AnimeCard
import me.animeheaven.tv.data.model.AnimeDetail
import me.animeheaven.tv.data.model.BannerItem
import me.animeheaven.tv.data.model.EpisodeItem
import me.animeheaven.tv.data.model.EpisodeStream
import me.animeheaven.tv.data.model.FastSearchItem
import me.animeheaven.tv.data.model.HomeFeed
import me.animeheaven.tv.data.model.RankDirection

/**
 * Pure-Kotlin zero-AST streaming JSON snapshot codec & gzip HTML codec so cold-start cache reads
 * bypass heavy Jsoup DOM parsing and intermediate Map/List allocations (<2ms hydration, minimal GC).
 * Runs cleanly on API 24+ and JVM tests.
 */
object CacheSnapshotCodec {

    private const val GZ_PREFIX = "GZ:"
    private const val MAX_HTML_BYTES = 16 * 1024 * 1024
    private val JSON_NUMBER = Regex("""-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?""")

    fun compressText(raw: String): String {
        if (raw.length < 512) return raw
        return runCatching {
            val baos = ByteArrayOutputStream(raw.length / 4)
            GZIPOutputStream(baos).use { gz ->
                gz.write(raw.toByteArray(StandardCharsets.UTF_8))
            }
            GZ_PREFIX + encodeBase64(baos.toByteArray())
        }.getOrDefault(raw)
    }

    fun decompressText(stored: String): String? {
        if (!stored.startsWith(GZ_PREFIX)) return stored
        if (stored.length > MAX_HTML_BYTES * 2) return null
        return runCatching {
            val bytes = decodeBase64(stored.substring(GZ_PREFIX.length))
            GZIPInputStream(ByteArrayInputStream(bytes)).use { gz ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                while (true) {
                    val count = gz.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > MAX_HTML_BYTES) return null
                    output.write(buffer, 0, count)
                }
                output.toString(StandardCharsets.UTF_8.name())
            }
        }.getOrNull()
    }

    fun encodeHomeFeed(feed: HomeFeed): String = buildString(4096) {
        append('{')
        writeStrField("seasonPath", feed.seasonPath, first = true)
        writeStrField("seasonLabel", feed.seasonLabel)
        writeArrayField("banners", feed.banners) { b ->
            append('{')
            writeStrField("id", b.animeId, first = true)
            writeStrField("title", b.title)
            writeStrField("img", b.bannerImageUrl)
            append('}')
        }
        writeArrayField("schedule", feed.scheduleItems) { writeAnimeCard(it) }
        writeArrayField("popular", feed.popularItems) { writeAnimeCard(it) }
        append('}')
    }

    fun decodeHomeFeed(json: String): HomeFeed? = runCatching {
        val r = StreamingJsonReader(json)
        var seasonPath: String? = null
        var seasonLabel: String? = null
        var banners: List<BannerItem> = emptyList()
        var schedule: List<AnimeCard> = emptyList()
        var popular: List<AnimeCard> = emptyList()

        r.readObject { key ->
            when (key) {
                "seasonPath" -> seasonPath = r.readNullableString()
                "seasonLabel" -> seasonLabel = r.readNullableString()
                "banners" -> banners = r.readListNotNull { readBannerItem(r) }
                "schedule" -> schedule = r.readListNotNull { readAnimeCard(r) }
                "popular" -> popular = r.readListNotNull { readAnimeCard(r) }
                else -> r.skipValue()
            }
        }
        r.expectEof()
        HomeFeed(
            banners = banners,
            scheduleItems = schedule,
            popularItems = popular,
            seasonPath = seasonPath,
            seasonLabel = seasonLabel
        )
    }.getOrNull()

    fun encodeChart(chart: Pair<String, List<AnimeCard>>): String = buildString(4096) {
        append('{')
        writeStrField("title", chart.first, first = true)
        writeArrayField("items", chart.second) { writeAnimeCard(it) }
        append('}')
    }

    fun decodeChart(json: String): Pair<String, List<AnimeCard>>? = runCatching {
        val r = StreamingJsonReader(json)
        var title = ""
        var items: List<AnimeCard> = emptyList()

        r.readObject { key ->
            when (key) {
                "title" -> title = r.readNullableString().orEmpty()
                "items" -> items = r.readListNotNull { readAnimeCard(r) }
                else -> r.skipValue()
            }
        }
        r.expectEof()
        title to items
    }.getOrNull()

    fun encodeAnimeDetail(detail: AnimeDetail): String = buildString(4096) {
        append('{')
        writeStrField("id", detail.id, first = true)
        writeStrField("title", detail.title)
        writeStrField("jp", detail.japaneseTitle)
        writeStrField("banner", detail.bannerUrl)
        writeStrField("poster", detail.posterUrl)
        writeStrField("landscape", detail.landscapeCoverUrl)
        writeStrField("synopsis", detail.synopsis)
        writeArrayField("tags", detail.tags) { writeJsonString(it) }
        writeStrField("epLabel", detail.episodesLabel)
        writeStrField("year", detail.year)
        writeStrField("score", detail.score)
        writeStrField("countdown", detail.countdownLabel)
        writeArrayField("episodes", detail.episodes) { ep ->
            append('{')
            writeStrField("k", ep.key, first = true)
            writeStrField("n", ep.numberLabel)
            writeStrField("a", ep.releaseAge)
            append('}')
        }
        writeArrayField("related", detail.relatedShows) { writeAnimeCard(it) }
        writeArrayField("similar", detail.similarShows) { writeAnimeCard(it) }
        append('}')
    }

    fun decodeAnimeDetail(json: String): AnimeDetail? = runCatching {
        val r = StreamingJsonReader(json)
        var id: String? = null
        var title = ""
        var jp = ""
        var banner: String? = null
        var poster = ""
        var landscape: String? = null
        var synopsis = ""
        var tags: List<String> = emptyList()
        var epLabel = ""
        var year = ""
        var score = ""
        var countdown: String? = null
        var episodes: List<EpisodeItem> = emptyList()
        var related: List<AnimeCard> = emptyList()
        var similar: List<AnimeCard> = emptyList()

        r.readObject { key ->
            when (key) {
                "id" -> id = r.readNullableString()
                "title" -> title = r.readNullableString().orEmpty()
                "jp" -> jp = r.readNullableString().orEmpty()
                "banner" -> banner = r.readNullableString()
                "poster" -> poster = r.readNullableString().orEmpty()
                "landscape" -> landscape = r.readNullableString()
                "synopsis" -> synopsis = r.readNullableString().orEmpty()
                "tags" -> tags = r.readListNotNull { r.readNullableString() }
                "epLabel" -> epLabel = r.readNullableString().orEmpty()
                "year" -> year = r.readNullableString().orEmpty()
                "score" -> score = r.readNullableString().orEmpty()
                "countdown" -> countdown = r.readNullableString()
                "episodes" -> episodes = r.readListNotNull { readEpisodeItem(r) }
                "related" -> related = r.readListNotNull { readAnimeCard(r) }
                "similar" -> similar = r.readListNotNull { readAnimeCard(r) }
                else -> r.skipValue()
            }
        }
        r.expectEof()
        AnimeDetail(
            id = id ?: return null,
            title = title,
            japaneseTitle = jp,
            bannerUrl = banner,
            posterUrl = poster,
            synopsis = synopsis,
            tags = tags,
            episodesLabel = epLabel,
            year = year,
            score = score,
            countdownLabel = countdown,
            episodes = episodes,
            relatedShows = related,
            similarShows = similar,
            landscapeCoverUrl = landscape
        )
    }.getOrNull()

    fun encodeEpisodeStream(stream: EpisodeStream): String = buildString(512) {
        append('{')
        writeStrField("k", stream.episodeKey, first = true)
        writeStrField("aid", stream.animeId)
        writeStrField("atitle", stream.animeTitle)
        writeStrField("ep", stream.episodeLabel)
        writeArrayField("srcs", stream.videoSources) { writeJsonString(it) }
        writeStrField("pk", stream.prevEpisodeKey)
        writeStrField("pl", stream.prevEpisodeLabel)
        writeStrField("nk", stream.nextEpisodeKey)
        writeStrField("nl", stream.nextEpisodeLabel)
        append('}')
    }

    fun decodeEpisodeStream(json: String): EpisodeStream? = runCatching {
        val r = StreamingJsonReader(json)
        var episodeKey: String? = null
        var animeId: String? = null
        var animeTitle = ""
        var episodeLabel = ""
        var videoSources: List<String> = emptyList()
        var prevKey: String? = null
        var prevLabel: String? = null
        var nextKey: String? = null
        var nextLabel: String? = null

        r.readObject { key ->
            when (key) {
                "k" -> episodeKey = r.readNullableString()
                "aid" -> animeId = r.readNullableString()
                "atitle" -> animeTitle = r.readNullableString().orEmpty()
                "ep" -> episodeLabel = r.readNullableString().orEmpty()
                "srcs" -> videoSources = r.readListNotNull { r.readNullableString() }
                "pk" -> prevKey = r.readNullableString()
                "pl" -> prevLabel = r.readNullableString()
                "nk" -> nextKey = r.readNullableString()
                "nl" -> nextLabel = r.readNullableString()
                else -> r.skipValue()
            }
        }
        r.expectEof()
        EpisodeStream(
            episodeKey = episodeKey ?: return null,
            animeId = animeId,
            animeTitle = animeTitle,
            episodeLabel = episodeLabel,
            videoSources = videoSources,
            prevEpisodeKey = prevKey,
            prevEpisodeLabel = prevLabel,
            nextEpisodeKey = nextKey,
            nextEpisodeLabel = nextLabel
        )
    }.getOrNull()

    fun encodeFastSearch(items: List<FastSearchItem>): String = buildString(1024) {
        append('[')
        items.forEachIndexed { idx, item ->
            if (idx > 0) append(',')
            append('{')
            writeStrField("id", item.animeId, first = true)
            writeStrField("t", item.title)
            writeStrField("c", item.coverUrl)
            append('}')
        }
        append(']')
    }

    fun decodeFastSearch(json: String): List<FastSearchItem>? = runCatching {
        val r = StreamingJsonReader(json)
        val result = r.readListNotNull { readFastSearchItem(r) }
        r.expectEof()
        result
    }.getOrNull()

    private fun readBannerItem(r: StreamingJsonReader): BannerItem? {
        var id: String? = null
        var title = ""
        var img = ""
        r.readObject { key ->
            when (key) {
                "id" -> id = r.readNullableString()
                "title" -> title = r.readNullableString().orEmpty()
                "img" -> img = r.readNullableString().orEmpty()
                else -> r.skipValue()
            }
        }
        return BannerItem(
            animeId = id ?: return null,
            title = title,
            bannerImageUrl = img
        )
    }

    private fun readEpisodeItem(r: StreamingJsonReader): EpisodeItem? {
        var epKey: String? = null
        var num = ""
        var age = ""
        r.readObject { key ->
            when (key) {
                "k" -> epKey = r.readNullableString()
                "n" -> num = r.readNullableString().orEmpty()
                "a" -> age = r.readNullableString().orEmpty()
                else -> r.skipValue()
            }
        }
        return EpisodeItem(
            key = epKey ?: return null,
            numberLabel = num,
            releaseAge = age
        )
    }

    private fun readFastSearchItem(r: StreamingJsonReader): FastSearchItem? {
        var id: String? = null
        var title = ""
        var cover = ""
        r.readObject { key ->
            when (key) {
                "id" -> id = r.readNullableString()
                "t" -> title = r.readNullableString().orEmpty()
                "c" -> cover = r.readNullableString().orEmpty()
                else -> r.skipValue()
            }
        }
        return FastSearchItem(
            animeId = id ?: return null,
            title = title,
            coverUrl = cover
        )
    }

    private fun readAnimeCard(r: StreamingJsonReader): AnimeCard? {
        var id: String? = null
        var title = ""
        var jp = ""
        var cover = ""
        var ep = ""
        var timer = ""
        var rankDelta: String? = null
        var dirStr: String? = null

        r.readObject { key ->
            when (key) {
                "id" -> id = r.readNullableString()
                "t" -> title = r.readNullableString().orEmpty()
                "jp" -> jp = r.readNullableString().orEmpty()
                "c" -> cover = r.readNullableString().orEmpty()
                "ep" -> ep = r.readNullableString().orEmpty()
                "tm" -> timer = r.readNullableString().orEmpty()
                "rd" -> rankDelta = r.readNullableString()
                "dir" -> dirStr = r.readNullableString()
                else -> r.skipValue()
            }
        }
        val dir = when (dirStr) {
            "UP" -> RankDirection.UP
            "DOWN" -> RankDirection.DOWN
            "SAME" -> RankDirection.SAME
            else -> null
        }
        return AnimeCard(
            id = id ?: return null,
            title = title,
            japaneseTitle = jp,
            coverUrl = cover,
            episodeLabel = ep,
            timerOrStatus = timer,
            rankDelta = rankDelta,
            rankDirection = dir
        )
    }

    private fun StringBuilder.writeAnimeCard(c: AnimeCard) {
        append('{')
        writeStrField("id", c.id, first = true)
        writeStrField("t", c.title)
        writeStrField("jp", c.japaneseTitle)
        writeStrField("c", c.coverUrl)
        writeStrField("ep", c.episodeLabel)
        writeStrField("tm", c.timerOrStatus)
        writeStrField("rd", c.rankDelta)
        writeStrField("dir", c.rankDirection?.name)
        append('}')
    }

    private fun StringBuilder.writeStrField(name: String, value: String?, first: Boolean = false) {
        if (!first) append(',')
        writeJsonString(name)
        append(':')
        if (value == null) append("null") else writeJsonString(value)
    }

    private inline fun <T> StringBuilder.writeArrayField(
        name: String,
        items: List<T>,
        writer: StringBuilder.(T) -> Unit
    ) {
        append(',')
        writeJsonString(name)
        append(":[")
        for (idx in items.indices) {
            if (idx > 0) append(',')
            writer(items[idx])
        }
        append(']')
    }

    private fun StringBuilder.writeJsonString(value: String) {
        append('"')
        var runStart = 0
        val len = value.length
        for (i in 0 until len) {
            val ch = value[i]
            if (ch == '"' || ch == '\\' || ch.code < 0x20) {
                if (i > runStart) append(value, runStart, i)
                when (ch) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> {
                        append("\\u")
                        append(ch.code.toString(16).padStart(4, '0'))
                    }
                }
                runStart = i + 1
            }
        }
        if (runStart < len) append(value, runStart, len)
        append('"')
    }

    /**
     * Single-pass pull reader that decodes directly into Kotlin models without building
     * intermediate Map/List ASTs.
     */
    private class StreamingJsonReader(private val s: String) {
        private var pos = 0
        private val len = s.length

        fun skipWs() {
            while (pos < len) {
                val c = s[pos]
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') pos++ else break
            }
        }

        fun expectEof() {
            skipWs()
            if (pos != len) throw IllegalArgumentException("Trailing JSON content at $pos")
        }

        inline fun readObject(onField: (String) -> Unit) {
            skipWs()
            if (pos >= len || s[pos] != '{') throw IllegalArgumentException("Expected '{' at $pos")
            pos++
            skipWs()
            if (pos < len && s[pos] == '}') {
                pos++
                return
            }
            while (true) {
                skipWs()
                val key = readString()
                skipWs()
                if (pos >= len || s[pos] != ':') throw IllegalArgumentException("Expected ':' at $pos")
                pos++
                onField(key)
                skipWs()
                if (pos >= len) throw IllegalArgumentException("Unterminated object")
                when (s[pos]) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return
                    }
                    else -> throw IllegalArgumentException("Expected ',' or '}' at $pos")
                }
            }
        }

        inline fun <T : Any> readListNotNull(elementReader: () -> T?): List<T> {
            skipWs()
            if (pos < len && s[pos] == 'n') {
                expect("null")
                return emptyList()
            }
            if (pos >= len || s[pos] != '[') throw IllegalArgumentException("Expected '[' at $pos")
            pos++
            skipWs()
            if (pos < len && s[pos] == ']') {
                pos++
                return emptyList()
            }
            val result = ArrayList<T>()
            while (true) {
                elementReader()?.let { result.add(it) }
                skipWs()
                if (pos >= len) throw IllegalArgumentException("Unterminated array")
                when (s[pos]) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return result
                    }
                    else -> throw IllegalArgumentException("Expected ',' or ']' at $pos")
                }
            }
        }

        fun readNullableString(): String? {
            skipWs()
            if (pos >= len) throw IllegalArgumentException("Unexpected EOF")
            return when (s[pos]) {
                'n' -> {
                    expect("null")
                    null
                }
                '"' -> readString()
                else -> {
                    skipValue()
                    null
                }
            }
        }

        fun readString(): String {
            skipWs()
            if (pos >= len || s[pos] != '"') throw IllegalArgumentException("Expected '\"' at $pos")
            pos++
            val start = pos
            // Fast path: scan until closing quote or first escape
            while (pos < len) {
                val c = s[pos]
                if (c == '"') {
                    val out = s.substring(start, pos)
                    pos++
                    return out
                }
                if (c == '\\') {
                    return readEscapedString(start)
                }
                require(c >= ' ') { "Unescaped control character" }
                pos++
            }
            throw IllegalArgumentException("Unterminated string")
        }

        private fun readEscapedString(start: Int): String {
            val sb = StringBuilder(maxOf(16, (pos - start) + 16))
            if (pos > start) sb.append(s, start, pos)
            while (pos < len) {
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= len) throw IllegalArgumentException("Unterminated escape")
                        when (val esc = s[pos++]) {
                            '"', '\\', '/' -> sb.append(esc)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > len) throw IllegalArgumentException("Bad unicode")
                                var code = 0
                                repeat(4) { index ->
                                    val hex = s[pos + index]
                                    val digit = when (hex) {
                                        in '0'..'9' -> hex - '0'
                                        in 'a'..'f' -> hex - 'a' + 10
                                        in 'A'..'F' -> hex - 'A' + 10
                                        else -> throw IllegalArgumentException("Bad Unicode escape")
                                    }
                                    code = (code shl 4) or digit
                                }
                                sb.append(code.toChar())
                                pos += 4
                            }
                            else -> throw IllegalArgumentException("Bad escape \\$esc")
                        }
                    }
                    else -> {
                        require(c >= ' ') { "Unescaped control character" }
                        sb.append(c)
                    }
                }
            }
            throw IllegalArgumentException("Unterminated string")
        }

        fun skipValue(depth: Int = 0) {
            require(depth < 64) { "Excessively nested JSON" }
            skipWs()
            if (pos >= len) throw IllegalArgumentException("Unexpected EOF")
            when (val c = s[pos]) {
                '"' -> readString()
                '{' -> readObject { skipValue(depth + 1) }
                '[' -> {
                    pos++
                    skipWs()
                    if (pos < len && s[pos] == ']') {
                        pos++
                        return
                    }
                    while (true) {
                        skipValue(depth + 1)
                        skipWs()
                        if (pos >= len) throw IllegalArgumentException("Unterminated array")
                        when (s[pos]) {
                            ',' -> pos++
                            ']' -> {
                                pos++
                                return
                            }
                            else -> throw IllegalArgumentException("Expected ',' or ']'")
                        }
                    }
                }
                'n' -> expect("null")
                't' -> expect("true")
                'f' -> expect("false")
                '-', in '0'..'9' -> {
                    val match = JSON_NUMBER.matchAt(s, pos)
                        ?: throw IllegalArgumentException("Invalid JSON number")
                    pos = match.range.last + 1
                }
                else -> throw IllegalArgumentException("Unexpected char '$c' at $pos")
            }
        }

        private fun expect(token: String) {
            if (!s.startsWith(token, pos)) throw IllegalArgumentException("Expected $token at $pos")
            pos += token.length
        }
    }

    private val B64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private val B64_REVERSE = IntArray(128) { -1 }.apply {
        for (i in B64_ALPHABET.indices) this[B64_ALPHABET[i].code] = i
    }

    private fun encodeBase64(data: ByteArray): String {
        val sb = StringBuilder(((data.size + 2) / 3) * 4)
        var i = 0
        while (i < data.size) {
            val b0 = data[i++].toInt() and 0xFF
            val hasB1 = i < data.size
            val b1 = if (hasB1) data[i++].toInt() and 0xFF else 0
            val hasB2 = i < data.size
            val b2 = if (hasB2) data[i++].toInt() and 0xFF else 0
            val triple = (b0 shl 16) or (b1 shl 8) or b2
            sb.append(B64_ALPHABET[(triple shr 18) and 0x3F])
            sb.append(B64_ALPHABET[(triple shr 12) and 0x3F])
            sb.append(if (hasB1) B64_ALPHABET[(triple shr 6) and 0x3F] else '=')
            sb.append(if (hasB2) B64_ALPHABET[triple and 0x3F] else '=')
        }
        return sb.toString()
    }

    private fun decodeBase64(str: String): ByteArray {
        val cleanLen = str.length
        require(cleanLen > 0 && cleanLen % 4 == 0) { "Invalid Base64 length" }
        var pad = 0
        if (cleanLen >= 1 && str[cleanLen - 1] == '=') pad++
        if (cleanLen >= 2 && str[cleanLen - 2] == '=') pad++
        val out = ByteArray((cleanLen / 4) * 3 - pad)
        fun digit(char: Char): Int = B64_REVERSE.getOrElse(char.code) { -1 }
            .also { require(it >= 0) { "Invalid Base64 character" } }
        var outIdx = 0
        var i = 0
        while (i < cleanLen) {
            val c0 = digit(str[i++])
            val c1 = digit(str[i++])
            val c2c = str[i++]
            val c3c = str[i++]
            val padded = i == cleanLen && pad > 0
            val c2 = if (padded && pad == 2) 0 else digit(c2c)
            val c3 = if (padded) 0 else digit(c3c)
            require(!padded || if (pad == 2) c1 and 15 == 0 else c2 and 3 == 0) {
                "Invalid Base64 padding bits"
            }
            val triple = (c0 shl 18) or (c1 shl 12) or (c2 shl 6) or c3
            out[outIdx++] = ((triple shr 16) and 0xFF).toByte()
            if (c2c != '=' && outIdx < out.size) out[outIdx++] = ((triple shr 8) and 0xFF).toByte()
            if (c3c != '=' && outIdx < out.size) out[outIdx++] = (triple and 0xFF).toByte()
        }
        return out
    }
}
