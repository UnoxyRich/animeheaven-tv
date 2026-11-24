package me.animeheaven.tv.data.network

import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import me.animeheaven.tv.data.parser.AnimeHeavenParser
import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Suspends until the OkHttp [Call] completes and immediately cancels the underlying socket
 * if the calling coroutine is cancelled (e.g. rapid Search typing, D-pad focus changes, or player exit).
 */
suspend fun OkHttpClient.awaitResponse(request: Request): Response =
    suspendCancellableCoroutine { cont ->
        val call = newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                cont.resume(response) { _, undelivered, _ -> undelivered.close() }
            }
        })
    }

/** Read and close the response while the Call's cancellation hook is still registered. */
internal suspend fun <T> OkHttpClient.readResponse(request: Request, read: (Response) -> T): T =
    suspendCancellableCoroutine { cont ->
        val call = newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val value = response.use {
                        if (!cont.isActive) return
                        read(it)
                    }
                    cont.resume(value)
                } catch (e: Exception) {
                    cont.resumeWithException(e)
                }
            }
        })
    }

class AnimeHeavenClient(
    val okHttpClient: OkHttpClient = defaultOkHttpClient()
) {
    private val noRedirectClient: OkHttpClient by lazy {
        okHttpClient.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    suspend fun fetchHtml(relativePath: String): String {
        val url = SITE.resolve(relativePath)?.takeIf { it.isSiteUrl() }
            ?: throw IOException("Invalid AnimeHeaven page URL")
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()

        return executeForBody(request)
    }

    suspend fun fetchFastSearch(query: String): String {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val request = Request.Builder()
            .url("$BASE_URL/fastsearch.php?xhr=1&s=$encoded")
            .header("User-Agent", USER_AGENT)
            .header("Referer", "$BASE_URL/")
            .header("X-Requested-With", "XMLHttpRequest")
            .build()

        return executeForBody(request)
    }

    suspend fun fetchRandomAnimeId(): String {
        val request = Request.Builder()
            .url("$BASE_URL/random.php")
            .cacheControl(CacheControl.FORCE_NETWORK)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "$BASE_URL/")
            .build()

        noRedirectClient.awaitResponse(request).use { response ->
            val location = response.header("Location")
            val fromLocation = location?.let { AnimeHeavenParser.extractAnimeId(it) }
            if (!fromLocation.isNullOrBlank()) {
                return fromLocation
            }
        }

        // Fallback in case server returned HTML or followed internally
        okHttpClient.awaitResponse(request).use { response ->
            val finalId = AnimeHeavenParser.extractAnimeId(response.request.url.toString())
            if (!finalId.isNullOrBlank()) {
                return finalId
            }
            throw IOException("Unable to resolve random anime ID")
        }
    }

    suspend fun fetchGateHtml(episodeKey: String, animeId: String?): String {
        require(EPISODE_KEY.matches(episodeKey)) { "Invalid episode key" }
        val referer = if (!animeId.isNullOrBlank()) {
            "$BASE_URL/anime.php?$animeId"
        } else {
            "$BASE_URL/"
        }
        val request = Request.Builder()
            .url("$BASE_URL/gate.php")
            .cacheControl(CacheControl.FORCE_NETWORK)
            .header("User-Agent", USER_AGENT)
            .header("Referer", referer)
            .header("Cookie", "key=$episodeKey")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .build()

        return executeForBody(request)
    }

    private suspend fun executeForBody(request: Request): String {
        var current = request
        repeat(6) {
            val result = noRedirectClient.readResponse(current) { resp ->
                if (resp.isRedirect) {
                    val url = resp.header("Location")?.let(current.url::resolve)
                        ?.takeIf { it.isSiteUrl() }
                        ?: throw IOException("Invalid AnimeHeaven redirect")
                    return@readResponse url to null
                }
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for ${current.url}")
                null to (resp.body?.string() ?: throw IOException("Empty response body"))
            }
            result.second?.let { return it }
            current = current.newBuilder().url(checkNotNull(result.first)).build()
        }
        throw IOException("Too many AnimeHeaven redirects")
    }

    companion object {
        const val BASE_URL = "https://animeheaven.me"
        private val SITE = BASE_URL.toHttpUrl()
        internal val EPISODE_KEY = Regex("[a-zA-Z0-9_-]+")
        private fun HttpUrl.isSiteUrl(): Boolean =
            scheme == SITE.scheme && host == SITE.host && port == SITE.port &&
                username.isEmpty() && password.isEmpty()
        const val USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        fun defaultOkHttpClient(dns: CachedDns = CachedDns()): OkHttpClient {
            val dispatcher = Dispatcher().apply {
                maxRequests = 32
                maxRequestsPerHost = 12
            }
            return OkHttpClient.Builder()
                .dispatcher(dispatcher)
                .dns(dns)
                .connectionPool(ConnectionPool(12, 5, TimeUnit.MINUTES))
                .followRedirects(true)
                .followSslRedirects(true)
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(12, TimeUnit.SECONDS)
                .writeTimeout(12, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    val original = chain.request()
                    val req = if (original.header("User-Agent") == null) {
                        original.newBuilder()
                            .header("User-Agent", USER_AGENT)
                            .build()
                    } else {
                        original
                    }
                    chain.proceed(req)
                }
                .build()
        }
    }
}
