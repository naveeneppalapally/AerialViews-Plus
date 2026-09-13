package com.neilturner.aerialviews.providers.youtube

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.ZonedDateTime
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.localization.DateWrapper
import org.schabi.newpipe.extractor.services.youtube.ItagItem
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream

@DisplayName("NewPipe Helper Tests")
internal class NewPipeHelperTest {
    @Test
    @DisplayName("Should treat dramatic pipe-separated titles as human content")
    fun testDramaticPipeSeparatedTitlesRejected() {
        assertTrue(NewPipeHelper.isLikelyHumanContentForTest("Snow Leopard fight Mountain Goat | Wildlife Documentary"))
        assertTrue(NewPipeHelper.isLikelyHumanContentForTest("Unbelievable | Cliff Chase | Amazing footage"))
    }

    @Test
    @DisplayName("Should allow ambient pipe-separated titles")
    fun testAmbientPipeSeparatedTitlesAllowed() {
        assertFalse(NewPipeHelper.isLikelyHumanContentForTest("Japan 4K | Nature Walk | Ambient Sounds"))
        assertFalse(NewPipeHelper.isLikelyHumanContentForTest("Norway Fjords | Aerial 4K | No Music"))
    }

    @Test
    @DisplayName("Should reject documentary-style titles")
    fun testDocumentaryTitleRejected() {
        assertTrue(NewPipeHelper.isLikelyHumanContentForTest("Wildlife Documentary Animals | Nature Film"))
    }

    @Test
    @DisplayName("Should allow ambient titles through filter")
    fun testAmbientTitlesAllowed() {
        assertFalse(NewPipeHelper.isLikelyHumanContentForTest("4K Japan Forest Walk Ambient No Music"))
        assertFalse(NewPipeHelper.isLikelyHumanContentForTest("Norwegian Fjords Aerial Drone 4K"))
    }

    @Test
    @DisplayName("Should prefer same-resolution AVC when 4K VP9 is unsupported")
    fun testSameResolutionCodecFallbackPreferred() {
        val selected =
            NewPipeHelper.selectBestVideoStreamForTest(
                streams =
                    listOf(
                        videoStream(
                            itag = 401,
                            codec = "vp09.00.51.08",
                            resolution = "2160p",
                            height = 2160,
                            bitrate = 15_000_000,
                            mediaFormat = MediaFormat.WEBM,
                        ),
                        videoStream(
                            itag = 266,
                            codec = "avc1.640033",
                            resolution = "2160p",
                            height = 2160,
                            bitrate = 9_000_000,
                            mediaFormat = MediaFormat.MPEG_4,
                        ),
                        videoStream(
                            itag = 137,
                            codec = "avc1.640028",
                            resolution = "1080p",
                            height = 1080,
                            bitrate = 6_000_000,
                            mediaFormat = MediaFormat.MPEG_4,
                        ),
                    ),
                targetHeight = 2160,
                supportedItags = setOf(266, 137),
                unsupportedItags = setOf(401),
            )

        assertEquals(266, selected?.getItag())
    }

    @Test
    @DisplayName("Should fall back to lower supported codec when same-resolution fallback is absent")
    fun testLowerResolutionFallbackWhenNeeded() {
        val selected =
            NewPipeHelper.selectBestVideoStreamForTest(
                streams =
                    listOf(
                        videoStream(
                            itag = 401,
                            codec = "vp09.00.51.08",
                            resolution = "2160p",
                            height = 2160,
                            bitrate = 15_000_000,
                            mediaFormat = MediaFormat.WEBM,
                        ),
                        videoStream(
                            itag = 137,
                            codec = "avc1.640028",
                            resolution = "1080p",
                            height = 1080,
                            bitrate = 6_000_000,
                            mediaFormat = MediaFormat.MPEG_4,
                        ),
                    ),
                targetHeight = 2160,
                supportedItags = setOf(137),
                unsupportedItags = setOf(401),
            )

        assertEquals(137, selected?.getItag())
    }

