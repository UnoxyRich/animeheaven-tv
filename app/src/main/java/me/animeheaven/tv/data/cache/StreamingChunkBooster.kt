package me.animeheaven.tv.data.cache

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.SimpleCache
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import me.animeheaven.tv.data.network.AnimeHeavenClient
import me.animeheaven.tv.data.network.readResponse
import me.animeheaven.tv.data.runCatchingCancellable
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Multi-mirror parallel HTTP-Range chunk feeder for Media3 SimpleCache.
 *
 * AnimeHeaven CDN servers throttle single TCP streams (~140-250 KB/s per socket), which sits
 * close to 1080p video bitrates and causes mid-episode rebuffering. Fetching verified equivalent
 * 512 KB byte spans in parallel across throughput-ranked healthy mirrors into RAM first (with
 * zero SimpleCache lock during network I/O, cancellable sockets, instant seek wakeup, and
 * non-faststart MP4 tail moov warmup) eliminates mid-stream stalls and makes Skip OP/seek instant.
 */
@OptIn(UnstableApi::class)
class StreamingChunkBooster(
    private val simpleCache: SimpleCache,
    private val cacheSinkFactory: CacheDataSink.Factory,
    private val videoHttpClient: OkHttpClient,
    private val mirrorHealthTracker: MirrorHealthTracker? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private class BoostSession(
        val cacheKey: String,
        val mirrors: List<String>,
        val totalLength: Long
    ) {
        val queueMutex = Mutex()
        val inFlightChunks = HashSet<Long>()
        val failedChunkAttempts = ConcurrentHashMap<Long, Int>()
        val wakeup = Channel<Unit>(Channel.CONFLATED)
    }

    @Volatile
    private var activeSession: BoostSession? = null

    @Volatile
    private var streamingJob: Job? = null

    @Volatile
    private var currentPlayheadMs: Long = 0L
    @Volatile
    private var currentBufferedMs: Long = 0L

    @Volatile
    private var currentDurationMs: Long = 0L

    @Volatile
    private var currentOpEndMs: Long = 0L

    @Volatile
    private var lastSeekEventAtMs: Long = 0L

    private val reusableBufferPool = ConcurrentLinkedQueue<ByteArray>()

    private fun acquireBuffer(): ByteArray =
        reusableBufferPool.poll() ?: ByteArray(CHUNK_SIZE_BYTES.toInt())

    private fun releaseBuffer(buffer: ByteArray) {
        if (reusableBufferPool.size < MAX_POOL_BUFFERS) {
            reusableBufferPool.offer(buffer)
        }
    }

    fun trimBuffers() {
        reusableBufferPool.clear()
    }

    /**
     * Pre-warms initial MP4 header (chunk 0), automatic tail chunk when chunk 0 lacks a `moov`
     * atom (non-faststart MP4), resume window (or head bytes), and post-OP jump window concurrently
     * across verified mirrors before playback starts.
     */
    suspend fun preloadInitialWindows(
        cacheKey: String,
        mirrors: List<String>,
        totalLength: Long,
        targetBytes: Long,
        resumePositionMs: Long,
        estimatedDurationMs: Long,
        opEndMs: Long
    ) {
        if (mirrors.isEmpty() || totalLength <= 0L || targetBytes <= 0L) return
        val rankedMirrors = mirrorHealthTracker?.rankMirrors(mirrors) ?: mirrors
        val chunks = buildInitialWindowChunks(
            totalLength = totalLength,
            targetBytes = targetBytes,
            resumePositionMs = resumePositionMs,
            estimatedDurationMs = estimatedDurationMs,
            opEndMs = opEndMs
        )
        val pending = ArrayDeque<Long>()
        for (start in chunks) {
            val len = minOf(CHUNK_SIZE_BYTES, totalLength - start)
            if (len > 0L && !isChunkCached(cacheKey, start, len)) {
                pending.addLast(start)
            }
        }
        if (pending.isEmpty()) return

        val workers = minOf(MAX_PRELOAD_WORKERS, maxOf(2, rankedMirrors.size))
        val queueMutex = Mutex()
        val tailStart = alignChunkStart((totalLength - 1L).coerceAtLeast(0L))

        coroutineScope {
            (0 until workers).map { workerIdx ->
                async(Dispatchers.IO) {
                    val buffer = acquireBuffer()
                    try {
                        while (isActive) {
                            val start = queueMutex.withLock {
                                if (pending.isNotEmpty()) pending.removeFirst() else null
                            } ?: break
                            val len = minOf(CHUNK_SIZE_BYTES, totalLength - start)
                            if (len <= 0L || isChunkCached(cacheKey, start, len)) continue
                            val mirror = rankedMirrors[workerIdx % rankedMirrors.size]
                            val bytesRead = runCatchingCancellable {
                                fetchAndCommitChunk(
                                    cacheKey = cacheKey,
                                    mirrorUrl = mirror,
                                    chunkStart = start,
                                    chunkLen = len,
                                    totalLength = totalLength,
                                    buffer = buffer
                                )
                            }.getOrDefault(-1)

                            // If chunk 0 lacks an MP4 'moov' atom, queue final chunk immediately so
                            // ExoPlayer can open non-faststart MP4s with zero extra tail RTT.
                            if (start == 0L && bytesRead > 0 && tailStart > 0L &&
                                !containsMp4MoovAtom(buffer, bytesRead)
                            ) {
                                val tailLen = minOf(CHUNK_SIZE_BYTES, totalLength - tailStart)
                                if (tailLen > 0L && !isChunkCached(cacheKey, tailStart, tailLen)) {
                                    queueMutex.withLock {
                                        if (tailStart !in pending) {
                                            pending.addFirst(tailStart)
                                        }
                                    }
                                }
                            }
                        }
                    } finally {
                        releaseBuffer(buffer)
                    }
                }
            }.awaitAll()
        }
    }

    /**
     * Runs 2-4 parallel workers maintaining a 36 MB (~4-5 min) ahead cache buffer beyond ExoPlayer's
     * playhead plus the post-OP skip landing zone until cancelled or stopped.
     */
    fun startStreamingBoost(
        cacheKey: String,
        mirrors: List<String>,
        totalLength: Long,
        initialPositionMs: Long,
        initialDurationMs: Long,
        opEndMs: Long
    ) {
        if (mirrors.isEmpty() || totalLength <= 0L) return
        streamingJob?.cancel()
        val session = BoostSession(
            cacheKey = cacheKey,
            mirrors = mirrors,
            totalLength = totalLength
        )
        activeSession = session
        currentPlayheadMs = initialPositionMs.coerceAtLeast(0L)
        currentBufferedMs = currentPlayheadMs
        currentDurationMs = initialDurationMs.coerceAtLeast(0L)
        currentOpEndMs = opEndMs.coerceAtLeast(0L)
        lastSeekEventAtMs = System.currentTimeMillis()

        val distinctHosts = mirrors.mapNotNull { runCatching { Uri.parse(it).host }.getOrNull() }.distinct().size
        val workerCount = if (distinctHosts >= 2) MULTI_MIRROR_WORKERS else SINGLE_HOST_WORKERS

        streamingJob = scope.launch {
            coroutineScope {
                (0 until workerCount).map { workerIdx ->
                    launch(Dispatchers.IO) {
                        val buffer = acquireBuffer()
                        var attemptShift = 0
                        try {
                            while (isActive && activeSession === session) {
                                val nextStart = claimNextStreamChunk(session)
                                if (nextStart == null) {
                                    withTimeoutOrNull(650L) {
                                        session.wakeup.receive()
                                    }
                                    continue
                                }
                                val len = minOf(CHUNK_SIZE_BYTES, session.totalLength - nextStart)
                                val mirrorUrl = pickAdaptiveMirror(session.mirrors, workerIdx, attemptShift)
                                val bytesRead = try {
                                    fetchAndCommitChunk(
                                        cacheKey = session.cacheKey,
                                        mirrorUrl = mirrorUrl,
                                        chunkStart = nextStart,
                                        chunkLen = len,
                                        totalLength = session.totalLength,
                                        buffer = buffer
                                    )
                                } catch (ce: CancellationException) {
                                    throw ce
                                } catch (_: IOException) {
                                    -1
                                } finally {
                                    releaseChunk(session, nextStart)
                                }
                                if (bytesRead < 0) {
                                    session.failedChunkAttempts[nextStart] =
                                        (session.failedChunkAttempts[nextStart] ?: 0) + 1
                                    attemptShift++
                                    delay(120L)
                                } else {
                                    attemptShift = 0
                                }
                            }
                        } finally {
                            releaseBuffer(buffer)
                        }
                    }
                }
            }
        }
    }

    fun updatePlayhead(positionMs: Long, durationMs: Long, opEndMs: Long, bufferedMs: Long = positionMs) {
        val clamped = positionMs.coerceAtLeast(0L)
        val prev = currentPlayheadMs
        currentPlayheadMs = clamped
        currentBufferedMs = bufferedMs.coerceAtLeast(clamped)
        if (durationMs > 0L) currentDurationMs = durationMs
        if (opEndMs > 0L) currentOpEndMs = opEndMs
        if (abs(clamped - prev) > SEEK_JUMP_THRESHOLD_MS) {
            lastSeekEventAtMs = System.currentTimeMillis()
            activeSession?.wakeup?.trySend(Unit)
        }
    }

    fun stopStreamingBoost() {
        val prev = activeSession
        activeSession = null
        streamingJob?.cancel()
        prev?.wakeup?.trySend(Unit)
        streamingJob = null
    }

    private fun pickAdaptiveMirror(
        mirrors: List<String>,
        workerIdx: Int,
        attemptShift: Int
    ): String {
        if (mirrors.size == 1) return mirrors[0]
        val ranked = mirrorHealthTracker?.rankMirrors(mirrors) ?: mirrors
        val activePoolSize = if (attemptShift == 0 && ranked.size > 2) {
            minOf(ranked.size, 3)
        } else {
            ranked.size
        }
        return ranked[(workerIdx + attemptShift) % activePoolSize]
    }

    private suspend fun claimNextStreamChunk(session: BoostSession): Long? {
        return session.queueMutex.withLock {
            val playheadMs = currentPlayheadMs
            val durMs = currentDurationMs
            val opEnd = currentOpEndMs

            val estByte = estimateByteOffset(maxOf(playheadMs, currentBufferedMs), durMs, session.totalLength)
            val recentlySeeked = (System.currentTimeMillis() - lastSeekEventAtMs) < RECENT_SEEK_WINDOW_MS
            val aheadChunks = if (recentlySeeked) 1L else 2L
            val aheadAnchor = alignChunkStart((estByte + aheadChunks * CHUNK_SIZE_BYTES).coerceAtLeast(0L))
            val maxAhead = minOf(session.totalLength, aheadAnchor + SLIDING_AHEAD_WINDOW_BYTES)

            // 1. Prioritize post-OP skip destination (1.5 MB) if playhead is still before OP end
            if (opEnd > playheadMs + 4_000L && durMs > 0L) {
                val opByte = alignChunkStart(estimateByteOffset(opEnd, durMs, session.totalLength))
                for (step in 0 until 3) {
                    val cStart = opByte + step * CHUNK_SIZE_BYTES
                    if (isChunkClaimable(session, cStart)) {
                        session.inFlightChunks.add(cStart)
                        return@withLock cStart
                    }
                }
            }

            // 2. Sequential sliding window ahead of playhead
            var cursor = aheadAnchor
            while (cursor < maxAhead) {
                if (isChunkClaimable(session, cursor)) {
                    session.inFlightChunks.add(cursor)
                    return@withLock cursor
                }
                cursor += CHUNK_SIZE_BYTES
            }
            null
        }
    }

    private suspend fun releaseChunk(session: BoostSession, chunkStart: Long) {
        session.queueMutex.withLock {
            session.inFlightChunks.remove(chunkStart)
        }
    }

    private fun isChunkClaimable(session: BoostSession, chunkStart: Long): Boolean {
        if (chunkStart < 0L || chunkStart >= session.totalLength) return false
        if (chunkStart in session.inFlightChunks) return false
        if ((session.failedChunkAttempts[chunkStart] ?: 0) >= MAX_CHUNK_RETRIES) return false
        val len = minOf(CHUNK_SIZE_BYTES, session.totalLength - chunkStart)
        return !isChunkCached(session.cacheKey, chunkStart, len)
    }

    private fun isChunkCached(cacheKey: String, position: Long, length: Long): Boolean {
        return runCatching {
            simpleCache.getCachedBytes(cacheKey, position, length) >= length
        }.getOrDefault(false)
    }

    private suspend fun fetchAndCommitChunk(
        cacheKey: String,
        mirrorUrl: String,
        chunkStart: Long,
        chunkLen: Long,
        totalLength: Long,
        buffer: ByteArray
    ): Int {
        if (isChunkCached(cacheKey, chunkStart, chunkLen)) return 0
        val rangeEnd = chunkStart + chunkLen - 1L
        val req = Request.Builder()
            .url(mirrorUrl)
            .header("User-Agent", AnimeHeavenClient.USER_AGENT)
            .header("Referer", "${AnimeHeavenClient.BASE_URL}/")
            .header("Range", "bytes=$chunkStart-$rangeEnd")
            .header("Accept-Encoding", "identity")
            .build()

        val expectedLen = chunkLen.toInt()
        val startNs = System.nanoTime()
        // Keep the worker's reusable buffer out of the callback: when a cancelled caller
        // returns it to the pool, the socket reader may still be unwinding.
        val bytes = try {
            videoHttpClient.readResponse(req) { resp ->
                if (!isValidChunkResponse(
                        resp.code, resp.header("Content-Range"), resp.header("Content-Type"),
                        resp.header("Content-Encoding"), chunkStart, rangeEnd, totalLength,
                        resp.header("Content-Length")?.toLongOrNull()
                    )
                ) {
                    throw IOException("Invalid HTTP ${resp.code} response for range $chunkStart-$rangeEnd")
                }
                val source = resp.body?.source() ?: throw IOException("Empty chunk body")
                source.readByteArray(expectedLen.toLong())
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (err: IOException) {
            mirrorHealthTracker?.recordFailure(mirrorUrl)
            throw err
        }

        currentCoroutineContext().ensureActive()
        val totalRead = bytes.size
        bytes.copyInto(buffer)

        val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
        mirrorHealthTracker?.recordChunkThroughput(mirrorUrl, totalRead.toLong(), elapsedMs)

        if (!commitValidatedChunk(cacheKey, chunkStart, buffer, totalRead)) delay(120L)
        return totalRead
    }

    internal suspend fun commitValidatedChunk(cacheKey: String, chunkStart: Long, buffer: ByteArray, bytes: Int): Boolean {
        val end = chunkStart + bytes
        var position = chunkStart
        while (position < end) {
            currentCoroutineContext().ensureActive()
            // Download first, then lock only the missing span while committing. If Media3
            // owns it already, leave that span to the player instead of writing over it.
            val span = simpleCache.startReadWriteNonBlocking(cacheKey, position, end - position)
                ?: return false
            if (span.isCached) {
                position = minOf(end, span.position + span.length)
                continue
            }
            try {
                val length = if (span.isOpenEnded) end - position else minOf(span.length, end - position)
                val dataSpec = DataSpec.Builder()
                    .setUri(Uri.parse(cacheKey))
                    .setKey(cacheKey)
                    .setPosition(position)
                    .setLength(length)
                    .build()
                val sink = cacheSinkFactory.createDataSink()
                try {
                    sink.open(dataSpec)
                    sink.write(buffer, (position - chunkStart).toInt(), length.toInt())
                } finally {
                    sink.close()
                }
                position += length
            } finally {
                simpleCache.releaseHoleSpan(span)
            }
        }
        return true
    }

    companion object {
        private val CONTENT_RANGE = Regex("""bytes (\d+)-(\d+)/(\d+)""", RegexOption.IGNORE_CASE)

        internal fun isValidChunkResponse(
            code: Int, range: String?, type: String?, encoding: String?, start: Long, end: Long,
            expectedTotal: Long? = null, contentLength: Long? = null
        ): Boolean {
            val mime = type.orEmpty().substringBefore(';').trim().lowercase()
            if (mime.startsWith("text/") || "html" in mime || "json" in mime ||
                (encoding != null && !encoding.equals("identity", ignoreCase = true))
            ) return false
            if (code == 200) return start == 0L && range == null &&
                (expectedTotal == null || contentLength == expectedTotal)
            if (code != 206) return false
            val fields = range?.let(CONTENT_RANGE::matchEntire)?.groupValues ?: return false
            val total = fields[3].toLongOrNull() ?: return false
            return fields[1].toLongOrNull() == start && fields[2].toLongOrNull() == end && total > end &&
                (expectedTotal == null || expectedTotal == total) &&
                (contentLength == null || contentLength == end - start + 1L)
        }

        const val CHUNK_SIZE_BYTES = 512L * 1024L
        private const val SLIDING_AHEAD_WINDOW_BYTES = 36L * 1024L * 1024L
        private const val MAX_PRELOAD_WORKERS = 4
        private const val MULTI_MIRROR_WORKERS = 4
        private const val SINGLE_HOST_WORKERS = 2
        private const val MAX_CHUNK_RETRIES = 3
        private const val MAX_POOL_BUFFERS = 4
        private const val SEEK_JUMP_THRESHOLD_MS = 4_000L
        private const val RECENT_SEEK_WINDOW_MS = 8_000L

        fun alignChunkStart(byteOffset: Long): Long =
            (byteOffset.coerceAtLeast(0L) / CHUNK_SIZE_BYTES) * CHUNK_SIZE_BYTES

        fun estimateByteOffset(
            positionMs: Long,
            durationMs: Long,
            totalLength: Long
        ): Long {
            if (positionMs <= 0L || durationMs <= 0L || totalLength <= 0L) return 0L
            val ratio = (positionMs.toDouble() / durationMs.toDouble()).coerceIn(0.0, 0.99)
            return (ratio * totalLength.toDouble()).toLong()
        }

        /**
         * Scans an MP4 head buffer for the 4-byte ASCII `moov` box identifier. If missing in chunk 0,
         * the MP4 is non-faststart and stores its movie index at the tail of the file.
         */
        fun containsMp4MoovAtom(bytes: ByteArray, length: Int = bytes.size): Boolean {
            val limit = minOf(length, bytes.size) - 3
            var i = 0
            while (i < limit) {
                if (bytes[i] == 0x6D.toByte() &&      // 'm'
                    bytes[i + 1] == 0x6F.toByte() &&  // 'o'
                    bytes[i + 2] == 0x6F.toByte() &&  // 'o'
                    bytes[i + 3] == 0x76.toByte()     // 'v'
                ) {
                    return true
                }
                i++
            }
            return false
        }

        fun buildInitialWindowChunks(
            totalLength: Long,
            targetBytes: Long,
            resumePositionMs: Long,
            estimatedDurationMs: Long,
            opEndMs: Long
        ): List<Long> {
            if (totalLength <= 0L || targetBytes <= 0L) return emptyList()
            val ordered = LinkedHashSet<Long>()
            ordered.add(0L)

            val chunkCount = ((targetBytes + CHUNK_SIZE_BYTES - 1L) / CHUNK_SIZE_BYTES)
                .toInt()
                .coerceAtLeast(1)

            val startByte = if (resumePositionMs > 10_000L && estimatedDurationMs > 0L) {
                val est = estimateByteOffset(resumePositionMs, estimatedDurationMs, totalLength)
                alignChunkStart((est - CHUNK_SIZE_BYTES).coerceAtLeast(0L))
            } else {
                0L
            }

            for (i in 0 until chunkCount) {
                val c = startByte + i * CHUNK_SIZE_BYTES
                if (c < totalLength) ordered.add(c)
            }

            if (opEndMs > 5_000L && estimatedDurationMs > 0L) {
                val opStart = alignChunkStart(estimateByteOffset(opEndMs, estimatedDurationMs, totalLength))
                for (step in 0 until 3) {
                    val c = opStart + step * CHUNK_SIZE_BYTES
                    if (c < totalLength) ordered.add(c)
                }
            }

            return ordered.toList()
        }
    }
}
