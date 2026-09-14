package com.neilturner.aerialviews.providers.youtube

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

@DisplayName("YouTube Library State Tests")
internal class YouTubeLibraryStateTest {
    @BeforeEach
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.i(any(), any(), any()) } returns 0
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(Log::class)
        YouTubeThrottling.clearForTest()
    }

    @Test
    @DisplayName("Populating count never exceeds the Room count")
    fun testPopulatingNeverExceedsRoom() =
        runTest {
            val cacheDao = FakeYouTubeCacheDao(mutableListOf())
            val repository = testRepository(cacheDao)
            val seen = mutableListOf<YouTubeLibraryState>()

            val collector =
                backgroundScope.launch {
                    repository.libraryState.collect { seen += it }
                }
            runCurrent()

            repository.refreshSearchResults(replaceExistingCache = true)

            runCurrent()
            collector.cancel()

            val populating = seen.filterIsInstance<YouTubeLibraryState.Populating>()
            assertTrue(populating.isNotEmpty(), "Expected Populating states, saw $seen")
            populating.forEach { state ->
                assertTrue(
                    state.persistedCount <= cacheDao.countGoodEntries(),
                    "Populating ${state.persistedCount} exceeds Room ${cacheDao.countGoodEntries()}",
                )
            }
            val last = seen.last()
            assertTrue(last is YouTubeLibraryState.Idle, "Expected Idle at end, saw $last")
        }

    @Test
    @DisplayName("Populating count is monotonically non-decreasing")
    fun testPopulatingMonotonic() =
        runTest {
            val repository = testRepository(FakeYouTubeCacheDao(mutableListOf()))
            val counts = mutableListOf<Int>()

            val collector =
                backgroundScope.launch {
                    repository.libraryState.collect {
                        if (it is YouTubeLibraryState.Populating) counts += it.persistedCount
                    }
                }
            runCurrent()

            repository.refreshSearchResults(replaceExistingCache = true)

            runCurrent()
            collector.cancel()

            counts.zipWithNext { a, b ->
                assertTrue(b >= a, "Counter moved backwards during populating: $a -> $b")
            }
        }

    @Test
    @DisplayName("Category backfill reports monotonic committed progress")
    fun testCategoryBackfillReportsMonotonicProgress() =
        runTest {
            val sharedPreferences = freshPrefs()
            val editor = sharedPreferences.edit()
            listOf(
                YouTubeSourceRepository.KEY_CATEGORY_NATURE,
                YouTubeSourceRepository.KEY_CATEGORY_ANIMALS,
                YouTubeSourceRepository.KEY_CATEGORY_DRONE,
                YouTubeSourceRepository.KEY_CATEGORY_CITIES,
                YouTubeSourceRepository.KEY_CATEGORY_SPACE,
                YouTubeSourceRepository.KEY_CATEGORY_OCEAN,
                YouTubeSourceRepository.KEY_CATEGORY_WEATHER,
                YouTubeSourceRepository.KEY_CATEGORY_WINTER,
            ).forEach { editor.putBoolean(it, false) }
            editor.commit()
            val cacheDao = FakeYouTubeCacheDao(categoryEntries(199))
            val repository =
                YouTubeSourceRepository(
                    context = mockPackageContext(),
                    cacheDao = cacheDao,
                    watchHistoryDao = FakeYouTubeWatchHistoryDao(),
                    sharedPreferences = sharedPreferences,
                    searcher = FakeVideoSearcher(),
                    extractor = FakeStreamExtractor(),
                )
            runCurrent()
            sharedPreferences.edit().putBoolean("yt_category_animals", true).commit()
            val counts = mutableListOf<Int>()
            val collector =
                backgroundScope.launch(Dispatchers.Unconfined) {
                    repository.libraryState.collect { state ->
                        if (state is YouTubeLibraryState.Populating) counts += state.persistedCount
                    }
                }
            runCurrent()

            val result = repository.applyCategoryDeltaRefresh()

            runCurrent()
            collector.cancel()
            assertTrue(result.insertedCount > 0, "Expected category backfill to insert an entry")
            assertTrue(counts.isNotEmpty(), "Expected committed Populating progress")
            counts.zipWithNext { first, second ->
                assertTrue(second >= first, "Backfill progress moved backwards: $first -> $second")
            }
            assertTrue(counts.all { it <= cacheDao.countGoodEntries() })
        }

    @Test
    @DisplayName("Starved pool settles honestly below target")
    fun testStarvedPoolSettlesHonestly() =
        runTest {
            val repository =
                YouTubeSourceRepository(
                    context = mockPackageContext(),
                    cacheDao = FakeYouTubeCacheDao(mutableListOf()),
                    watchHistoryDao = FakeYouTubeWatchHistoryDao(),
                    sharedPreferences = freshPrefs(),
                    searcher = ScarceVideoSearcher(),
                    extractor = FakeStreamExtractor(),
                )
            val seen = mutableListOf<YouTubeLibraryState>()

            val collector =
                backgroundScope.launch {
                    repository.libraryState.collect { seen += it }
                }
            runCurrent()

            repository.refreshSearchResults(replaceExistingCache = true)

            runCurrent()
            collector.cancel()

            assertTrue(
                seen.none { it is YouTubeLibraryState.Populating && it.persistedCount >= 200 },
                "Must never emit 200 when the pool cannot fill it: $seen",
            )
            val last = seen.last()
            assertTrue(last is YouTubeLibraryState.Idle, "Expected Idle at end, saw $last")
        }

    @Test
    @DisplayName("Searching carries no target denominator")
    fun testSearchingHasNoDenominator() =
        runTest {
            val repository = testRepository(FakeYouTubeCacheDao(mutableListOf()))
            val seen = mutableListOf<YouTubeLibraryState>()

            val collector =
                backgroundScope.launch {
                    repository.libraryState.collect { seen += it }
                }
            runCurrent()

            repository.refreshSearchResults(replaceExistingCache = true)

            runCurrent()
            collector.cancel()

            // Searching is a data class without targetCount by construction;
            // this asserts the contract structurally.
            seen.filterIsInstance<YouTubeLibraryState.Searching>().forEach { state ->
                assertTrue(state.candidatesFound >= 0)
            }
        }

    private fun testRepository(cacheDao: FakeYouTubeCacheDao): YouTubeSourceRepository =
        YouTubeSourceRepository(
            context = mockPackageContext(),
            cacheDao = cacheDao,
            watchHistoryDao = FakeYouTubeWatchHistoryDao(),
            sharedPreferences = freshPrefs(),
            searcher = FakeVideoSearcher(),
            extractor = FakeStreamExtractor(),
        )

    private fun categoryEntries(count: Int): MutableList<YouTubeCacheEntity> =
        (1..count).map { index ->
            YouTubeCacheEntity(
                videoId = "categoryvideo$index",
                videoPageUrl = "https://www.youtube.com/watch?v=categoryvideo$index",
                streamUrl = "https://cdn.example.com/categoryvideo$index.mp4",
                title = "Ambient nature video $index",
                uploaderName = "Nature Channel $index",
                durationSeconds = 600,
                categoryKey = "animals",
                streamUrlExpiresAt = System.currentTimeMillis() + 86_400_000L,
                searchCachedAt = System.currentTimeMillis(),
                searchQuery = "wildlife animals ambient",
            )
        }.toMutableList()

    private fun streamSignature(quality: String): String =
        "$quality|videoOnly=true|selector=v${YouTubeSourceRepository.STREAM_SELECTION_STRATEGY_VERSION}"

    private fun freshPrefs(): InMemorySharedPreferences =
        InMemorySharedPreferences(
            mutableMapOf(
                YouTubeSourceRepository.KEY_CACHE_VERSION to 29,
                YouTubeSourceRepository.KEY_CACHE_SIGNATURE to "1|v29",
                YouTubeSourceRepository.KEY_STREAM_QUALITY_SIGNATURE to streamSignature("best"),
                YouTubeSourceRepository.KEY_QUALITY to "best",
                YouTubeHistoryTracker.KEY_FIRST_LAUNCH to false,
                YouTubeHistoryTracker.KEY_FIRST_LAUNCH_INDEX to 0,
            ),
        )

    private fun mockPackageContext(): Context {
        val packageManager = mockk<PackageManager>()
        val packageInfo = mockk<PackageInfo>()
        every { packageInfo.longVersionCode } returns 1L
        every { packageManager.getPackageInfo(any<String>(), any<Int>()) } returns packageInfo

        val context = mockk<Context>()
        every { context.packageManager } returns packageManager
        every { context.packageName } returns "com.naveen.aerialviewsplus"
        return context
    }

    private class FakeVideoSearcher : VideoSearcher {
        private var counter = 0

        override suspend fun searchVideos(
            query: String,
            category: QueryFormulaEngine.ContentCategory?,
        ): List<StreamInfoItem> =
            (1..30).map {
                counter += 1
                StreamInfoItem(
                    0,
                    "https://www.youtube.com/watch?v=fakevideo$counter",
                    "Ambient forest real footage $counter",
                    StreamType.VIDEO_STREAM,
                ).apply {
                    uploaderName = "Fake Nature Channel $counter"
                    setDuration(600L)
                }
            }
    }

    private class ScarceVideoSearcher : VideoSearcher {
        override suspend fun searchVideos(
            query: String,
            category: QueryFormulaEngine.ContentCategory?,
        ): List<StreamInfoItem> =
            listOf("scarcevideo1", "scarcevideo2").map { videoId ->
                StreamInfoItem(
                    0,
                    "https://www.youtube.com/watch?v=$videoId",
                    "Ambient forest real footage $videoId",
                    StreamType.VIDEO_STREAM,
                ).apply {
                    uploaderName = "Fake Nature Channel"
                    setDuration(600L)
                }
            }
    }

    private class FakeStreamExtractor : StreamExtractor {
        override suspend fun extractPlaybackStreams(
            videoPageUrl: String,
            preferredQuality: String,
            preferVideoOnly: Boolean,
            allowAdaptiveManifests: Boolean,
            preferAdaptiveManifests: Boolean,
            preferManifests: Boolean,
        ): YouTubePlaybackUrls {
            val videoId = videoPageUrl.substringAfter("v=").substringBefore("&").ifBlank { "unknown" }
            return YouTubePlaybackUrls(videoUrl = "https://cdn.example.com/$videoId.mp4")
        }
    }
}