    @Test
    @DisplayName("Should prefer a stronger 1440p stream over a weak 4K stream")
    fun testPreferStrongerLowerResolutionWhen4kBitrateIsWeak() {
        val selected =
            NewPipeHelper.selectBestVideoStreamForTest(
                streams =
                    listOf(
                        videoStream(
                            itag = 401,
                            codec = "vp09.00.51.08",
                            resolution = "2160p",
                            height = 2160,
                            bitrate = 8_000_000,
                            mediaFormat = MediaFormat.WEBM,
                        ),
                        videoStream(
                            itag = 271,
                            codec = "vp09.00.50.08",
                            resolution = "1440p",
                            height = 1440,
                            bitrate = 13_000_000,
                            mediaFormat = MediaFormat.WEBM,
                        ),
                        videoStream(
                            itag = 137,
                            codec = "avc1.640028",
                            resolution = "1080p",
                            height = 1080,
                            bitrate = 6_000_000,
                            mediaFormat = MediaFormat.MPEG_4,
                        ),
                    ),
                targetHeight = 2160,
                supportedItags = setOf(401, 271, 137),
            )

        assertEquals(271, selected?.getItag())
    }

    @Test
    @DisplayName("Should detect explicit text-overlay titles")
    fun testDetectTextOverlayTitle() {
        assertTrue(
            NewPipeHelper.isLikelyHumanContentForTest(
                title = "Forest walk with captions",
                uploader = "Nature Footage",
                durationSeconds = 900,
            ),
        )
    }

    @Test
    @DisplayName("Should reject short educational channels for ambient results")
    fun testDetectEducationalShortVideo() {
        assertTrue(
            NewPipeHelper.isLikelyHumanContentForTest(
                title = "Storm clouds over mountains",
                uploader = "Science Explained",
                durationSeconds = 420,
            ),
        )
    }

    @Test
    @DisplayName("Should keep long-form ambient footage")
    fun testAllowAmbientNatureVideo() {
        assertFalse(
            NewPipeHelper.isLikelyHumanContentForTest(
                title = "4K Forest Canopy Ambient Nature Sounds",
                uploader = "Nature Focus",
                durationSeconds = 1800,
            ),
        )
    }

    @Test
    @DisplayName("Should reject fast-motion titles for ambient results")
    fun testRejectFastMotionVideo() {
        assertTrue(
            NewPipeHelper.isLikelyHumanContentForTest(
                title = "4K Tokyo Skyline Timelapse",
                uploader = "City Motion",
                durationSeconds = 600,
            ),
        )
    }

    @Test
    @DisplayName("Should reject subtitle-heavy titles")
    fun testRejectSubtitleHeavyTitle() {
        assertTrue(
            NewPipeHelper.isLikelyHumanContentForTest(
                title = "Aurora over Iceland with subtitles",
                uploader = "Sky Stories",
                durationSeconds = 900,
            ),
        )
    }

    @Test
    @DisplayName("Should accept 16:9 aspect ratios")
    fun testAcceptWideAspectRatio() {
        assertTrue(NewPipeHelper.hasPreferredAspectRatioForTest("1920x1080"))
    }

    @Test
    @DisplayName("Should reject obvious 4:3 aspect ratios")
    fun testRejectFourByThreeAspectRatio() {
        assertFalse(NewPipeHelper.hasPreferredAspectRatioForTest("1440x1080"))
    }

    @Test
    @DisplayName("Should allow frozen titles despite the zen word gate")
    fun testFrozenTitleAllowedDespiteZen() {
        assertFalse(NewPipeHelper.isLikelySyntheticWallpaperForTest("4K Frozen Waterfall Winter"))
        assertFalse(
            NewPipeHelper.isLikelyHumanContentForTest(
                title = "4K Frozen Waterfall Winter",
                uploader = "Nature Focus",
                durationSeconds = 900,
            ),
        )
    }

