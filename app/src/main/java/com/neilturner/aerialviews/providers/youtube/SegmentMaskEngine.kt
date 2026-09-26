package com.neilturner.aerialviews.providers.youtube

import kotlin.random.Random

/**
 * Pure 64-bit segment tracking engine for long-form ambient videos.
 *
 * Slices long videos (15m to 8h+) into non-overlapping segments (default 8 minutes)
 * tracked via a 64-bit integer bitmask ([Long]).
 *
 * Guarantees:
 * 1. Non-repeating segments: An already viewed slice of a video will not be shown again
 *    until all available slices of that video have been viewed.
 * 2. Automatic rollover: Once all segments in a video are consumed, the bitmask cleanly
 *    resets to allow fresh cycles without playback stoppage.
 * 3. Intro & Outro protection: Skips intro logos/titles on segment 0 and reserves outro guards
 *    on final segments.
 */
object SegmentMaskEngine {
    const val DEFAULT_SEGMENT_DURATION_MS = 8 * 60 * 1000L // 8 minutes
    const val INTRO_SKIP_MS = 30_000L
    const val OUTRO_GUARD_MS = 20_000L
    const val MAX_SUPPORTED_SEGMENTS = 64

    data class SegmentResult(
        val segmentIndex: Int,
        val startMs: Long,
        val endMs: Long,
        val updatedMask: Long,
        val isRollover: Boolean = false,
    )

    fun totalSegmentCount(
        durationMs: Long,
        segmentDurationMs: Long = DEFAULT_SEGMENT_DURATION_MS,
    ): Int {
        if (durationMs <= 0L || segmentDurationMs <= 0L) {
            return 1
        }
        val effectiveSegmentDuration = segmentDurationMs.coerceAtMost(durationMs)
        return ((durationMs / effectiveSegmentDuration).toInt()).coerceIn(1, MAX_SUPPORTED_SEGMENTS)
    }

    fun unconsumedSegmentIndices(
        totalSegments: Int,
        mask: Long,
    ): List<Int> {
        val unconsumed = mutableListOf<Int>()
        for (i in 0 until totalSegments) {
            val bit = (mask ushr i) and 1L
            if (bit == 0L) {
                unconsumed.add(i)
            }
        }
        return unconsumed
    }

    fun isAllSegmentsConsumed(
        durationMs: Long,
        segmentDurationMs: Long = DEFAULT_SEGMENT_DURATION_MS,
        mask: Long,
    ): Boolean {
        val total = totalSegmentCount(durationMs, segmentDurationMs)
        return unconsumedSegmentIndices(total, mask).isEmpty()
    }

    fun calculateNextSegment(
        durationMs: Long,
        segmentDurationMs: Long = DEFAULT_SEGMENT_DURATION_MS,
        currentMask: Long = 0L,
        random: Random = Random.Default,
        introSkipMs: Long = INTRO_SKIP_MS,
        outroGuardMs: Long = OUTRO_GUARD_MS,
    ): SegmentResult {
        if (durationMs <= 0L || segmentDurationMs <= 0L) {
            return SegmentResult(
                segmentIndex = 0,
                startMs = 0L,
                endMs = durationMs.coerceAtLeast(0L),
                updatedMask = 1L,
            )
        }

        val effectiveSegmentDuration = segmentDurationMs.coerceAtMost(durationMs)
        val totalSegments = totalSegmentCount(durationMs, effectiveSegmentDuration)

        if (totalSegments <= 1) {
            val totalGuards = introSkipMs + outroGuardMs
            val (startMs, endMs) =
                if (durationMs > totalGuards) {
                    Pair(introSkipMs, (durationMs - outroGuardMs).coerceAtLeast(introSkipMs))
                } else {
                    Pair(0L, durationMs)
                }
            return SegmentResult(
                segmentIndex = 0,
                startMs = startMs,
                endMs = endMs,
                updatedMask = 1L,
                isRollover = false,
            )
        }

        val unconsumed = unconsumedSegmentIndices(totalSegments, currentMask)
        val isRollover = unconsumed.isEmpty()
        val availableIndices = if (isRollover) (0 until totalSegments).toList() else unconsumed

        val chosenIndex = availableIndices[random.nextInt(availableIndices.size)]
        val rawStartMs = chosenIndex * effectiveSegmentDuration
        val rawEndMs = ((chosenIndex + 1) * effectiveSegmentDuration).coerceAtMost(durationMs)

        // Protect intro boundaries on the initial segment
        val adjustedStartMs =
            if (chosenIndex == 0 && rawStartMs < introSkipMs && durationMs > (introSkipMs + outroGuardMs)) {
                introSkipMs
            } else {
                rawStartMs
            }

        // Protect outro boundaries on the terminating segment
        val adjustedEndMs =
            if (chosenIndex == totalSegments - 1 && (durationMs - rawEndMs) < outroGuardMs) {
                (durationMs - outroGuardMs).coerceAtLeast(adjustedStartMs)
            } else {
                rawEndMs
            }

        val baseMask = if (isRollover) 0L else currentMask
        val updatedMask = baseMask or (1L shl chosenIndex)

        return SegmentResult(
            segmentIndex = chosenIndex,
            startMs = adjustedStartMs,
            endMs = adjustedEndMs,
            updatedMask = updatedMask,
            isRollover = isRollover,
        )
    }
}
