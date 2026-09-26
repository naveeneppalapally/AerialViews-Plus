package com.neilturner.aerialviews.providers.youtube

import java.util.ArrayDeque
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Playlist Orderer Tests")
internal class PlaylistOrdererTest {
    @Test
    @DisplayName("Should never pick the just-played video when alternatives exist")
    fun testAvoidsImmediateRepeat() {
        val entries = listOf(
            entry("videoA", "Forest trail"),
            entry("videoB", "Mountain valley"),
        )

        val picked =
            PlaylistOrderer.pickCandidate(
                entries = entries,
                playbackHistory = listOf("videoA"),
                recentThemes = emptyList(),
                lastChannel = "",
                firstLaunchActive = false,
                firstLaunchSequenceIndex = 0,
                recentPlaybackCutoff = 0L,
                random = Random(0),
            )

        assertEquals("videoB", picked?.videoId)
    }

    @Test
    @DisplayName("Should return null for empty or all-bad entries")
    fun testNullWhenNothingPlayable() {
        assertNull(
            PlaylistOrderer.pickCandidate(
                entries = emptyList(),
                playbackHistory = emptyList(),
                recentThemes = emptyList(),
                lastChannel = "",
                firstLaunchActive = false,
                firstLaunchSequenceIndex = 0,
                recentPlaybackCutoff = 0L,
                random = Random(0),
            ),
        )
        assertNull(
            PlaylistOrderer.pickCandidate(
                entries = listOf(entry("videoA", "Forest trail", isBad = true)),
                playbackHistory = emptyList(),
                recentThemes = emptyList(),
                lastChannel = "",
                firstLaunchActive = false,
                firstLaunchSequenceIndex = 0,
                recentPlaybackCutoff = 0L,
                random = Random(0),
            ),
        )
    }

    @Test
    @DisplayName("Should prefer unseen themes and channels while alternatives exist")
    fun testStrictExclusionsApply() {
        // Strict tier needs MIN_STRICT_PLAYBACK_CANDIDATES (10) to hold;
        // below that the logic intentionally relaxes.
        val entries =
            (1..12).map { index ->
                entry("videoForest$index", "Forest trail pines $index", uploader = "Woodland Films")
            } + listOf(
                entry("videoOcean", "Ocean waves beach", uploader = "Sea Channel"),
                entry("videoSameChannel", "Forest trail", uploader = "Sea Channel"),
            )

        val picked =
            PlaylistOrderer.pickCandidate(
                entries = entries,
                playbackHistory = emptyList(),
                recentThemes = listOf("ocean"),
                lastChannel = "Sea Channel",
                firstLaunchActive = false,
                firstLaunchSequenceIndex = 0,
                recentPlaybackCutoff = 0L,
                random = Random(0),
            )

        assertTrue(picked != null && picked.videoId.startsWith("videoForest"))
    }

    @Test
    @DisplayName("Should open first launch with the sequenced theme")
    fun testFirstLaunchSequence() {
        // First-launch sequencing needs MIN_FIRST_LAUNCH_CANDIDATES (12).
        val entries =
            (1..12).map { index ->
                entry("videoForest$index", "Forest trail pines $index")
            } + listOf(entry("videoSpace", "Earth from space ISS view"))

        val picked =
            PlaylistOrderer.pickCandidate(
                entries = entries,
                playbackHistory = emptyList(),
                recentThemes = emptyList(),
                lastChannel = "",
                firstLaunchActive = true,
                firstLaunchSequenceIndex = 0,
                recentPlaybackCutoff = 0L,
                random = Random(0),
            )

        assertEquals("videoSpace", picked?.videoId)
    }

    @Test
    @DisplayName("Should detect location themes from titles")
    fun testDetectTheme() {
        assertEquals("japan", PlaylistOrderer.detectTheme("Tokyo tower evening lights"))
        assertEquals("desert", PlaylistOrderer.detectTheme("Sahara dunes sunrise"))
        assertEquals("other", PlaylistOrderer.detectTheme("Gentle meadow breeze"))
    }

    @Test
    @DisplayName("Should restrict the repeat window to unwatched entries")
    fun testApplyRepeatWindow() {
        val now = System.currentTimeMillis()
        val entries = listOf(
            entry("playedRecent", "Forest trail", lastPlayedAt = now),
            entry("unplayed", "Mountain valley", lastPlayedAt = 0L),
        )

        val windowed = PlaylistOrderer.applyRepeatWindow(entries, recentPlaybackCutoff = now - 1_000L)

        assertEquals(listOf("unplayed"), windowed.map { it.videoId })
    }

