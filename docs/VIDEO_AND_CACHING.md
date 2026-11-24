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
- `NetworkPolicy` registers a `ConnectivityManager.NetworkCallback` to track unmetered vs. metered networks reactively without repeated Binder IPC on every preload check.
- `NetworkPreconnector` performs asynchronous head/DNS warming for AnimeHeaven and learned top healthy video CDN hosts from `MirrorHealthTracker`.

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
- `cachedDetails`: max `64`
- `cachedStreams`: max `48`
- `cachedFastSearch`: max `100`

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

## 4. Predictive Preloading ([`AnimePreloader.kt`](../app/src/main/java/me/animeheaven/tv/data/cache/AnimePreloader.kt))

Preloading is split into two priority bands with dedicated semaphores:
- **Band 0 (`band0Gate`, 3 permits)**: Hero images, top Continue Watching episode gate + mirror speed probe + resume-aware `2 MB` video head preload, visible viewport images, and active episode detail/stream warming.
- **Band 1 (`band1Gate`, 2 permits)**: Background catalog tabs (`New`, `Popular`, seasonal feed), top Home detail pages, and bookmark warming. Delayed by a `450 ms` startup grace window (`BAND1_STARTUP_GRACE_MS`) so initial screens settle first.

Foreground work calls `beginUserPriorityWork()` / `endUserPriorityWork()` around `foregroundUserWorkCount: MutableStateFlow<Int>`, letting Band 1 pause reactively via `yieldIfUserActive()` (max `6s` wait) with zero polling loops.

---

## 5. Multi-Mirror Video Streaming & Booster

### Shared Episode Cache Keys ([`VideoCacheManager.kt`](../app/src/main/java/me/animeheaven/tv/data/cache/VideoCacheManager.kt))
Mirrors for the same episode are frequently CDN aliases or tokenized URLs for the exact same video file. `buildEpisodeCacheKey(episodeKey, contentLength, fallbackUrl)` returns `"ep:$episodeKey:$contentLength"` once a probe confirms `contentLength >= MIN_VALID_VIDEO_BYTES`.

Result:
- Switching mirrors mid-playback or failover recovers cached byte spans at **0 ms**.
- `SimpleCache` uses at most one quarter of usable storage, targeting `512 MB` below `4 GB` free and `1 GB` otherwise (`CacheDataSink` fragment size `2 MB`, chunk unit `512 KB`).
- Current-episode preloads survive `PlayerScreen` startup via `videoCacheManager.cancelPreloadsExcept(episodeKey)`.

### First-Valid-Mirror Probe & `MirrorHealthTracker`
- `probeMirrorsForPlayback(urls, maxWaitMs = 900L)` races Range `bytes=0-0` probes concurrently and unblocks playback as soon as the **first** valid full-length mirror responds (~80–150 ms), while slower probes finish in the background to join the booster.
- `MirrorHealthTracker` records RTT latency, consecutive failure cooldowns, EWMA streaming throughput (`avgBytesPerSec`), and flags tiny error/placeholder clips (`< 1 MB`) as stubs so `rankMirrors()` always sorts healthy fast full-length hosts first and persists rankings to `SharedPreferences` (throttled writes).

### `StreamingChunkBooster` ([`StreamingChunkBooster.kt`](../app/src/main/java/me/animeheaven/tv/data/cache/StreamingChunkBooster.kt))
Single HTTP connections to anime CDNs often throttle around `140–250 KB/s`. `StreamingChunkBooster` fetches `512 KB` byte ranges in parallel across ranked healthy mirrors into pooled `ByteArray` buffers **outside** `SimpleCache` locks, then commits completed chunks into `SimpleCache`.
- **Seek Wakeup**: `onPlayheadUpdate(...)` signals `Channel.CONFLATED` on seeks so the booster jumps immediately to the new byte offset (`+1 chunk` post-seek anchor tightening).
- **Non-Faststart MP4 Tail Warmup**: If chunk `0` lacks a `moov` atom (`containsMoovBox`), the booster automatically queues the trailing tail chunks so ExoPlayer resolves MP4 duration and frame index tables immediately.
- **Idle Playback Preload**: When `PreloadMode.AGGRESSIVE` is enabled on unmetered Wi-Fi, `PlayerViewModel` escalates next-episode preloading once per episode (up to `8 MB` video chunks).

### ExoPlayer Tuning
- `buildFastLoadControl()`: `250 ms` playback start buffer, `750 ms` after-rebuffer threshold, RAM-capped buffers (`15s/35s/24 MB` low-RAM vs `25s/55s/48 MB` standard).
- `leanExtractorsFactory`: Registers only `Mp4Extractor`, `FragmentedMp4Extractor`, and `MatroskaExtractor` (no reflection scan over unused formats).
- `PlayerScreen`: Configures `DefaultRenderersFactory` with `forceEnableMediaCodecAsynchronousQueueing()` and `setEnableDecoderFallback(true)`, `AudioAttributes` (`USAGE_MEDIA`, `CONTENT_TYPE_MOVIE`), and `setMediaItem(mediaItem, resumePositionMs)` before `prepare()` so ExoPlayer seeks directly on startup.

---

## 6. Skip OP / ED Pipeline ([`SkipTimesClient.kt`](../app/src/main/java/me/animeheaven/tv/data/network/SkipTimesClient.kt), [`SkipPromptLogic.kt`](../app/src/main/java/me/animeheaven/tv/ui/screens/player/SkipPromptLogic.kt))

1. Episode number is parsed from the episode label (`SkipTimesParser.parseEpisodeNumber`).
2. Anime title is resolved to a MyAnimeList ID via AniList GraphQL (`https://graphql.anilist.co`), deduplicated in memory and persisted in `web_cache` for `30 days`.
3. `https://api.aniskip.com/v2/skip-times/{malId}/{episodeNum}?types=op&types=ed&episodeLength=0` is fetched and cached in `web_cache` for `7 days`.
4. `SkipPromptLogic` resolves active `OP` / `ED` / fallback OP (`skipOpSeconds`) / final `Next Episode` prompts inside the 500 ms playback poll loop.
