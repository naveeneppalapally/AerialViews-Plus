package com.neilturner.aerialviews.providers.youtube

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecList
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.WindowManager
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request.Builder
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.linkhandler.LinkHandler
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.ContentAvailability
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream
import java.net.URLEncoder
import java.io.IOException
import java.time.ZonedDateTime
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

object NewPipeHelper {
    private const val TAG = "NewPipeHelper"
    private const val USER_AGENT =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    private const val REFERER = "https://www.youtube.com/"
    private const val ORIGIN = "https://www.youtube.com"

    data class PlaybackStreams(
        val videoUrl: String,
        val audioUrl: String = "",
    )

    @Volatile
    private var initialized = false

    private val httpClient by lazy {
        OkHttpClient
            .Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    fun init() {
        if (initialized) {
            return
        }

        synchronized(this) {
            if (initialized) {
                return
            }

            NewPipe.init(OkHttpDownloader(httpClient))
            initialized = true
        }
    }

    suspend fun searchVideos(
        query: String,
        category: QueryFormulaEngine.ContentCategory? = null,
    ): List<StreamInfoItem> =
        withContext(Dispatchers.IO) {
            init()

            try {
                val searchInfo = loadSearchInfo(query)
                val baseCandidates = buildSearchCandidates(searchInfo)
                selectSearchResults(category, baseCandidates)
            } catch (exception: Exception) {
                Timber.tag(TAG).w(exception, "Failed to search YouTube for \"%s\"", query)
                throw YouTubeExtractionException(
                    "Failed to search YouTube for \"$query\"",
                    exception,
                )
            }
        }

    suspend fun extractStreamUrl(
        videoPageUrl: String,
        context: Context,
        preferredQuality: String = "1080p",
        preferVideoOnly: Boolean = false,
        allowAdaptiveManifests: Boolean = true,
        preferAdaptiveManifests: Boolean = false,
        preferManifests: Boolean = false,
    ): String =
        extractPlaybackStreams(
            videoPageUrl = videoPageUrl,
            context = context,
            preferredQuality = preferredQuality,
            preferVideoOnly = preferVideoOnly,
            allowAdaptiveManifests = allowAdaptiveManifests,
            preferAdaptiveManifests = preferAdaptiveManifests,
            preferManifests = preferManifests,
        ).videoUrl

    suspend fun extractPlaybackStreams(
        videoPageUrl: String,
        context: Context,
        preferredQuality: String = "1080p",
        preferVideoOnly: Boolean = false,
        allowAdaptiveManifests: Boolean = true,
        preferAdaptiveManifests: Boolean = false,
        preferManifests: Boolean = false,
    ): PlaybackStreams =
        withContext(Dispatchers.IO) {
            init()

            try {
                loadPlayableStreamUrls(
                    videoPageUrl = videoPageUrl,
                    context = context,
                    preferredQuality = preferredQuality,
                    preferVideoOnly = preferVideoOnly,
                    allowAdaptiveManifests = allowAdaptiveManifests,
                    preferAdaptiveManifests = preferAdaptiveManifests,
                    preferManifests = preferManifests,
                )
            } catch (exception: AgeRestrictedContentException) {
                Timber.tag(TAG).d("Age-restricted stream rejected: %s", videoPageUrl)
                throw exception
            } catch (exception: SignInConfirmNotBotException) {
                // IP-level bot gate, not a bad video: open the circuit breaker
                // so callers back off instead of hammering (which extends it).
                YouTubeThrottling.noteBotBlock()
                Timber.tag(TAG).w("YouTube bot gate for %s, cooling down", videoPageUrl)
                throw exception
            } catch (exception: GeographicRestrictionException) {
                Timber.tag(TAG).d("Geo-blocked stream rejected: %s", videoPageUrl)
                throw exception
            } catch (exception: ContentNotAvailableException) {
                Timber.tag(TAG).d("Unavailable stream rejected: %s", videoPageUrl)
                throw exception
            } catch (exception: ExtractionException) {
                Timber.tag(TAG).w(exception, "NewPipe extraction failed for %s", videoPageUrl)
                throw exception
            } catch (exception: YouTubeExtractionException) {
                throw exception
            } catch (exception: Exception) {
                Timber.tag(TAG).w(exception, "Unexpected stream extraction failure for %s", videoPageUrl)
                throw YouTubeExtractionException(
                    "Failed to extract a playable stream for $videoPageUrl",
                    exception,
                )
            }
        }

    private fun loadSearchInfo(query: String): SearchInfo {
        val service = NewPipe.getService("YouTube")
        val searchUrl = buildSearchUrl(query, YoutubeSearchQueryHandlerFactory.VIDEOS)
        return SearchInfo.getInfo(
            service,
            SearchQueryHandler(
                searchUrl,
                searchUrl,
                query,
                listOf(YoutubeSearchQueryHandlerFactory.VIDEOS),
                "",
            ),
        )
    }

    private fun buildSearchCandidates(
        searchInfo: SearchInfo,
    ): List<StreamInfoItem> {
        val rawCandidates = searchInfo.relatedItems.filterIsInstance<StreamInfoItem>()
        val withMetadata = rawCandidates.filter(::hasUsableMetadata)
        Log.i(TAG, "YouTube search candidates: raw=${rawCandidates.size} metadata=${withMetadata.size}")

        val playableFormat = withMetadata.filter(::hasPlayableFormat)
        Log.i(TAG, "After format filter: ${playableFormat.size} passed")

        val afterAiFilter = playableFormat.filterNot(::isLikelyAI)
        Log.i(TAG, "After AI filter: ${afterAiFilter.size} passed")

        val afterHumanFilter =
            afterAiFilter.filterNot { item ->
                isHumanContent(
                    title = item.getName(),
                    uploader = item.getUploaderName().orEmpty(),
                    durationSeconds = item.getDuration().takeIf { duration -> duration > 0L },
                )
            }
        Log.i(TAG, "After human filter: ${afterHumanFilter.size} passed")

        val afterSyntheticFilter =
            afterHumanFilter.filterNot { item ->
                isLikelySyntheticWallpaperTitle(item.getName().lowercase(Locale.US))
            }
        Log.i(TAG, "After synthetic filter: ${afterSyntheticFilter.size} passed")

        val afterRecency = afterSyntheticFilter.filter(::isRecentEnough)
        Log.i(TAG, "After recency filter: ${afterRecency.size} passed")
        return afterRecency
    }

    private fun selectSearchResults(
        category: QueryFormulaEngine.ContentCategory?,
        baseCandidates: List<StreamInfoItem>,
    ): List<StreamInfoItem> {
        // Dedup by videoId only. A previous second pass deduped by normalized
        // title fingerprint, which dropped distinct videos sharing generic
        // titles ("4K Nature Relaxation Film") and shrank the pool so far that
        // the healthy-pool fallbacks refetched needlessly.
        return baseCandidates
            .asSequence()
            .distinctBy { extractVideoId(it.getUrl()) ?: it.getUrl() }
            .sortedByDescending { candidate ->
                val titleLower = candidate.getName().lowercase(Locale.US)
                QueryFormulaEngine.categoryMatchScore(
                    title = candidate.getName(),
                    uploader = candidate.getUploaderName().orEmpty(),
                    category = category,
                ) + preferredContentScore(titleLower) + slowPacedContentScore(titleLower) - fastMotionPenalty(titleLower)
            }
            .take(MAX_RESULTS_PER_QUERY)
            .toList()
    }

    private fun hasUsableMetadata(item: StreamInfoItem): Boolean =
        item.getUrl().isNotBlank() && item.getName().isNotBlank()

    /**
     * Format gate: only plain VOD belongs in an ambient cache. Live/upcoming/
     * members-only/paid items fail later at extraction (wasted work), and
     * Shorts are vertical — both rejected here instead.
     */
    private fun hasPlayableFormat(item: StreamInfoItem): Boolean {
        if (item.getStreamType() != StreamType.VIDEO_STREAM) {
            return false
        }
        if (item.isShortFormContent()) {
            return false
        }
        // Shorts backstop: some layouts don't flag them (duration -1 there).
        val duration = item.getDuration()
        if (duration in 1..59) {
            return false
        }
        return when (item.getContentAvailability()) {
            ContentAvailability.UPCOMING,
            ContentAvailability.MEMBERSHIP,
            ContentAvailability.PAID,
            -> false
            else -> true
        }
    }

    private fun isFilteredCandidate(item: StreamInfoItem): Boolean {
        val titleLower = item.getName().lowercase(Locale.US)
        return isLikelyAI(item) ||
            isHumanContent(
                item.getName(),
                item.getUploaderName().orEmpty(),
                item.getDuration().takeIf { duration -> duration > 0L },
            ) ||
            isLikelySyntheticWallpaperTitle(titleLower)
    }

    private fun loadPlayableStreamUrls(
        videoPageUrl: String,
        context: Context,
        preferredQuality: String,
        preferVideoOnly: Boolean,
        allowAdaptiveManifests: Boolean,
        preferAdaptiveManifests: Boolean,
        preferManifests: Boolean,
    ): PlaybackStreams {
        val service = NewPipe.getService("YouTube")
        val videoId =
            extractVideoId(videoPageUrl)
                ?: throw YouTubeExtractionException("Could not extract a video ID from $videoPageUrl")
        val streamExtractor =
            service.getStreamExtractor(
                LinkHandler(
                    videoPageUrl,
                    videoPageUrl,
                    videoId,
                ),
            )
        streamExtractor.fetchPage()
        val allStreams = streamExtractor.videoStreams + streamExtractor.videoOnlyStreams
        if (!hasPreferredAspectRatioCandidate(allStreams)) {
            throw YouTubeExtractionException("Rejected non-16:9 YouTube video for $videoPageUrl")
        }
        val screenHeight = getScreenHeight(context)

        return selectBestStreamUrl(
            progressiveStreams = streamExtractor.videoStreams,
            videoOnlyStreams = streamExtractor.videoOnlyStreams,
            audioStreams = streamExtractor.audioStreams,
            dashUrl = streamExtractor.dashMpdUrl,
            hlsUrl = streamExtractor.hlsUrl,
            screenHeight = screenHeight,
            preferredQuality = preferredQuality,
            preferVideoOnly = preferVideoOnly,
            allowAdaptiveManifests = allowAdaptiveManifests,
            preferAdaptiveManifests = preferAdaptiveManifests,
            preferManifests = preferManifests,
        )?.takeIf { playback -> playback.videoUrl.isNotBlank() }
            ?: throw YouTubeExtractionException("No playable stream found for $videoPageUrl")
    }

    private fun selectBestStreamUrl(
        progressiveStreams: List<VideoStream>,
        videoOnlyStreams: List<VideoStream>,
        audioStreams: List<AudioStream>,
        dashUrl: String?,
        hlsUrl: String?,
        screenHeight: Int,
        preferredQuality: String,
        preferVideoOnly: Boolean,
        allowAdaptiveManifests: Boolean,
        preferAdaptiveManifests: Boolean,
        preferManifests: Boolean,
    ): PlaybackStreams? {
        // Adaptive-first (like the official YouTube/TizenTube player): a DASH
        // manifest starts instantly at a low rendition and ramps to the best
        // sustainable quality, instead of gambling one fixed progressive file
        // that either stalls (too big) or looks soft (too small).
        if (preferManifests && allowAdaptiveManifests) {
            dashUrl?.takeIf { it.isNotBlank() }?.let {
                Log.i(TAG, "STREAM PICKED: DASH manifest (adaptive)")
                return PlaybackStreams(videoUrl = it)
            }
            hlsUrl?.takeIf { it.isNotBlank() }?.let {
                Log.i(TAG, "STREAM PICKED: HLS manifest (adaptive)")
                return PlaybackStreams(videoUrl = it)
            }
        }
        val effectiveScreenHeight =
            if (isAmlogicDevice() && screenHeight in 1 until 1080) {
                1080
            } else {
                screenHeight
            }
        val screenTargetHeight = targetHeightForScreen(effectiveScreenHeight)
        val qualityTargetHeight = preferredHeightForQuality(preferredQuality)
        // "best" (Highest Available) means the best this screen can show, not
        // unconditionally 4K: a 1080p TV must not download 2160p files it can
        // never display. Explicit pins below still behave exactly as before.
        val targetHeight =
            if (preferredQuality.trim().equals("best", ignoreCase = true)) {
                minOf(screenTargetHeight, 2160)
            } else if (qualityTargetHeight > 0) {
                qualityTargetHeight
            } else {
                screenTargetHeight
            }
        val minimumFallbackHeight = minimumAllowedHeight(targetHeight)
        Log.i(
            TAG,
            "Screen: ${screenHeight}p (effective=${effectiveScreenHeight}p, screenTarget=${screenTargetHeight}p), quality=${preferredQuality}, targeting: ${targetHeight}p",
        )
        val playableProgressiveStreams =
            progressiveStreams.filter { !it.isVideoOnly() && it.isUrl() && it.getContent().isNotBlank() }
        val playableVideoOnlyStreams =
            videoOnlyStreams.filter { it.isUrl() && it.getContent().isNotBlank() }
        val playableAnyStreams = (playableProgressiveStreams + playableVideoOnlyStreams)
        val primaryStreams =
            if (preferVideoOnly && playableVideoOnlyStreams.isNotEmpty()) {
                playableVideoOnlyStreams
            } else {
                playableProgressiveStreams
            }
        val secondaryStreams =
            if (primaryStreams === playableVideoOnlyStreams) {
                playableProgressiveStreams
            } else {
                playableVideoOnlyStreams
            }
        Timber.tag(TAG).i(
            "Evaluating YouTube streams (preferred=%s, progressive=%s, videoOnly=%s, mode=%s)",
            preferredQuality,
            playableProgressiveStreams.size,
            playableVideoOnlyStreams.size,
            if (primaryStreams === playableVideoOnlyStreams) "videoOnlyPreferred" else "progressivePreferred",
        )
        val primarySelection = selectPreferredStream(primaryStreams, targetHeight)
        if (shouldPreferDashManifest(dashUrl, primarySelection, targetHeight, allowAdaptiveManifests, preferAdaptiveManifests)) {
            Log.i(TAG, "STREAM PICKED: DASH manifest preferred for ${targetHeight}p over ${describeStream(primarySelection!!)}")
            return PlaybackStreams(videoUrl = checkNotNull(dashUrl))
        }
        primarySelection?.let { stream ->
            playbackStreamsFor(stream, audioStreams)?.let { playback ->
                logSelectedStream(stream)
                return playback
            }
        }

        val secondarySelection = selectPreferredStream(secondaryStreams, targetHeight)
        if (shouldPreferDashManifest(dashUrl, secondarySelection, targetHeight, allowAdaptiveManifests, preferAdaptiveManifests)) {
            Log.i(TAG, "STREAM PICKED: DASH manifest preferred for ${targetHeight}p over ${describeStream(secondarySelection!!)}")
            return PlaybackStreams(videoUrl = checkNotNull(dashUrl))
        }
        secondarySelection?.let { stream ->
            playbackStreamsFor(stream, audioStreams)?.let { playback ->
                logSelectedStream(stream)
                return playback
            }
        }

        // Fallbacks stay within budget AND above the bitrate floor: a weak
        // high-res file (e.g. 720p at 140kbps) must not beat a strong lower
        // one. Anything weaker falls through to adaptive DASH (which carries
        // proper-bitrate renditions) or skips the video entirely.
        return playableProgressiveStreams
            .filter { stream -> streamHeight(stream) in minimumFallbackHeight..targetHeight }
            .filter(::meetsBitrateFloor)
                .sortedWith(streamQualityComparator())
                .firstOrNull()
                ?.let { stream ->
                Log.w(TAG, "STREAM FALLBACK 1 (quality floor ${minimumFallbackHeight}p): ${describeStream(stream)}")
                PlaybackStreams(videoUrl = stream.getContent())
            }
            ?: playableAnyStreams
                .filter { stream -> streamHeight(stream) in minimumFallbackHeight..targetHeight }
                .filter(::meetsBitrateFloor)
                .sortedWith(streamQualityComparator())
                .firstOrNull()
                ?.let { stream ->
                Log.w(TAG, "STREAM FALLBACK 2 (quality floor ${minimumFallbackHeight}p): ${describeStream(stream)}")
                playbackStreamsFor(stream, audioStreams) ?: PlaybackStreams(videoUrl = stream.getContent())
            }
            ?: dashUrl?.takeIf { allowAdaptiveManifests && it.isNotBlank() }?.let {
                Log.w(TAG, "STREAM FALLBACK 3 (dash): using DASH manifest URL")
                PlaybackStreams(videoUrl = it)
            }
            ?: hlsUrl?.takeIf { allowAdaptiveManifests && it.isNotBlank() }?.let {
                Log.w(TAG, "STREAM FALLBACK 4 (hls): using HLS manifest URL")
                PlaybackStreams(videoUrl = it)
            }
            ?: run {
                Timber.tag(TAG).w(
                    "No playable YouTube stream found for target=%sp (preference=%s progressive=%s videoOnly=%s dash=%s hls=%s preferVideoOnly=%s)",
                    targetHeight,
                    preferredQuality,
                    progressiveStreams.size,
                    videoOnlyStreams.size,
                    !dashUrl.isNullOrBlank(),
                    !hlsUrl.isNullOrBlank(),
                    preferVideoOnly,
                )
                null
            }
    }

    private fun playbackStreamsFor(
        stream: VideoStream,
        audioStreams: List<AudioStream>,
    ): PlaybackStreams? {
        if (!stream.isVideoOnly()) {
            return PlaybackStreams(videoUrl = stream.getContent())
        }

        val audioStream = selectBestAudioStream(audioStreams)
        if (audioStream == null) {
            Timber.tag(TAG).w("No audio-only stream available to merge with %s", describeStream(stream))
            return null
        }

        Log.i(
            TAG,
            "AUDIO PICKED: codec=${audioStream.getCodec()} itag=${audioStream.getItag()} bitrate=${audioBitrate(audioStream)}",
        )
        return PlaybackStreams(
            videoUrl = stream.getContent(),
            audioUrl = audioStream.getContent(),
        )
    }

    private fun selectPreferredStream(
        streams: List<VideoStream>,
        targetHeight: Int,
    ): VideoStream? =
        selectBestVideoStream(streams, targetHeight, allowUnsupportedFallback = false)
            ?: selectBestVideoStream(streams, targetHeight, allowUnsupportedFallback = true)

    private fun shouldPreferDashManifest(
        dashUrl: String?,
        selectedStream: VideoStream?,
        targetHeight: Int,
        allowAdaptiveManifests: Boolean,
        preferAdaptiveManifests: Boolean,
    ): Boolean {
        if (
            !allowAdaptiveManifests ||
            !preferAdaptiveManifests ||
            dashUrl.isNullOrBlank() ||
            selectedStream == null ||
            targetHeight < MIN_DASH_PREFERRED_HEIGHT
        ) {
            return false
        }

        val selectedHeight = streamHeight(selectedStream)
        val selectedCodec = selectedStream.getCodec().orEmpty().lowercase(Locale.US)
        val selectedNeedsAdaptiveHelp =
            selectedHeight < targetHeight ||
                !meetsBitrateFloor(selectedStream) ||
                (selectedHeight >= 2160 && (selectedCodec.contains("vp9") || selectedCodec.contains("av01") || selectedCodec.contains("av1")))

        return selectedNeedsAdaptiveHelp
    }

    private fun selectStreamContent(
        streams: List<VideoStream>,
        targetHeight: Int,
        allowUnsupportedFallback: Boolean,
    ): String? =
        selectBestVideoStream(streams, targetHeight, allowUnsupportedFallback)?.let { stream ->
            logSelectedStream(stream)
            stream.getContent()
        }

    private fun selectBestVideoStream(
        streams: List<VideoStream>,
        targetHeight: Int,
        allowUnsupportedFallback: Boolean = false,
        supportResolver: (CodecFamily, VideoStream) -> DecoderSupport = ::decoderSupport,
    ): VideoStream? {
        val minimumHeight = minimumAllowedHeight(targetHeight)
        val deviceSafeCandidates =
            streams
                .let(::applyDeviceCodecRestrictions)
                .filter { streamHeight(it) > 0 }
                .filter { streamHeight(it) >= minimumHeight }
                .filter { streamHeight(it) <= maxTargetHeight(targetHeight) }
        val aspectRatioCandidates = deviceSafeCandidates.filter(::hasPreferredAspectRatio)
        val sizeConstrainedCandidates = aspectRatioCandidates.ifEmpty { deviceSafeCandidates }
        if (sizeConstrainedCandidates.isEmpty()) {
            Timber.tag(TAG).w(
                "Rejecting YouTube streams because no candidates fit target=%sp min=%sp (available=%s)",
                targetHeight,
                minimumHeight,
                streams.map { stream -> "${streamHeight(stream)}p/itag=${stream.getItag()}" },
            )
            return null
        }

        val preferredCandidates =
            sizeConstrainedCandidates.filter { candidate ->
                streamHeight(candidate) in MIN_PREFERRED_PROGRESSIVE_HEIGHT..targetHeight &&
                    candidate.getItag() !in REJECTED_LOW_QUALITY_ITAGS
            }
        val rankedCandidates =
            preferredCandidates.ifEmpty { sizeConstrainedCandidates }
        val strictPreferredMinimumHeight = strictMinimumPreferredHeight(targetHeight)
        val strictCandidates =
            rankedCandidates.filter { candidate ->
                streamHeight(candidate) >= strictPreferredMinimumHeight
            }
        val supportPriority =
            buildList {
                add(DecoderSupport.SUPPORTED)
                add(DecoderSupport.UNKNOWN)
                if (allowUnsupportedFallback) {
                    add(DecoderSupport.UNSUPPORTED)
                }
            }

        pickBestFromSupportTiers(strictCandidates, targetHeight, supportPriority, supportResolver)?.let { return it }
        if (strictCandidates.size != rankedCandidates.size) {
            pickBestFromSupportTiers(rankedCandidates, targetHeight, supportPriority, supportResolver)?.let { return it }
        }

        return null
    }

    internal fun selectBestVideoStreamForTest(
        streams: List<VideoStream>,
        targetHeight: Int,
        allowUnsupportedFallback: Boolean = false,
        supportedItags: Set<Int> = emptySet(),
        unsupportedItags: Set<Int> = emptySet(),
    ): VideoStream? =
        selectBestVideoStream(
            streams = streams,
            targetHeight = targetHeight,
            allowUnsupportedFallback = allowUnsupportedFallback,
            supportResolver = { _, stream ->
                when (stream.getItag()) {
                    in supportedItags -> DecoderSupport.SUPPORTED
                    in unsupportedItags -> DecoderSupport.UNSUPPORTED
                    else -> DecoderSupport.UNKNOWN
                }
            },
        )

    internal fun selectBestAudioStreamForTest(audioStreams: List<AudioStream>): AudioStream? =
        selectBestAudioStream(audioStreams)

    private fun selectBestAudioStream(audioStreams: List<AudioStream>): AudioStream? =
        audioStreams
            .filter { stream -> stream.isUrl() && stream.getContent().isNotBlank() }
            .sortedWith(audioStreamComparator())
            .firstOrNull()

    private fun audioStreamComparator(): Comparator<AudioStream> =
        compareBy<AudioStream> { audioCodecPriorityIndex(it) }
            .thenByDescending(::audioBitrate)

    private fun audioCodecPriorityIndex(stream: AudioStream): Int {
        val codec = stream.getCodec().orEmpty().lowercase(Locale.US)
        return when {
            codec.contains("mp4a") || codec.contains("aac") -> 0
            codec.contains("opus") -> 1
            codec.contains("vorbis") -> 2
            else -> 3
        }
    }

    private fun audioBitrate(stream: AudioStream): Int =
        maxOf(stream.getBitrate(), stream.getAverageBitrate())

    private fun pickBestFromSupportTiers(
        candidates: List<VideoStream>,
        targetHeight: Int,
        supportPriority: List<DecoderSupport>,
        supportResolver: (CodecFamily, VideoStream) -> DecoderSupport,
    ): VideoStream? {
        if (candidates.isEmpty()) {
            return null
        }

        supportPriority.forEach { support ->
            val supportCandidates =
                candidates.filter { candidate ->
                    supportResolver(codecFamily(candidate), candidate) == support
                }
            if (supportCandidates.isEmpty()) {
                return@forEach
            }
            selectBestVideoStreamFromTier(supportCandidates, targetHeight, supportResolver)?.let { return it }
        }
        return null
    }

    private fun applyDeviceCodecRestrictions(streams: List<VideoStream>): List<VideoStream> {
        if (!isAmlogicDevice()) {
            return streams
        }

        val nonAv1Candidates =
            streams.filter { stream ->
                codecFamily(stream) != CodecFamily.AV1
            }
        if (nonAv1Candidates.size != streams.size) {
            Log.i(TAG, "Applying Amlogic safe codec restriction: removing AV1 streams (${nonAv1Candidates.size}/${streams.size} streams remain)")
        }
        return nonAv1Candidates.ifEmpty { streams }
    }

    private fun selectBestVideoStreamFromTier(
        streams: List<VideoStream>,
        preferredHeight: Int,
        supportResolver: (CodecFamily, VideoStream) -> DecoderSupport,
    ): VideoStream? {
        resolutionPriority(preferredHeight).forEach { resolution ->
            selectBestStreamAtResolution(
                streams = streams,
                resolution = resolution,
                supportResolver = supportResolver,
                requireBitrateFloor = true,
            )?.let { return it }
        }

        Timber.tag(TAG).w("No preferred YouTube stream quality found, using best available fallback")
        return streams.sortedWith(streamQualityComparator(supportResolver)).firstOrNull()
    }

    private fun selectBestStreamAtResolution(
        streams: List<VideoStream>,
        resolution: Int,
        supportResolver: (CodecFamily, VideoStream) -> DecoderSupport,
        requireBitrateFloor: Boolean,
    ): VideoStream? {
        val atResolution = streams.filter { streamHeight(it) == resolution }
        if (atResolution.isEmpty()) {
            return null
        }

        val candidates =
            if (requireBitrateFloor) {
                atResolution.filter(::meetsBitrateFloor)
            } else {
                atResolution
            }

        if (candidates.isEmpty()) {
            return null
        }

        CODEC_PRIORITY.forEach { codec ->
            candidates
                .filter { stream ->
                    stream.getCodec().orEmpty().lowercase(Locale.US).contains(codec)
                }.sortedWith(streamQualityComparator(supportResolver))
                    .firstOrNull()
                    ?.let { return it }
        }

        return candidates.sortedWith(streamQualityComparator(supportResolver)).firstOrNull()
    }

    private fun resolutionPriority(preferredHeight: Int): List<Int> =
        buildList {
            add(preferredHeight)
            // Nearest above first (a strong 1080p beats a starved 720p when
            // targeting 720p), then below in descending order. Entries above
            // maxTargetHeight simply find no candidates downstream.
            addAll(RESOLUTION_PRIORITY.filter { it > preferredHeight }.sorted())
            addAll(RESOLUTION_PRIORITY.filter { it < preferredHeight })
        }.distinct()

    private fun streamHeight(stream: VideoStream): Int {
        if (stream.getHeight() > 0) {
            return stream.getHeight()
        }

        val fromResolution =
            RESOLUTION_REGEX
                .find(stream.getResolution())
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
        if (fromResolution != null) {
            return fromResolution
        }

        return RESOLUTION_REGEX
            .find(stream.getQuality().orEmpty())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: 0
    }

    private fun codecScore(stream: VideoStream): Int {
        val codec = stream.getCodec().orEmpty().lowercase(Locale.US)
        return when {
            codec.contains("vp9") || codec.contains("vp09") -> 3
            codec.contains("avc") || codec.contains("h264") -> 2
            codec.contains("av01") || codec.contains("av1") -> 1
            else -> 0
        }
    }

    private fun codecPriorityIndex(stream: VideoStream): Int {
        val codec = stream.getCodec().orEmpty().lowercase(Locale.US)
        return CODEC_PRIORITY.indexOfFirst(codec::contains).takeIf { it >= 0 } ?: CODEC_PRIORITY.size
    }

    private fun codecPenalty(
        stream: VideoStream,
        supportResolver: (CodecFamily, VideoStream) -> DecoderSupport,
    ): Int {
        val codecFamily = codecFamily(stream)
        return when (supportResolver(codecFamily, stream)) {
            DecoderSupport.SUPPORTED ->
                when (codecFamily) {
                    CodecFamily.VP9 -> 0
                    CodecFamily.AVC -> 100
                    CodecFamily.AV1 -> 200
                    CodecFamily.OTHER -> 300
                }

            DecoderSupport.UNKNOWN ->
                when (codecFamily) {
                    CodecFamily.VP9 -> 0
                    CodecFamily.AVC -> 100
                    CodecFamily.AV1 -> 200
                    CodecFamily.OTHER -> 300
                }

            DecoderSupport.UNSUPPORTED ->
                when (codecFamily) {
                    CodecFamily.VP9 -> 2_000 // Heavily penalize but still allow if no other choice
                    CodecFamily.AVC -> 2_100
                    CodecFamily.AV1 -> 5_200 // AV1 is risky on some TV chips, keep high penalty
                    CodecFamily.OTHER -> 3_300
                }
        }
    }

    private fun logSelectedStream(stream: VideoStream) {
        Log.i(TAG, "STREAM PICKED: ${describeStream(stream)}")
    }

    private fun describeStream(stream: VideoStream): String =
        "${streamHeight(stream)}p codec=${stream.getCodec()} itag=${stream.getItag()} bitrate=${stream.getBitrate()} support=${decoderSupport(codecFamily(stream), stream)}"

    private fun isLikelyAiTitle(titleLower: String): Boolean {
        if (AI_WORD_REGEX.containsMatchIn(titleLower) || AI_PUNCT_WORD_REGEX.containsMatchIn(titleLower)) {
            return true
        }
        // Bare generator names are word-boundary gated, never substrings:
        // "veo" fires inside "evolve", "luma" inside "plumage".
        if (AI_TITLE_WORD_REGEXES.any { it.containsMatchIn(titleLower) }) {
            return true
        }

        return QueryFormulaEngine.aiVideoBlacklist.any { blacklisted ->
            titleLower.contains(blacklisted.lowercase(Locale.US))
        }
    }

    private val AI_TITLE_WORD_REGEXES =
        listOf(
            "veo",
            "kling",
            "hailuo",
            "minimax",
            "luma",
            "pixverse",
            "jimeng",
            "dreamina",
            "hunyuan",
            "genmo",
            "kaiber",
            "seaweed",
        ).map { token ->
            Regex("\\b${Regex.escape(token)}\\b")
        }

    private fun isLikelyAI(item: StreamInfoItem): Boolean {
        // Title/channel signals only: exact-hour durations (1h/2h/...) are the
        // most common length for legitimate ambient loops, so duration alone
        // must never flag a video.
        val titleLower = item.getName().lowercase(Locale.US)
        val uploaderLower = item.getUploaderName().orEmpty().lowercase(Locale.US)

        if (isLikelyAiTitle(titleLower)) {
            return true
        }
        if (
            AI_CHANNEL_PATTERNS.any { pattern ->
                uploaderLower.contains(pattern)
            }
        ) {
            return true
        }
        // Slop farms brand in the uploader name/handle while keeping titles
        // clean ("AI Scenics", "@AIRelaxationTV4K"). Word-boundary matching so
        // real words don't trip it.
        if (AI_UPLOADER_WORD_REGEXES.any { it.containsMatchIn(uploaderLower) }) {
            return true
        }
        if (uploaderHandleIndicatesAi(item.getUploaderUrl().orEmpty())) {
            return true
        }
        // Self-disclosure lives in the description, invisible to title filters.
        if (DESCRIPTION_AI_PATTERNS.any { item.getShortDescription().orEmpty().lowercase(Locale.US).contains(it) }) {
            return true
        }
        // Mass-uploaded slop has near-zero views per video. Only as a combo
        // with a young upload date (never alone — small pilots deserve a
        // chance), and verified channels are exempt outright.
        if (!item.isUploaderVerified() && isLowTractionFreshUpload(item)) {
            return true
        }
        return false
    }

    private fun uploaderHandleIndicatesAi(uploaderUrl: String): Boolean {
        val rawHandle =
            uploaderUrl
                .substringAfter("/@", "")
                .substringBefore('?')
                .substringBefore('/')
                .ifBlank {
                    uploaderUrl
                        .substringAfter("/c/", "")
                        .substringBefore('?')
                        .substringBefore('/')
                }.takeIf { it.isNotBlank() }
                ?: return false
        // Camel-split needs original case ("SoraScenics" -> sora + scenics).
        if (splitHandleTokens(rawHandle).any { it in AI_UPLOADER_WORDS }) {
            return true
        }
        val handle = rawHandle.lowercase(Locale.US)
        if (AI_UPLOADER_WORD_REGEXES.any { it.containsMatchIn(handle) }) {
            return true
        }
        val digits = handle.count(Char::isDigit)
        val letters = handle.count(Char::isLetter)
        return digits >= 3 && digits >= letters
    }

    private fun splitHandleTokens(handle: String): List<String> =
        handle
            .split(Regex("(?<=[a-z])(?=[A-Z])|[_\\-.\\s]+|(?<=\\D)(?=\\d)|(?<=\\d)(?=\\D)"))
            .map { it.lowercase(Locale.US) }
            .filter { it.isNotBlank() }

    private fun isLowTractionFreshUpload(item: StreamInfoItem): Boolean {
        val views = item.getViewCount()
        if (views !in 0..LOW_TRACTION_MAX_VIEWS) {
            return false
        }
        val uploadedAt = item.getUploadDate()?.getInstant() ?: return false
        return uploadedAt.isAfter(ZonedDateTime.now().minusDays(LOW_TRACTION_MAX_AGE_DAYS).toInstant())
    }

    private fun isBumperOrVlogTitle(titleLower: String): Boolean =
        QueryFormulaEngine.bumperTitleBlacklist.any { blacklisted ->
            titleLower.contains(blacklisted.lowercase(Locale.US))
        }

    private fun isHumanContent(
        title: String,
        uploader: String,
        durationSeconds: Long? = null,
    ): Boolean {
        val titleLower = title.lowercase(Locale.US)
        val uploaderLower = uploader.lowercase(Locale.US)
        return isBumperOrVlogTitle(titleLower) ||
            isTopListTitle(titleLower) ||
            hasDramaticPipePattern(title) ||
            HUMAN_TITLE_BLACKLIST.any(titleLower::contains) ||
            TITLE_WORD_REGEXES.any { it.containsMatchIn(titleLower) } ||
            LOFI_REGEX.containsMatchIn(titleLower) ||
            TITLE_WORD_REGEXES.any { it.containsMatchIn(titleLower) } ||
            HUMAN_CHANNEL_BLACKLIST.any(uploaderLower::contains) ||
            CHANNEL_WORD_REGEXES.any { it.containsMatchIn(uploaderLower) } ||
            isLikelyTextHeavyEducationalContent(titleLower, uploaderLower, durationSeconds) ||
            isLikelyFastMotionContent(titleLower) ||
            PERSONAL_VLOG_TITLE_REGEX.containsMatchIn(title)
    }

    internal fun isLikelyHumanContentForTest(
        title: String,
        uploader: String = "",
        durationSeconds: Long? = null,
    ): Boolean = isHumanContent(title = title, uploader = uploader, durationSeconds = durationSeconds)

    internal fun isLikelyAiForTest(item: StreamInfoItem): Boolean = isLikelyAI(item)

    internal fun isLikelySyntheticWallpaperForTest(title: String): Boolean =
        isLikelySyntheticWallpaperTitle(title.lowercase(Locale.US))

    internal fun hasPlayableFormatForTest(item: StreamInfoItem): Boolean = hasPlayableFormat(item)

    private fun isLikelyTextHeavyEducationalContent(
        titleLower: String,
        uploaderLower: String,
        durationSeconds: Long?,
    ): Boolean {
        if (TEXT_HEAVY_TITLE_HINTS.any(titleLower::contains)) {
            return true
        }

        val looksEducational = TEXT_HEAVY_CHANNEL_HINTS.any(uploaderLower::contains)
        if (!looksEducational) {
            return false
        }

        val duration = durationSeconds ?: return false
        return duration in 1..SHORT_TEXT_HEAVY_MAX_DURATION_SECONDS
    }

    private fun isLikelyFastMotionContent(titleLower: String): Boolean =
        FAST_MOTION_TITLE_HINTS.any(titleLower::contains)

    private fun isTopListTitle(titleLower: String): Boolean =
        TOP_LIST_TITLE_REGEX.containsMatchIn(titleLower) ||
            TOP_LIST_TITLE_BLACKLIST.any(titleLower::contains)

    private fun hasDramaticPipePattern(title: String): Boolean {
        val parts = title.split("|").map(String::trim).filter(String::isNotEmpty)
        if (parts.size < 2) {
            return false
        }

        return parts.any { part ->
            val partLower = part.lowercase(Locale.US)
            DRAMATIC_PIPE_WORDS.any(partLower::contains)
        }
    }

    private fun matchesQueryIntent(
        queryLower: String,
        titleLower: String,
        category: QueryFormulaEngine.ContentCategory?,
    ): Boolean {
        val queryTokens = significantQueryTokens(queryLower)
        val matchedTokens = queryTokens.count { token -> titleLower.contains(token) }
        val queryIsAerial = AERIAL_QUERY_REGEX.containsMatchIn(queryLower)
        val titleIsAerial = AERIAL_TITLE_REGEX.containsMatchIn(titleLower)
        val requiredTokenMatches = if (category == null) 2 else 1

        return when {
            queryTokens.isEmpty() -> true
            queryIsAerial -> titleIsAerial || matchedTokens >= requiredTokenMatches
            else -> matchedTokens >= requiredTokenMatches
        }
    }

    private fun significantQueryTokens(queryLower: String): List<String> =
        queryLower
            .split(QUERY_TOKEN_SPLIT_REGEX)
            .map(String::trim)
            .filter { token ->
                token.length >= 4 &&
                    token !in GENERIC_QUERY_TOKENS &&
                    token.any(Char::isLetter)
            }

    private fun hasPreferredContentSignal(titleLower: String): Boolean =
        PREFERRED_CONTENT_SIGNALS.any { signal ->
            titleLower.contains(signal)
        }

    private fun preferredContentScore(titleLower: String): Int =
        PREFERRED_CONTENT_SIGNALS.count(titleLower::contains)

    private fun slowPacedContentScore(titleLower: String): Int =
        SLOW_PACED_TITLE_HINTS.count(titleLower::contains)

    private fun fastMotionPenalty(titleLower: String): Int =
        FAST_MOTION_TITLE_HINTS.count(titleLower::contains) * 3

    private fun streamQualityComparator(
        supportResolver: (CodecFamily, VideoStream) -> DecoderSupport = ::decoderSupport,
    ): Comparator<VideoStream> =
        compareByDescending<VideoStream> { streamHeight(it) }
            .thenByDescending { meetsBitrateFloor(it) }
            .thenBy { codecPenalty(it, supportResolver) }
            .thenByDescending { codecScore(it) }
            .thenByDescending { it.getBitrate() }
            .thenBy { codecPriorityIndex(it) }

    private fun meetsBitrateFloor(stream: VideoStream): Boolean {
        val bitrate = stream.getBitrate()
        if (bitrate <= 0) {
            return true
        }

        val floor =
            when {
                streamHeight(stream) >= 2160 -> 12_000_000
                streamHeight(stream) >= 1440 -> 8_000_000
                streamHeight(stream) >= 1080 -> 4_500_000
                streamHeight(stream) >= 720 -> 2_500_000
                streamHeight(stream) >= 480 -> 1_200_000
                else -> 0
            }
        return bitrate >= floor
    }

    private fun isLikelySyntheticWallpaperTitle(titleLower: String): Boolean =
        WALLPAPER_WORD_REGEXES.any { it.containsMatchIn(titleLower) } ||
            SYNTHETIC_WALLPAPER_BLACKLIST.any { token ->
                titleLower.contains(token)
            }

    private fun decoderSupport(
        codecFamily: CodecFamily,
        stream: VideoStream,
    ): DecoderSupport {
        if (isKnownUnsupportedTvCodecPath(codecFamily, stream)) {
            return DecoderSupport.UNSUPPORTED
        }

        val mimeType = codecFamily.mimeType ?: return DecoderSupport.UNKNOWN
        val streamSize = streamSize(stream) ?: return decoderAvailability(mimeType)
        val cacheKey = DecoderSupportKey(mimeType, streamSize.first, streamSize.second)

        synchronized(decoderSupportCache) {
            decoderSupportCache[cacheKey]?.let { return it }
        }

        val support = inspectDecoderSupport(mimeType, streamSize)

        synchronized(decoderSupportCache) {
            decoderSupportCache[cacheKey] = support
        }
        return support
    }

    private fun inspectDecoderSupport(
        mimeType: String,
        streamSize: Pair<Int, Int>,
    ): DecoderSupport =
        runCatching {
            val decoders =
                MediaCodecList(MediaCodecList.ALL_CODECS)
                    .codecInfos
                    .asSequence()
                    .filterNot { it.isEncoder }
                    .filter { codecInfo ->
                        codecInfo.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }
                    }.toList()

            when {
                decoders.isEmpty() -> DecoderSupport.UNSUPPORTED
                decoders.any { codecInfo -> codecSupportsSize(codecInfo, mimeType, streamSize) } -> DecoderSupport.SUPPORTED
                else -> DecoderSupport.UNSUPPORTED
            }
        }.getOrElse { exception ->
            Timber.tag(TAG).w(
                exception,
                "Failed to inspect decoder support for %s at %sx%s",
                mimeType,
                streamSize.first,
                streamSize.second,
            )
            DecoderSupport.UNKNOWN
        }

