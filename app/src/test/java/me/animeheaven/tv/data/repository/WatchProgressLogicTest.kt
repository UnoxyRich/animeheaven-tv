package me.animeheaven.tv.data.repository

import me.animeheaven.tv.data.local.WatchProgressEntity
import me.animeheaven.tv.data.model.EpisodeItem
import me.animeheaven.tv.ui.screens.detail.DetailViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchProgressLogicTest {

    @Test
    fun `mid-episode progress builds resume continue item`() {
        val progress = WatchProgressEntity(
            episodeKey = "ep3",
            animeId = "0pqfl",
            animeTitle = "Black Clover 2nd Season",
            coverUrl = "https://animeheaven.me/image.php?82gob",
            episodeLabel = "3",
            positionMs = 300_000L,
            durationMs = 1_400_000L,
            isWatched = false,
            nextEpisodeKey = "ep4",
            nextEpisodeLabel = "4",
            updatedAt = 1000L
        )

        val cw = AnimeHeavenRepository.buildContinueWatchingItem(progress)
        assertNotNull(cw)
        assertFalse(cw!!.isNextEpisode)
        assertEquals("ep3", cw.targetEpisodeKey)
        assertEquals(300_000L, cw.resumePositionMs)
    }

    @Test
    fun `watched episode promotes continue watching directly to next episode`() {
        val progress = WatchProgressEntity(
            episodeKey = "ep3",
            animeId = "0pqfl",
            animeTitle = "Black Clover 2nd Season",
            coverUrl = "https://animeheaven.me/image.php?82gob",
            episodeLabel = "3",
            positionMs = 1_250_000L,
            durationMs = 1_400_000L,
            isWatched = true,
            nextEpisodeKey = "ep4",
            nextEpisodeLabel = "4",
            updatedAt = 2000L
        )

        val cw = AnimeHeavenRepository.buildContinueWatchingItem(progress)
        assertNotNull(cw)
        assertTrue(cw!!.isNextEpisode)
        assertEquals("ep4", cw.targetEpisodeKey)
        assertEquals("4", cw.targetEpisodeLabel)
        assertEquals(0L, cw.resumePositionMs)
    }

    @Test
    fun `watched final episode with no next episode returns null`() {
        val progress = WatchProgressEntity(
            episodeKey = "ep7",
            animeId = "0pqfl",
            animeTitle = "Black Clover 2nd Season",
            coverUrl = "",
            episodeLabel = "7",
            positionMs = 1_390_000L,
            durationMs = 1_400_000L,
            isWatched = true,
            nextEpisodeKey = null,
            nextEpisodeLabel = null,
            updatedAt = 3000L
        )

        assertNull(AnimeHeavenRepository.buildContinueWatchingItem(progress))
    }

    @Test
    fun `resume position threshold resets when too short or near completion`() {
        assertEquals(0L, AnimeHeavenRepository.resolveResumePositionMs(5_000L, 1_400_000L))
        assertEquals(250_000L, AnimeHeavenRepository.resolveResumePositionMs(250_000L, 1_400_000L))
        assertEquals(0L, AnimeHeavenRepository.resolveResumePositionMs(1_320_000L, 1_400_000L))
    }

    @Test
    fun `detail primary action picks Ep 1 initially and Play Next after finishing an episode`() {
        val episodes = listOf(
            EpisodeItem("k3", "3", "1d ago"),
            EpisodeItem("k2", "2", "8d ago"),
            EpisodeItem("k1", "1", "15d ago")
        )

        val freshAction = DetailViewModel.computePrimaryAction(episodes, emptyMap())
        assertEquals("k1", freshAction?.episodeKey)
        assertEquals("Watch Episode 1", freshAction?.buttonLabel)

        val watchedEp1 = mapOf(
            "k1" to WatchProgressEntity(
                episodeKey = "k1",
                animeId = "a1",
                animeTitle = "Show",
                coverUrl = "",
                episodeLabel = "1",
                positionMs = 1300_000L,
                durationMs = 1400_000L,
                isWatched = true,
                nextEpisodeKey = "k2",
                nextEpisodeLabel = "2",
                updatedAt = 5000L
            )
        )
        val nextAction = DetailViewModel.computePrimaryAction(episodes, watchedEp1)
        assertEquals("k2", nextAction?.episodeKey)
        assertEquals("Play Next: Ep 2", nextAction?.buttonLabel)
    }
}
