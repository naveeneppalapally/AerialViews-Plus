package com.neilturner.aerialviews.providers.youtube

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

@DisplayName("YouTube Source Repository Tests")
internal class YouTubeSourceRepositoryTest {
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
    @DisplayName("Should not return the same video twice in a row")
    fun testGetNextVideoUrlAvoidsConsecutiveRepeats() = runTest {
        val now = System.currentTimeMillis()
        val cacheDao = FakeYouTubeCacheDao(buildEntries(now))
        val watchHistoryDao = FakeYouTubeWatchHistoryDao()
        val sharedPreferences =
            InMemorySharedPreferences(
                mutableMapOf(
                    YouTubeSourceRepository.KEY_CACHE_VERSION to 29,
                    YouTubeSourceRepository.KEY_CACHE_SIGNATURE to "1|v29",
                    YouTubeSourceRepository.KEY_STREAM_QUALITY_SIGNATURE to streamSignature("best"),
                    YouTubeHistoryTracker.KEY_FIRST_LAUNCH to false,
                    YouTubeHistoryTracker.KEY_FIRST_LAUNCH_INDEX to 0,
                ),
            )

        val packageManager = mockk<PackageManager>()
        val packageInfo = mockk<PackageInfo>()
        every { packageInfo.longVersionCode } returns 1L
        every { packageManager.getPackageInfo(any<String>(), any<Int>()) } returns packageInfo

        val context = mockk<Context>()
        every { context.packageManager } returns packageManager
        every { context.packageName } returns "com.naveen.aerialviewsplus"

        val repository =
            YouTubeSourceRepository(
                context = context,
                cacheDao = cacheDao,
                watchHistoryDao = watchHistoryDao,
                sharedPreferences = sharedPreferences,
            )

        val playedIds = mutableListOf<String>()
        repeat(10) {
            val streamUrl = repository.getNextVideoUrl()
            assertTrue(streamUrl.startsWith("https://cdn.example.com/video"))
            playedIds += watchHistoryDao.lastPlayedVideoId()
        }

        assertTrue(
            playedIds.zipWithNext().all { (first, second) -> first != second },
            "Expected no consecutive repeat, but got $playedIds",
        )
    }

    @Test
    @DisplayName("Should invalidate cached stream URLs when YouTube quality target changes")
    fun testGetCachedVideosSnapshotInvalidatesStreamUrlsWhenQualityChanges() = runTest {
        val now = System.currentTimeMillis()
        val cacheDao = FakeYouTubeCacheDao(buildEntries(now))
        val watchHistoryDao = FakeYouTubeWatchHistoryDao()
        val sharedPreferences =
            InMemorySharedPreferences(
                mutableMapOf(
                    YouTubeSourceRepository.KEY_CACHE_VERSION to 29,
                    YouTubeSourceRepository.KEY_CACHE_SIGNATURE to "1|v29",
                    YouTubeSourceRepository.KEY_STREAM_QUALITY_SIGNATURE to streamSignature("1080p"),
                    YouTubeSourceRepository.KEY_QUALITY to "2160p",
                    YouTubeHistoryTracker.KEY_FIRST_LAUNCH to false,
                    YouTubeHistoryTracker.KEY_FIRST_LAUNCH_INDEX to 0,
                ),
            )

        val packageManager = mockk<PackageManager>()
        val packageInfo = mockk<PackageInfo>()
        every { packageInfo.longVersionCode } returns 1L
        every { packageManager.getPackageInfo(any<String>(), any<Int>()) } returns packageInfo

        val context = mockk<Context>()
        every { context.packageManager } returns packageManager
        every { context.packageName } returns "com.naveen.aerialviewsplus"

        val repository =
            YouTubeSourceRepository(
                context = context,
                cacheDao = cacheDao,
                watchHistoryDao = watchHistoryDao,
                sharedPreferences = sharedPreferences,
            )

        val cachedEntries = repository.getCachedVideosSnapshot()

        assertTrue(cachedEntries.isNotEmpty())
        assertTrue(cachedEntries.all { it.streamUrl.isBlank() })
        assertEquals(cacheDao.countGoodEntries(), cacheDao.invalidatedStreamUrlCount)
        assertEquals(
            streamSignature("2160p"),
            sharedPreferences.getString(YouTubeSourceRepository.KEY_STREAM_QUALITY_SIGNATURE, null),
        )
    }

