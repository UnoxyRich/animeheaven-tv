package me.animeheaven.tv.data.model

import androidx.compose.runtime.Immutable
import me.animeheaven.tv.data.network.ArtworkTitleMatcher

enum class ArtworkSlot { PORTRAIT_2_3, LANDSCAPE_16_9, WIDE_BANNER }
enum class ArtworkPriority { CACHED_ONLY, NORMAL, FEATURED, HIGH }

@Immutable
data class ArtworkLookupRequest(
    val animeId: String,
    val title: String,
    val japaneseTitle: String = "",
    val year: Int? = null,
    val priority: ArtworkPriority = ArtworkPriority.NORMAL
) {
    internal val titleKey get() = "artwork:title:${ArtworkTitleMatcher.normalize(title)}${year?.let { ":$it" }.orEmpty()}"
    internal val animeKey get() = if (animeId.isEmpty()) titleKey else "artwork:ah:$animeId"
}

@Immutable
data class AnimeArtwork(
    val anilistId: Int? = null,
    val malId: Int? = null,
    val aniListPoster: String? = null,
    val aniListBanner: String? = null,
    val accentColorHex: String? = null,
    val trailerId: String? = null,
    val aniZipPoster: String? = null,
    val aniZipBanner: String? = null,
    val landscapeFanartUrl: String? = null,
    val episodeThumbs: Map<Int, String> = emptyMap(),
    val kitsuPoster: String? = null,
    val kitsuBanner: String? = null,
    val jikanPoster: String? = null,
    val jikanTrailer: String? = null,
    internal val aniZipChecked: Boolean = false,
    internal val kitsuChecked: Boolean = false,
    internal val jikanChecked: Boolean = false
) {
    fun urls(
        slot: ArtworkSlot,
        episodeNumber: Int? = null,
        fallbackUrl: String = "",
        nativeLandscapeFirst: Boolean = false
    ): List<String> = when (slot) {
        ArtworkSlot.PORTRAIT_2_3 -> listOf(aniZipPoster, aniListPoster, kitsuPoster, jikanPoster, fallbackUrl)
        ArtworkSlot.WIDE_BANNER -> listOf(aniListBanner, kitsuBanner, landscapeFanartUrl, aniZipBanner, fallbackUrl)
        ArtworkSlot.LANDSCAPE_16_9 -> if (episodeNumber != null || nativeLandscapeFirst) {
            listOf(episodeThumbs[episodeNumber], landscapeFanartUrl, fallbackUrl)
        } else {
            val trailer = trailerId?.let { "https://i.ytimg.com/vi/$it/maxresdefault.jpg" }
            val trailerMedium = trailerId?.let { "https://i.ytimg.com/vi/$it/mqdefault.jpg" }
            listOf(landscapeFanartUrl, trailer, jikanTrailer, trailerMedium, aniListBanner, kitsuBanner, fallbackUrl)
        }
    }.filterNotNull().filter(String::isNotBlank).distinct()

    fun bestUrl(slot: ArtworkSlot, episodeNumber: Int? = null, fallbackUrl: String): String =
        urls(slot, episodeNumber, fallbackUrl).firstOrNull() ?: fallbackUrl

    internal fun merge(other: AnimeArtwork): AnimeArtwork = AnimeArtwork(
        anilistId ?: other.anilistId, malId ?: other.malId,
        aniListPoster ?: other.aniListPoster, aniListBanner ?: other.aniListBanner,
        accentColorHex ?: other.accentColorHex, trailerId ?: other.trailerId,
        aniZipPoster ?: other.aniZipPoster, aniZipBanner ?: other.aniZipBanner,
        landscapeFanartUrl ?: other.landscapeFanartUrl, other.episodeThumbs + episodeThumbs,
        kitsuPoster ?: other.kitsuPoster, kitsuBanner ?: other.kitsuBanner,
        jikanPoster ?: other.jikanPoster, jikanTrailer ?: other.jikanTrailer,
        aniZipChecked || other.aniZipChecked, kitsuChecked || other.kitsuChecked, jikanChecked || other.jikanChecked
    )

    internal fun hasArtwork(): Boolean =
        anilistId != null || malId != null || ArtworkSlot.entries.any { urls(it).isNotEmpty() }
}

object ArtworkSmartFit {
    fun needsAmbient(imageRatio: Float, frameRatio: Float): Boolean =
        imageRatio.isFinite() && frameRatio.isFinite() && imageRatio > 0 && frameRatio > 0 &&
            maxOf(imageRatio, frameRatio) > minOf(imageRatio, frameRatio) * 1.55f
}

object ArtworkImageHeaders {
    fun referer(host: String): String? =
        if (host == "animeheaven.me" || host.endsWith(".animeheaven.me")) "https://animeheaven.me/" else null
}
