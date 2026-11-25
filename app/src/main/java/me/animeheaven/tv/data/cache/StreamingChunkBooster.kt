package me.animeheaven.tv.data.cache

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.SimpleCache
import java.io.IOException
import java.io.RandomAccessFile
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
 * Uses [Mp4MoovSeekParser] to assemble faststart or multi-chunk tail `moov` atoms and build
 * exact `(timeMs -> byteOffset)` keyframe seek tables, pins preloaded hot windows in
 * [PinnedPriorityCacheEvictor], and feeds active playback immediately at the playhead frontier
 * with zero blind-spot gaps.
 */
@OptIn(UnstableApi::class)
class StreamingChunkBooster(
    private val simpleCache: SimpleCache,
    private val cacheSinkFactory: CacheDataSink.Factory,
    private val videoHttpClient: OkHttpClient,
    private val mirrorHealthTracker: MirrorHealthTracker? = null,
    private val cacheEvictor: PinnedPriorityCacheEvictor? = null,
    private val lowRamDevice: Boolean = false,
    private val onVideoIndexReady: (suspend (EpisodeVideoIndex) -> Unit)? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val slidingAheadWindowBytes: Long =
        if (lowRamDevice) LOW_RAM_SLIDING_AHEAD_WINDOW_BYTES else SLIDING_AHEAD_WINDOW_BYTES

    private class BoostSession(
        val cacheKey: String,
        val mirrors: List<String>,
        val totalLength: Long,
        @Volatile var videoIndex: EpisodeVideoIndex? = null
    ) {
        val queueMutex = Mutex()
        val inFlightChunks = HashSet<Long>()
        val failedChunkAttempts = ConcurrentHashMap<Long, Int>()
        val wakeup = Channel<Unit>(Channel.CONFLATED)
        @Volatile var lastDemotedTrailingByte: Long = 0L
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
     * Pre-warms chunk 0, complete faststart or multi-chunk tail `moov` atom, parses & persists
     * an exact [EpisodeVideoIndex], pins the hot window in [cacheEvictor], and fetches the exact
     * start/resume and post-OP windows concurrently across verified mirrors.
     */
    suspend fun preloadInitialWindows(
        cacheKey: String,
        mirrors: List<String>,
        totalLength: Long,
        targetBytes: Long,
        resumePositionMs: Long,
        estimatedDurationMs: Long,
        opEndMs: Long,
        episodeKey: String? = null,
        existingIndex: EpisodeVideoIndex? = null
    ): EpisodeVideoIndex? {
        if (mirrors.isEmpty() || totalLength <= 0L || targetBytes <= 0L) return existingIndex
        val rankedMirrors = mirrorHealthTracker?.rankMirrors(mirrors) ?: mirrors
        var resolvedIndex = existingIndex

        // Step 1: Ensure chunk 0 is cached & available for MP4 moov inspection.
        val chunk0Len = minOf(CHUNK_SIZE_BYTES, totalLength)
        val chunk0Bytes = ensureChunk0Bytes(cacheKey, rankedMirrors, chunk0Len, totalLength)
        cacheEvictor?.markHotWindow(cacheKey, 0L, chunk0Len)

        // Step 2: Locate and warm the complete MP4 moov box (head or multi-chunk tail) and parse seek index.
        val moovLoc = if (chunk0Bytes != null) {
            Mp4MoovSeekParser.inspectTopLevelBoxes(chunk0Bytes, chunk0Bytes.size, totalLength)
        } else {
            null
        }
        val moovChunks = ArrayList<Long>(4)
        if (moovLoc != null && moovLoc.size in 16L..Mp4MoovSeekParser.MAX_MOOV_PARSE_BYTES) {
            val firstMoovChunk = alignChunkStart(moovLoc.offset)
            val moovEnd = minOf(totalLength, moovLoc.offset + moovLoc.size)
            var c = firstMoovChunk
            while (c < moovEnd) {
                moovChunks.add(c)
                c += CHUNK_SIZE_BYTES
            }
            cacheEvictor?.markHotWindow(cacheKey, firstMoovChunk, moovEnd)
        } else if (chunk0Bytes != null && !containsMp4MoovAtom(chunk0Bytes, chunk0Bytes.size)) {
            // Non-faststart MP4 without explicit mdat length: warm trailing 3 chunks (up to 1.5 MB).
            val tailStart = alignChunkStart((totalLength - 1L).coerceAtLeast(0L))
            for (step in 0 until FALLBACK_TAIL_MOOV_CHUNKS) {
                val c = tailStart - step * CHUNK_SIZE_BYTES
                if (c > 0L) moovChunks.add(c)
            }
            if (moovChunks.isNotEmpty()) {
                cacheEvictor?.markHotWindow(cacheKey, moovChunks.min(), totalLength)
            }
        }

        if (moovChunks.isNotEmpty()) {
            fetchChunksParallel(cacheKey, rankedMirrors, totalLength, moovChunks)
        }

        if (resolvedIndex == null || resolvedIndex.seekPoints.isEmpty()) {
            val epKey = episodeKey?.takeIf { it.isNotBlank() }
            if (epKey != null && moovLoc != null && moovLoc.size in 16L..Mp4MoovSeekParser.MAX_MOOV_PARSE_BYTES) {
                val moovData = readCachedRange(cacheKey, moovLoc.offset, moovLoc.size.toInt())
                if (moovData != null) {
                    val parsed = Mp4MoovSeekParser.parseMoovIndex(
                        episodeKey = epKey,
                        contentLength = totalLength,
                        moovFileOffset = moovLoc.offset,
                        buffer = moovData,
                        bufferLength = moovData.size,
                        bufferBaseFileOffset = moovLoc.offset
                    )
                    if (parsed != null) {
                        resolvedIndex = parsed
                        runCatchingCancellable { onVideoIndexReady?.invoke(parsed) }
                    }
                }
            }
        }

        // Step 3: Compute target start/resume & post-OP chunks using exact moov seek index when available.
        val effectiveDurationMs = when {
            resolvedIndex != null && resolvedIndex.durationMs > 0L -> resolvedIndex.durationMs
            estimatedDurationMs > 0L -> estimatedDurationMs
            else -> 0L
        }
        val chunks = buildInitialWindowChunks(
            totalLength = totalLength,
            targetBytes = targetBytes,
            resumePositionMs = resumePositionMs,
            estimatedDurationMs = effectiveDurationMs,
            opEndMs = opEndMs,
            videoIndex = resolvedIndex
        )
        pinChunkList(cacheKey, chunks, totalLength)
        fetchChunksParallel(cacheKey, rankedMirrors, totalLength, chunks)
        return resolvedIndex
    }

    private suspend fun ensureChunk0Bytes(
        cacheKey: String,
        rankedMirrors: List<String>,
        chunk0Len: Long,
        totalLength: Long
    ): ByteArray? {
        if (isChunkCached(cacheKey, 0L, chunk0Len)) {
            return readCachedRange(cacheKey, 0L, chunk0Len.toInt())
        }
        val buffer = acquireBuffer()
        try {
            for (mirror in rankedMirrors.take(3)) {
                val read = runCatchingCancellable {
                    fetchAndCommitChunk(
                        cacheKey = cacheKey,
                        mirrorUrl = mirror,
                        chunkStart = 0L,
                        chunkLen = chunk0Len,
                        totalLength = totalLength,
                        buffer = buffer
                    )
                }.getOrDefault(-1)
                if (read > 0) {
                    return buffer.copyOf(read)
                }
            }
            return readCachedRange(cacheKey, 0L, chunk0Len.toInt())
        } finally {
            releaseBuffer(buffer)
        }
    }

    private suspend fun fetchChunksParallel(
        cacheKey: String,
        rankedMirrors: List<String>,
        totalLength: Long,
        chunks: List<Long>
    ) {
        val pending = ArrayDeque<Long>()
        val seen = HashSet<Long>(chunks.size)
        for (start in chunks) {
            if (!seen.add(start)) continue
            val len = minOf(CHUNK_SIZE_BYTES, totalLength - start)
            if (len > 0L && !isChunkCached(cacheKey, start, len)) {
                pending.addLast(start)
            }
        }
        if (pending.isEmpty()) return

        val workers = minOf(MAX_PRELOAD_WORKERS, maxOf(2, rankedMirrors.size))
        val queueMutex = Mutex()
        coroutineScope {
            (0 until workers).map { workerIdx ->
                async(Dispatchers.IO) {
                    val buffer = acquireBuffer()
                    var attemptShift = 0
                    try {
                        while (isActive) {
                            val start = queueMutex.withLock {
                                if (pending.isNotEmpty()) pending.removeFirst() else null
                            } ?: break
                            val len = minOf(CHUNK_SIZE_BYTES, totalLength - start)
                            if (len <= 0L || isChunkCached(cacheKey, start, len)) continue

                            var succeeded = false
                            val maxTries = minOf(rankedMirrors.size, 2)
                            for (retry in 0 until maxTries) {
                                val mirror = rankedMirrors[(workerIdx + attemptShift + retry) % rankedMirrors.size]
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
                                if (bytesRead >= 0) {
                                    succeeded = true
                                    break
                                }
                            }
                            if (!succeeded) attemptShift++
                        }
                    } finally {
                        releaseBuffer(buffer)
                    }
                }
            }.awaitAll()
        }
    }

    private fun pinChunkList(cacheKey: String, chunks: List<Long>, totalLength: Long) {
        val evictor = cacheEvictor ?: return
        if (chunks.isEmpty()) return
        val sorted = chunks.sorted()
        var rangeStart = sorted[0]
        var rangeEnd = minOf(totalLength, rangeStart + CHUNK_SIZE_BYTES)
        for (i in 1 until sorted.size) {
            val cStart = sorted[i]
            val cEnd = minOf(totalLength, cStart + CHUNK_SIZE_BYTES)
            if (cStart <= rangeEnd) {
                rangeEnd = maxOf(rangeEnd, cEnd)
            } else {
                evictor.markHotWindow(cacheKey, rangeStart, rangeEnd)
                rangeStart = cStart
                rangeEnd = cEnd
            }
        }
        evictor.markHotWindow(cacheKey, rangeStart, rangeEnd)
    }

    /**
     * Runs 3-4 parallel workers maintaining a 48 MB (24 MB low-RAM) ahead cache buffer right from
     * the playhead/buffer frontier plus the exact post-OP skip landing zone until cancelled/stopped.
     */
    fun startStreamingBoost(
        cacheKey: String,
        mirrors: List<String>,
        totalLength: Long,
        initialPositionMs: Long,
        initialDurationMs: Long,
        opEndMs: Long,
        videoIndex: EpisodeVideoIndex? = null
    ) {
        if (mirrors.isEmpty() || totalLength <= 0L) return
        streamingJob?.cancel()
        val resolvedDur = when {
            initialDurationMs > 0L -> initialDurationMs
            videoIndex != null && videoIndex.durationMs > 0L -> videoIndex.durationMs
            else -> 0L
        }
        val session = BoostSession(
            cacheKey = cacheKey,
            mirrors = mirrors,
            totalLength = totalLength,
            videoIndex = videoIndex
        )
        activeSession = session
        currentPlayheadMs = initialPositionMs.coerceAtLeast(0L)
        currentBufferedMs = currentPlayheadMs
        currentDurationMs = resolvedDur
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

        // Demote watched chunks > 3 minutes behind playhead so they evict before preloaded anime starts
        val session = activeSession
        val resolvedDur = currentDurationMs
        if (session != null && clamped > TRAILING_DEMOTE_BACK_BUFFER_MS && resolvedDur > 0L) {
            val behindMs = clamped - TRAILING_DEMOTE_BACK_BUFFER_MS
            val behindByte = resolveByteOffset(session.videoIndex, behindMs, resolvedDur, session.totalLength)
            if (behindByte > CHUNK_SIZE_BYTES * 2L &&
                behindByte - session.lastDemotedTrailingByte >= TRAILING_DEMOTE_STEP_BYTES
            ) {
                session.lastDemotedTrailingByte = behindByte
                cacheEvictor?.demoteTrailingChunks(session.cacheKey, alignChunkStart(behindByte))
            }
        }

        if (abs(clamped - prev) > SEEK_JUMP_THRESHOLD_MS) {
            lastSeekEventAtMs = System.currentTimeMillis()
            session?.wakeup?.trySend(Unit)
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
            val durMs = currentDurationMs.takeIf { it > 0L }
                ?: session.videoIndex?.durationMs?.takeIf { it > 0L }
                ?: 0L
            val opEnd = currentOpEndMs

            val targetMs = maxOf(playheadMs, currentBufferedMs)
            val estByte = resolveByteOffset(session.videoIndex, targetMs, durMs, session.totalLength)
            // Start boosting immediately at the playhead/buffer frontier (0 blind-spot chunks).
            val aheadAnchor = alignChunkStart(estByte.coerceAtLeast(0L))
            val maxAhead = minOf(session.totalLength, aheadAnchor + slidingAheadWindowBytes)
            cacheEvictor?.markHotWindow(session.cacheKey, aheadAnchor, minOf(session.totalLength, aheadAnchor + ACTIVE_PIN_AHEAD_BYTES))

            // 1. Prioritize post-OP skip destination (1.5 MB) if playhead is still before OP end
            if (opEnd > playheadMs + 4_000L && durMs > 0L) {
                val opByte = alignChunkStart(
                    resolveByteOffset(session.videoIndex, opEnd, durMs, session.totalLength)
                )
                for (step in 0 until 3) {
                    val cStart = opByte + step * CHUNK_SIZE_BYTES
                    if (isChunkClaimable(session, cStart)) {
                        session.inFlightChunks.add(cStart)
                        return@withLock cStart
                    }
                }
            }

            // 2. Sequential sliding window starting directly at the frontier
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

    private fun readCachedRange(cacheKey: String, startOffset: Long, length: Int): ByteArray? {
        if (length <= 0) return null
        val spans = runCatching { simpleCache.getCachedSpans(cacheKey) }.getOrNull() ?: return null
        val out = ByteArray(length)
        val endOffset = startOffset + length.toLong()
        var covered = 0
        for (span in spans) {
            if (!span.isCached) continue
            val file = span.file ?: continue
            val spanStart = span.position
            val spanEnd = span.position + span.length
            val overlapStart = maxOf(startOffset, spanStart)
            val overlapEnd = minOf(endOffset, spanEnd)
            if (overlapEnd <= overlapStart) continue
            val filePos = overlapStart - spanStart
            val destPos = (overlapStart - startOffset).toInt()
            val count = (overlapEnd - overlapStart).toInt()
            val ok = runCatching {
                RandomAccessFile(file, "r").use { raf ->
                    raf.seek(filePos)
                    raf.readFully(out, destPos, count)
                }
                true
            }.getOrDefault(false)
            if (!ok) return null
            covered += count
        }
        return if (covered == length) out else null
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
        private const val SLIDING_AHEAD_WINDOW_BYTES = 48L * 1024L * 1024L
        private const val LOW_RAM_SLIDING_AHEAD_WINDOW_BYTES = 24L * 1024L * 1024L
        private const val ACTIVE_PIN_AHEAD_BYTES = 16L * 1024L * 1024L
        private const val TRAILING_DEMOTE_BACK_BUFFER_MS = 180_000L
        private const val TRAILING_DEMOTE_STEP_BYTES = 4L * 1024L * 1024L
        private const val FALLBACK_TAIL_MOOV_CHUNKS = 3
        private const val MAX_PRELOAD_WORKERS = 4
        private const val MULTI_MIRROR_WORKERS = 4
        private const val SINGLE_HOST_WORKERS = 3
        private const val MAX_CHUNK_RETRIES = 3
        private const val MAX_POOL_BUFFERS = 6
        private const val SEEK_JUMP_THRESHOLD_MS = 4_000L

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

        fun resolveByteOffset(
            videoIndex: EpisodeVideoIndex?,
            positionMs: Long,
            durationMs: Long,
            totalLength: Long
        ): Long {
            if (positionMs <= 0L || totalLength <= 0L) return 0L
            videoIndex?.resolveByteOffset(positionMs)?.let { exact ->
                return exact.coerceIn(0L, (totalLength - 1L).coerceAtLeast(0L))
            }
            return estimateByteOffset(positionMs, durationMs, totalLength)
        }

        fun containsMp4MoovAtom(bytes: ByteArray, length: Int = bytes.size): Boolean =
            Mp4MoovSeekParser.containsMp4MoovAtom(bytes, length)

        fun buildInitialWindowChunks(
            totalLength: Long,
            targetBytes: Long,
            resumePositionMs: Long,
            estimatedDurationMs: Long,
            opEndMs: Long,
            videoIndex: EpisodeVideoIndex? = null
        ): List<Long> {
            if (totalLength <= 0L || targetBytes <= 0L) return emptyList()
            val ordered = LinkedHashSet<Long>()
            ordered.add(0L)

            val chunkCount = ((targetBytes + CHUNK_SIZE_BYTES - 1L) / CHUNK_SIZE_BYTES)
                .toInt()
                .coerceAtLeast(1)

            val effectiveDurMs = when {
                videoIndex != null && videoIndex.durationMs > 0L -> videoIndex.durationMs
                else -> estimatedDurationMs
            }

            val exactResumeByte = if (resumePositionMs > 10_000L) {
                videoIndex?.resolveByteOffset(resumePositionMs)
            } else {
                null
            }

            val startByte: Long
            val totalWindowChunks: Int
            if (exactResumeByte != null) {
                val aligned = alignChunkStart(exactResumeByte)
                startByte = if (exactResumeByte - aligned < 64L * 1024L && aligned >= CHUNK_SIZE_BYTES) {
                    aligned - CHUNK_SIZE_BYTES
                } else {
                    aligned
                }
                totalWindowChunks = chunkCount
            } else if (resumePositionMs > 10_000L && effectiveDurMs > 0L) {
                // Widen CBR fallback lead by 2 chunks (1 MB) to absorb anime VBR variance.
                val est = estimateByteOffset(resumePositionMs, effectiveDurMs, totalLength)
                startByte = alignChunkStart((est - 2L * CHUNK_SIZE_BYTES).coerceAtLeast(0L))
                totalWindowChunks = chunkCount + 2
            } else {
                startByte = 0L
                totalWindowChunks = chunkCount
            }

            for (i in 0 until totalWindowChunks) {
                val c = startByte + i * CHUNK_SIZE_BYTES
                if (c < totalLength) ordered.add(c)
            }

            if (opEndMs > 5_000L && effectiveDurMs > 0L) {
                val opByte = resolveByteOffset(videoIndex, opEndMs, effectiveDurMs, totalLength)
                val opStart = alignChunkStart(opByte)
                for (step in 0 until 3) {
                    val c = opStart + step * CHUNK_SIZE_BYTES
                    if (c < totalLength) ordered.add(c)
                }
            }

            return ordered.toList()
        }
    }
}