    @Test
    @DisplayName("Should invalidate cached stream URLs when the stream selection strategy changes")
    fun testGetCachedVideosSnapshotInvalidatesStreamUrlsWhenStrategyChanges() = runTest {
        val now = System.currentTimeMillis()
        val cacheDao = FakeYouTubeCacheDao(buildEntries(now))
        val watchHistoryDao = FakeYouTubeWatchHistoryDao()
        val sharedPreferences =
            InMemorySharedPreferences(
                mutableMapOf(
                    YouTubeSourceRepository.KEY_CACHE_VERSION to 29,
                    YouTubeSourceRepository.KEY_CACHE_SIGNATURE to "1|v29",
                    YouTubeSourceRepository.KEY_STREAM_QUALITY_SIGNATURE to "2160p|videoOnly=true",
                    YouTubeSourceRepository.KEY_QUALITY to "2160p",
                    YouTubeHistoryTracker.KEY_FIRST_LAUNCH to false,
                    YouTubeHistoryTracker.KEY_FIRST_LAUNCH_INDEX to 0,
                ),
            )

        val packageManager = mockk<PackageManager>()
        val packageInfo = mockk<PackageInfo>()
        every { packageInfo.longVersionCode } returns 1L
        every { packageManager.getPackageInfo(any<String>(), any<Int>()) } returns packageInfo

        val context = mockk<Context>()
        every { context.packageManager } returns packageManager
        every { context.packageName } returns "com.naveen.aerialviewsplus"

        val repository =
            YouTubeSourceRepository(
                context = context,
                cacheDao = cacheDao,
                watchHistoryDao = watchHistoryDao,
                sharedPreferences = sharedPreferences,
            )

        val cachedEntries = repository.getCachedVideosSnapshot()

        assertTrue(cachedEntries.isNotEmpty())
        assertTrue(cachedEntries.all { it.streamUrl.isBlank() })
        assertEquals(cacheDao.countGoodEntries(), cacheDao.invalidatedStreamUrlCount)
        assertEquals(
            streamSignature("2160p"),
            sharedPreferences.getString(YouTubeSourceRepository.KEY_STREAM_QUALITY_SIGNATURE, null),
        )
    }

    @Test
    @DisplayName("Should build the library offline via injected searcher and extractor")
    fun testRefreshPipelineRunsOfflineWithFakes() =
        runTest {
            val cacheDao = FakeYouTubeCacheDao(mutableListOf())
            val watchHistoryDao = FakeYouTubeWatchHistoryDao()
            val sharedPreferences =
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

            val context = mockPackageContext()
            val repository =
                YouTubeSourceRepository(
                    context = context,
                    cacheDao = cacheDao,
                    watchHistoryDao = watchHistoryDao,
                    sharedPreferences = sharedPreferences,
                    searcher = FakeVideoSearcher(),
                    extractor = FakeStreamExtractor(),
                )

            val entries = repository.refreshSearchResults(replaceExistingCache = true)

            assertTrue(entries.isNotEmpty(), "Expected fakes to produce cache entries without network")
            // Hybrid JIT: small eager head with streams, metadata tail blank.
            val eager = entries.filter { it.streamUrl.isNotBlank() }
            val metadata = entries.filter { it.streamUrl.isBlank() }
            assertTrue(eager.isNotEmpty() && eager.size <= 12)
            assertTrue(eager.all { it.streamUrl.startsWith("https://cdn.example.com/") })
            assertTrue(metadata.isNotEmpty())
            assertTrue(metadata.all { it.streamUrlExpiresAt == 0L })
            assertEquals(entries.size, cacheDao.countGoodEntries())
        }

