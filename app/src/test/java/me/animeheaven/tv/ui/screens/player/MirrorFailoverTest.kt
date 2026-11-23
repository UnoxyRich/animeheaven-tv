package me.animeheaven.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorFailoverTest {

    @Test
    fun `mirror queue advances automatically on error until exhausted`() {
        val mirrors = listOf(
            "https://ct.animeheaven.me/video.mp4?ep1",
            "https://f1.animeheaven.me/video.mp4?ep1",
            "https://ck.animeheaven.me/video.mp4?ep1"
        )
        var queue = MirrorQueueState(sources = mirrors)

        assertEquals(1, queue.displayIndex)
        assertEquals(mirrors[0], queue.currentUrl)
        assertFalse(queue.exhausted)

        queue = queue.advanceOnError()
        assertEquals(2, queue.displayIndex)
        assertEquals(mirrors[1], queue.currentUrl)
        assertFalse(queue.exhausted)

        queue = queue.advanceOnError()
        assertEquals(3, queue.displayIndex)
        assertEquals(mirrors[2], queue.currentUrl)
        assertFalse(queue.exhausted)

        queue = queue.advanceOnError()
        assertTrue(queue.exhausted)
    }

    @Test
    fun `manual cycle wraps back to first mirror`() {
        val mirrors = listOf("https://m1/v.mp4", "https://m2/v.mp4")
        var queue = MirrorQueueState(sources = mirrors)

        queue = queue.cycleManual()
        assertEquals("https://m2/v.mp4", queue.currentUrl)

        queue = queue.cycleManual()
        assertEquals("https://m1/v.mp4", queue.currentUrl)
    }

    @Test
    fun `empty mirror list handles cycle safely`() {
        val queue = MirrorQueueState(sources = emptyList()).cycleManual()
        assertNull(queue.currentUrl)
        assertEquals(0, queue.displayIndex)
    }
}
