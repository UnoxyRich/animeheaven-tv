package me.animeheaven.tv.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.animeheaven.tv.data.cache.LruCacheMap
import me.animeheaven.tv.data.local.AppSettingsStore
import me.animeheaven.tv.data.model.PreloadMode
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * In-memory DNS cache with 5-minute TTL and stale fallback so OkHttp connection
 * rebuilds avoid repeated OS getaddrinfo stalls.
 */
class CachedDns(
    private val ttlMs: Long = DEFAULT_DNS_TTL_MS,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val delegate: Dns = Dns.SYSTEM
) : Dns {
    private data class Entry(
        val addresses: List<InetAddress>,
        val resolvedAtMs: Long
    )

    private val cache = LruCacheMap<String, Entry>(128)

    override fun lookup(hostname: String): List<InetAddress> {
        val key = hostname.lowercase()
        val now = clockMs()
        val cached = cache[key]
        if (cached != null && (now - cached.resolvedAtMs) < ttlMs) {
            return cached.addresses
        }
        return try {
            val fresh = delegate.lookup(hostname)
            if (fresh.isNotEmpty()) {
                cache[key] = Entry(fresh, now)
            }
            fresh
        } catch (err: UnknownHostException) {
            cached?.takeIf { now - it.resolvedAtMs in 0 until ttlMs * 2 }?.addresses ?: throw err
        }
    }

    fun clear() {
        cache.clear()
    }

    companion object {
        const val DEFAULT_DNS_TTL_MS = 5 * 60_000L
    }
}

/**
 * Evaluates current connectivity (unmetered LAN/Wi-Fi vs metered cellular/hotspot) together
 * with the user's PreloadMode preference. Strictly disables all background video preloading
 * whenever the active network is metered.
 */
class NetworkPolicy(
    context: Context,
    private val settingsStore: AppSettingsStore,
    private val dns: CachedDns? = null
) {
    private val connectivityManager: ConnectivityManager? =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    @Volatile
    private var cachedMetered: Boolean =
        runCatching { connectivityManager?.isActiveNetworkMetered ?: false }.getOrDefault(false)

    private val _isUnmeteredFlow = MutableStateFlow(!cachedMetered)
    val isUnmeteredFlow: StateFlow<Boolean> = _isUnmeteredFlow.asStateFlow()

    init {
        registerCallbackSafely()
    }

    private fun registerCallbackSafely() {
        val cm = connectivityManager ?: return
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities
                ) {
                    val metered = !networkCapabilities.hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_NOT_METERED
                    )
                    cachedMetered = metered
                    _isUnmeteredFlow.value = !metered
                }

                override fun onAvailable(network: Network) {
                    cachedMetered = true
                    _isUnmeteredFlow.value = false
                    dns?.clear()
                }

                override fun onLost(network: Network) {
                    cachedMetered = true
                    _isUnmeteredFlow.value = false
                }
            })
        }
    }

    fun isMeteredNetwork(): Boolean = cachedMetered

    fun effectivePreloadMode(): PreloadMode =
        resolvePreloadMode(settingsStore.settings.value.preloadMode, isMeteredNetwork())

    fun shouldPreloadVideo(): Boolean = effectivePreloadMode() != PreloadMode.DATA_SAVER

    fun shouldPreloadHomeDetails(): Boolean = effectivePreloadMode() != PreloadMode.DATA_SAVER

    fun maxContinueWatchingPreloadCount(): Int = when (effectivePreloadMode()) {
        PreloadMode.AGGRESSIVE -> 15
        PreloadMode.BALANCED -> 5
        PreloadMode.DATA_SAVER -> 0
    }

    fun continueVideoBytes(index: Int): Long = when (effectivePreloadMode()) {
        PreloadMode.AGGRESSIVE -> when {
            index == 0 -> 8L * 1024L * 1024L
            index in 1..2 -> 6L * 1024L * 1024L
            index in 3..14 -> 5L * 1024L * 1024L
            else -> 0L
        }
        PreloadMode.BALANCED -> when {
            index == 0 -> 4L * 1024L * 1024L
            index in 1..2 -> 3L * 1024L * 1024L
            else -> 0L
        }
        PreloadMode.DATA_SAVER -> 0L
    }

    fun continuePrimaryVideoBytes(): Long = continueVideoBytes(0)

    fun bannerVideoCount(): Int = when (effectivePreloadMode()) {
        PreloadMode.AGGRESSIVE -> 5
        PreloadMode.BALANCED -> 1
        PreloadMode.DATA_SAVER -> 0
    }

    fun bannerVideoBytes(index: Int): Long = when (effectivePreloadMode()) {
        PreloadMode.AGGRESSIVE -> if (index == 0) 6L * 1024L * 1024L else 5L * 1024L * 1024L
        PreloadMode.BALANCED -> if (index == 0) 3L * 1024L * 1024L else 0L
        PreloadMode.DATA_SAVER -> 0L
    }

    fun bookmarkDetailCount(): Int = when (effectivePreloadMode()) {
        PreloadMode.AGGRESSIVE -> 15
        PreloadMode.BALANCED -> 3
        PreloadMode.DATA_SAVER -> 0
    }

    fun bookmarkVideoCount(): Int = when (effectivePreloadMode()) {
        PreloadMode.AGGRESSIVE -> 15
        PreloadMode.BALANCED -> 0
        PreloadMode.DATA_SAVER -> 0
    }

    fun bookmarkVideoBytes(): Long = when (effectivePreloadMode()) {
        PreloadMode.AGGRESSIVE -> 4608L * 1024L
        PreloadMode.BALANCED -> 0L
        PreloadMode.DATA_SAVER -> 0L
    }

    fun homePopularDetailCount(): Int = when (effectivePreloadMode()) {
        PreloadMode.AGGRESSIVE -> 12
        PreloadMode.BALANCED -> 4
        PreloadMode.DATA_SAVER -> 0
    }

    fun homeScheduleDetailCount(): Int = when (effectivePreloadMode()) {
        PreloadMode.AGGRESSIVE -> 24
        PreloadMode.BALANCED -> 4
        PreloadMode.DATA_SAVER -> 0
    }

    fun homeShowVideoBytes(): Long = when (effectivePreloadMode()) {
        PreloadMode.AGGRESSIVE -> 4L * 1024L * 1024L
        PreloadMode.BALANCED -> 0L
        PreloadMode.DATA_SAVER -> 0L
    }

    fun catalogDetailCount(): Int = when (effectivePreloadMode()) {
        PreloadMode.AGGRESSIVE -> 10
        PreloadMode.BALANCED -> 3
        PreloadMode.DATA_SAVER -> 0
    }

    fun catalogVideoCount(): Int = when (effectivePreloadMode()) {
        PreloadMode.AGGRESSIVE -> 8
        PreloadMode.BALANCED -> 0
        PreloadMode.DATA_SAVER -> 0
    }

    fun detailPrimaryVideoBytes(): Long = when (effectivePreloadMode()) {
        PreloadMode.AGGRESSIVE -> 8L * 1024L * 1024L
        PreloadMode.BALANCED -> 4L * 1024L * 1024L
        PreloadMode.DATA_SAVER -> 0L
    }

    fun detailFollowupVideoBytes(): Long = when (effectivePreloadMode()) {
        PreloadMode.AGGRESSIVE -> 4L * 1024L * 1024L
        PreloadMode.BALANCED -> 0L
        PreloadMode.DATA_SAVER -> 0L
    }

    fun focusHoverVideoBytes(): Long = when (effectivePreloadMode()) {
        PreloadMode.AGGRESSIVE -> 5L * 1024L * 1024L
        PreloadMode.BALANCED -> 0L
        PreloadMode.DATA_SAVER -> 0L
    }

    fun nextEpisodeInitialBytes(): Long = when (effectivePreloadMode()) {
        PreloadMode.AGGRESSIVE -> 6L * 1024L * 1024L
        PreloadMode.BALANCED -> 4L * 1024L * 1024L
        PreloadMode.DATA_SAVER -> 0L
    }

    fun nextEpisodeEscalatedBytes(): Long = when (effectivePreloadMode()) {
        PreloadMode.AGGRESSIVE -> 20L * 1024L * 1024L
        PreloadMode.BALANCED -> 10L * 1024L * 1024L
        PreloadMode.DATA_SAVER -> 0L
    }
}

