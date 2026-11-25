package me.animeheaven.tv.data.cache

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheSpan
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.lang.reflect.Proxy
import me.animeheaven.tv.data.model.PreloadMode
import me.animeheaven.tv.data.network.resolvePreloadMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(UnstableApi::class)
class ZeroBufferCacheTest {

    @Test
    fun `mp4MoovSeekParser locates and parses faststart VBR keyframe table`() {
        val sampleCount = 1440 // 24 minutes at 1 sample/sec
        // Deliberately VBR: first 720s use small 20 KB samples, second 720s use large 140 KB samples
        val sampleSizes = IntArray(sampleCount) { i -> if (i < 720) 20_000 else 140_000 }
        val syncSamples = (1..sampleCount step 5).toList().toIntArray() // keyframe every 5s

        val ftyp = buildBox("ftyp", "isom\u0000\u0000\u0002\u0000isommp41".toByteArray(Charsets.US_ASCII))
        val mdatOffset = ftyp.size.toLong() + 30_000L // approximate placeholder for first pass
        val placeholderMoov = buildSyntheticMoov(
            timescale = 1000L,
            durationUnits = 1_440_000L,
            sampleSizes = sampleSizes,
            syncSamples = syncSamples,
            firstChunkOffset = mdatOffset
        )
        val exactMdatStart = (ftyp.size + placeholderMoov.size + 8).toLong()
        val moov = buildSyntheticMoov(
            timescale = 1000L,
            durationUnits = 1_440_000L,
            sampleSizes = sampleSizes,
            syncSamples = syncSamples,
            firstChunkOffset = exactMdatStart
        )
        val mdatPayloadSize = sampleSizes.fold(0L) { acc, sz -> acc + sz }
        val mdatHeader = buildBoxHeader("mdat", 8L + mdatPayloadSize)
        val totalLength = ftyp.size.toLong() + moov.size.toLong() + 8L + mdatPayloadSize
        val headBytes = concat(ftyp, moov, mdatHeader)

        val loc = Mp4MoovSeekParser.inspectTopLevelBoxes(headBytes, headBytes.size, totalLength)
        assertNotNull(loc)
        assertEquals(ftyp.size.toLong(), loc!!.offset)
        assertEquals(moov.size.toLong(), loc.size)
        assertFalse(loc.isAtTail)

        val parsed = Mp4MoovSeekParser.parseMoovIndex(
            episodeKey = "ep_vbr_1",
            contentLength = totalLength,
            moovFileOffset = loc.offset,
            buffer = headBytes,
            bufferLength = headBytes.size,
            bufferBaseFileOffset = 0L,
            sampleIntervalMs = 5_000L,
            nowMs = 123_456L
        )
        assertNotNull(parsed)
        assertEquals(1_440_000L, parsed!!.durationMs)
        assertFalse(parsed.isMoovAtTail)
        assertTrue(parsed.seekPoints.size >= 200)

        // At t = 600_000 ms (sample #601): exact VBR byte offset = exactMdatStart + 600 * 20_000 (~12 MB),
        // whereas CBR linear estimate would have guessed ~50% (~57 MB)!
        val expectedAt10Min = exactMdatStart + 600L * 20_000L
        assertEquals(expectedAt10Min, parsed.resolveByteOffset(600_000L))

        // At t = 85_000 ms (post-OP): exact keyframe at 85s
        val expectedAtOpEnd = exactMdatStart + 85L * 20_000L
        assertEquals(expectedAtOpEnd, parsed.resolveByteOffset(85_000L))
    }