    private fun codecSupportsSize(
        codecInfo: android.media.MediaCodecInfo,
        mimeType: String,
        streamSize: Pair<Int, Int>,
    ): Boolean {
        val supportedType =
            codecInfo.supportedTypes.firstOrNull { it.equals(mimeType, ignoreCase = true) }
                ?: return false
        val capabilities = codecInfo.getCapabilitiesForType(supportedType)
        val videoCapabilities = capabilities.videoCapabilities ?: return true
        return videoCapabilities.isSizeSupported(streamSize.first, streamSize.second) ||
            videoCapabilities.isSizeSupported(streamSize.second, streamSize.first)
    }

    private fun decoderAvailability(mimeType: String): DecoderSupport {
        synchronized(decoderAvailabilityCache) {
            decoderAvailabilityCache[mimeType]?.let { return it }
        }

        val support =
            runCatching {
                val hasDecoder =
                    MediaCodecList(MediaCodecList.ALL_CODECS)
                        .codecInfos
                        .asSequence()
                        .filterNot { it.isEncoder }
                        .any { codecInfo ->
                            codecInfo.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }
                        }
                if (hasDecoder) {
                    DecoderSupport.UNKNOWN
                } else {
                    DecoderSupport.UNSUPPORTED
                }
            }.getOrElse { exception ->
                Timber.tag(TAG).w(exception, "Failed to inspect decoder availability for %s", mimeType)
                DecoderSupport.UNKNOWN
            }

