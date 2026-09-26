package com.neilturner.aerialviews.providers.youtube

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("YouTube History Tracker Tests")
internal class YouTubeHistoryTrackerTest {
    @Test
    @DisplayName("Should record playback across cache, watch table and prefs")
    fun testRecordPlayback() =
        runTest {
            val cacheDao = FakeYouTubeCacheDao(mutableListOf(cacheEntry("video1")))
            val watchHistoryDao = FakeYouTubeWatchHistoryDao()
            val prefs = InMemorySharedPreferences(mutableMapOf())
            val tracker = YouTubeHistoryTracker(cacheDao, watchHistoryDao, prefs)

            tracker.recordPlayback(cacheEntry("video1", title = "Forest trail pines"))

            assertEquals(listOf("video1"), tracker.playHistory().toList())
            assertEquals("Woodland Films", tracker.lastPlayedChannel())
            assertEquals(1, tracker.firstLaunchIndex())
            assertTrue(tracker.isFirstLaunchActive())
            assertTrue(cacheDao.getAll().first { it.videoId == "video1" }.consumedSegmentsMask != 0L)
        }

    @Test
    @DisplayName("Should prune watch rows outside the repeat window")
    fun testPruneDeletesOldRows() =
        runTest {
            val now = System.currentTimeMillis()
            val cacheDao = FakeYouTubeCacheDao(mutableListOf(cacheEntry("video1")))
            val watchHistoryDao = FakeYouTubeWatchHistoryDao()
            val prefs = InMemorySharedPreferences(mutableMapOf())
            val tracker = YouTubeHistoryTracker(cacheDao, watchHistoryDao, prefs)

            watchHistoryDao.insert(
                YouTubeWatchHistoryEntity(videoId = "ancient", playedAt = now - 30L * 24L * 3_600_000L),
            )
            watchHistoryDao.insert(
                YouTubeWatchHistoryEntity(videoId = "fresh", playedAt = now),
            )

            tracker.prunePlayHistory(cacheDao.getAllGood())

            val remaining = watchHistoryDao.recentHistory(10).map { it.videoId }
            assertEquals(listOf("fresh"), remaining)
        }

    @Test
    @DisplayName("Should track refresh ids with a bounded history")
    fun testRecentRefreshIds() {
        val tracker =
            YouTubeHistoryTracker(
                FakeYouTubeCacheDao(mutableListOf()),
                FakeYouTubeWatchHistoryDao(),
                InMemorySharedPreferences(mutableMapOf()),
            )

        tracker.recordRefreshHistory(listOf(cacheEntry("a"), cacheEntry("b"), cacheEntry("a")))

        assertEquals(listOf("b", "a"), tracker.recentRefreshIds().toList())
    }

    @Test
    @DisplayName("Should fall back to prefs history when the watch table is empty")
    fun testPlayHistoryPrefsFallback() =
        runTest {
            val tracker =
                YouTubeHistoryTracker(
                    FakeYouTubeCacheDao(mutableListOf()),
                    FakeYouTubeWatchHistoryDao(),
                    InMemorySharedPreferences(
                        mutableMapOf(YouTubeHistoryTracker.KEY_PLAY_HISTORY to "x|y"),
                    ),
                )

            assertEquals(listOf("x", "y"), tracker.playHistory().toList())
            assertTrue(tracker.themeHistory().isEmpty())
        }

    private fun cacheEntry(
        videoId: String,
        title: String = "Ambient video",
    ): YouTubeCacheEntity =
        YouTubeCacheEntity(
            videoId = videoId,
            videoPageUrl = "https://www.youtube.com/watch?v=$videoId",
            streamUrl = "https://cdn.example.com/$videoId.mp4",
            title = title,
            uploaderName = "Woodland Films",
            durationSeconds = 600,
            categoryKey = "nature",
            streamUrlExpiresAt = System.currentTimeMillis() + 86_400_000L,
            searchCachedAt = System.currentTimeMillis(),
            searchQuery = "4K aerial nature ambient",
        )
}