    @Test
    @DisplayName("Should exclude last batch IDs from the next batch")
    fun testTier1ExcludedFromNextBatch() =
        runTest {
            val prefs = freshPrefs()
            val cacheDao = FakeYouTubeCacheDao(mutableListOf())
            val watchHistoryDao = FakeYouTubeWatchHistoryDao()
            // Seed novelty memory with a previous batch. Small ID sets keep
            // the top-up backfill (which re-searches per category) fast.
            val tier1Ids = (1..12).map { "tier1video$it" }
            val freshIds = (1..8).map { "freshvideo$it" }
            YouTubeHistoryTracker(cacheDao, watchHistoryDao, prefs)
                .recordRefreshHistory(tierEntries(tier1Ids))
            val repository =
                YouTubeSourceRepository(
                    context = mockPackageContext(),
                    cacheDao = cacheDao,
                    watchHistoryDao = watchHistoryDao,
                    sharedPreferences = prefs,
                    searcher = FixedIdSearcher(tier1Ids + freshIds),
                    extractor = FakeStreamExtractor(),
                )

            val entries = repository.refreshSearchResults(replaceExistingCache = true)
            val resultIds = entries.map { it.videoId }.toSet()

            assertTrue(entries.isNotEmpty(), "Expected fresh videos to fill the batch")
            assertTrue(
                resultIds.none { it.startsWith("tier1video") },
                "Tier-1 IDs must not recur in the next batch",
            )
            assertTrue(resultIds.any { it.startsWith("freshvideo") })
        }

    @Test
    @DisplayName("Should admit Tier-1 IDs rather than fail when starved")
    fun testEmergencyValveAdmitsWhenStarved() =
        runTest {
            val prefs = freshPrefs()
            val cacheDao = FakeYouTubeCacheDao(mutableListOf())
            val watchHistoryDao = FakeYouTubeWatchHistoryDao()
            val tier1Ids = (1..12).map { "tier1video$it" }
            YouTubeHistoryTracker(cacheDao, watchHistoryDao, prefs)
                .recordRefreshHistory(tierEntries(tier1Ids))
            val repository =
                YouTubeSourceRepository(
                    context = mockPackageContext(),
                    cacheDao = cacheDao,
                    watchHistoryDao = watchHistoryDao,
                    sharedPreferences = prefs,
                    searcher = FixedIdSearcher(tier1Ids),
                    extractor = FakeStreamExtractor(),
                )

            val entries = repository.refreshSearchResults(replaceExistingCache = true)

            assertTrue(entries.isNotEmpty(), "Emergency valve must keep the refresh alive")
            assertTrue(entries.all { it.videoId.startsWith("tier1video") })
        }

    private fun tierEntries(videoIds: List<String>): List<YouTubeCacheEntity> =
        videoIds.map { videoId ->
            YouTubeCacheEntity(
                videoId = videoId,
                videoPageUrl = "https://www.youtube.com/watch?v=$videoId",
                streamUrl = "https://cdn.example.com/$videoId.mp4",
                title = "Ambient video $videoId",
                uploaderName = "Channel $videoId",
                durationSeconds = 600,
                categoryKey = "nature",
                streamUrlExpiresAt = System.currentTimeMillis() + 86_400_000L,
                searchCachedAt = System.currentTimeMillis(),
                searchQuery = "4K aerial nature ambient",
            )
        }