    @Test
    fun `mp4MoovSeekParser detects and parses non-faststart tail moov`() {
        val sampleSizes = IntArray(600) { 100_000 } // 10 min @ 100 KB/s = 60 MB
        val syncSamples = (1..600 step 5).toList().toIntArray()
        val ftyp = buildBox("ftyp", "isom\u0000\u0000\u0002\u0000isommp41".toByteArray(Charsets.US_ASCII))
        val mdatPayloadSize = sampleSizes.fold(0L) { acc, sz -> acc + sz }
        val mdatStart = ftyp.size.toLong() + 8L
        val moov = buildSyntheticMoov(
            timescale = 1000L,
            durationUnits = 600_000L,
            sampleSizes = sampleSizes,
            syncSamples = syncSamples,
            firstChunkOffset = mdatStart
        )
        val mdatHeader = buildBoxHeader("mdat", 8L + mdatPayloadSize)
        val moovOffset = ftyp.size.toLong() + 8L + mdatPayloadSize
        val totalLength = moovOffset + moov.size.toLong()
        val headChunk = concat(ftyp, mdatHeader, ByteArray(1024))

        val loc = Mp4MoovSeekParser.inspectTopLevelBoxes(headChunk, headChunk.size, totalLength)
        assertNotNull(loc)
        assertEquals(moovOffset, loc!!.offset)
        assertEquals(moov.size.toLong(), loc.size)
        assertTrue(loc.isAtTail)

        val parsed = Mp4MoovSeekParser.parseMoovIndex(
            episodeKey = "ep_tail_1",
            contentLength = totalLength,
            moovFileOffset = loc.offset,
            buffer = moov,
            bufferLength = moov.size,
            bufferBaseFileOffset = loc.offset
        )
        assertNotNull(parsed)
        assertTrue(parsed!!.isMoovAtTail)
        assertEquals(600_000L, parsed.durationMs)
        assertEquals(mdatStart + 300L * 100_000L, parsed.resolveByteOffset(300_000L))
    }

    @Test
    fun `cacheSnapshotCodec round-trips EpisodeVideoIndex accurately`() {
        val original = EpisodeVideoIndex(
            episodeKey = "ep42",
            contentLength = 154_321_000L,
            durationMs = 1_420_000L,
            moovOffset = 154_000_000L,
            moovSize = 321_000L,
            seekPoints = listOf(
                Mp4SeekPoint(0L, 40L),
                Mp4SeekPoint(85_000L, 8_200_000L),
                Mp4SeekPoint(600_000L, 61_500_000L)
            ),
            updatedAtMs = 1_700_000_000_000L
        )
        val json = CacheSnapshotCodec.encodeEpisodeVideoIndex(original)
        val decoded = CacheSnapshotCodec.decodeEpisodeVideoIndex(json)
        assertEquals(original, decoded)
        assertNull(CacheSnapshotCodec.decodeEpisodeVideoIndex("{broken"))
    }

    @Test
    fun `buildInitialWindowChunks uses exact VBR keyframe offset from EpisodeVideoIndex`() {
        val totalLen = 120L * 1024L * 1024L
        val exactResumeByte = 20L * 1024L * 1024L // 20 MB instead of 60 MB CBR midpoint
        val exactOpByte = 6L * 1024L * 1024L
        val vbrIndex = EpisodeVideoIndex(
            episodeKey = "ep_vbr",
            contentLength = totalLen,
            durationMs = 1_200_000L,
            seekPoints = listOf(
                Mp4SeekPoint(0L, 64L),
                Mp4SeekPoint(85_000L, exactOpByte),
                Mp4SeekPoint(600_000L, exactResumeByte)
            )
        )
        val chunks = StreamingChunkBooster.buildInitialWindowChunks(
            totalLength = totalLen,
            targetBytes = 2L * 1024L * 1024L,
            resumePositionMs = 600_000L,
            estimatedDurationMs = 1_200_000L,
            opEndMs = 85_000L,
            videoIndex = vbrIndex
        )
        assertEquals(0L, chunks.first())
        assertTrue(StreamingChunkBooster.alignChunkStart(exactResumeByte) in chunks)
        assertTrue(StreamingChunkBooster.alignChunkStart(exactOpByte) in chunks)
        assertFalse(StreamingChunkBooster.alignChunkStart(60L * 1024L * 1024L) in chunks)
    }

