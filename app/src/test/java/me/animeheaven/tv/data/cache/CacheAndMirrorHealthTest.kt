package me.animeheaven.tv.data.cache

import java.net.InetAddress
import java.net.UnknownHostException
import me.animeheaven.tv.data.network.CachedDns
import me.animeheaven.tv.data.network.SkipSegmentType
import me.animeheaven.tv.data.network.SkipTimesParser
import me.animeheaven.tv.data.repository.CachedEntry
import me.animeheaven.tv.ui.screens.player.MirrorQueueState
import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CacheAndMirrorHealthTest {

    @Test
    fun `rankMirrors preserves order when no host stats exist`() {
        val tracker = MirrorHealthTracker(clockMs = { 1_000L })
        val mirrors = listOf(
            "https://s1.example.com/v1.mp4",
            "https://s2.example.com/v1.mp4",
            "https://s3.example.com/v1.mp4"
        )
        assertEquals(mirrors, tracker.rankMirrors(mirrors))
    }

    @Test
    fun `rankMirrors demotes failed host during cooldown and restores after expiry`() {
        var now = 10_000L
        val tracker = MirrorHealthTracker(clockMs = { now })
        val dead = "https://ct.animeheaven.me/video.mp4?t=1"
        val healthy = "https://v1.animeheaven.me/video.mp4?t=1"
        val untried = "https://v2.animeheaven.me/video.mp4?t=1"

        tracker.recordFailure(dead)
        tracker.recordSuccess(healthy, latencyMs = 120L)

        val rankedDuringCooldown = tracker.rankMirrors(listOf(dead, untried, healthy))
        assertEquals(listOf(healthy, untried, dead), rankedDuringCooldown)

        now += MirrorHealthTracker.FAILURE_COOLDOWN_MS + 1L
        val rankedAfterCooldown = tracker.rankMirrors(listOf(dead, untried, healthy))
        assertEquals(listOf(healthy, dead, untried), rankedAfterCooldown)
    }

    @Test
    fun `rankMirrors sorts healthy hosts by lowest latency and resets failure on success`() {
        var now = 1_000L
        val tracker = MirrorHealthTracker(clockMs = { now })
        val slow = "https://slow.cdn/video.mp4"
        val fast = "https://fast.cdn/video.mp4"

        tracker.recordFailure(fast)
        tracker.recordSuccess(slow, latencyMs = 450L)
        assertEquals(listOf(slow, fast), tracker.rankMirrors(listOf(fast, slow)))

        now += 500L
        tracker.recordSuccess(fast, latencyMs = 55L)
        assertEquals(listOf(fast, slow), tracker.rankMirrors(listOf(slow, fast)))
    }

    @Test
    fun `cachedEntry checks soft TTL boundary accurately`() {
        val entry = CachedEntry(value = "html", fetchedAt = 100_000L)
        assertFalse(entry.isStale(softTtlMs = 60_000L, nowMs = 159_999L))
        assertTrue(entry.isStale(softTtlMs = 60_000L, nowMs = 160_000L))
    }

    @Test
    fun `skipTimesParser extracts idMal episode number and op ed intervals`() {
        assertEquals(12, SkipTimesParser.parseEpisodeNumber("Episode 12"))
        assertEquals(
            21,
            SkipTimesParser.parseMalIdFromAniList("""{"data":{"Media":{"idMal":21,"episodes":1000}}}""")
        )

        val aniSkipJson = """
            {
              "found": true,
              "results": [
                {
                  "interval": {"startTime": 85.5, "endTime": 175.0},
                  "skipType": "op"
                },
                {
                  "interval": {"startTime": 1310.0, "endTime": 1400.0},
                  "skipType": "ed"
                }
              ]
            }
        """.trimIndent()

        val parsed = SkipTimesParser.parseAniSkipResponse(aniSkipJson)
        assertNotNull(parsed.op)
        assertEquals(SkipSegmentType.OP, parsed.op?.type)
        assertEquals(85_500L, parsed.op?.startMs)
        assertEquals(175_000L, parsed.op?.endMs)

        assertNotNull(parsed.ed)
        assertEquals(SkipSegmentType.ED, parsed.ed?.type)
        assertEquals(1_310_000L, parsed.ed?.startMs)
        assertEquals(1_400_000L, parsed.ed?.endMs)

        val empty = SkipTimesParser.parseAniSkipResponse("""{"found":false,"results":[]}""")
        assertNull(empty.op)
        assertNull(empty.ed)
    }

    @Test
    fun `mirrorQueueState exhausts after last source and cycles cleanly`() {
        val queue = MirrorQueueState(sources = listOf("u1", "u2"))
        assertEquals("u1", queue.currentUrl)
        val second = queue.advanceOnError()
        assertEquals("u2", second.currentUrl)
        assertFalse(second.exhausted)
        val done = second.advanceOnError()
        assertTrue(done.exhausted)
        val wrapped = second.cycleManual()
        assertEquals("u1", wrapped.currentUrl)
        assertFalse(wrapped.exhausted)
    }

    @Test
    fun `hasFreshStats tracks active success and cooldown expiration`() {
        var now = 5_000L
        val tracker = MirrorHealthTracker(clockMs = { now })
        val url = "https://cdn.animeheaven.me/ep1.mp4"

        assertFalse(tracker.hasFreshStats(url))
        tracker.recordFailure(url)
        assertTrue(tracker.hasFreshStats(url))

        now += MirrorHealthTracker.FAILURE_COOLDOWN_MS + 1L
        assertFalse(tracker.hasFreshStats(url))

        tracker.recordSuccess(url, latencyMs = 42L)
        assertTrue(tracker.hasFreshStats(url))
    }

    @Test
    fun `cachedDns caches lookups within TTL and falls back to stale entries offline`() {
        var now = 1_000L
        var calls = 0
        var shouldFail = false
        val fakeAddr = listOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        val delegate = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                calls++
                if (shouldFail) throw UnknownHostException("offline")
                return fakeAddr
            }
        }
        val cachedDns = CachedDns(ttlMs = 60_000L, clockMs = { now }, delegate = delegate)

        assertEquals(fakeAddr, cachedDns.lookup("animeheaven.me"))
        assertEquals(fakeAddr, cachedDns.lookup("animeheaven.me"))
        assertEquals(1, calls)

        now += 70_000L
        shouldFail = true
        assertEquals(fakeAddr, cachedDns.lookup("animeheaven.me"))
        assertEquals(2, calls)
    }

    @Test
    fun `rankMirrors demotes stub videos under 1MB below full-length mirrors`() {
        val tracker = MirrorHealthTracker(clockMs = { 1_000L })
        val stub = "https://ax.animeheaven.me/video.mp4?stub"
        val fullA = "https://ci.animeheaven.me/video.mp4?full"
        val fullB = "https://ct.animeheaven.me/video.mp4?full"

        tracker.recordProbeSuccess(stub, latencyMs = 15L, contentLength = 88_566L)
        tracker.recordProbeSuccess(fullA, latencyMs = 180L, contentLength = 169_854_846L)
        tracker.recordProbeSuccess(fullB, latencyMs = 95L, contentLength = 169_854_846L)

        assertEquals(listOf(fullB, fullA, stub), tracker.rankMirrors(listOf(stub, fullA, fullB)))
    }

    @Test
    fun `videoCacheManager parses Content-Range headers and scales disk capacity`() {
        assertEquals(
            169_854_846L,
            VideoCacheManager.parseTotalLengthFromHeaders("bytes 0-0/169854846", "1", 206)
        )
        assertEquals(
            10_485_760L,
            VideoCacheManager.parseTotalLengthFromHeaders(null, "10485760", 200)
        )
        assertEquals(
            -1L,
            VideoCacheManager.parseTotalLengthFromHeaders(null, "1024", 206)
        )
        assertEquals(
            512L * 1024L * 1024L,
            VideoCacheManager.calculateVideoCacheMaxBytes(2L * 1024L * 1024L * 1024L)
        )
        assertEquals(
            1024L * 1024L * 1024L,
            VideoCacheManager.calculateVideoCacheMaxBytes(6L * 1024L * 1024L * 1024L)
        )
    }

    @Test
    fun `streamingChunkBooster schedules header, resume offset, and post-OP windows`() {
        val totalLen = 120L * 1024L * 1024L // 120 MB
        val durationMs = 24L * 60_000L // 24 min = 1,440,000 ms
        val chunk = StreamingChunkBooster.CHUNK_SIZE_BYTES // 512 KB

        // Fresh start: [0, 512K, 1M, 1.5M] + post-OP ~85s offset chunks
        val freshChunks = StreamingChunkBooster.buildInitialWindowChunks(
            totalLength = totalLen,
            targetBytes = 2L * 1024L * 1024L,
            resumePositionMs = 0L,
            estimatedDurationMs = durationMs,
            opEndMs = 85_000L
        )
        assertEquals(0L, freshChunks.first())
        val expectedOpStart = StreamingChunkBooster.alignChunkStart(
            StreamingChunkBooster.estimateByteOffset(85_000L, durationMs, totalLen)
        )
        assertTrue(expectedOpStart in freshChunks)

        // Resume mid-episode at 12:00 (~50% = 60 MB): includes header 0L + ~59.5-61.5 MB resume window
        val resumeChunks = StreamingChunkBooster.buildInitialWindowChunks(
            totalLength = totalLen,
            targetBytes = 2L * 1024L * 1024L,
            resumePositionMs = 12L * 60_000L,
            estimatedDurationMs = durationMs,
            opEndMs = 85_000L
        )
        assertEquals(0L, resumeChunks.first())
        val expectedMid = StreamingChunkBooster.alignChunkStart(60L * 1024L * 1024L - chunk)
        assertTrue(expectedMid in resumeChunks)
    }

    @Test
    fun `rankMirrors prioritizes proven high throughput over low-RTT throttled hosts`() {
        val tracker = MirrorHealthTracker(clockMs = { 10_000L })
        val throttledLowRtt = "https://throttled.cdn/video.mp4"
        val unprovenMedium = "https://unproven.cdn/video.mp4"
        val fastThroughput = "https://fast.cdn/video.mp4"

        tracker.recordProbeSuccess(throttledLowRtt, latencyMs = 20L, contentLength = 150_000_000L)
        tracker.recordProbeSuccess(unprovenMedium, latencyMs = 120L, contentLength = 150_000_000L)
        tracker.recordProbeSuccess(fastThroughput, latencyMs = 140L, contentLength = 150_000_000L)

        // 512 KB in 5000ms = ~104 KB/s (throttled), vs 512 KB in 250ms = ~2.0 MB/s (fast)
        tracker.recordChunkThroughput(throttledLowRtt, bytes = 512L * 1024L, elapsedMs = 5_000L)
        tracker.recordChunkThroughput(fastThroughput, bytes = 512L * 1024L, elapsedMs = 250L)

        assertEquals(
            listOf(fastThroughput, unprovenMedium, throttledLowRtt),
            tracker.rankMirrors(listOf(throttledLowRtt, unprovenMedium, fastThroughput))
        )
        assertEquals(listOf("fast.cdn", "unproven.cdn"), tracker.topHealthyHosts(limit = 2))
    }

    @Test
    fun `buildEpisodeCacheKey shares key across verified mirrors and falls back for stubs`() {
        val mirror1 = "https://sr.animeheaven.me/video.mp4?token=a"
        val mirror2 = "https://ct.animeheaven.me/video.mp4?token=b"

        val key1 = VideoCacheManager.buildEpisodeCacheKey("ep42", 169_854_846L, mirror1)
        val key2 = VideoCacheManager.buildEpisodeCacheKey("ep42", 169_854_846L, mirror2)
        assertEquals("ep:ep42:169854846", key1)
        assertEquals(key1, key2)

        // Stub (< 1 MB) or unknown size falls back to mirror URL so corrupted/stub sizes aren't shared
        assertEquals(mirror1, VideoCacheManager.buildEpisodeCacheKey("ep42", 50_000L, mirror1))
        assertEquals(mirror1, VideoCacheManager.buildEpisodeCacheKey(null, 169_854_846L, mirror1))
    }

    @Test
    fun `containsMp4MoovAtom detects faststart vs non-faststart MP4 headers`() {
        val faststart = "0000ftypisom0000moovmvhd".toByteArray(Charsets.US_ASCII)
        val nonFaststart = "0000ftypisom0000mdatpayloadbytes".toByteArray(Charsets.US_ASCII)

        assertTrue(StreamingChunkBooster.containsMp4MoovAtom(faststart, faststart.size))
        assertFalse(StreamingChunkBooster.containsMp4MoovAtom(nonFaststart, nonFaststart.size))
    }

    @Test
    fun `lruCacheMap evicts least recently used entries and respects trimToSize`() {
        val lru = LruCacheMap<String, Int>(maxEntries = 3)
        lru["a"] = 1
        lru["b"] = 2
        lru["c"] = 3
        // Touch 'a' so 'b' becomes least-recently used
        assertEquals(1, lru["a"])
        lru["d"] = 4
        assertNull(lru["b"])
        assertEquals(3, lru.size())

        lru.trimToSize(1)
        assertEquals(1, lru.size())
        assertEquals(4, lru["d"])
    }
}
