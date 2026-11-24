# Architecture & Android TV UI Guide

This document describes the runtime wiring, state flow, navigation, and TV-specific UI subsystems in `animeheaven-tv`.

---

## 1. Startup & Dependency Injection

`animeheaven-tv` uses a lightweight manual container ([`AppContainer.kt`](../app/src/main/java/me/animeheaven/tv/AppContainer.kt)) instead of a runtime DI framework so cold start incurs zero reflection or generated graph overhead.

### Startup Sequence
1. [`AnimeHeavenApplication.onCreate()`](../app/src/main/java/me/animeheaven/tv/AnimeHeavenApplication.kt) constructs `AppContainer` and immediately fires `container.warmUpOnStartup()` onto an `IO` `CoroutineScope`.
2. **Lazy Heavy Subsystems**: `okHttpClient`, `client`, `database`, `networkPolicy`, `repository`, `skipTimesClient`, `videoCacheManager`, `imageLoader`, and `preloader` are all initialized via `lazy(LazyThreadSafetyMode.SYNCHRONIZED)`. Only lightweight `AppSettingsStore` reads synchronously during container construction.
3. **Async Warm-Up (`warmUpOnStartup`)**:
   - Preconnects TLS sockets asynchronously via [`NetworkPreconnector.preconnectAsync`](../app/src/main/java/me/animeheaven/tv/data/network/NetworkOptimizer.kt).
   - Calls `repository.hydrateHomeFastSync()`, reading `home_snapshot.v1.json` (~1–3 ms) via zero-AST streaming JSON so the Home feed can appear immediately before Room opens.
   - Preloads the first 2 `HERO_BANNER` images and first 6 `POPULAR_CARD` covers at exact composable target dimensions.
   - Opens Room via `repository.warmUpFromDisk()` and schedules `preloadHomeEcosystem(...)`.
   - Delays `800 ms` after launch to warm ExoPlayer's `SimpleCache` & `MirrorHealthTracker` off the main thread after Frame 1 renders.
   - Delays `9,000 ms` before pruning expired `web_cache` rows (> 14 days).
4. **Memory Pressure Handling**: [`AnimeHeavenApplication.onTrimMemory()`](../app/src/main/java/me/animeheaven/tv/AnimeHeavenApplication.kt) forwards trim levels to `AppContainer.onTrimMemory()`, trimming Coil's memory cache (half at `TRIM_MEMORY_RUNNING_LOW`, clear at `RUNNING_CRITICAL`/`UI_HIDDEN`) and clearing LRU maps / booster buffers when needed.

### Root Window Overdraw & Theme Scoping
In [`MainActivity.kt`](../app/src/main/java/me/animeheaven/tv/MainActivity.kt):
- Compose observes **only** `container.settingsStore.themeId.collectAsStateWithLifecycle()`. Toggling non-theme settings (skip times, preload mode, auto-play, keyboard layout) never triggers root recomposition.
- `window.setBackgroundDrawable(null)` removes the redundant Android window background because `AnimeHeavenTheme` already paints the full-screen canvas, eliminating one full-screen 1080p/4K GPU overdraw pass every frame.

---

## 2. Data & UI State Flow

```text
  AnimeHeavenClient (OkHttp + CachedDns)
           │
           ▼
  AnimeHeavenParser (Pre-compiled Jsoup Evaluators)
           │
           ▼
  AnimeHeavenRepository (RequestCoalescer + LruCacheMap + Stale-While-Revalidate)
     ├── Fast Home Snapshot: home_snapshot.v1.json (CacheSnapshotCodec)
     └── Durable Cache/User Data: AppDatabase (Room v3, WAL)
           │
           ▼
  ViewModels (Home / Catalog / Bookmarks / Detail / Search / Player)
     └── Flow.combine(..., SharingStarted.WhileSubscribed(5_000L)) ──▶ @Immutable UiState
           │
           ▼
  Jetpack Compose TV Screens (D-pad Focus + Canvas Player HUD)
```

