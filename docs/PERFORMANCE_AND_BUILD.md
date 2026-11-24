# Performance, Build & Contributor Guide

This guide explains the Compose skippability setup, R8 full-mode shrinking rules, JVM unit tests, and critical invariants to preserve during future development in `animeheaven-tv`.

---

## 1. Compose Compiler & Runtime Performance

Configuration in [`app/build.gradle.kts`](../app/build.gradle.kts):
- **Compose BOM**: `2025.02.00` (Strong Skipping + Intrinsic Remember enabled by default in Kotlin `2.0.21`)
- **`OptimizeNonSkippingGroups`**: Enabled via `composeCompiler.featureFlags`
- **Stability Config**: [`app/compose_stability.conf`](../app/compose_stability.conf) marks `AppContainer`, `AppSettingsStore`, `AnimeHeavenRepository`, `VideoCacheManager`, `AnimePreloader`, `SkipTimesClient`, all 6 screen `ViewModel`s, `OkHttpClient`, and `ImageLoader` as reference-stable.
- **Model Annotations**: All UI/feed/cache data classes passed to Compose (`BannerItem`, `AnimeCard`, `HomeFeed`, `AnimeDetail`, `PlayerUiState`, `AppSettings`, `SkipSegment`, `BookmarkEntity`, `WatchProgressEntity`, `AhThemePalette`, etc.) are annotated `@Immutable`.

### Recomposition & Scroll Hygiene Patterns
When modifying UI code, preserve these rules:
1. **Isolate ticking state to leaves**:
   - In `PlayerScreen`, `PlaybackProgressState` updates every `500 ms`; only `PlayerSeekSection` (via `Canvas`) and time text read it. Do not hoist `positionMs` reads up into the root `PlayerScreen` scope.
   - In `SkipPromptOverlay` and `SearchScreen`'s `BlinkingCaret`, animate via `Animatable` + `graphicsLayer { ... }` instead of mutating Compose `State` on every frame or blink tick.
2. **Avoid `String.format` in hot loops**: Use [`formatDurationMs`](../app/src/main/java/me/animeheaven/tv/ui/components/AnimeCards.kt) (`buildString`) instead of `String.format` / `Locale` allocation on every 500 ms tick.
3. **Keep `LazyListPrefetchStrategy` opt-in**: Using `rememberLazyListState(prefetchStrategy = remember { LazyListPrefetchStrategy(nestedPrefetchItemCount = ...) })` requires `@OptIn(ExperimentalFoundationApi::class)` in Compose BOM `2025.02.00`.
4. **Precomputed Grid Rows**: Screens displaying grids convert card lists into keyed row models (`toGridRows`) so `LazyColumn` recycles rows without rebuilding nested grids during scroll.
5. **Lifecycle-gated Auto-Rotation**: `HeroBannerCarousel` only ticks its auto-advance timer while the lifecycle is `Lifecycle.State.RESUMED`.

### Verifying Compose Metrics
Run:
```bash
./gradlew assembleRelease -PcomposeCompilerReports=true
```
Then inspect:
- `app/build/compose_compiler/app_release-composables.csv` — verify named `@Composable` functions stay `skippable=1, restartable=1`.
- `app/build/compose_compiler/app_release-classes.txt` — verify no UI models, states, or ViewModels become `unstable` (only `Application`/`Activity`, network/cache singletons, and `AhIcons` should appear as non-stable).

---

## 2. Build Optimization & R8 Full Mode

### Gradle Flags ([`gradle.properties`](../gradle.properties))
- `org.gradle.parallel=true`, `org.gradle.caching=true`, `org.gradle.configuration-cache=true`
- `android.nonTransitiveRClass=true`
- `android.enableR8.fullMode=true`

### APK Size Controls ([`app/build.gradle.kts`](../app/build.gradle.kts), [`proguard-rules.pro`](../app/proguard-rules.pro))
1. **Lean Iconography ([`AhIcons.kt`](../app/src/main/java/me/animeheaven/tv/ui/components/AhIcons.kt))**:
   - Uses `material-icons-core` instead of `material-icons-extended` (avoids pulling thousands of unused icon vectors).
   - Required extended icons (`BookmarkBorder`, `AutoAwesome`, `Whatshot`, etc.) are compiled in-tree as exact Material Rounded `ImageVector`s.
