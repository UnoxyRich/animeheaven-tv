# Video Playback, Caching & Preloading Engine

This document covers the networking, HTML parsing, multi-tier caching, predictive preloading, and adaptive multi-mirror Media3 playback stack in `animeheaven-tv`.

---

## 1. Networking & HTML Parsing

### OkHttp & Connection Reuse ([`AnimeHeavenClient.kt`](../app/src/main/java/me/animeheaven/tv/data/network/AnimeHeavenClient.kt))
- Base URL: `https://animeheaven.me`
- Dispatcher limits: `maxRequests = 32`, `maxRequestsPerHost = 12`
- ConnectionPool: `12` idle connections, `5 min` keep-alive
- HTML/API caching is owned by Room and bounded in-memory caches; OkHttp has no on-disk HTTP cache. Coil owns its image cache separately.
- Cancellable calls via `OkHttpClient.awaitResponse(request)` using `suspendCancellableCoroutine` + `cont.invokeOnCancellation { call.cancel() }`, aborting sockets immediately when search typing or screen navigation cancels a job.
- [`CachedDns`](../app/src/main/java/me/animeheaven/tv/data/network/NetworkOptimizer.kt): 5-minute in-memory DNS TTL cache with stale IP fallback on `UnknownHostException`.

### Network Policy & Preconnection ([`NetworkOptimizer.kt`](../app/src/main/java/me/animeheaven/tv/data/network/NetworkOptimizer.kt))
- `NetworkPolicy` registers a `ConnectivityManager.NetworkCallback` to track unmetered LAN/Wi-Fi vs. metered networks reactively via `isUnmeteredFlow: StateFlow<Boolean>`, strictly enforcing `DATA_SAVER` (`0 B` background video preloading) whenever the active network is metered and resuming startup preloading automatically when unmetered connectivity returns.
- `NetworkPreconnector` performs asynchronous head/DNS warming for AnimeHeaven and learned top-3 healthy video CDN hosts from `MirrorHealthTracker`.

### HTML Parsing ([`AnimeHeavenParser.kt`](../app/src/main/java/me/animeheaven/tv/data/parser/AnimeHeavenParser.kt))
All CSS selectors are compiled **once** as Jsoup `Evaluator`s (`QueryParser.parse(...)`) rather than re-parsing CSS strings on every page load. Parsers cover:
- Home feed (`index.php` -> banners, schedule, popular strip, current season link)
- Catalog pages (`new.php`, `popular.php`, seasonal `*.php`, `tags.php?tag=...`)
- Fast search JSON/HTML (`fastsearch.php?xhr=1&s=...`) & full search (`search.php?s=...`)
- Anime detail (`anime.php?...` -> metadata, tags, episodes, related/similar shows)
- Episode mirror gate extraction (`gate.php` -> episode URL / video `<source>` mirrors)

---

## 2. Three-Tier Data Caching & Persistence

### Tier 1 — Bounded In-Memory LRU ([`LruCacheMap.kt`](../app/src/main/java/me/animeheaven/tv/data/cache/LruCacheMap.kt), [`AnimeHeavenRepository.kt`](../app/src/main/java/me/animeheaven/tv/data/repository/AnimeHeavenRepository.kt))
Synchronized access-ordered `LinkedHashMap` caches prevent unbounded heap growth on long sessions:
- `cachedCharts`: max `24`
- `cachedGrids`: max `24`
- `cachedDetails`: max `128`
- `cachedStreams`: max `128`
- `cachedFastSearch`: max `100`
- In-memory watch-progress maps (`progressByEpisode`, `latestProgressByAnime`) hydrated at startup alongside recent `gate:*` streams (`48`), `detail:*` entries (`24`), and `vmeta:*` MP4 seek indices (`128`) so warm episodes open in 0 ms without DB or network blocking.

Soft TTLs control Stale-While-Revalidate (SWR) background refreshes:
- `TTL_HOME_MS` = `5 min`
- `TTL_CHART_MS` = `5 min`
- `TTL_SEASONAL_MS` = `30 min`
- `TTL_SEARCH_MS` = `30 min`
- `TTL_DETAIL_MS` = `15 min`
- `TTL_GATE_MS` = `15 min`

### Request Coalescing (`RequestCoalescer`)
Concurrent callers requesting the same resource key (e.g., foreground `DetailScreen` + background `AnimePreloader`) share a single `Deferred<String>` inside `repositoryScope` (`SupervisorJob + Dispatchers.IO`). Cancelling a background preloader coroutine does **not** cancel the shared network fetch or break the foreground screen awaiting it.

### Tier 2 — Fast Home Snapshot ([`CacheSnapshotCodec.kt`](../app/src/main/java/me/animeheaven/tv/data/cache/CacheSnapshotCodec.kt))
- Home feed is persisted atomically as `home_snapshot.v1.json` in `filesDir`.
- **Zero-AST Streaming Reader**: `StreamingJsonReader` parses JSON tokens directly via fast unescaped substring slicing into strongly-typed models (`HomeFeed`) instead of materializing intermediate `Map`/`List` AST trees, keeping cold-start hydration around ~1–3 ms.
- When `web_cache.parsedJson` is available, `AnimeHeavenRepository` skips redundant GZIP+Base64 HTML payload storage and writes DB/snapshot persistence off the caller path.