### Why `SharingStarted.WhileSubscribed(5_000L)` Matters
Screen ViewModels (`HomeViewModel`, `CatalogViewModel`, `BookmarksViewModel`, `DetailViewModel`) stop collecting Room `Flow` emissions `5s` after the screen leaves the active back-stack/lifecycle (for example, while watching fullscreen video in `PlayerScreen`). Previously, active collectors on Home triggered repeated ecosystem and Coil preloads during video playback; `WhileSubscribed` + signature deduplication in `AnimePreloader` eliminates background playback storms.

---

## 3. Navigation Graph

Routes live in [`AppNavGraph.kt`](../app/src/main/java/me/animeheaven/tv/ui/navigation/AppNavGraph.kt):

| Route | Screen | Parameters / Notes |
| :--- | :--- | :--- |
| `home` | `HomeScreen` | Start destination. Uses top-tab navigation (`saveState = true`, `launchSingleTop = true`, `restoreState = true`). |
| `new` | `CatalogScreen` | `CatalogKind.NEW` (`new.php` latest episodes) |
| `popular` | `CatalogScreen` | `CatalogKind.POPULAR` (`popular.php`) |
| `bookmarks` | `BookmarksScreen` | Bookmarked shows + Continue Watching + Clear Watch History |
| `seasonal/{path}/{label}` | `CatalogScreen` | `CatalogKind.SEASONAL` (URI-encoded path & season label from home header) |
| `tag/{tag}` | `CatalogScreen` | `CatalogKind.TAG` |
| `search` | `SearchScreen` | Split pane: D-pad custom keyboard on left, live fast/full results on right |
| `detail/{animeId}` | `AnimeDetailScreen` | Synopsis, metadata, tags, bookmark toggle, episode tiles, related/similar strips |
| `player/{episodeKey}/{animeId}/{animeTitle}/{coverUrl}` | `PlayerScreen` | Blank placeholders encoded as `"_"` / `"AnimeHeaven"` |
| `settings` | `SettingsScreen` | Theme selector, keyboard mode, default view mode, skip/preload controls, cache stats/clear |

Transitions use crisp `130 ms` `fadeIn` / `100 ms` `fadeOut` animations tailored for 10-foot UI.

---

## 4. Android TV UI & Input Subsystems

### Manifest Configuration
[`AndroidManifest.xml`](../app/src/main/AndroidManifest.xml) configures:
- Both `android.intent.category.LAUNCHER` and `android.intent.category.LEANBACK_LAUNCHER`.
- `android.software.leanback` and `android.hardware.touchscreen` both `required="false"` so the APK installs cleanly on TVs, boxes, and emulators.
- Fixed `android:screenOrientation="landscape"` and broad `configChanges` (`keyboard|keyboardHidden|navigation|orientation|screenSize|screenLayout|smallestScreenSize`).

### High-Performance D-Pad Focus (`TvFocusModifiers.kt`)
Standard Compose Material/foundation `.border()` + `.scale()` + `.clip()` modifiers allocate `BorderStroke`, `Animatable` coroutines, and extra render nodes for **every** card and keyboard key on screen — even unfocused ones.

[`TvFocusModifiers.kt`](../app/src/main/java/me/animeheaven/tv/ui/components/TvFocusModifiers.kt) replaces that with two focused primitives:
1. **`AhFocusableBox`** backed by `AhTvFocusNode` (`Modifier.Node` implementing `FocusEventModifierNode`, `LayoutModifierNode`, `DrawModifierNode`):
   - **Lazy `Animatable` allocation**: Zero animation objects or coroutines exist until a card actually receives focus.
   - **Draw-phase rendering**: Focus background fill and outline stroke (`2.5.dp` when focused, optional `1.dp` unfocused) are drawn directly in `ContentDrawScope`, avoiding `BorderStroke` allocations and relayouts.
   - **Merged graphics layer**: Scale (`1.03f` default) and shape clipping execute in `placeWithLayer` rather than a separate `.clip()` node.
   - **Hoisted shapes**: `AhShapeRect`, `AhShape2`, `AhShape3`, `AhShape4`, `AhShape16`, `AhShape20`.
