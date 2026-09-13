package com.neilturner.aerialviews.providers.youtube

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("YouTube Throttling Tests")
internal class YouTubeThrottlingTest {
    @AfterEach
    fun tearDown() {
        YouTubeThrottling.clearForTest()
    }

    @Test
    @DisplayName("Should open with 45 minutes on first block")
    fun testFirstBlockOpens45Minutes() {
        YouTubeThrottling.init(InMemorySharedPreferences(mutableMapOf()))

        YouTubeThrottling.noteBotBlock()

        assertTrue(YouTubeThrottling.isBlocked())
        assertTrue(YouTubeThrottling.remainingCooldownMinutes() in 1..45)
    }

    @Test
    @DisplayName("Should not extend or escalate on repeat hits while blocked")
    fun testRepeatHitsWhileBlockedAreOneEpisode() {
        YouTubeThrottling.init(InMemorySharedPreferences(mutableMapOf()))

        YouTubeThrottling.noteBotBlock()
        val firstRemaining = YouTubeThrottling.remainingCooldownMs()
        YouTubeThrottling.noteBotBlock()
        YouTubeThrottling.noteBotBlock()

        assertEquals(1, YouTubeThrottling.consecutiveBlocksForTest())
        assertTrue(YouTubeThrottling.remainingCooldownMs() <= firstRemaining)
    }

    @Test
    @DisplayName("Should escalate exponentially across episodes")
    fun testEscalationAcrossEpisodes() {
        val prefs = InMemorySharedPreferences(mutableMapOf())
        YouTubeThrottling.init(prefs)

        YouTubeThrottling.noteBotBlock()
        assertEquals(1, YouTubeThrottling.consecutiveBlocksForTest())

        // Simulate expiry of the first episode, then a fresh block.
        prefs.edit().putLong("yt_bot_blocked_until", 0L).apply()
        YouTubeThrottling.init(prefs)
        YouTubeThrottling.noteBotBlock()

        assertEquals(2, YouTubeThrottling.consecutiveBlocksForTest())
        assertTrue(YouTubeThrottling.remainingCooldownMinutes() in 46..90)
    }

    @Test
    @DisplayName("Should survive process death via persisted state")
    fun testPersistenceAcrossRestart() {
        val prefs = InMemorySharedPreferences(mutableMapOf())
        YouTubeThrottling.init(prefs)
        YouTubeThrottling.noteBotBlock()
        assertTrue(YouTubeThrottling.isBlocked())

        // Fresh process: re-init from the same prefs must restore the gate.
        YouTubeThrottling.init(prefs)

        assertTrue(YouTubeThrottling.isBlocked())
        assertEquals(1, YouTubeThrottling.consecutiveBlocksForTest())
    }

    @Test
    @DisplayName("Should reset on proven extraction success")
    fun testSuccessResets() {
        YouTubeThrottling.init(InMemorySharedPreferences(mutableMapOf()))
        YouTubeThrottling.noteBotBlock()
        assertTrue(YouTubeThrottling.isBlocked())

        YouTubeThrottling.noteExtractionSuccess()

        assertFalse(YouTubeThrottling.isBlocked())
        assertEquals(0, YouTubeThrottling.consecutiveBlocksForTest())
    }
}