    @Test
    @DisplayName("Simulation record should advance history and first-launch state")
    fun testSimulationRecord() {
        val simulation =
            PlaylistOrderer.PlaylistSimulation(
                history = ArrayDeque(),
                themeHistory = ArrayDeque(),
                lastChannel = "",
                firstLaunchActive = true,
                firstLaunchIndex = 0,
                random = Random(0),
            )

        simulation.record(entry("videoA", "Forest trail", uploader = "Woodland Films"), "forest")

        assertTrue(simulation.history.contains("videoA"))
        assertTrue(simulation.themeHistory.contains("forest"))
        assertEquals("Woodland Films", simulation.lastChannel)
        assertEquals(1, simulation.firstLaunchIndex)
    }

    @Test
    @DisplayName("Should exclude the just-played category when alternatives exist")
    fun testCategoryInterleaving() {
        val entries =
            (1..12).map { index ->
                entry("videoOcean$index", "Ocean waves coral reef $index", categoryKey = "ocean")
            } + listOf(
                entry("videoDroneA", "Drone mountain flight", categoryKey = "drone"),
                entry("videoDroneB", "Drone forest valley", categoryKey = "drone"),
            )

        val picked =
            PlaylistOrderer.pickCandidate(
                entries = entries,
                playbackHistory = emptyList(),
                recentThemes = emptyList(),
                lastChannel = "",
                firstLaunchActive = false,
                firstLaunchSequenceIndex = 0,
                recentPlaybackCutoff = 0L,
                random = Random(0),
                recentCategories = listOf("drone"),
            )

        assertTrue(picked != null && picked.categoryKey == "ocean", "Expected category interleaving to pick ocean instead of drone")
    }

    @Test
    @DisplayName("Should boost weights according to circadian hour of day")
    fun testCircadianWeightMultiplier() {
        // Morning (8 AM)
        assertEquals(2, PlaylistOrderer.getCircadianWeightMultiplier("drone", "mountain", hourOfDay = 8))
        assertEquals(2, PlaylistOrderer.getCircadianWeightMultiplier("nature", "forest", hourOfDay = 8))
        assertEquals(1, PlaylistOrderer.getCircadianWeightMultiplier("space", "space", hourOfDay = 8))

        // Afternoon (2 PM / 14:00)
        assertEquals(2, PlaylistOrderer.getCircadianWeightMultiplier("ocean", "ocean", hourOfDay = 14))
        assertEquals(2, PlaylistOrderer.getCircadianWeightMultiplier("animals", "other", hourOfDay = 14))
        assertEquals(1, PlaylistOrderer.getCircadianWeightMultiplier("cities", "city", hourOfDay = 14))

        // Evening (7 PM / 19:00)
        assertEquals(2, PlaylistOrderer.getCircadianWeightMultiplier("cities", "city", hourOfDay = 19))
        assertEquals(2, PlaylistOrderer.getCircadianWeightMultiplier("drone", "japan", hourOfDay = 19))
        assertEquals(1, PlaylistOrderer.getCircadianWeightMultiplier("animals", "other", hourOfDay = 19))

        // Night (11 PM / 23:00)
        assertEquals(2, PlaylistOrderer.getCircadianWeightMultiplier("space", "space", hourOfDay = 23))
        assertEquals(2, PlaylistOrderer.getCircadianWeightMultiplier("weather", "weather", hourOfDay = 23))
        assertEquals(1, PlaylistOrderer.getCircadianWeightMultiplier("ocean", "ocean", hourOfDay = 23))

        // Disabled (-1)
        assertEquals(1, PlaylistOrderer.getCircadianWeightMultiplier("space", "space", hourOfDay = -1))
    }

    @Test
    @DisplayName("Simulation record should advance category history")
    fun testSimulationRecordCategory() {
        val simulation =
            PlaylistOrderer.PlaylistSimulation(
                history = ArrayDeque(),
                themeHistory = ArrayDeque(),
                lastChannel = "",
                firstLaunchActive = false,
                firstLaunchIndex = 0,
                random = Random(0),
            )

        simulation.record(entry("videoDrone", "Drone flight", categoryKey = "drone"), "mountain")

        assertTrue(simulation.categoryHistory.contains("drone"))
    }

    private fun entry(
        videoId: String,
        title: String,
        uploader: String = "Some Channel",
        lastPlayedAt: Long = 0L,
        isBad: Boolean = false,
        categoryKey: String = "nature",
    ): YouTubeCacheEntity =
        YouTubeCacheEntity(
            videoId = videoId,
            videoPageUrl = "https://www.youtube.com/watch?v=$videoId",
            streamUrl = "https://cdn.example.com/$videoId.mp4",
            title = title,
            uploaderName = uploader,
            durationSeconds = 600,
            categoryKey = categoryKey,
            streamUrlExpiresAt = System.currentTimeMillis() + 86_400_000L,
            searchCachedAt = System.currentTimeMillis(),
            searchQuery = "4K aerial nature ambient",
            isBad = isBad,
            lastPlayedAt = lastPlayedAt,
        )
}