    @Test
    @DisplayName("Should report cumulative search progress across fallback pools")
    fun testCumulativeSearchProgressAcrossFallbacks() =
        runTest {
            // Seed past cold-start so the full pool pipeline (main + long-tail
            // + healthy + supplemental fallbacks) runs against a scarce
            // searcher that always returns the same 2 videos.
            val cacheDao = FakeYouTubeCacheDao(buildEntries(System.currentTimeMillis()).take(10).toMutableList())
            val repository =
                YouTubeSourceRepository(
                    context = mockPackageContext(),
                    cacheDao = cacheDao,
                    watchHistoryDao = FakeYouTubeWatchHistoryDao(),
                    sharedPreferences = freshPrefs(),
                    searcher = ScarceVideoSearcher(),
                    extractor = FakeStreamExtractor(),
                )

            val searchStates = mutableListOf<YouTubeLibraryState.Searching>()
            val collector =
                backgroundScope.launch {
                    repository.libraryState.collect { state ->
                        if (state is YouTubeLibraryState.Searching && state.queriesTotal > 0) {
                            searchStates += state
                        }
                    }
                }
            runCurrent()

            repository.refreshSearchResults(replaceExistingCache = true)

            runCurrent()
            collector.cancel()

            assertTrue(searchStates.isNotEmpty(), "Expected Searching state emissions with query progress")
            val searchPairs = searchStates.map { Pair(it.queriesCompleted, it.queriesTotal) }
            // Fallbacks must have run: total grows past the 25-query main pool.
            assertTrue(searchPairs.any { it.second > 25 }, "Expected fallback pools to extend the total: $searchPairs")
            // Completed count never goes backwards while searching.
            searchPairs.zipWithNext { a, b ->
                assertTrue(b.first >= a.first, "Search progress went backwards: $a -> $b")
            }
            // Totals only grow, never shrink mid-refresh.
            searchPairs.zipWithNext { a, b ->
                assertTrue(b.second >= a.second, "Search total shrank: $a -> $b")
            }
        }

    @Test
    @DisplayName("Category removal does not wait for a full refresh mutex")
    fun testCategoryRemovalDoesNotWaitForRefreshMutex() =
        runTest {
            val entries = buildEntries(System.currentTimeMillis())
            (190 until 200).forEach { index ->
                entries[index] =
                    entries[index].copy(
                        categoryKey = "animals",
                        searchQuery = "wildlife animals ambient",
                    )
            }
            val cacheDao = FakeYouTubeCacheDao(entries)
            val sharedPreferences = freshPrefs()
            val searchStarted = CompletableDeferred<Unit>()
            val releaseSearch = CompletableDeferred<Unit>()
            val repository =
                YouTubeSourceRepository(
                    context = mockPackageContext(),
                    cacheDao = cacheDao,
                    watchHistoryDao = FakeYouTubeWatchHistoryDao(),
                    sharedPreferences = sharedPreferences,
                    searcher = BlockingVideoSearcher(searchStarted, releaseSearch),
                    extractor = FakeStreamExtractor(),
                )
            val refreshJob =
                backgroundScope.launch {
                    repository.refreshSearchResults(replaceExistingCache = true)
                }
            searchStarted.await()

            sharedPreferences.edit().putBoolean("yt_category_animals", false).commit()
            try {
                val result =
                    withContext(Dispatchers.Default.limitedParallelism(1)) {
                        withTimeout(5_000L) {
                            repository.applyCategoryDeltaRefresh()
                        }
                    }

                assertEquals(10, result.removedCount)
                assertEquals(190, result.finalCount)
                assertEquals(190, cacheDao.countGoodEntries())
                assertTrue(repository.libraryState.value is YouTubeLibraryState.Idle)
            } finally {
                releaseSearch.complete(Unit)
            }
            refreshJob.join()
        }