### Tier 3 — Room Database v3 ([`AppDatabase.kt`](../app/src/main/java/me/animeheaven/tv/data/local/AppDatabase.kt))
Database file: `animeheaven_tv.db`, opened with `JournalMode.WRITE_AHEAD_LOGGING` and `PRAGMA synchronous = NORMAL`, `temp_store = MEMORY`, `cache_size = -4000` to avoid per-transaction `fsync` stalls on TV eMMC flash.

| Table | Purpose | Key Indexes / Notes |
| :--- | :--- | :--- |
| `bookmarks` | User saved anime (`addedAt DESC`) | PrimaryKey `animeId` |
| `watch_progress` | Per-episode position, duration, `isWatched`, `nextEpisodeKey` | Indexed on `(animeId, updatedAt)` and `(updatedAt)`. `observeLatestPerAnime()` returns at most 1 row per anime (`LIMIT 30`). |
| `search_history` | Recent search queries (`LIMIT 10`) | PrimaryKey `query` |
| `web_cache` | Parsed JSON + optional compressed HTML (`MAX_DISK_RETENTION_MS = 14 days`) | Indexed on `fetchedAt`; stores both page caches and AniSkip/AniList responses |

Migrations `MIGRATION_1_2` and `MIGRATION_2_3` preserve existing user progress/bookmarks across upgrades.

### Watch Progress Thresholds (`AnimeHeavenRepository.kt`)
- `MIN_RESUME_THRESHOLD_MS` = `10,000 ms` (positions <= 10s start from `0`)
- `WATCHED_RATIO` = `0.85f` (episodes >= 85% watched advance Continue Watching to `nextEpisodeKey`)
- `MAX_RESUME_RATIO` = `0.92f` (re-opening an episode past 92% resets resume offset to `0`)

---

## 3. Image Preloading & Memory Cache Parity

Coil 3 keys its `MemoryCache` by URL **and** decode target size (`widthPx × heightPx`). If the preloader decodes an image at a different size than the Composable, Coil misses RAM and re-decodes from disk.

All preload calls and `AhCoverImage` slots therefore share the canonical [`ImageTargetSize`](../app/src/main/java/me/animeheaven/tv/data/cache/ImageTargetSize.kt) enum:
- `HERO_BANNER`: `1280 × 360`
- `DETAIL_POSTER`: `360 × 540`
- `CONTINUE_CARD`: `500 × 280`
- `POPULAR_CARD`: `360 × 320`
- `STANDARD_CARD`: `420 × 236`
- `SEARCH_THUMB`: `256 × 144`

Memory budget in `AppContainer`: `18%` of app memory class on low-RAM devices (`isLowRamDevice` or `memoryClass <= 192 MB`), `25%` otherwise, plus a `250 MB` disk cache (`cacheDir/image_cache`) and `RGB_565` enabled.

---

## 4. 4-Wave Startup & Interactive Preloading ([`AnimePreloader.kt`](../app/src/main/java/me/animeheaven/tv/data/cache/AnimePreloader.kt))

