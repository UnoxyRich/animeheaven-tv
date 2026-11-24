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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.animeheaven.tv.data.cache.AnimePreloader
import me.animeheaven.tv.data.cache.ImageTargetSize
import me.animeheaven.tv.data.cache.VideoCacheManager
import me.animeheaven.tv.data.local.AppDatabase
import me.animeheaven.tv.data.local.AppSettingsStore
import me.animeheaven.tv.data.model.ArtworkImageHeaders
import me.animeheaven.tv.data.model.PreloadMode
import me.animeheaven.tv.data.network.AnimeArtworkResolver
import me.animeheaven.tv.data.network.AnimeHeavenClient
import me.animeheaven.tv.data.network.CachedDns
import me.animeheaven.tv.data.network.NetworkPolicy
import me.animeheaven.tv.data.network.NetworkPreconnector
import me.animeheaven.tv.data.network.SkipTimesClient
import me.animeheaven.tv.data.repository.AnimeHeavenRepository
import okhttp3.OkHttpClient

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    internal val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cacheClearMutex = Mutex()
    private var startupJob: Job? = null
    private val dns = CachedDns()

    // Light/essential startup components; heavy I/O subsystems are lazy & warmed off main thread
    val okHttpClient: OkHttpClient by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AnimeHeavenClient.defaultOkHttpClient(dns)
    }
    val client: AnimeHeavenClient by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AnimeHeavenClient(okHttpClient)
    }
    val database: AppDatabase by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AppDatabase.build(appContext)
    }
    val settingsStore: AppSettingsStore = AppSettingsStore(appContext)
    val networkPolicy: NetworkPolicy by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        NetworkPolicy(appContext, settingsStore, dns)
    }
    private val repositoryLazy = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AnimeHeavenRepository(client, database, appContext.filesDir)
    }
    val repository: AnimeHeavenRepository by repositoryLazy

    private val artworkResolverLazy = lazy {
        AnimeArtworkResolver(okHttpClient, database.webCacheDao(), appScope) {
            networkPolicy.effectivePreloadMode() == PreloadMode.DATA_SAVER
        }
    }
    val artworkResolver: AnimeArtworkResolver by artworkResolverLazy

    private val skipTimesClientLazy = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        SkipTimesClient(okHttpClient, database.webCacheDao(), artworkResolver)
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
                val imageClient = okHttpClient.newBuilder().cache(null).followSslRedirects(false)
                    .addNetworkInterceptor { chain ->
                        val request = chain.request().newBuilder().removeHeader("Referer")
                        ArtworkImageHeaders.referer(chain.request().url.host)?.let { request.header("Referer", it) }
                        chain.proceed(request.build())
                    }.build()
                add(OkHttpNetworkFetcherFactory(callFactory = { imageClient }))
            }
            .allowRgb565(true)
            .crossfade(100)
            .build()
    }
    val imageLoader: ImageLoader by imageLoaderLazy

    private val preloaderLazy = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
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
    val preloader: AnimePreloader by preloaderLazy

    /**
     * Runs entirely asynchronously off the main thread so Application.onCreate() finishes in <5ms
     * while snapshot hydration, TLS preconnect, Room warmup, and SimpleCache init overlap with
     * Activity & Compose inflation.
     */
    fun warmUpOnStartup() {
        startupJob = appScope.launch {
            launch { artworkResolver.hydrateRecentFromDisk() }
            if (networkPolicy.shouldPreloadHomeDetails()) {
                NetworkPreconnector.preconnectAsync(okHttpClient)
                NetworkPreconnector.preconnectCdnHostsAsync(okHttpClient, listOf("graphql.anilist.co", "api.ani.zip"))
            }

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
            if (artworkResolverLazy.isInitialized()) artworkResolver.trimMemory()
            if (repositoryLazy.isInitialized()) repository.trimMemory(aggressive = aggressive)
            if (skipTimesClientLazy.isInitialized()) {
                runCatching { skipTimesClient.trimMemory(aggressive = aggressive) }
            }
            if (videoCacheManagerLazy.isInitialized()) {
                runCatching { videoCacheManager.trimMemory() }
            }
        }
    }

    suspend fun clearAllCaches(): Unit = withContext(Dispatchers.IO) {
        cacheClearMutex.withLock {
            startupJob?.cancelAndJoin()
            if (preloaderLazy.isInitialized()) preloader.withoutPreloading { clearCachesOnDisk() }
            else clearCachesOnDisk()
        }
    }

    private suspend fun clearCachesOnDisk() {
        if (skipTimesClientLazy.isInitialized()) skipTimesClient.clearMemoryCache()
        artworkResolver.withCacheCleared { repository.clearWebPageCache() }
        videoCacheManager.clearAll()
        runCatching {
            if (imageLoaderLazy.isInitialized()) {
                imageLoader.memoryCache?.clear()
                imageLoader.diskCache?.clear()
            } else {
                File(appContext.cacheDir, "image_cache").deleteRecursively()
            }
        }
        File(appContext.cacheDir, "http_cache").deleteRecursively()
    }

    suspend fun getCacheSummaryText(): String = withContext(Dispatchers.IO) {
        val webKb = repository.getWebCacheBytes() / 1024L
        val pageCount = repository.getCachedPageCount()
        val imgMb = (imageLoader.diskCache?.size ?: 0L) / (1024L * 1024L)
        val vidMb = videoCacheManager.cachedBytesSize() / (1024L * 1024L)
        "Web: ${webKb} KB ($pageCount pages) • Images: ${imgMb} MB • Video: ${vidMb} MB"
    }
}