    @Test
    @DisplayName("Should fail fast without searching while bot-blocked")
    fun testRefreshFailsFastWhileBlocked() =
        runTest {
            val cacheDao = FakeYouTubeCacheDao(mutableListOf())
            val searcher = FakeVideoSearcher()
            val repository =
                YouTubeSourceRepository(
                    context = mockPackageContext(),
                    cacheDao = cacheDao,
                    watchHistoryDao = FakeYouTubeWatchHistoryDao(),
                    sharedPreferences = freshPrefs(),
                    searcher = searcher,
                    extractor = FakeStreamExtractor(),
                )
            // Block AFTER construction: init() loads persisted state, so a
            // pre-construction block would be (correctly) wiped as stale.
            YouTubeThrottling.noteBotBlock()

            try {
                repository.refreshSearchResults(replaceExistingCache = true)
                fail("Expected YouTubeBotBlockedException")
            } catch (exception: YouTubeBotBlockedException) {
                assertTrue(exception.cooldownMinutes in 1..45)
            }
            assertEquals(0, searcher.searchCalls)
            assertEquals(0, cacheDao.countGoodEntries())
        }

    @Test
    @DisplayName("Should keep cache and notify while bot-blocked on rebuild")
    fun testRebuildKeepsCacheWhileBlocked() =
        runTest {
            val cacheDao = FakeYouTubeCacheDao(buildEntries(System.currentTimeMillis()))
            val repository =
                YouTubeSourceRepository(
                    context = mockPackageContext(),
                    cacheDao = cacheDao,
                    watchHistoryDao = FakeYouTubeWatchHistoryDao(),
                    sharedPreferences = freshPrefs(),
                    searcher = FakeVideoSearcher(),
                    extractor = FakeStreamExtractor(),
                )
            YouTubeThrottling.noteBotBlock()

            val events = mutableListOf<YouTubeSourceRepository.RefreshEvent>()
            val collector =
                backgroundScope.launch {
                    repository.refreshEvents.collect { events += it }
                }
            // Park the collector in collect() BEFORE the rebuild emits.
            runCurrent()

            repository.triggerFullLibraryRebuild()

            runCurrent()
            collector.cancel()
            assertTrue(events.filterIsInstance<YouTubeSourceRepository.RefreshEvent.BotBlocked>().size == 1)
            assertEquals(200, cacheDao.countGoodEntries())
        }

    @Test
    @DisplayName("Should throw BotBlocked without network while blocked with blank URL")
    fun testBlockedResolveMakesNoNetworkCalls() =
        runTest {
            val metadataEntry =
                YouTubeCacheEntity(
                    videoId = "metavideo1",
                    videoPageUrl = "https://www.youtube.com/watch?v=metavideo1",
                    streamUrl = "",
                    title = "Ambient forest real footage",
                    uploaderName = "Fake Nature Channel",
                    durationSeconds = 600,
                    categoryKey = "nature",
                    streamUrlExpiresAt = 0L,
                    searchCachedAt = System.currentTimeMillis(),
                    searchQuery = "4K aerial nature ambient",
                )
            val cacheDao = FakeYouTubeCacheDao(mutableListOf(metadataEntry))
            val extractor = FakeStreamExtractor()
            val repository =
                YouTubeSourceRepository(
                    context = mockPackageContext(),
                    cacheDao = cacheDao,
                    watchHistoryDao = FakeYouTubeWatchHistoryDao(),
                    sharedPreferences = freshPrefs(),
                    searcher = FakeVideoSearcher(),
                    extractor = extractor,
                )
            YouTubeThrottling.noteBotBlock()

            try {
                repository.resolveVideoPlayback("https://www.youtube.com/watch?v=metavideo1")
                fail("Expected YouTubeBotBlockedException")
            } catch (exception: YouTubeBotBlockedException) {
                // Expected: fail fast, no 25s burn.
            }
            assertEquals(0, extractor.extractionCalls)
            // Blank-URL guard must not poison the row for later retry.
            assertEquals(1, cacheDao.countGoodEntries())
        }

