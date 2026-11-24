package me.animeheaven.tv.data

import me.animeheaven.tv.data.cache.CacheSnapshotCodec
import me.animeheaven.tv.data.cache.StreamingChunkBooster
import me.animeheaven.tv.data.model.PreloadMode
import me.animeheaven.tv.data.network.SkipTimesClient
import me.animeheaven.tv.data.network.resolvePreloadMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemediationRegressionTest {
    @Test
    fun `invalid range responses are never cached`() {
        fun accept(code: Int, start: Long, end: Long, range: String?, type: String = "video/mp4") =
            StreamingChunkBooster.isValidChunkResponse(code, range, type, "identity", start, end)

        assertTrue(accept(200, 0, 511, null))
        assertFalse(accept(200, 512, 1023, null))
        assertFalse(accept(200, 0, 511, null, "text/html"))
        assertFalse(accept(206, 512, 1023, "bytes 0-511/2048"))
        assertFalse(accept(206, 512, 1023, "bytes 512-1022/2048"))
        assertTrue(accept(206, 512, 1023, "bytes 512-1023/2048"))
    }

    @Test
    fun `transient skip lookup results do not become durable misses`() {
        val miss = """{"data":{"Media":{"idMal":null}}}"""
        assertEquals(-1, SkipTimesClient.cacheableMalId(200, miss))
        assertEquals(-1, SkipTimesClient.cacheableMalId(404, ""))
        assertNull(SkipTimesClient.cacheableMalId(429, miss))
        assertNull(SkipTimesClient.cacheableMalId(503, miss))
        assertNull(SkipTimesClient.cacheableMalId(200, """{"data":null,"errors":[{}]}"""))
    }

    @Test
    fun `metered network overrides both speculative preload modes`() {
        for (mode in PreloadMode.entries) {
            assertEquals(PreloadMode.DATA_SAVER, resolvePreloadMode(mode, metered = true))
            assertEquals(mode, resolvePreloadMode(mode, metered = false))
        }
    }

    @Test
    fun `corrupt gzip and deeply nested cache snapshots are rejected`() {
        for (input in listOf("GZ:", "GZ:A", "GZ:AA=A", "GZ:☃AAA", "GZ:AAAA")) {
            assertNull(CacheSnapshotCodec.decompressText(input))
        }
        val nested = "[".repeat(100) + "0" + "]".repeat(100)
        assertNull(CacheSnapshotCodec.decodeHomeFeed("""{"unknown":$nested}"""))
        assertNull(CacheSnapshotCodec.decodeHomeFeed("""{"unknown":0.}"""))
        assertNull(CacheSnapshotCodec.decodeHomeFeed("""{"seasonPath":"\u+123"}"""))
    }
}
