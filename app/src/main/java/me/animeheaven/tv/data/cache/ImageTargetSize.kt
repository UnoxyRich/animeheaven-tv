package me.animeheaven.tv.data.cache

/**
 * Canonical Coil decode dimensions shared by both [AnimePreloader] and Compose UI cards.
 * Coil 3 keys memory cache entries by (url + target size), so preloading and rendering with
 * identical dimensions guarantees instant 0ms RAM hits instead of duplicate disk re-decodes.
 */
enum class ImageTargetSize(val widthPx: Int, val heightPx: Int) {
    HERO_BANNER(1280, 360),
    DETAIL_POSTER(360, 540),
    CONTINUE_CARD(500, 280),
    POPULAR_CARD(360, 320),
    STANDARD_CARD(420, 236),
    SEARCH_THUMB(256, 144)
}
