package me.animeheaven.tv

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.allowRgb565
import coil3.request.crossfade
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.animeheaven.tv.data.cache.AnimePreloader
import me.animeheaven.tv.data.cache.ImageTargetSize
import me.animeheaven.tv.data.cache.VideoCacheManager
import me.animeheaven.tv.data.local.AppDatabase
import me.animeheaven.tv.data.local.AppSettingsStore
import me.animeheaven.tv.data.network.AnimeHeavenClient
import me.animeheaven.tv.data.network.NetworkPolicy
import me.animeheaven.tv.data.network.NetworkPreconnector
import me.animeheaven.tv.data.network.SkipTimesClient
import me.animeheaven.tv.data.repository.AnimeHeavenRepository
import okhttp3.OkHttpClient

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Light/essential startup components; heavy I/O subsystems are lazy & warmed off main thread
    val okHttpClient: OkHttpClient by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AnimeHeavenClient.defaultOkHttpClient(appContext.cacheDir)
    }
    val client: AnimeHeavenClient by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AnimeHeavenClient(okHttpClient)
    }
    val database: AppDatabase by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AppDatabase.build(appContext)
    }
    val settingsStore: AppSettingsStore = AppSettingsStore(appContext)
    val networkPolicy: NetworkPolicy by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        NetworkPolicy(appContext, settingsStore)
    }
    val repository: AnimeHeavenRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AnimeHeavenRepository(client, database, appContext.filesDir)
    }

    private val skipTimesClientLazy = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        SkipTimesClient(okHttpClient, database.webCacheDao())
    }
    val skipTimesClient: SkipTimesClient by skipTimesClientLazy

    private val videoCacheManagerLazy = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        VideoCacheManager(appContext, okHttpClient)
    }
    val videoCacheManager: VideoCacheManager by videoCacheManagerLazy

    private val imageLoaderLazy = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val isLowRam = am?.isLowRamDevice == true || (am?.memoryClass ?: 256) <= 192
        val memFraction = if (isLowRam) 0.18 else 0.25
        // Share ConnectionPool/Dispatcher/CachedDns with okHttpClient, but disable OkHttp's HTTP
        // disk cache here so images are NOT written twice (Coil owns its 250 MB image_cache).
        val coilHttpClient = okHttpClient.newBuilder().cache(null).build()

        ImageLoader.Builder(appContext)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(appContext, memFraction)
                    .strongReferencesEnabled(true)
                    .weakReferencesEnabled(true)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(File(appContext.cacheDir, "image_cache"))
                    .maxSizeBytes(250L * 1024L * 1024L)
                    .build()
            }
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { coilHttpClient }))
            }
            .allowRgb565(true)
            .crossfade(100)
            .build()
    }
    val imageLoader: ImageLoader by imageLoaderLazy

    val preloader: AnimePreloader by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AnimePreloader(
            context = appContext,
            repository = repository,
            skipTimesClientProvider = { skipTimesClient },
            imageLoaderProvider = { imageLoader },
            videoCacheManagerProvider = { videoCacheManager },
            okHttpClient = okHttpClient,
            networkPolicy = networkPolicy
        )
    }

    /**
     * Runs entirely asynchronously off the main thread so Application.onCreate() finishes in <5ms
     * while snapshot hydration, TLS preconnect, Room warmup, and SimpleCache init overlap with
     * Activity & Compose inflation.
     */
    fun warmUpOnStartup() {
        appScope.launch {
            NetworkPreconnector.preconnectAsync(okHttpClient)

            val fastFeed = runCatching { repository.hydrateHomeFastSync() }.getOrNull()
            if (fastFeed != null) {
                preloader.preloadImages(
                    urls = fastFeed.banners.map { it.bannerImageUrl },
                    maxCount = 2,
                    targetSize = ImageTargetSize.HERO_BANNER
                )
                preloader.preloadImages(
                    urls = fastFeed.popularItems.map { it.coverUrl },
                    maxCount = 6,
                    targetSize = ImageTargetSize.POPULAR_CARD
                )
            }

            runCatching { repository.warmUpFromDisk() }.onSuccess { (feed, continueItems) ->
                if (feed != null) {
                    preloader.preloadHomeEcosystem(feed, continueItems)
                }
            }

            // Warm SimpleCache & MirrorHealthTracker off main thread after Frame 1 settles
            delay(800L)
            runCatching { videoCacheManager }

            delay(9_000L)
            runCatching { repository.pruneExpiredCache() }
        }
    }

    @Suppress("DEPRECATION")
    fun onTrimMemory(level: Int) {
        val aggressive = level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
        val moderate = level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW

        if (imageLoaderLazy.isInitialized()) {
            runCatching {
                val memCache = imageLoader.memoryCache
                if (aggressive) {
                    memCache?.clear()
                } else if (moderate && memCache != null) {
                    memCache.trimToSize(memCache.maxSize / 2)
                }
            }
        }
        if (aggressive || moderate) {
            runCatching { repository.trimMemory(aggressive = aggressive) }
            if (skipTimesClientLazy.isInitialized()) {
                runCatching { skipTimesClient.trimMemory(aggressive = aggressive) }
            }
            if (videoCacheManagerLazy.isInitialized()) {
                runCatching { videoCacheManager.trimMemory() }
            }
        }
    }

    suspend fun clearAllCaches() {
        repository.clearWebPageCache()
        if (skipTimesClientLazy.isInitialized()) {
            skipTimesClient.clearMemoryCache()
        }
        preloader.resetSessionMarkers()
        videoCacheManager.clearAll()
        runCatching {
            if (imageLoaderLazy.isInitialized()) {
                imageLoader.memoryCache?.clear()
                imageLoader.diskCache?.clear()
            }
        }
        runCatching { okHttpClient.cache?.evictAll() }
    }

    suspend fun getCacheSummaryText(): String {
        val webKb = repository.getWebCacheBytes() / 1024L
        val pageCount = repository.getCachedPageCount()
        val imgMb = (imageLoader.diskCache?.size ?: 0L) / (1024L * 1024L)
        val vidMb = videoCacheManager.cachedBytesSize() / (1024L * 1024L)
        return "Web: ${webKb} KB ($pageCount pages) • Images: ${imgMb} MB • Video: ${vidMb} MB"
    }
}
