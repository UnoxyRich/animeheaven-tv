package me.animeheaven.tv.ui.screens.player

import androidx.compose.runtime.Immutable
import me.animeheaven.tv.data.network.EpisodeSkipTimes
import me.animeheaven.tv.data.network.SkipSegment
import me.animeheaven.tv.data.network.SkipSegmentType

const val SKIP_PROMPT_COUNTDOWN_SECONDS = 7
const val MIN_FALLBACK_EPISODE_DURATION_MS = 10 * 60_000L
const val FALLBACK_OP_START_MS = 3_000L
const val FALLBACK_OP_TRIGGER_END_MS = 15_000L
const val FALLBACK_ED_REMAINING_START_MS = 95_000L
const val FALLBACK_ED_REMAINING_END_MS = 15_000L
const val ED_TO_NEXT_EP_TAIL_MS = 12_000L

@Immutable
data class ActiveSkipPrompt(
    val segment: SkipSegment,
    val remainingSeconds: Int = SKIP_PROMPT_COUNTDOWN_SECONDS,
    val totalSeconds: Int = SKIP_PROMPT_COUNTDOWN_SECONDS
)

enum class SkipActionKind {
    SEEK_TO,
    NEXT_EPISODE
}

@Immutable
data class SkipAction(
    val kind: SkipActionKind,
    val targetPositionMs: Long,
    val feedbackLabel: String
)

object SkipPromptLogic {

    fun resolveCandidateSegments(
        exactSkipTimes: EpisodeSkipTimes,
        durationMs: Long,
        skipOpFallbackSeconds: Int,
        allowFallbackOp: Boolean
    ): List<SkipSegment> {
        val segments = mutableListOf<SkipSegment>()

        val exactOp = exactSkipTimes.op
        if (exactOp != null) {
            segments.add(exactOp)
        } else if (allowFallbackOp && durationMs >= MIN_FALLBACK_EPISODE_DURATION_MS) {
            segments.add(
                SkipSegment(
                    type = SkipSegmentType.OP,
                    startMs = FALLBACK_OP_START_MS,
                    endMs = FALLBACK_OP_START_MS + skipOpFallbackSeconds * 1000L,
                    isExact = false
                )
            )
        }

        val exactEd = exactSkipTimes.ed
        if (exactEd != null) {
            segments.add(exactEd)
        } else if (durationMs >= MIN_FALLBACK_EPISODE_DURATION_MS) {
            val startMs = (durationMs - FALLBACK_ED_REMAINING_START_MS).coerceAtLeast(durationMs / 2L)
            val endMs = (durationMs - 2_000L).coerceAtLeast(startMs + 10_000L)
            segments.add(
                SkipSegment(
                    type = SkipSegmentType.ED,
                    startMs = startMs,
                    endMs = endMs,
                    isExact = false
                )
            )
        }

        return segments
    }

    fun findTriggerableSegment(
        positionMs: Long,
        durationMs: Long,
        segments: List<SkipSegment>,
        handledTypes: Set<SkipSegmentType>
    ): SkipSegment? {
        for (segment in segments) {
            if (segment.type in handledTypes) continue
            if (isInTriggerWindow(positionMs, durationMs, segment)) {
                return segment
            }
        }
        return null
    }

    fun isStillInSegmentWindow(
        positionMs: Long,
        durationMs: Long,
        segment: SkipSegment
    ): Boolean {
        return if (segment.isExact) {
            positionMs in segment.startMs..segment.endMs
        } else if (segment.type == SkipSegmentType.OP) {
            positionMs in FALLBACK_OP_START_MS..segment.endMs
        } else {
            val startMs = (durationMs - FALLBACK_ED_REMAINING_START_MS).coerceAtLeast(0L)
            positionMs in startMs..durationMs
        }
    }