    @Test
    fun `pinnedPriorityCacheEvictor evicts Tier B watched spans before older Tier A hot windows`() {
        val mb = 1024L * 1024L
        val evictor = PinnedPriorityCacheEvictor(maxBytes = 10L * mb, protectedRatio = 0.40)
        val removed = mutableListOf<CacheSpan>()
        lateinit var fakeCache: Cache
        fakeCache = Proxy.newProxyInstance(
            Cache::class.java.classLoader,
            arrayOf(Cache::class.java)
        ) { _, method, args ->
            if (method.name == "removeSpan" && args != null && args.isNotEmpty()) {
                val span = args[0] as CacheSpan
                removed.add(span)
                evictor.onSpanRemoved(fakeCache, span)
            }
            null
        } as Cache

        // Pin 4 MB hot window on ep:hot:100 with OLDER timestamps (t = 1..4)
        evictor.markHotWindow("ep:hot:100", 0L, 4L * mb)
        for (i in 0 until 4) {
            evictor.onSpanAdded(fakeCache, CacheSpan("ep:hot:100", i * mb, mb, (i + 1).toLong(), null))
        }
        assertEquals(4L * mb, evictor.protectedTrackedBytes)

        // Add 8 MB of NEWER unpinned Tier B watched spans (t = 10..17) -> total would be 12 MB > 10 MB cap
        for (i in 0 until 8) {
            evictor.onSpanAdded(fakeCache, CacheSpan("ep:watched:100", i * mb, mb, (10 + i).toLong(), null))
        }
        // Only Tier B watched spans should have been evicted; all 4 MB of Tier A hot window survive!
        assertTrue(removed.isNotEmpty())
        assertTrue(removed.all { it.key == "ep:watched:100" })
        assertEquals(4L * mb, evictor.protectedTrackedBytes)
        assertTrue(evictor.totalTrackedBytes <= 10L * mb)

        // Demote trailing [0, 2 MB) of ep:hot:100 -> Tier B, then write 2 MB more -> only demoted trailing chunks evict first
        removed.clear()
        evictor.demoteTrailingChunks("ep:hot:100", behindByteOffset = 2L * mb)
        assertEquals(2L * mb, evictor.protectedTrackedBytes)
        for (i in 8 until 10) {
            evictor.onSpanAdded(fakeCache, CacheSpan("ep:watched:100", i * mb, mb, (20 + i).toLong(), null))
        }
        assertTrue(removed.any { it.key == "ep:hot:100" && it.position < 2L * mb })
        assertTrue(removed.none { it.key == "ep:hot:100" && it.position >= 2L * mb })
        assertEquals(2L * mb, evictor.protectedTrackedBytes)
    }

    @Test
    fun `videoCacheManager scales tiered disk budget up to 2 GB and respects quarter cap`() {
        val gb = 1024L * 1024L * 1024L
        assertEquals(512L * 1024L * 1024L, VideoCacheManager.calculateVideoCacheMaxBytes(2L * gb))
        assertEquals(1L * gb, VideoCacheManager.calculateVideoCacheMaxBytes(6L * gb))
        assertEquals(2L * gb, VideoCacheManager.calculateVideoCacheMaxBytes(16L * gb))
        assertEquals(256L * 1024L * 1024L, VideoCacheManager.calculateVideoCacheMaxBytes(1L * gb))
    }

    @Test
    fun `networkPolicy enforces strict Wi-Fi-only policy on metered networks`() {
        assertEquals(PreloadMode.DATA_SAVER, resolvePreloadMode(PreloadMode.AGGRESSIVE, metered = true))
        assertEquals(PreloadMode.DATA_SAVER, resolvePreloadMode(PreloadMode.BALANCED, metered = true))
        assertEquals(PreloadMode.DATA_SAVER, resolvePreloadMode(PreloadMode.DATA_SAVER, metered = true))
        assertEquals(PreloadMode.AGGRESSIVE, resolvePreloadMode(PreloadMode.AGGRESSIVE, metered = false))
        assertEquals(PreloadMode.BALANCED, resolvePreloadMode(PreloadMode.BALANCED, metered = false))
    }

    // --- Synthetic ISO-BMFF builders ---

