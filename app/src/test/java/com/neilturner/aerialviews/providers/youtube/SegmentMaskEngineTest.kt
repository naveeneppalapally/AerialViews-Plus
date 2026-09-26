package com.neilturner.aerialviews.providers.youtube

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class SegmentMaskEngineTest {
    @Test
    fun totalSegmentCount_calculatesExpectedSegments() {
        // 1 hour (3,600,000 ms) / 8 min (480,000 ms) = 7 segments
        assertEquals(7, SegmentMaskEngine.totalSegmentCount(3_600_000L, 480_000L))

        // 3 hours (10,800,000 ms) / 8 min = 22 segments
        assertEquals(22, SegmentMaskEngine.totalSegmentCount(10_800_000L, 480_000L))

        // 5 minutes (300,000 ms) with 8 min segment = 1 segment
        assertEquals(1, SegmentMaskEngine.totalSegmentCount(300_000L, 480_000L))

        // Zero or negative
        assertEquals(1, SegmentMaskEngine.totalSegmentCount(0L, 480_000L))
        assertEquals(1, SegmentMaskEngine.totalSegmentCount(100_000L, 0L))

        // Upper limit clamped to 64
        assertEquals(64, SegmentMaskEngine.totalSegmentCount(100 * 3_600_000L, 60_000L))
    }

    @Test
    fun calculateNextSegment_progressivelyConsumesAllSegmentsWithoutRepetition() {
        val durationMs = 3_600_000L // 60 minutes
        val segmentMs = 600_000L // 10 minutes -> 6 segments
        val totalSegments = 6

        var currentMask = 0L
        val consumedIndices = mutableSetOf<Int>()

        for (i in 0 until totalSegments) {
            val result =
                SegmentMaskEngine.calculateNextSegment(
                    durationMs = durationMs,
                    segmentDurationMs = segmentMs,
                    currentMask = currentMask,
                    random = Random(42L + i),
                )

            assertFalse(result.isRollover, "Rollover should not trigger before all segments consumed")
            assertTrue(result.segmentIndex in 0 until totalSegments, "Segment index should be within bounds")
            assertTrue(result.segmentIndex !in consumedIndices, "Segment index should not have been previously consumed")

            consumedIndices.add(result.segmentIndex)
            currentMask = result.updatedMask

            // Verify that the chosen segment's bit is set in currentMask
            val bit = (currentMask ushr result.segmentIndex) and 1L
            assertEquals(1L, bit)
        }

        assertEquals(6, consumedIndices.size, "All 6 segments must be consumed")
        assertTrue(SegmentMaskEngine.isAllSegmentsConsumed(durationMs, segmentMs, currentMask))

        // The 7th call must trigger rollover and reset the cycle
        val rolloverResult =
            SegmentMaskEngine.calculateNextSegment(
                durationMs = durationMs,
                segmentDurationMs = segmentMs,
                currentMask = currentMask,
                random = Random(999L),
            )

        assertTrue(rolloverResult.isRollover, "Rollover flag must be true when all segments were previously consumed")
        // In rollover, new mask should only contain the newly selected segment's single bit
        assertEquals(1L shl rolloverResult.segmentIndex, rolloverResult.updatedMask)
    }

    @Test
    fun calculateNextSegment_enforcesIntroSkipAndOutroGuard() {
        val durationMs = 1_800_000L // 30 minutes
        val segmentMs = 600_000L // 10 minutes -> 3 segments

        // Force segment 0
        val seg0 =
            SegmentMaskEngine.calculateNextSegment(
                durationMs = durationMs,
                segmentDurationMs = segmentMs,
                currentMask = 0b110L, // bits 1 and 2 already consumed -> must pick 0
                random = Random(0),
            )
        assertEquals(0, seg0.segmentIndex)
        assertEquals(30_000L, seg0.startMs) // Intro skip applied
        assertEquals(600_000L, seg0.endMs)

        // Force final segment (index 2)
        val seg2 =
            SegmentMaskEngine.calculateNextSegment(
                durationMs = durationMs,
                segmentDurationMs = segmentMs,
                currentMask = 0b011L, // bits 0 and 1 consumed -> must pick 2
                random = Random(0),
            )
        assertEquals(2, seg2.segmentIndex)
        assertEquals(1_200_000L, seg2.startMs)
        assertEquals(1_780_000L, seg2.endMs) // Outro guard applied: 1,800,000 - 20,000 = 1,780,000
    }

    @Test
    fun shortVideo_handlesSafely() {
        val shortDuration = 45_000L // 45 seconds
        val result =
            SegmentMaskEngine.calculateNextSegment(
                durationMs = shortDuration,
                segmentDurationMs = 480_000L,
                currentMask = 0L,
            )
        assertEquals(0, result.segmentIndex)
        assertEquals(0L, result.startMs)
        assertEquals(45_000L, result.endMs)
    }
}