internal fun resolvePreloadMode(configured: PreloadMode, metered: Boolean): PreloadMode =
    if (metered) PreloadMode.DATA_SAVER else configured

object NetworkPreconnector {
    private val SKIP_API_URLS = listOf(
        "https://graphql.anilist.co",
        "https://api.aniskip.com"
    )
    private val COOLDOWN_MS = TimeUnit.MINUTES.toMillis(4)
    private val lastWarmedAtMs = LruCacheMap<String, Long>(64)

    @Synchronized
    private fun claimWarmup(url: String, now: Long): Boolean {
        val prev = lastWarmedAtMs[url]
        if (prev != null && now - prev in 0 until COOLDOWN_MS) return false
        lastWarmedAtMs[url] = now
        return true
    }

    fun preconnectAsync(okHttpClient: OkHttpClient) {
        preconnectUrls(okHttpClient, listOf("${AnimeHeavenClient.BASE_URL}/"))
    }

    fun preconnectSkipApisAsync(okHttpClient: OkHttpClient) {
        preconnectUrls(okHttpClient, SKIP_API_URLS)
    }

    fun preconnectCdnHostsAsync(okHttpClient: OkHttpClient, hosts: List<String>) {
        if (hosts.isEmpty()) return
        val urls = hosts.asSequence()
            .map { it.trim().lowercase() }
            .filter { it.matches(Regex("""[a-z0-9.-]+""")) }
            .distinct()
            .take(3)
            .map { "https://$it/" }
            .toList()
        preconnectUrls(okHttpClient, urls)
    }

    private fun preconnectUrls(okHttpClient: OkHttpClient, urls: List<String>) {
        val now = System.currentTimeMillis()
        for (url in urls) {
            val parsed = url.toHttpUrlOrNull() ?: continue
            if (!claimWarmup(parsed.toString(), now)) continue
            val req = Request.Builder()
                .url(parsed)
                .head()
                .header("User-Agent", AnimeHeavenClient.USER_AGENT)
                .build()
            okHttpClient.newCall(req).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = Unit

                override fun onResponse(call: Call, response: Response) {
                    response.close()
                }
            })
        }
    }
}