        synchronized(decoderAvailabilityCache) {
            decoderAvailabilityCache[mimeType] = support
        }
        return support
    }

    private fun codecFamily(stream: VideoStream): CodecFamily {
        val codec = stream.getCodec().orEmpty().lowercase(Locale.US)
        return when {
            codec.contains("av01") -> CodecFamily.AV1
            codec.contains("vp09") || codec.contains("vp9") -> CodecFamily.VP9
            codec.contains("avc") -> CodecFamily.AVC
            else -> CodecFamily.OTHER
        }
    }

    private fun streamSize(stream: VideoStream): Pair<Int, Int>? {
        actualStreamSize(stream)?.let { return it }

        val height = streamHeight(stream)
        if (height <= 0) {
            return null
        }

        val width = (height * 16f / 9f).toInt().coerceAtLeast(1)
        return Pair(width, height)
    }

    internal fun hasPreferredAspectRatioForTest(resolution: String): Boolean =
        streamSizeFromResolution(resolution)?.let { (width, height) ->
            hasPreferredAspectRatio(width, height)
        } ?: true

    private fun hasPreferredAspectRatioCandidate(streams: List<VideoStream>): Boolean {
        val knownSizes = streams.mapNotNull(::actualStreamSize)
        if (knownSizes.isEmpty()) {
            return true
        }

        return knownSizes.any { (width, height) -> hasPreferredAspectRatio(width, height) }
    }

    private fun hasPreferredAspectRatio(stream: VideoStream): Boolean =
        actualStreamSize(stream)?.let { (width, height) ->
            hasPreferredAspectRatio(width, height)
        } ?: true

    private fun hasPreferredAspectRatio(
        width: Int,
        height: Int,
    ): Boolean {
        if (width <= 0 || height <= 0) {
            return true
        }

        val aspectRatio = width.toDouble() / height.toDouble()
        return aspectRatio in MIN_ACCEPTABLE_ASPECT_RATIO..MAX_ACCEPTABLE_ASPECT_RATIO
    }

    private fun actualStreamSize(stream: VideoStream): Pair<Int, Int>? =
        streamSizeFromResolution(stream.getResolution().orEmpty())

    private fun streamSizeFromResolution(resolution: String): Pair<Int, Int>? {
        RESOLUTION_PAIR_REGEX.find(resolution)?.let { match ->
            val width = match.groupValues.getOrNull(1)?.toIntOrNull()
            val height = match.groupValues.getOrNull(2)?.toIntOrNull()
            if (width != null && height != null) {
                return Pair(width, height)
            }
        }
        return null
    }

    private fun isKnownUnsupportedTvCodecPath(
        codecFamily: CodecFamily,
        stream: VideoStream,
    ): Boolean {
        val height = streamHeight(stream)
        if (isAmlogicDevice() && codecFamily == CodecFamily.AV1) {
            Timber.tag(TAG).w(
                "Treating %sp %s as unsupported on this device due to Amlogic AV1 decoder instability",
                height,
                stream.getCodec(),
            )
            return true
        }

        return false
    }

    private fun isAmlogicDevice(): Boolean =
        DEVICE_FINGERPRINT.contains("amlogic") ||
            DEVICE_FINGERPRINT.contains("t5d") ||
            DEVICE_FINGERPRINT.contains("rango") ||
            DEVICE_FINGERPRINT.contains("mitv")

    private fun isRecentEnough(item: StreamInfoItem): Boolean {
        val uploadInstant = item.getUploadDate()?.getInstant() ?: return true
        return uploadInstant.isAfter(ZonedDateTime.now().minusYears(10).toInstant())
    }

    fun getScreenHeight(context: Context): Int {
        val wm = context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val displayManager = context.getSystemService(DisplayManager::class.java)
            val visualDisplay =
                runCatching { context.display }
                    .getOrNull()
            val display =
                visualDisplay
                    ?: displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
            val activeHeight = display?.mode?.physicalHeight ?: 0
            val supportedHeight = display?.supportedModes?.maxOfOrNull { mode -> mode.physicalHeight } ?: 0
            val windowHeight = wm.currentWindowMetrics.bounds.height()
            return maxOf(activeHeight, supportedHeight, windowHeight).coerceAtLeast(720)
        }

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        return metrics.heightPixels
    }

    private fun targetHeightForScreen(screenHeight: Int): Int =
        when {
            screenHeight >= 2160 -> 2160
            screenHeight >= 1440 -> 1440
            screenHeight >= 1080 -> 1080
            // Deliberately supersample 1080p on 720p screens: 1080p YouTube
            // renditions carry far higher bitrates than 720p ones, and the
            // downscale looks markedly better than native 720p playback.
            else -> 1080
        }

    private fun preferredHeightForQuality(preferredQuality: String): Int {
        val quality = preferredQuality.lowercase(Locale.US)
        return when {
            quality == "best" -> 2160 // Ceiling only; callers resolve "best" against the screen first
            quality.contains("2160") || quality.contains("4k") -> 2160
            quality.contains("1440") -> 1440
            quality.contains("1080") -> 1080
            quality.contains("720") -> 720
            else -> 1080 // Default to 1080p if not specified
        }
    }

    // Screen-bounded ceiling: an explicit low pick may use one step above
    // its target (1080p downscaled beats starved 720p), but never 4K waste
    // for a sub-4K target, and never above the target once at/above 1080p.
    private fun maxTargetHeight(targetHeight: Int): Int = maxOf(targetHeight, 1080)

    private fun minimumAllowedHeight(targetHeight: Int): Int =
        when {
            targetHeight >= 2160 -> 1080
            targetHeight >= 1440 -> 1080
            targetHeight >= 1080 -> 720
            targetHeight >= 720 -> 480
            else -> 360
        }

    private fun strictMinimumPreferredHeight(targetHeight: Int): Int =
        when {
            targetHeight >= 2160 -> 1440
            targetHeight >= 1440 -> 1080
            targetHeight >= 1080 -> 1080
            targetHeight >= 720 -> 720
            else -> minimumAllowedHeight(targetHeight)
        }

    private class OkHttpDownloader(
        private val client: OkHttpClient,
    ) : Downloader() {
        override fun execute(request: Request): Response {
            val okHttpRequest = buildOkHttpRequest(request)

            client.newCall(okHttpRequest).execute().use { response ->
                return Response(
                    response.code,
                    response.message,
                    response.headers.toMultimap(),
                    if (request.httpMethod() == "HEAD") "" else readCappedBody(response),
                    response.request.url.toString(),
                )
            }
        }

        private fun readCappedBody(response: okhttp3.Response): String {
            val body = response.body ?: return ""
            val declaredLength = body.contentLength()
            if (declaredLength > MAX_DOWNLOAD_BYTES) {
                throw IOException("NewPipe response too large ($declaredLength bytes): ${response.request.url}")
            }
            body.source().use { source ->
                source.request(MAX_DOWNLOAD_BYTES + 1)
                val buffered = source.buffer
                if (buffered.size > MAX_DOWNLOAD_BYTES) {
                    throw IOException("NewPipe response exceeded ${MAX_DOWNLOAD_BYTES} bytes: ${response.request.url}")
                }
                return buffered.readUtf8()
            }
        }

        private fun buildOkHttpRequest(request: Request) =
            requestBuilder(request).let { requestBuilder ->
                when (request.httpMethod()) {
                    "POST" -> {
                        val mediaType = request.getHeader("Content-Type")?.toMediaTypeOrNull()
                        val body = (request.dataToSend() ?: ByteArray(0)).toRequestBody(mediaType)
                        requestBuilder.post(body).build()
                    }

                    "HEAD" -> requestBuilder.head().build()
                    else -> requestBuilder.get().build()
                }
            }

        private fun requestBuilder(request: Request): Builder =
            Builder()
                .url(request.url())
                .header("User-Agent", USER_AGENT)
                .header("Referer", REFERER)
                .header("Origin", ORIGIN)
                .also { requestBuilder ->
                    request.headers().forEach { (name, values) ->
                        if (name.isBlank()) {
                            return@forEach
                        }

                        requestBuilder.removeHeader(name)
                        values.forEach { value ->
                            requestBuilder.addHeader(name, value)
                        }
                    }
                }

        private fun Request.getHeader(name: String): String? =
            headers()[name]?.firstOrNull()
    }

    private val SearchInfo.relatedItems: List<InfoItem>
        get() = getRelatedItems()

    private const val MAX_RESULTS_PER_QUERY = 24
    private const val MAX_DOWNLOAD_BYTES = 8L * 1024L * 1024L // YouTube pages/manifests are KBs; refuse absurd bodies before OOM
    private const val MIN_QUERY_MATCH_RESULTS = 4
    private const val MIN_PREFERRED_RESULTS_PER_QUERY = 6
    private val AI_WORD_REGEX = Regex("\\bai\\b", RegexOption.IGNORE_CASE)
    private val AI_PUNCT_WORD_REGEX = Regex("\\ba\\W*i\\b", RegexOption.IGNORE_CASE)
    private val QUERY_TOKEN_SPLIT_REGEX = Regex("[^a-z0-9']+")
    private val DEVICE_FINGERPRINT =
        listOf(
            Build.HARDWARE,
            Build.BOARD,
            Build.DEVICE,
            Build.MANUFACTURER,
            Build.MODEL,
        ).joinToString(" ") { value ->
            value.orEmpty()
        }.lowercase(Locale.US)
    private val AERIAL_QUERY_REGEX = Regex("(aerial|drone|flyover|flythrough|bird's eye|hyperlapse)", RegexOption.IGNORE_CASE)
    private val AERIAL_TITLE_REGEX = Regex("(aerial|drone|flyover|flythrough|fpv|bird's eye|uav)", RegexOption.IGNORE_CASE)
    private val GENERIC_QUERY_TOKENS =
        setOf(
            "4k",
            "8k",
            "hdr",
            "ultra",
            "ultrahd",
            "hd",
            "cinematic",
            "aerial",
            "drone",
            "footage",
            "timelapse",
            "flyover",
            "flythrough",
            "view",
            "video",
            "nature",
            "music",
            "ambient",
            "sounds",
            "only",
            "clear",
            "crystal",
            "sunrise",
            "sunset",
        )
    private val PREFERRED_CONTENT_SIGNALS =
        listOf(
            "no music",
            "no talking",
            "wildlife",
            "slow motion",
            "documentary",
            "real footage",
            "real time",
            "tripod",
            "locked off",
            "nature film",
            "national park",
            "nature sounds",
            "slow tv",
            "calm",
            "serene",
            "underwater",
            "ocean",
            "waterfall",
            "forest",
            "coral reef",
            "drone",
            "aerial",
            "flyover",
            "fpv",
            "whale",
            "dolphin",
            "penguin",
            "elephant",
        )
    private val SYNTHETIC_WALLPAPER_BLACKLIST =
        listOf(
            "wallpaper",
            "cgi",
            "3d",
            "render",
            "rendered",
            "animation",
            "animated",
            "loop",
            "visualizer",
            "unreal engine",
            "blender",
            "simulation",
            "fantasy",
            "dreamscape",
            "ambient video",
            "relaxing video",
            "generated video",
            "upscaled",
            "upscale",
            "text to video",
            "veo",
            "sora",
            "kling",
            "pika labs",
            "pika ai",
            "pixverse",
            "luma ai",
            "hailuo",
            "haiper",
            "hunyuan",
            "dreamina",
            "image to video",
            "sleep",
            "study",
            "meditation",
            "healing",
            "slideshow",
            "backgrounds",
            "stock footage",
            "travel wallpaper",
            "nature wallpaper",
            "relaxing music",
            "sleep music",
            "sleep sounds",
            "meditation music",
            "healing music",
            "spa music",
            "yoga music",
            "ambient music",
            "guided meditation",
            "morning affirmations",
            "432hz",
            "528hz",
            "432 hz",
            "528 hz",
            "solfeggio",
            "binaural",
            "deep sleep",
            "all night",
            "hours of",
            "still image",
            "static image",
            "photo slideshow",
            "official audio",
            "behind the scenes",
            "dj mix",
            "nonstop mix",
            "hour mix",
            "music mix",
            "song cover",
            "cover song",
            "lyrics",
            "piano music",
            "guitar music",
            "karaoke",
        )
    // Bare "zen"/"spa"/"tv" must never be substring-matched ("frozen"
    // waterfall is a legit WINTER query, "space" contains "spa"). These are
    // word-boundary gated instead.
    private val WALLPAPER_WORD_REGEXES =
        listOf("zen", "spa", "tv").map { token ->
            Regex("\\b${Regex.escape(token)}\\b")
        }
    private val AI_CHANNEL_PATTERNS =
        listOf(
            "ai art",
            "ai video",
            "ai film",
            "ai nature",
            "ai generated",
            "runway clips",
            "synthwave",
            "neural",
            "diffusion studio",
            "ai cinema",
            "artificial",
        )
    // Bare AI-tool tokens matched word-boundary-only against uploader names
    // and handles ("AI Scenics", "@AIRelaxationTV"). Never substrings:
    // "runway" is a real word (aviation/travel footage).
    private val AI_UPLOADER_WORDS =
        listOf(
            "ai",
            "sora",
            "veo",
            "kling",
            "pika",
            "luma",
            "hailuo",
            "haiper",
            "midjourney",
            "diffusion",
            "genmo",
            "kaiber",
            "invideo",
            "fliki",
            "pictory",
            "synthesia",
            "deepbrain",
            "minimax",
        )
    private val AI_UPLOADER_WORD_REGEXES =
        AI_UPLOADER_WORDS.map { token ->
            Regex("\\b${Regex.escape(token)}\\b")
        }
    // Self-disclosure hides in descriptions, invisible to title filters.
    private val DESCRIPTION_AI_PATTERNS =
        listOf(
            "created with ai",
            "generated with ai",
            "generated by ai",
            "made with sora",
            "made with pika",
            "made with kling",
            "made with runway",
            "made with luma",
            "made with hailuo",
            "made with midjourney",
            "stable diffusion",
            "text-to-video",
            "image-to-video",
            "ai voiceover",
            "ai narration",
            "ai slideshow",
            "ai-generated",
            "synthetic content",
            "altered content",
            "altered or synthetic content",
            "significantly edited or digitally generated",
            "created with generative ai",
            "prompt:",
            "negative prompt:",
        )
    private const val LOW_TRACTION_MAX_VIEWS = 99L
    private const val LOW_TRACTION_MAX_AGE_DAYS = 21L
    // Single-stem title signals, word-boundary gated. Bare substrings would
    // fire on real words ("anchor" in "anchorage", "mix" in "mixed forest").
    private val TITLE_WORD_REGEXES =
        listOf(
            "101",
            "diy",
            "demo",
            "demos",
            "hack",
            "hacks",
            "mix",
            "talk",
            "talks",
            "talking",
            "talked",
            "host",
            "hosts",
            "hosted",
            "hosting",
            "speech",
            "anchor",
            "debate",
            "rant",
        ).map { token ->
            Regex("\\b${Regex.escape(token)}\\b")
        }
    private val CHANNEL_WORD_REGEXES =
        listOf("tv", "zen", "spa", "fm").map { token ->
            Regex("\\b${Regex.escape(token)}\\b")
        }
    private val LOFI_REGEX = Regex("lo[-\\s]?fi")
    private val TEXT_HEAVY_TITLE_HINTS =
        listOf(
            "with text",
            "with captions",
            "with subtitles",
            "subtitled",
            "subtitles",
            "subtitle",
            "captioned",
            "narrated",
            "lecture",
            "presentation",
            "tutorial overlay",
            "text on screen",
            "on-screen text",
            "animated text",
            "closed captions",
            "cc available",
            "transcript",
            "with labels",
            "info text",
            "step by step",
            "step-by-step",
            "mistakes to avoid",
            "beginner guide",
            "deep dive",
        )
    private val TEXT_HEAVY_CHANNEL_HINTS =
        listOf(
            "explainer",
            "education",
            "learn",
            "school",
            "lesson",
            "facts",
            "science explained",
            "history of",
            "academy",
            "university",
            "coach",
            "guru",
        )
    private val FAST_MOTION_TITLE_HINTS =
        listOf(
            "timelapse",
            "time lapse",
            "hyperlapse",
            "fpv",
            "flythrough",
            "drone racing",
            "fast motion",
            "high speed",
            "speed ramp",
        )
    private val SLOW_PACED_TITLE_HINTS =
        listOf(
            "ambient",
            "real footage",
            "real time",
            "no talking",
            "no music",
            "nature sounds",
            "slow tv",
            "calm",
            "serene",
            "gentle",
            "stillness",
            "slow motion",
        )
    private const val SHORT_TEXT_HEAVY_MAX_DURATION_SECONDS = 8 * 60L
    private const val MIN_DASH_PREFERRED_HEIGHT = 1080
    private const val MIN_PREFERRED_PROGRESSIVE_HEIGHT = 1080
    private const val MIN_ACCEPTABLE_ASPECT_RATIO = 1.70
    private const val MAX_ACCEPTABLE_ASPECT_RATIO = 1.90
    private val RESOLUTION_PRIORITY = listOf(2160, 1440, 1080, 720, 480)
    private val CODEC_PRIORITY = listOf("vp9", "vp09", "avc1", "avc", "av01", "av1")
    private val REJECTED_LOW_QUALITY_ITAGS = setOf(18, 36, 133, 134, 135, 160) // itag 18 is 360p, 133 is 240p, 134 is 360p, 135 is 480p, 160 is 144p. Added 36 (240p).
    private val TOP_LIST_TITLE_BLACKLIST =
        listOf(
            "top 10",
            "top ten",
            "most beautiful places",
            "best places to visit",
        )
    private val TOP_LIST_TITLE_REGEX = Regex("\\btop\\s*\\d+\\b", RegexOption.IGNORE_CASE)
    private val HUMAN_TITLE_BLACKLIST =
        listOf(
            "vlog",
            "day in my life",
            "daily vlog",
            "week in my life",
            "come with me",
            "grwm",
            "get ready with me",
            "storytime",
            "story time",
            "what i eat",
            "tutorial",
            "how to",
            "with text",
            "with captions",
            "subtitled",
            "narrated",
            "tips",
            "tricks",
            "guide",
            "review",
            "unboxing",
            "haul",
            "try on",
            "reaction",
            "reacts",
            "challenge",
            "prank",
            "comedy",
            "funny",
            "fails",
            "compilation",
            "my morning",
            "my routine",
            "my day",
            "my life",
            "i tried",
            "i spent",
            "i ate",
            "we tried",
            "interview",
            "podcast",
            "talk show",
            "news",
            "explained",
            "analysis",
            "lecture",
            "presentation",
            "slideshow",
            "text on screen",
            "animated text",
            "opinion",
            "gameplay",
            "gaming",
            "playthrough",
            "let's play",
            "recipe",
            "cooking",
            "mukbang",
            "food review",
            "music video",
            "official video",
            "lyric video",
            "live performance",
            "concert",
            "epilepsy warning",
            "flashing lights",
            "flashing warning",
            "seizure warning",
            "photosensitive",
            "strobe",
            "strobing",
            "flicker warning",
            "trigger warning",
            "content warning",
            "wildlife documentary",
            "animal documentary",
            "animals documentary",
            "nature documentary",
            "unbelievable",
            "cliff chase",
            "fight",
            "attack",
            "predator vs",
            "vs predator",
            "hunt",
            "hunting",
            "chase",
            "survival",
            "incredible moment",
            "caught on camera",
            "rare footage",
            "amazing footage",
            "shocking",
            "brutal",
            "epic battle",
            "weather forecast",
            "day forecast",
            "weather report",
            "live radar",
            "doppler",
            "tornado warning",
            "tornado watch",
            "hurricane update",
            "storm warning",
            "severe weather",
            "weather alert",
            "weekend outlook",
            "extended outlook",
            "spaghetti model",
            "tracking the storm",
            "storm track",
            "evacuation",
            "meteorologist",
            "accuweather",
            "fox weather",
            "weather nation",
            "press conference",
            "live coverage",
            "breaking news",
            "talking head",
            "talking-head",
            "facecam",
            "commentary",
            "panel discussion",
            "monologue",
            "sermon",
            "stand-up",
            "standup",
            "travel with me",
            "explore with me",
            "join me",
            "morning routine",
            "night routine",
            "evening routine",
            "get unready",
            "pack with me",
            "life update",
            "life lately",
            "weekly vlog",
            "weekend vlog",
            "wedding vlog",
            "birthday vlog",
            "ask me anything",
            "q&a",
            "q & a",
            "q and a",
            "solo travel",
            "van life",
            "digital nomad",
            "expat",
            "couple travel",
            "family vlog",
            "first time in",
            "why i left",
            "i moved",
            "i quit",
            "ultimate guide",
            "complete guide",
            "masterclass",
            "walkthrough",
            "demonstration",
            "do's and don'ts",
        )
    private val HUMAN_CHANNEL_BLACKLIST =
        listOf(
            "vlog",
            "daily",
            "family",
            "kids",
            "children",
            "cooking",
            "gaming",
            "news",
            "politics",
            "comedy",
            "funny",
            "entertainment",
            "reviews",
            "tutorials",
            "explainer",
            "education",
            "lesson",
            "facts",
            "podcast",
            "tv shows",
            "official",
            "epilepsy",
            "seizure",
            "strobe",
            "flicker",
            "documentary",
            "wildlife films",
            "animal planet",
            "nat geo wild",
            "discovery channel",
            "weather channel",
            "accuweather",
            "fox weather",
            "weather nation",
            "met office",
            "local news",
            "meditation",
            "soothing",
            "relax",
            "lullaby",
            "mindful",
            "wellness",
            "spa sounds",
            "sleep therapy",
            "records",
            "productions",
            "ministry",
            "church",
            "academy",
            "university",
            "coach",
            "guru",
            "memes",
            "esports",
            "radio",
        )
    private val DRAMATIC_PIPE_WORDS =
        listOf(
            "fight",
            "attack",
            "chase",
            "hunt",
            "kill",
            "vs",
            "predator",
            "prey",
            "incredible",
            "unbelievable",
            "shocking",
            "rare",
            "amazing",
            "caught",
        )
    private val PERSONAL_VLOG_TITLE_REGEX =
        Regex(
            "^[A-Z][a-z]+(?:\\s+[A-Z][a-z]+){0,2}\\s+(vlog|storytime|tutorial|review|challenge|podcast|interview|routine|update|morning|wedding)\\b",
            RegexOption.IGNORE_CASE,
        )
    private val RESOLUTION_REGEX = Regex("(\\d{3,4})p")
    private val RESOLUTION_PAIR_REGEX = Regex("(\\d{3,4})\\s*[xX]\\s*(\\d{3,4})")
    private val decoderSupportCache = mutableMapOf<DecoderSupportKey, DecoderSupport>()
    private val decoderAvailabilityCache = mutableMapOf<String, DecoderSupport>()

    private fun buildSearchUrl(
        query: String,
        contentFilter: String,
    ): String {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val encodedSearchParameter =
            URLEncoder.encode(
                YoutubeSearchQueryHandlerFactory.getSearchParameter(contentFilter),
                "UTF-8",
            )
        return "https://www.youtube.com/results?search_query=$encodedQuery&sp=$encodedSearchParameter"
    }

    private fun extractVideoId(videoPageUrl: String): String? {
        QUERY_VIDEO_ID_REGEX
            .find(videoPageUrl)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        val trimmedPath =
            videoPageUrl
                .substringAfter("://", videoPageUrl)
                .substringAfter('/', "")
                .substringBefore('?')
                .substringBefore('#')
                .substringAfterLast('/')

        // A bare endpoint path (".../watch" with no ?v=) is not a video ID;
        // without this guard every malformed URL collapsed to cache key "watch".
        return trimmedPath.takeIf { it.isNotBlank() && it.lowercase(Locale.US) !in NON_VIDEO_ID_PATH_SEGMENTS }
    }

    fun playbackRequestHeaders(): Map<String, String> =
        mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to REFERER,
            "Origin" to ORIGIN,
        )

    private inline fun <T> List<T>.ifEnoughOrElse(
        minimumSize: Int,
        fallback: () -> List<T>,
    ): List<T> = if (size >= minimumSize) this else fallback()

    private data class DecoderSupportKey(
        val mimeType: String,
        val width: Int,
        val height: Int,
    )

    private enum class CodecFamily(
        val mimeType: String?,
    ) {
        AV1("video/av01"),
        VP9("video/x-vnd.on2.vp9"),
        AVC("video/avc"),
        OTHER(null),
    }

    private enum class DecoderSupport {
        SUPPORTED,
        UNSUPPORTED,
        UNKNOWN,
    }

    private val QUERY_VIDEO_ID_REGEX = Regex("[?&]v=([^&#]+)")
    private val NON_VIDEO_ID_PATH_SEGMENTS =
        setOf(
            "watch",
            "results",
            "playlist",
            "channel",
            "feed",
            "hashtag",
            "shorts",
            "live",
        )
}