    fun computeSkipAction(
        segment: SkipSegment,
        currentPositionMs: Long,
        durationMs: Long,
        fallbackSkipSeconds: Int,
        hasNextEpisode: Boolean
    ): SkipAction {
        return when (segment.type) {
            SkipSegmentType.OP -> {
                val safeEnd = if (durationMs > 0L) {
                    (durationMs - 2_000L).coerceAtLeast(currentPositionMs)
                } else {
                    Long.MAX_VALUE
                }
                val targetMs = if (segment.isExact) {
                    segment.endMs.coerceAtMost(safeEnd)
                } else {
                    (currentPositionMs + fallbackSkipSeconds * 1000L).coerceAtMost(safeEnd)
                }
                SkipAction(
                    kind = SkipActionKind.SEEK_TO,
                    targetPositionMs = targetMs,
                    feedbackLabel = "Skipped OP"
                )
            }
            SkipSegmentType.ED -> {
                val jumpsToNext = hasNextEpisode &&
                    (!segment.isExact || (durationMs > 0L && segment.endMs >= durationMs - ED_TO_NEXT_EP_TAIL_MS))
                if (jumpsToNext) {
                    SkipAction(
                        kind = SkipActionKind.NEXT_EPISODE,
                        targetPositionMs = durationMs.coerceAtLeast(0L),
                        feedbackLabel = "Skipped ED → Next Ep"
                    )
                } else {
                    val safeTail = if (durationMs > 0L) {
                        (durationMs - 1_500L).coerceAtLeast(currentPositionMs)
                    } else {
                        currentPositionMs + fallbackSkipSeconds * 1000L
                    }
                    val targetMs = if (segment.isExact) {
                        segment.endMs.coerceAtMost(safeTail)
                    } else {
                        (durationMs - 5_000L).coerceAtLeast(currentPositionMs)
                    }
                    SkipAction(
                        kind = SkipActionKind.SEEK_TO,
                        targetPositionMs = targetMs,
                        feedbackLabel = "Skipped ED"
                    )
                }
            }
        }
    }

    fun computeManualHudSkipOp(
        exactOp: SkipSegment?,
        currentPositionMs: Long,
        durationMs: Long,
        fallbackSkipSeconds: Int
    ): SkipAction {
        val safeEnd = if (durationMs > 0L) {
            (durationMs - 2_000L).coerceAtLeast(currentPositionMs)
        } else {
            currentPositionMs + fallbackSkipSeconds * 1000L
        }
        val targetMs = if (exactOp != null && currentPositionMs < exactOp.endMs - 2_000L) {
            exactOp.endMs.coerceAtMost(safeEnd)
        } else {
            (currentPositionMs + fallbackSkipSeconds * 1000L).coerceAtMost(safeEnd)
        }
        return SkipAction(
            kind = SkipActionKind.SEEK_TO,
            targetPositionMs = targetMs,
            feedbackLabel = if (exactOp != null && currentPositionMs < exactOp.endMs - 2_000L) {
                "Skipped OP"
            } else {
                "+${fallbackSkipSeconds}s"
            }
        )
    }

    fun computeManualHudSkipEd(
        exactEd: SkipSegment?,
        currentPositionMs: Long,
        durationMs: Long,
        fallbackSkipSeconds: Int,
        hasNextEpisode: Boolean
    ): SkipAction {
        val segment = exactEd ?: SkipSegment(
            type = SkipSegmentType.ED,
            startMs = (durationMs - FALLBACK_ED_REMAINING_START_MS).coerceAtLeast(0L),
            endMs = (durationMs - 2_000L).coerceAtLeast(currentPositionMs),
            isExact = false
        )
        return computeSkipAction(
            segment = segment,
            currentPositionMs = currentPositionMs,
            durationMs = durationMs,
            fallbackSkipSeconds = fallbackSkipSeconds,
            hasNextEpisode = hasNextEpisode
        )
    }

    private fun isInTriggerWindow(
        positionMs: Long,
        durationMs: Long,
        segment: SkipSegment
    ): Boolean {
        return if (segment.isExact) {
            val triggerCeiling = (segment.endMs - 4_000L).coerceAtLeast(segment.startMs)
            positionMs in segment.startMs..triggerCeiling
        } else if (segment.type == SkipSegmentType.OP) {
            positionMs in FALLBACK_OP_START_MS..FALLBACK_OP_TRIGGER_END_MS
        } else {
            val triggerStart = (durationMs - FALLBACK_ED_REMAINING_START_MS).coerceAtLeast(0L)
            val triggerEnd = (durationMs - FALLBACK_ED_REMAINING_END_MS).coerceAtLeast(triggerStart)
            positionMs in triggerStart..triggerEnd
        }
    }
}