    @Test
    @DisplayName("Should reject zen garden slideshows by word match")
    fun testZenGardenRejected() {
        assertTrue(NewPipeHelper.isLikelySyntheticWallpaperForTest("Zen Garden Meditation"))
    }

    @Test
    @DisplayName("Should reject forecast and presenter titles but keep storm footage")
    fun testForecastRejectedStormAllowed() {
        assertTrue(
            NewPipeHelper.isLikelyHumanContentForTest(
                title = "10-Day Forecast: Heat Dome Settles In",
                uploader = "FOX Weather",
            ),
        )
        assertTrue(
            NewPipeHelper.isLikelyHumanContentForTest(
                title = "Storm Damage Explained by Our Meteorologist",
                uploader = "Weather Channel",
            ),
        )
        assertFalse(
            NewPipeHelper.isLikelyHumanContentForTest(
                title = "Storm Clouds Over Mountains Real Footage",
                uploader = "Sky Focus",
                durationSeconds = 900,
            ),
        )
    }

    @Test
    @DisplayName("Should reject sleep music compilations")
    fun testSleepMusicRejected() {
        assertTrue(
            NewPipeHelper.isLikelyHumanContentForTest(
                title = "3 Hours Relaxing Piano Music for Sleep",
                uploader = "Soothing Sounds",
            ),
        )
    }

    @Test
    @DisplayName("Should flag AI-branded uploaders and self-disclosing descriptions")
    fun testAiUploaderAndDescription() {
        assertTrue(
            NewPipeHelper.isLikelyAiForTest(
                searchItem(
                    title = "Beautiful Mountain Landscape",
                    uploader = "AI Scenics",
                ),
            ),
        )
        assertTrue(
            NewPipeHelper.isLikelyAiForTest(
                searchItem(
                    title = "Beautiful Mountain Landscape",
                    uploader = "Nature Focus",
                    description = "Created with AI for relaxation",
                ),
            ),
        )
        assertFalse(
            NewPipeHelper.isLikelyAiForTest(
                searchItem(
                    title = "Beautiful Mountain Landscape",
                    uploader = "Nature Focus",
                ),
            ),
        )
    }

    @Test
    @DisplayName("Should allow airport runway footage despite the Runway tool name")
    fun testRunwayAirportAllowed() {
        assertFalse(
            NewPipeHelper.isLikelyAiForTest(
                searchItem(
                    title = "St. Maarten Airport Runway 4K Aerial",
                    uploader = "Aviation Daily",
                ),
            ),
        )
    }

    @Test
    @DisplayName("Should allow American Pika wildlife despite the Pika tool name")
    fun testPikaMammalAllowed() {
        assertFalse(
            NewPipeHelper.isLikelyAiForTest(
                searchItem(
                    title = "American Pika in the Rockies",
                    uploader = "Wildlife Films",
                ),
            ),
        )
        assertTrue(
            NewPipeHelper.isLikelyAiForTest(
                searchItem(
                    title = "Beautiful Mountain Landscape",
                    uploader = "Pika Labs",
                ),
            ),
        )
    }

    @Test
    @DisplayName("Should gate generator names by word boundary in titles")
    fun testGeneratorWordBoundary() {
        assertTrue(
            NewPipeHelper.isLikelyAiForTest(
                searchItem(title = "Kling Mountain Dream 4K", uploader = "Nature Focus"),
            ),
        )
        assertFalse(
            NewPipeHelper.isLikelyAiForTest(
                searchItem(title = "Live Ocean Waves 4K", uploader = "Nature Focus"),
            ),
        )
    }

    @Test
    @DisplayName("Should catch AI branding glued into handles")
    fun testGluedHandleDetection() {
        assertTrue(
            NewPipeHelper.isLikelyAiForTest(
                searchItem(
                    title = "Beautiful Mountain Landscape",
                    uploader = "Scenic Views",
                    uploaderUrl = "https://www.youtube.com/@SoraScenics",
                ),
            ),
        )
        assertFalse(
            NewPipeHelper.isLikelyAiForTest(
                searchItem(
                    title = "Beautiful Mountain Landscape",
                    uploader = "John Smith",
                    uploaderUrl = "https://www.youtube.com/@johnsmith4821",
                ),
            ),
        )
    }

