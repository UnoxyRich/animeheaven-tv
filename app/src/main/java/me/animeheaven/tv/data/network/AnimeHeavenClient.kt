package me.animeheaven.tv.data.network

import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import me.animeheaven.tv.data.parser.AnimeHeavenParser
import okhttp3.Cache
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
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
                if (cont.isActive) {
                    cont.resume(response)
                } else {
                    response.close()
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
        val cleanPath = relativePath.trimStart('/')
        val url = if (cleanPath.startsWith("http://") || cleanPath.startsWith("https://")) {
            cleanPath
        } else {
            "$BASE_URL/$cleanPath"
        }
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()

        return executeForBody(okHttpClient, request)
    }

    suspend fun fetchFastSearch(query: String): String {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val request = Request.Builder()
            .url("$BASE_URL/fastsearch.php?xhr=1&s=$encoded")
            .header("User-Agent", USER_AGENT)
            .header("Referer", "$BASE_URL/")
            .header("X-Requested-With", "XMLHttpRequest")
            .build()

        return executeForBody(okHttpClient, request)
    }

    suspend fun fetchRandomAnimeId(): String {
        val request = Request.Builder()
            .url("$BASE_URL/random.php")
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
        val referer = if (!animeId.isNullOrBlank()) {
            "$BASE_URL/anime.php?$animeId"
        } else {
            "$BASE_URL/"
        }
        val request = Request.Builder()
            .url("$BASE_URL/gate.php")
            .header("User-Agent", USER_AGENT)
            .header("Referer", referer)
            .header("Cookie", "key=$episodeKey")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .build()

        return executeForBody(okHttpClient, request)
    }

    private suspend fun executeForBody(client: OkHttpClient, request: Request): String {
        val response = client.awaitResponse(request)
        return withContext(Dispatchers.IO) {
            response.use { resp ->
                if (!resp.isSuccessful) {
                    throw IOException("HTTP ${resp.code} for ${request.url}")
                }
                resp.body?.string() ?: throw IOException("Empty response body for ${request.url}")
            }
        }
    }

    companion object {
        const val BASE_URL = "https://animeheaven.me"
        const val USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private const val HTTP_CACHE_SIZE_BYTES = 24L * 1024L * 1024L

        fun defaultOkHttpClient(cacheDir: File? = null): OkHttpClient {
            val dispatcher = Dispatcher().apply {
                maxRequests = 32
                maxRequestsPerHost = 12
            }
            return OkHttpClient.Builder()
                .dispatcher(dispatcher)
                .dns(CachedDns())
                .connectionPool(ConnectionPool(12, 5, TimeUnit.MINUTES))
                .followRedirects(true)
                .followSslRedirects(true)
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(12, TimeUnit.SECONDS)
                .writeTimeout(12, TimeUnit.SECONDS)
                .apply {
                    if (cacheDir != null) {
                        cache(Cache(File(cacheDir, "http_cache"), HTTP_CACHE_SIZE_BYTES))
                    }
                }
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
