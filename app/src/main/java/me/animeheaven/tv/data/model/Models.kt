package me.animeheaven.tv.data.model

import androidx.compose.runtime.Immutable

enum class RankDirection {
    UP,
    DOWN,
    SAME
}

enum class FeedViewMode {
    LIST,
    GRID
}

enum class KeyboardMode {
    QWERTY,
    ABC,
    SYMBOLS
}

enum class PreloadMode(
    val displayName: String,
    val subtitle: String
) {
    AGGRESSIVE(
        "Aggressive (Recommended)",
        "Preload Home shows, tabs, Bookmarks & up to 8 MB Next Ep video on Wi-Fi/LAN"
    ),
    BALANCED(
        "Balanced",
        "Preload Home banners, Continue Watching & metadata on D-pad focus"
    ),
    DATA_SAVER(
        "Data Saver",
        "Metadata & thumbnails only • zero background video preloading"
    )
}

enum class AhThemeId(
    val displayName: String,
    val cssKey: String,
    val subtitle: String
) {
    DARK("Cinema Dark", "dark", "Deep charcoal • Netflix Red"),
    DARK_INDIGO("Midnight Indigo", "dark2", "Plum to midnight navy"),
    DARK_GRAPHITE("Graphite Slate", "dark3", "Charcoal metallic dark"),
    DARK_TEAL_PLUM("Teal Plum", "dark4", "Abyssal teal to plum"),
    DARK_CRIMSON_VELVET("Crimson Velvet", "dark5", "Deep maroon to plum"),
    DARK_MAGENTA("Magenta Dusk", "dark6", "Vibrant magenta to purple"),
    DARK_TWILIGHT("Steel Twilight", "dark7", "Slate blue to indigo"),
    LIGHT("Classic Light", "light", "White canvas • Crisp cards");

    companion object {
        fun fromCssKey(key: String?): AhThemeId =
            entries.firstOrNull { it.cssKey == key } ?: DARK
    }
}

@Immutable
data class BannerItem(
    val animeId: String,
    val title: String,
    val bannerImageUrl: String
)

@Immutable
data class AnimeCard(
    val id: String,
    val title: String,
    val japaneseTitle: String = "",
    val coverUrl: String = "",
    val episodeLabel: String = "",
    val timerOrStatus: String = "",
    val rankDelta: String? = null,
    val rankDirection: RankDirection? = null
)

@Immutable
data class HomeFeed(
    val banners: List<BannerItem>,
    val scheduleItems: List<AnimeCard>,
    val popularItems: List<AnimeCard>,
    val seasonPath: String?,
    val seasonLabel: String?
)

@Immutable
data class FastSearchItem(
    val animeId: String,
    val title: String,
    val coverUrl: String
)

@Immutable
data class EpisodeItem(
    val key: String,
    val numberLabel: String,
    val releaseAge: String
)

@Immutable
data class AnimeDetail(
    val id: String,
    val title: String,
    val japaneseTitle: String,
    val bannerUrl: String?,
    val posterUrl: String,
    val synopsis: String,
    val tags: List<String>,
    val episodesLabel: String,
    val year: String,
    val score: String,
    val countdownLabel: String?,
    val episodes: List<EpisodeItem>,
    val relatedShows: List<AnimeCard>,
    val similarShows: List<AnimeCard>
)

@Immutable
data class EpisodeStream(
    val episodeKey: String,
    val animeId: String?,
    val animeTitle: String,
    val episodeLabel: String,
    val videoSources: List<String>,
    val prevEpisodeKey: String?,
    val prevEpisodeLabel: String?,
    val nextEpisodeKey: String?,
    val nextEpisodeLabel: String?
)

@Immutable
data class ContinueWatchingItem(
    val animeId: String,
    val animeTitle: String,
    val coverUrl: String,
    val targetEpisodeKey: String,
    val targetEpisodeLabel: String,
    val isNextEpisode: Boolean,
    val resumePositionMs: Long,
    val durationMs: Long,
    val lastWatchedLabel: String
)
