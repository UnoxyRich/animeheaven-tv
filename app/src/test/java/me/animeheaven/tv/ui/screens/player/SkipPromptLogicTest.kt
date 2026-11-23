package me.animeheaven.tv.ui.screens.player

import me.animeheaven.tv.data.network.EpisodeSkipTimes
import me.animeheaven.tv.data.network.SkipSegment
import me.animeheaven.tv.data.network.SkipSegmentType
import me.animeheaven.tv.data.network.SkipTimesParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkipPromptLogicTest {

    @Test
    fun `countdown default is 7 seconds`() {
        assertEquals(7, SKIP_PROMPT_COUNTDOWN_SECONDS)
        val prompt = ActiveSkipPrompt(
            segment = SkipSegment(SkipSegmentType.OP, 0L, 90_000L, isExact = true)
        )
        assertEquals(7, prompt.remainingSeconds)
        assertEquals(7, prompt.totalSeconds)
    }

    @Test
    fun `SkipTimesParser parses AniList MAL id, AniSkip OP and ED, and episode numbers`() {
        val aniListJson = """
            {"data":{"Media":{"id":97940,"idMal":34572,"title":{"romaji":"Black Clover","english":"Black Clover"}}}}
        """.trimIndent()
        assertEquals(34572, SkipTimesParser.parseMalIdFromAniList(aniListJson))

        val aniSkipJson = """
            {
              "found": true,
              "results": [
                {"interval":{"startTime":180.683,"endTime":270.683},"skipType":"op","skipId":"id1","episodeLength":1434.059},
                {"interval":{"startTime":1276.963,"endTime":1430.963},"skipType":"ed","skipId":"id2","episodeLength":1435.946}
              ],
              "statusCode": 200
            }
        """.trimIndent()
        val parsed = SkipTimesParser.parseAniSkipResponse(aniSkipJson)
        assertNotNull(parsed.op)
        assertNotNull(parsed.ed)
        assertEquals(180_683L, parsed.op!!.startMs)
        assertEquals(270_683L, parsed.op!!.endMs)
        assertTrue(parsed.op!!.isExact)
        assertEquals(1_276_963L, parsed.ed!!.startMs)
        assertEquals(1_430_963L, parsed.ed!!.endMs)

        // Prefers standard op over mixed-op
        val mixedJson = """
            {
              "found": true,
              "results": [
                {"skipType":"mixed-op","interval":{"startTime":10.0,"endTime":95.0}},
                {"skipType":"op","interval":{"startTime":20.0,"endTime":110.0}}
              ]
            }
        """.trimIndent()
        val mixedParsed = SkipTimesParser.parseAniSkipResponse(mixedJson)
        assertEquals(20_000L, mixedParsed.op?.startMs)
        assertEquals(110_000L, mixedParsed.op?.endMs)

        val notFound = SkipTimesParser.parseAniSkipResponse("""{"found":false,"results":[]}""")
        assertNull(notFound.op)
        assertNull(notFound.ed)

        assertEquals(7, SkipTimesParser.parseEpisodeNumber("Episode 7"))
        assertEquals(12, SkipTimesParser.parseEpisodeNumber("Episode 12raw"))
        assertEquals(108, SkipTimesParser.parseEpisodeNumber("108"))
    }

    @Test
    fun `exact OP and ED trigger windows and handledTypes suppression work accurately`() {
        val exact = EpisodeSkipTimes(
            op = SkipSegment(SkipSegmentType.OP, 90_000L, 180_000L, isExact = true),
            ed = SkipSegment(SkipSegmentType.ED, 1_300_000L, 1_390_000L, isExact = true)
        )
        val durationMs = 1_440_000L
        val segments = SkipPromptLogic.resolveCandidateSegments(
            exactSkipTimes = exact,
            durationMs = durationMs,
            skipOpFallbackSeconds = 85,
            allowFallbackOp = true
        )

        assertNull(SkipPromptLogic.findTriggerableSegment(50_000L, durationMs, segments, emptySet()))

        val triggeredOp = SkipPromptLogic.findTriggerableSegment(95_000L, durationMs, segments, emptySet())
        assertEquals(SkipSegmentType.OP, triggeredOp?.type)

        // Does not re-trigger once handled
        assertNull(
            SkipPromptLogic.findTriggerableSegment(
                96_000L,
                durationMs,
                segments,
                setOf(SkipSegmentType.OP)
            )
        )

        val triggeredEd = SkipPromptLogic.findTriggerableSegment(
            1_310_000L,
            durationMs,
            segments,
            setOf(SkipSegmentType.OP)
        )
        assertEquals(SkipSegmentType.ED, triggeredEd?.type)
    }

    @Test
    fun `fallback OP and ED apply only to long episodes and respect mid-episode resume`() {
        val duration24Min = 24 * 60_000L
        val segments = SkipPromptLogic.resolveCandidateSegments(
            exactSkipTimes = EpisodeSkipTimes(),
            durationMs = duration24Min,
            skipOpFallbackSeconds = 85,
            allowFallbackOp = true
        )
        assertEquals(2, segments.size)
        assertEquals(SkipSegmentType.OP, SkipPromptLogic.findTriggerableSegment(5_000L, duration24Min, segments, emptySet())?.type)
        assertEquals(SkipSegmentType.ED, SkipPromptLogic.findTriggerableSegment(duration24Min - 80_000L, duration24Min, segments, emptySet())?.type)

        // Resumed mid-episode: no fallback OP
        val resumedSegments = SkipPromptLogic.resolveCandidateSegments(
            exactSkipTimes = EpisodeSkipTimes(),
            durationMs = duration24Min,
            skipOpFallbackSeconds = 85,
            allowFallbackOp = false
        )
        assertEquals(1, resumedSegments.size)
        assertEquals(SkipSegmentType.ED, resumedSegments[0].type)

        // Short 3-minute episode: no fallback OP or ED
        val shortSegments = SkipPromptLogic.resolveCandidateSegments(
            exactSkipTimes = EpisodeSkipTimes(),
            durationMs = 3 * 60_000L,
            skipOpFallbackSeconds = 85,
            allowFallbackOp = true
        )
        assertTrue(shortSegments.isEmpty())
    }

    @Test
    fun `computeSkipAction chooses exact seek for OP, post-credits ED seek, or next episode for tail ED`() {
        val durationMs = 1_440_000L

        val exactOp = SkipSegment(SkipSegmentType.OP, 90_000L, 180_000L, isExact = true)
        val opAction = SkipPromptLogic.computeSkipAction(exactOp, 95_000L, durationMs, 85, hasNextEpisode = true)
        assertEquals(SkipActionKind.SEEK_TO, opAction.kind)
        assertEquals(180_000L, opAction.targetPositionMs)
        assertEquals("Skipped OP", opAction.feedbackLabel)

        val fallbackOp = SkipSegment(SkipSegmentType.OP, 3_000L, 88_000L, isExact = false)
        val fallbackAction = SkipPromptLogic.computeSkipAction(fallbackOp, 5_000L, durationMs, 85, hasNextEpisode = true)
        assertEquals(SkipActionKind.SEEK_TO, fallbackAction.kind)
        assertEquals(90_000L, fallbackAction.targetPositionMs)

        // ED with post-credits scene (ends 60s before end) -> seeks to 1_380_000L instead of skipping episode
        val postCreditsEd = SkipSegment(SkipSegmentType.ED, 1_290_000L, 1_380_000L, isExact = true)
        val edSeekAction = SkipPromptLogic.computeSkipAction(postCreditsEd, 1_295_000L, durationMs, 85, hasNextEpisode = true)
        assertEquals(SkipActionKind.SEEK_TO, edSeekAction.kind)
        assertEquals(1_380_000L, edSeekAction.targetPositionMs)

        // ED at end of episode (within 12s tail) with next episode -> jumps to Next Episode
        val tailEd = SkipSegment(SkipSegmentType.ED, 1_340_000L, 1_435_000L, isExact = true)
        val nextEpAction = SkipPromptLogic.computeSkipAction(tailEd, 1_345_000L, durationMs, 85, hasNextEpisode = true)
        assertEquals(SkipActionKind.NEXT_EPISODE, nextEpAction.kind)
        assertEquals("Skipped ED → Next Ep", nextEpAction.feedbackLabel)

        // Manual HUD Skip OP falls back to +85s if past exact OP
        val manualPastOp = SkipPromptLogic.computeManualHudSkipOp(exactOp, 400_000L, durationMs, 85)
        assertFalse(manualPastOp.feedbackLabel == "Skipped OP")
        assertEquals(485_000L, manualPastOp.targetPositionMs)
    }
}