    @Test
    @DisplayName("Should preserve Projectivy YouTube UHD quality targets")
    fun testProjectivyPlaybackResolutionQualityFor() {        assertEquals("2160p", YouTubeSourceRepository.projectivyPlaybackResolutionQualityFor("best"))
        assertEquals("2160p", YouTubeSourceRepository.projectivyPlaybackResolutionQualityFor("2160p"))
        assertEquals("1440p", YouTubeSourceRepository.projectivyPlaybackResolutionQualityFor("1440p"))
        assertEquals("1080p", YouTubeSourceRepository.projectivyPlaybackResolutionQualityFor("1080p"))
        assertEquals("720p", YouTubeSourceRepository.projectivyPlaybackResolutionQualityFor("720p"))
        assertEquals("2160p", YouTubeSourceRepository.projectivyPlaybackResolutionQualityFor("  "))
    }

    private fun streamSignature(quality: String): String =
        "$quality|videoOnly=true|selector=v${YouTubeSourceRepository.STREAM_SELECTION_STRATEGY_VERSION}"

    private fun buildEntries(now: Long): MutableList<YouTubeCacheEntity> =
        (1..200).map { index ->
            YouTubeCacheEntity(
                videoId = "video$index",
                videoPageUrl = "https://www.youtube.com/watch?v=video$index",
                streamUrl = "https://cdn.example.com/video$index.mp4",
                title = "Ambient nature video $index",
                uploaderName = "channel$index",
                durationSeconds = 600,
                categoryKey = "nature",
                streamUrlExpiresAt = now + 86_400_000L,
                searchCachedAt = now,
                searchQuery = "4K aerial nature ambient",
                isBad = false,
                lastPlayedAt = 0L,
            )
        }.toMutableList()

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

    private fun mockPackageContext(): Context {        val packageManager = mockk<PackageManager>()
        val packageInfo = mockk<PackageInfo>()
        every { packageInfo.longVersionCode } returns 1L
        every { packageManager.getPackageInfo(any<String>(), any<Int>()) } returns packageInfo

        val context = mockk<Context>()
        every { context.packageManager } returns packageManager
        every { context.packageName } returns "com.naveen.aerialviewsplus"
        return context
    }

        private class FakeVideoSearcher : VideoSearcher {        private var counter = 0
        var searchCalls = 0
            private set

        override suspend fun searchVideos(
            query: String,
            category: QueryFormulaEngine.ContentCategory?,
        ): List<StreamInfoItem> {
            searchCalls += 1
            return (1..30).map {
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
    }

    private class FixedIdSearcher(
        private val videoIds: List<String>,
    ) : VideoSearcher {
        override suspend fun searchVideos(
            query: String,
            category: QueryFormulaEngine.ContentCategory?,
        ): List<StreamInfoItem> =
            videoIds.map { videoId ->
                StreamInfoItem(
                    0,
                    "https://www.youtube.com/watch?v=$videoId",
                    "Ambient video $videoId",
                    StreamType.VIDEO_STREAM,
                ).apply {
                    uploaderName = "Channel $videoId"
                    setDuration(600L)
                }
            }
    }

    private class BlockingVideoSearcher(
        private val started: CompletableDeferred<Unit>,
        private val release: CompletableDeferred<Unit>,
    ) : VideoSearcher {
        override suspend fun searchVideos(
            query: String,
            category: QueryFormulaEngine.ContentCategory?,
        ): List<StreamInfoItem> {
            started.complete(Unit)
            release.await()
            return emptyList()
        }
    }

    private class ScarceVideoSearcher : VideoSearcher {        // Always the same 2 videos: forces every fallback pool to run.
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
        var extractionCalls = 0
            private set

        override suspend fun extractPlaybackStreams(
            videoPageUrl: String,
            preferredQuality: String,
            preferVideoOnly: Boolean,
            allowAdaptiveManifests: Boolean,
            preferAdaptiveManifests: Boolean,
            preferManifests: Boolean,
        ): YouTubePlaybackUrls {
            extractionCalls += 1
            val videoId = videoPageUrl.substringAfter("v=").substringBefore("&").ifBlank { "unknown" }
            return YouTubePlaybackUrls(videoUrl = "https://cdn.example.com/$videoId.mp4")
        }
    }

}