2. **`AhStaticFocusableBox`**: Zero-animation lightweight focus wrapper for high-density buttons or static controls where focus scaling isn't needed.

### D-Pad Navigation Bar (`AnimeHeavenHeader.kt`)
[`AnimeHeavenHeader.kt`](../app/src/main/java/me/animeheaven/tv/ui/components/AnimeHeavenHeader.kt) renders the top overscan-padded bar with `HeaderDestination` items: `HOME`, `SEARCH`, `NEW`, `BOOKMARKS`, `POPULAR`, `SEASONAL`, `RANDOM`, and `SETTINGS`.

### Custom On-Screen TV Keyboard (`TvCustomKeyboard.kt`)
[`TvCustomKeyboard.kt`](../app/src/main/java/me/animeheaven/tv/ui/components/TvCustomKeyboard.kt) implements 3 selectable layouts (`QWERTY`, `ABC`, `Symbols`), explicit right-edge `focusProperties` jumping straight into `resultsFocusRequester` when search matches exist, and bottom Space / Backspace / Clear / Search action keys. [`SearchScreen.kt`](../app/src/main/java/me/animeheaven/tv/ui/screens/search/SearchScreen.kt) isolates the `530 ms` blinking caret onto a `graphicsLayer { alpha = ... }` leaf composable so idle screens don't recompose all 35+ keys twice per second.

### TV Video Player Remote Controls (`PlayerScreen.kt`)
[`PlayerScreen.kt`](../app/src/main/java/me/animeheaven/tv/ui/screens/player/PlayerScreen.kt) handles TV remote keys directly via `.onPreviewKeyEvent` when the player surface holds focus:

| Key | Behavior (HUD Hidden) | Behavior (HUD Visible) |
| :--- | :--- | :--- |
| `MediaPlayPause` / `MediaPlay` / `MediaPause` | Toggles/sets playback and shows HUD | Same |
| `MediaRewind` / `MediaFastForward` | `-10s` / `+10s` seek | `-10s` / `+10s` seek |
| `DirectionLeft` / `DirectionRight` | `-10s` / `+10s` quick seek + transient feedback pill | Passes focus through HUD controls & resets auto-hide timer |
| `DirectionUp` / `DirectionDown` | Dismisses active skip prompt and reveals HUD | Passes focus through HUD controls & resets auto-hide timer |
| `DirectionCenter` / `Enter` / `NumPadEnter` | If a Skip OP/ED prompt is active, triggers skip action; otherwise toggles/reveals HUD & pause | Standard button click |

Progress polling updates an isolated `@Stable PlaybackProgressState` every `500 ms`, while the seekbar draws inside a `Canvas` leaf (`PlayerSeekSection`) and `SkipPromptOverlay` animates countdown progress via `Animatable` + `graphicsLayer { scaleX = ... }`, resulting in **0 full-screen root recompositions** during playback.

---

## 5. User Settings (`AppSettingsStore.kt`)

Persisted in `SharedPreferences` (`animeheaven_tv_settings`):

| Setting | Default | Purpose |
| :--- | :--- | :--- |
| `themeId` | `AhThemeId.DARK` | Active palette (`dark`, `dark2`..`dark7`, `light`) |
| `defaultKeyboardMode` | `KeyboardMode.QWERTY` | Initial Search keyboard layout (`QWERTY` / `ABC` / `SYMBOLS`) |
| `defaultViewMode` | `FeedViewMode.LIST` | Default layout for Home/Catalog grids (`LIST` / `GRID`) |
| `autoPlayNext` | `true` | Shows 5-second Up Next countdown at episode end |
| `skipOpSeconds` | `85` | Fallback OP skip jump when AniSkip timestamps are unavailable |
| `skipPromptEnabled` | `true` | Enables contextual `Skip OP` / `Skip ED` / `Next Ep` overlay prompts |
| `preloadMode` | `PreloadMode.AGGRESSIVE` | Controls background preloading (`AGGRESSIVE` Wi-Fi / `BALANCED` / `DATA_SAVER`) |
