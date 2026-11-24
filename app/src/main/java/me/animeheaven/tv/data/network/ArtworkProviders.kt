package me.animeheaven.tv.data.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import me.animeheaven.tv.data.model.AnimeArtwork
import me.animeheaven.tv.data.model.ArtworkLookupRequest
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal object ArtworkProviders {
    private const val FIELDS = "id idMal title{english romaji native} synonyms seasonYear format popularity coverImage{extraLarge large color} bannerImage trailer{id site}"
    private val youtubeId = Regex("[A-Za-z0-9_-]{11}")

    fun aniListBody(requests: List<ArtworkLookupRequest>): String {
        require(requests.size in 1..8)
        val args = requests.indices.joinToString(",") { "\$s$it:String!" }
        val aliases = requests.indices.joinToString("") {
            "a$it:Page(perPage:5){media(search:\$s$it,type:ANIME,sort:[SEARCH_MATCH,POPULARITY_DESC]){$FIELDS}}"
        }
        return buildJsonObject {
            put("query", "query($args){$aliases}")
            put("variables", buildJsonObject { requests.forEachIndexed { i, request -> put("s$i", request.title) } })
        }.toString()
    }

    private fun parse(data: String): JsonObject? = runCatching { Json.parseToJsonElement(data) as? JsonObject }.getOrNull()
    private fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun JsonObject.string(key: String): String? = get(key).string()
    private fun JsonObject.int(key: String): Int? = (get(key) as? JsonPrimitive)?.intOrNull
    private fun JsonObject.bool(key: String): Boolean = (get(key) as? JsonPrimitive)?.booleanOrNull == true
    private fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject
    private fun JsonObject.array(key: String): JsonArray? = get(key) as? JsonArray
    private fun JsonObject.rows(key: String): List<JsonObject> = array(key)?.filterIsInstance<JsonObject>().orEmpty()
    private fun JsonObject.url(key: String): String? = string(key)?.takeIf { raw ->
        raw.toHttpUrlOrNull()?.let { it.isHttps && it.username.isEmpty() && it.password.isEmpty() } == true
    }

    fun aniList(data: String, requests: List<ArtworkLookupRequest>): List<AnimeArtwork?> {
        val root = parse(data)?.obj("data")
        return requests.mapIndexed { i, request ->
            val rows = root?.obj("a$i")?.rows("media").orEmpty()
            val titles = rows.map { row ->
                val title = row.obj("title")
                ArtworkTitleMatcher.Candidate(
                    listOfNotNull(title?.string("english"), title?.string("romaji"), title?.string("native")) +
                        row.array("synonyms").orEmpty().mapNotNull { it.string() },
                    row.int("seasonYear"), row.string("format").orEmpty(), row.int("popularity") ?: 0
                )
            }
            ArtworkTitleMatcher.bestIndex(request, titles)?.let { rows[it] }?.let { row ->
                val cover = row.obj("coverImage")
                val trailer = row.obj("trailer")
                AnimeArtwork(
                    anilistId = row.int("id"), malId = row.int("idMal"),
                    aniListPoster = cover?.url("extraLarge") ?: cover?.url("large"),
                    aniListBanner = row.url("bannerImage"), accentColorHex = cover?.string("color"),
                    trailerId = trailer?.string("id")?.takeIf {
                        trailer.string("site").equals("youtube", true) && youtubeId.matches(it)
                    }
                )
            }
        }
    }

    fun aniZip(data: String): AnimeArtwork? {
        val root = parse(data) ?: return null
        if (root.array("images") == null && root.obj("episodes") == null) return null
        val images = root.rows("images")
        fun image(type: String) = images.firstNotNullOfOrNull {
            if (it.string("coverType").equals(type, true)) it.url("url") else null
        }
        val episodes = root.obj("episodes").orEmpty().mapNotNull { (key, value) ->
            val number = key.toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            (value as? JsonObject)?.url("image")?.let { number to it }
        }.toMap()
        return AnimeArtwork(
            malId = root.obj("mappings")?.int("mal_id"), aniZipPoster = image("poster"),
            aniZipBanner = image("banner"), landscapeFanartUrl = image("fanart"),
            episodeThumbs = episodes, aniZipChecked = true
        )
    }

    fun kitsu(data: String, request: ArtworkLookupRequest): AnimeArtwork? {
        val root = parse(data) ?: return null
        if (root.array("data") == null) return null
        val rows = root.rows("data").mapNotNull { it.obj("attributes") }
        val titles = rows.map { row ->
            ArtworkTitleMatcher.Candidate(
                row.obj("titles").orEmpty().values.mapNotNull { it.string() } +
                    row.array("abbreviatedTitles").orEmpty().mapNotNull { it.string() } +
                    listOfNotNull(row.string("canonicalTitle")),
                row.string("startDate")?.take(4)?.toIntOrNull(), row.string("subtype").orEmpty(), row.int("userCount") ?: 0
            )
        }
        val row = ArtworkTitleMatcher.bestIndex(request, titles)?.let { rows[it] }
        val poster = row?.obj("posterImage")
        val cover = row?.obj("coverImage")
        return AnimeArtwork(
            kitsuPoster = poster?.url("large") ?: poster?.url("original") ?: poster?.url("medium"),
            kitsuBanner = cover?.url("large") ?: cover?.url("small") ?: cover?.url("original"),
            kitsuChecked = true
        )
    }

    fun jikan(data: String, request: ArtworkLookupRequest): AnimeArtwork? {
        val root = parse(data) ?: return null
        val row = root.obj("data") ?: run {
            if (root.array("data") == null) return null
            val rows = root.rows("data")
            val titles = rows.map { row ->
                ArtworkTitleMatcher.Candidate(
                    row.rows("titles").mapNotNull { item -> item.string("title") } +
                        listOf("title", "title_english", "title_japanese").mapNotNull { row.string(it) },
                    row.int("year") ?: row.obj("aired")?.string("from")?.take(4)?.toIntOrNull(),
                    row.string("type").orEmpty(), row.int("members") ?: 0
                )
            }
            ArtworkTitleMatcher.bestIndex(request, titles)?.let { rows[it] }
        }
        val images = row?.obj("images")
        val trailer = row?.obj("trailer")?.obj("images")
        return AnimeArtwork(
            malId = row?.int("mal_id"),
            jikanPoster = images?.obj("webp")?.url("large_image_url") ?: images?.obj("jpg")?.url("large_image_url"),
            jikanTrailer = trailer?.url("maximum_image_url") ?: trailer?.url("large_image_url"),
            jikanChecked = true
        )
    }

    fun encode(art: AnimeArtwork): String = buildJsonObject {
        art.anilistId?.let { put("anilistId", it) }
        art.malId?.let { put("malId", it) }
        mapOf(
            "aniListPoster" to art.aniListPoster, "aniListBanner" to art.aniListBanner,
            "accentColorHex" to art.accentColorHex, "trailerId" to art.trailerId,
            "aniZipPoster" to art.aniZipPoster, "aniZipBanner" to art.aniZipBanner,
            "landscapeFanartUrl" to art.landscapeFanartUrl, "kitsuPoster" to art.kitsuPoster,
            "kitsuBanner" to art.kitsuBanner, "jikanPoster" to art.jikanPoster, "jikanTrailer" to art.jikanTrailer
        ).forEach { (key, value) -> value?.let { put(key, it) } }
        put("episodeThumbs", buildJsonObject { art.episodeThumbs.forEach { (k, v) -> put(k.toString(), v) } })
        put("aniZipChecked", art.aniZipChecked)
        put("kitsuChecked", art.kitsuChecked)
        put("jikanChecked", art.jikanChecked)
    }.toString()

    fun decode(data: String): AnimeArtwork? = parse(data)?.let { row ->
        AnimeArtwork(
            row.int("anilistId"), row.int("malId"), row.string("aniListPoster"), row.string("aniListBanner"),
            row.string("accentColorHex"), row.string("trailerId"), row.string("aniZipPoster"), row.string("aniZipBanner"),
            row.string("landscapeFanartUrl"), row.obj("episodeThumbs").orEmpty().mapNotNull { (k, v) ->
                val number = k.toIntOrNull() ?: return@mapNotNull null
                v.string()?.let { number to it }
            }.toMap(), row.string("kitsuPoster"), row.string("kitsuBanner"), row.string("jikanPoster"),
            row.string("jikanTrailer"), row.bool("aniZipChecked"), row.bool("kitsuChecked"), row.bool("jikanChecked")
        )
    }
}