    @Test
    @DisplayName("Should only flag low traction as a young-and-tiny combo")
    fun testTractionCombo() {
        // 500 views at 10 days: a small real pilot, must survive.
        assertFalse(
            NewPipeHelper.isLikelyAiForTest(
                searchItem(
                    title = "Dolomites FPV Flight",
                    uploader = "Solo Pilot",
                    viewCount = 500L,
                    uploadDaysAgo = 10L,
                ),
            ),
        )
        // 50 views at 10 days, unverified: slop-shaped.
        assertTrue(
            NewPipeHelper.isLikelyAiForTest(
                searchItem(
                    title = "Beautiful Mountain Landscape",
                    uploader = "Scenic Views",
                    viewCount = 50L,
                    uploadDaysAgo = 10L,
                ),
            ),
        )
        // Verified channels are exempt from traction entirely.
        assertFalse(
            NewPipeHelper.isLikelyAiForTest(
                searchItem(
                    title = "Beautiful Mountain Landscape",
                    uploader = "BBC Earth",
                    viewCount = 50L,
                    uploadDaysAgo = 10L,
                    verified = true,
                ),
            ),
        )
    }

    @Test
    @DisplayName("Should reject non-VOD formats and Shorts at filter time")
    fun testFormatGate() {        assertFalse(
            NewPipeHelper.hasPlayableFormatForTest(
                searchItem(
                    title = "Live Nature Radio",
                    uploader = "Nature Focus",
                    streamType = StreamType.LIVE_STREAM,
                ),
            ),
        )
        assertFalse(
            NewPipeHelper.hasPlayableFormatForTest(
                searchItem(
                    title = "Quick Waterfall Clip",
                    uploader = "Nature Focus",
                    durationSeconds = 30L,
                ),
            ),
        )
        assertTrue(
            NewPipeHelper.hasPlayableFormatForTest(
                searchItem(
                    title = "Forest Walk Real Footage",
                    uploader = "Nature Focus",
                    durationSeconds = 900L,
                ),
            ),
        )
    }

    private fun searchItem(
        title: String,
        uploader: String,
        durationSeconds: Long = 600L,
        streamType: StreamType = StreamType.VIDEO_STREAM,
        description: String? = null,
        viewCount: Long = -1L,
        uploadDaysAgo: Long? = null,
        uploaderUrl: String? = null,
        verified: Boolean = false,
    ): StreamInfoItem =
        StreamInfoItem(
            0,
            "https://www.youtube.com/watch?v=testvideoid1",
            title,
            streamType,
        ).apply {
            uploaderName = uploader
            setDuration(durationSeconds)
            description?.let { setShortDescription(it) }
            setViewCount(viewCount)
            uploadDaysAgo?.let {
                setUploadDate(
                    DateWrapper(
                        ZonedDateTime.now().minusDays(it).toInstant(),
                    ),
                )
            }
            uploaderUrl?.let { setUploaderUrl(it) }
            setUploaderVerified(verified)
        }

    private fun videoStream(
        itag: Int,
        codec: String,
        resolution: String,
        height: Int,
        bitrate: Int,
        mediaFormat: MediaFormat,
    ): VideoStream {
        val itagItem = ItagItem(itag, ItagItem.ItagType.VIDEO_ONLY, mediaFormat, resolution)
        itagItem.setWidth((height * 16f / 9f).toInt())
        itagItem.setHeight(height)
        itagItem.setBitrate(bitrate)
        itagItem.setQuality(resolution)
        itagItem.setCodec(codec)

        return VideoStream.Builder()
            .setId(itag.toString())
            .setContent("https://example.com/$itag", true)
            .setMediaFormat(mediaFormat)
            .setIsVideoOnly(true)
            .setResolution(resolution)
            .setItagItem(itagItem)
            .build()
    }
}