Preloading starts immediately on app launch (zero startup delay on unmetered Wi-Fi/Ethernet, strictly paused on metered networks) across four prioritized waves with an `originHtmlGate(2)` semaphore, `150 ms` Wave-2 pacing, and automatic HTTP `429`/`503` exponential backoff:
- **Wave 0 (`0 ms`)**: Top 3 Continue Watching shows (`8 MB` for #1 + pre-demuxed `PreloadMediaSource` in RAM, `6 MB` for #2–#3) + Hero Banner #1 (`6 MB`), targeting each episode's exact saved resume offset (or opening + post-OP).
- **Wave 1 (`+350 ms`)**: Continue Watching #4–#15 (`5 MB` each), Hero Banners #2–#5 (`5 MB` each), and Bookmarks #1–#15 (`4.5 MB` each).
- **Wave 2 (Paced)**: Every show in the Home Popular strip (`12` shows) and Home Schedule (`24` shows) warmed with `4 MB` (~15–20s) of start/resume bytes, followed by background catalog tabs (`New`, `Popular`, seasonal feed).
- **Wave 3 (Interactive & Watch Next)**: D-pad card focus (`4–5 MB`), Detail screen primary episode (`8 MB` + follow-up `4 MB`), Detail episode-tile D-pad focus (`5 MB`), and Watch Next (`6 MB` immediately on playback start, escalating to `20 MB` once the active episode has `>= 15s` buffered ahead).

Foreground work calls `beginUserPriorityWork()` / `endUserPriorityWork()` around `foregroundUserWorkCount: MutableStateFlow<Int>`, pausing lower waves reactively via `yieldIfUserActive()`.

---

## 5. Zero-Buffering Video Engine, MP4 Seek Index & Gapless Watch Next

### Exact MP4 Keyframe Indexing ([`Mp4MoovSeekParser.kt`](../app/src/main/java/me/animeheaven/tv/data/cache/Mp4MoovSeekParser.kt))
- Pure-Kotlin ISO-BMFF parser inspects top-level boxes (`ftyp`/`moov`/`mdat`) to locate faststart or multi-chunk trailing `moov` atoms and parses `mvhd`/`mdhd`/`hdlr`/`stts`/`stss`/`stsc`/`stsz`/`stco`/`co64` into downsampled `EpisodeVideoIndex` keyframe tables persisted in Room `web_cache` (`"vmeta:<episodeKey>"`, zero schema migration).
- Eliminates VBR linear-guess misses when preloading Continue Watching resume offsets and Skip OP landing zones, and enables instant 0 ms cold-start `ep:<episodeKey>:<contentLength>` lookup without network probes.

### Tiered 2 GB Cache & Eviction Protection ([`PinnedPriorityCacheEvictor.kt`](../app/src/main/java/me/animeheaven/tv/data/cache/PinnedPriorityCacheEvictor.kt), [`VideoCacheManager.kt`](../app/src/main/java/me/animeheaven/tv/data/cache/VideoCacheManager.kt))
- Shared episode cache keys (`"ep:$episodeKey:$contentLength"`) reuse cached bytes across verified mirrors at **0 ms**.
- Disk budget scales up to `2 GB` (`512 MB` `< 4 GB` free, `1 GB` `< 12 GB` free, `2 GB` `>= 12 GB` free, strictly `<= 25%` of usable storage).
- `PinnedPriorityCacheEvictor` reserves up to `40%` of the budget for **Tier A** hot-start, Continue Watching resume, `moov`, and Watch Next windows while evicting **Tier B** watched trailing history first. `StreamingChunkBooster` automatically demotes watched chunks `> 3 min` behind the playhead from Tier A to Tier B.

### First-Valid-Mirror Probe & `StreamingChunkBooster` ([`StreamingChunkBooster.kt`](../app/src/main/java/me/animeheaven/tv/data/cache/StreamingChunkBooster.kt))
- Warm disk hits skip blocking mirror probes completely (`PlayerViewModel.buildWarmStateIfAvailable`), starting playback on Frame 0 while `probeMirrorsInBackground()` refreshes mirror metrics asynchronously.
- Cold starts race Range `bytes=0-0` probes (`probeMirrorsForPlayback`, `maxWaitMs = 900L`) and unblock on the first valid full-length mirror (`>= 1 MB`).
- `StreamingChunkBooster` maintains a **48 MB** ahead window (`24 MB` on low-RAM TVs) from the exact playhead frontier (`alignChunkStart(estByte)`, zero blind spot) with `SINGLE_HOST_WORKERS = 3` and parallel multi-mirror workers, instant `Channel.CONFLATED` seek wakeups, and post-OP landing-zone priority.

### `PlayerWarmupPool` & Gapless ExoPlayer Playlist ([`PlayerWarmupPool.kt`](../app/src/main/java/me/animeheaven/tv/data/cache/PlayerWarmupPool.kt))
- `PlayerWarmupPool` shares a single playback `HandlerThread` (`playbackLooper`) and uses Media3 `PreloadMediaSource.Factory` to pre-demux 6s of RAM samples for up to 2 high-priority episodes with **zero extra hardware `MediaCodec` decoders** (safe on budget TV sticks).
- `PlayerScreen` binds ExoPlayer to `warmupPool.playbackLooper`, enables `pauseAtEndOfMediaItems = true` and `ExoPlayer.PreloadConfiguration(15_000_000L)`, appends the pre-warmed Watch Next item directly into the active ExoPlayer playlist, and switches via `seekToNextMediaItem()` for a **0 ms instant cut** across Up Next, Play Now, Skip ED, and HUD Next Episode.
- `buildFastLoadControl()`: `200 ms` initial playback buffer, `500 ms` after-rebuffer threshold, `20s–30s` low-RAM / `40s–60s` standard buffers, plus a `220 ms` anti-flicker spinner debounce when `isDiskCacheWarm`.

---

## 6. Skip OP / ED Pipeline ([`SkipTimesClient.kt`](../app/src/main/java/me/animeheaven/tv/data/network/SkipTimesClient.kt), [`SkipPromptLogic.kt`](../app/src/main/java/me/animeheaven/tv/ui/screens/player/SkipPromptLogic.kt))

1. Episode number is parsed from the episode label (`SkipTimesParser.parseEpisodeNumber`).
2. Anime title is resolved to a MyAnimeList ID via AniList GraphQL (`https://graphql.anilist.co`), deduplicated in memory and persisted in `web_cache` for `30 days`.
3. `https://api.aniskip.com/v2/skip-times/{malId}/{episodeNum}?types=op&types=ed&episodeLength=0` is fetched and cached in `web_cache` for `7 days`.
4. `SkipPromptLogic` resolves active `OP` / `ED` / fallback OP (`skipOpSeconds`) / final `Next Episode` prompts inside the 500 ms playback poll loop.