    private fun buildSyntheticMoov(
        timescale: Long,
        durationUnits: Long,
        sampleSizes: IntArray,
        syncSamples: IntArray,
        firstChunkOffset: Long
    ): ByteArray {
        val mvhd = buildMvhd(timescale, durationUnits)
        val audioTrak = buildTrak(
            handler = "soun",
            timescale = timescale,
            durationUnits = durationUnits,
            stbl = buildBox("stbl", ByteArray(0))
        )
        val stts = buildFullBox("stts") { out ->
            out.writeInt(1) // 1 run
            out.writeInt(sampleSizes.size)
            out.writeInt(1000) // 1000 units = 1s per sample
        }
        val stss = buildFullBox("stss") { out ->
            out.writeInt(syncSamples.size)
            for (s in syncSamples) out.writeInt(s)
        }
        val stsc = buildFullBox("stsc") { out ->
            out.writeInt(1)
            out.writeInt(1) // first_chunk = 1
            out.writeInt(1) // 1 sample per chunk
            out.writeInt(1) // sample_description_index
        }
        val stsz = buildFullBox("stsz") { out ->
            out.writeInt(0) // variable sizes
            out.writeInt(sampleSizes.size)
            for (sz in sampleSizes) out.writeInt(sz)
        }
        val stco = buildFullBox("stco") { out ->
            out.writeInt(sampleSizes.size)
            var offset = firstChunkOffset
            for (sz in sampleSizes) {
                out.writeInt(offset.toInt())
                offset += sz.toLong()
            }
        }
        val stbl = buildBox("stbl", concat(stts, stss, stsc, stsz, stco))
        val videoTrak = buildTrak("vide", timescale, durationUnits, stbl)
        return buildBox("moov", concat(mvhd, audioTrak, videoTrak))
    }

    private fun buildTrak(handler: String, timescale: Long, durationUnits: Long, stbl: ByteArray): ByteArray {
        val mdhd = buildFullBox("mdhd") { out ->
            out.writeInt(0) // creation_time
            out.writeInt(0) // modification_time
            out.writeInt(timescale.toInt())
            out.writeInt(durationUnits.toInt())
            out.writeInt(0) // language + pre_defined
        }
        val hdlr = buildFullBox("hdlr") { out ->
            out.writeInt(0) // pre_defined
            out.write(handler.toByteArray(Charsets.US_ASCII))
            out.write(ByteArray(12))
        }
        val minf = buildBox("minf", stbl)
        val mdia = buildBox("mdia", concat(mdhd, hdlr, minf))
        return buildBox("trak", mdia)
    }

    private fun buildMvhd(timescale: Long, durationUnits: Long): ByteArray =
        buildFullBox("mvhd") { out ->
            out.writeInt(0) // creation_time
            out.writeInt(0) // modification_time
            out.writeInt(timescale.toInt())
            out.writeInt(durationUnits.toInt())
            out.write(ByteArray(80))
        }

    private fun buildFullBox(type: String, writer: (DataOutputStream) -> Unit): ByteArray {
        val baos = ByteArrayOutputStream()
        DataOutputStream(baos).use { out ->
            out.writeInt(0) // version 0 + flags 0
            writer(out)
        }
        return buildBox(type, baos.toByteArray())
    }

    private fun buildBox(type: String, payload: ByteArray): ByteArray {
        val header = buildBoxHeader(type, 8L + payload.size.toLong())
        return concat(header, payload)
    }

    private fun buildBoxHeader(type: String, totalSize: Long): ByteArray {
        val baos = ByteArrayOutputStream()
        DataOutputStream(baos).use { out ->
            if (totalSize <= 0xFFFFFFFFL) {
                out.writeInt(totalSize.toInt())
                out.write(type.toByteArray(Charsets.US_ASCII))
            } else {
                out.writeInt(1)
                out.write(type.toByteArray(Charsets.US_ASCII))
                out.writeLong(totalSize)
            }
        }
        return baos.toByteArray()
    }

    private fun concat(vararg arrays: ByteArray): ByteArray {
        val total = arrays.sumOf { it.size }
        val out = ByteArray(total)
        var pos = 0
        for (a in arrays) {
            System.arraycopy(a, 0, out, pos, a.size)
            pos += a.size
        }
        return out
    }
}
