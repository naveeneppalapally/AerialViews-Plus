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
import kotlinx.coroutines.launch
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

    private class FakeVideoSearcher : VideoSearcher {
        private var counter = 0
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