2. **Strict ProGuard Rules (`proguard-rules.pro`)**:
   - Enables `-repackageclasses 'o'`, `-allowaccessmodification`, `-optimizationpasses 5`.
   - Strips `Log.v`, `Log.d`, and `Log.i` in release builds via `-assumenosideeffects`.
   - Avoids broad wildcard `-keep class ... { *; }` on Media3, Coil, OkHttp, Jsoup, and app packages (library consumer ProGuard rules already protect JNI/reflection entry points).
3. **Resource & Packaging Trimming**:
   - `resourceConfigurations += listOf("en")` drops ~80 unused framework locale tables.
   - `packaging.resources.excludes` strips `DebugProbesKt.bin`, `kotlin-tooling-metadata.json`, `*.kotlin_module`, and duplicate license metadata.
4. **Baseline Profile ([`app/src/main/baseline-prof.txt`](../app/src/main/baseline-prof.txt))**:
   - Targets exact hot startup, Theme, Compose UI, OkHttp/Jsoup, Repository, Codec, and ExoPlayer paths instead of overly broad `Lme/animeheaven/tv/**` wildcards.

> **Release Signing Note**: `buildTypes.release` in [`app/build.gradle.kts`](../app/build.gradle.kts) currently uses `signingConfigs.getByName("debug")` for convenient local/TV sideloading. Configure a dedicated release keystore before publishing to a store.

---

## 3. Unit Testing

All unit tests run fast on the JVM (`testOptions.unitTests.isReturnDefaultValues = true`) without requiring an emulator or live network access:

```bash
./gradlew testDebugUnitTest
```

| Test Class | Package | Coverage |
| :--- | :--- | :--- |
| `CacheAndMirrorHealthTest` | `data.cache` | `MirrorHealthTracker` EWMA throughput ranking, stub demotion, shared `ep:<key>:<len>` key, MP4 `moov` detection, `LruCacheMap` eviction |
| `CacheSnapshotCodecTest` | `data.cache` | `StreamingJsonReader` round-trips, unicode escapes, forward-compatible unknown fields |
| `AnimeHeavenParserTest` | `data.parser` | Home, FastSearch, Detail, Episode gate, URL/ID extraction |
| `RepositoryOptimizationTest` | `data.repository` | `RequestCoalescer` deduplication and caller-cancellation isolation |
| `WatchProgressLogicTest` | `data.repository` | Resume thresholds, `>= 85%` watched, `>= 92%` reset, Continue Watching progression |
| `MirrorFailoverTest` | `ui.screens.player` | `MirrorQueueState` queue ordering, retry rotation, exhaustion |
| `SkipPromptLogicTest` | `ui.screens.player` | OP/ED/fallback/next-episode skip evaluation windows |
| `SearchViewModelTest` | `ui.screens.search` | Debounced fast search, instant submission, state updates via mock `SearchGateway` |

---

## 4. Invariants Not to Break

When extending the app, double-check these rules:

- **Do NOT add `material-icons-extended`** — extract only needed icon paths into `AhIcons.kt`.
- **Do NOT enable OkHttp's disk cache on Coil's `OkHttpClient`** — keep `okHttpClient.newBuilder().cache(null).build()` in `AppContainer` so Coil never double-writes images to `http_cache` and `image_cache`.
- **Keep `ImageTargetSize` synced** — any change to a card/banner decode size in a screen or `AnimeCards.kt` must use the matching `ImageTargetSize` constant used by `AnimePreloader`.
- **Do NOT add broad ProGuard keeps** for `androidx.media3.**`, `org.jsoup.**`, or `me.animeheaven.tv.data.**` — check release APK size after any ProGuard change.
- **Keep `ViewModel` flows scoped** with `SharingStarted.WhileSubscribed(5_000L)` and `AnimePreloader` signature checks intact so background screens never trigger preload storms during fullscreen playback.
- **Observe `themeId` separately at the root** — non-theme settings belong in `settings`, not `MainActivity`'s root Compose scope.
