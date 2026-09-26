package com.neilturner.aerialviews.ui.core

import android.content.Context
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.effect.Contrast
import androidx.media3.effect.HslAdjustment
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector.Parameters
import androidx.media3.exoplayer.util.EventLogger
import androidx.media3.ui.AspectRatioFrameLayout
import com.neilturner.aerialviews.models.enums.AerialMediaSource
import com.neilturner.aerialviews.models.enums.ImmichAuthType
import com.neilturner.aerialviews.models.enums.LimitLongerVideos
import com.neilturner.aerialviews.models.enums.SchemeType
import com.neilturner.aerialviews.models.enums.VideoScale
import com.neilturner.aerialviews.models.music.MusicTrack
import com.neilturner.aerialviews.models.prefs.GeneralPrefs
import com.neilturner.aerialviews.models.prefs.ImmichMediaPrefs
import com.neilturner.aerialviews.models.prefs.NCMemoriesMediaPrefs
import com.neilturner.aerialviews.models.prefs.WebDavMediaPrefs
import com.neilturner.aerialviews.models.prefs.WebDavMediaPrefs2
import com.neilturner.aerialviews.models.prefs.YouTubeVideoPrefs
import com.neilturner.aerialviews.models.videos.AerialMedia
import com.neilturner.aerialviews.providers.ncmemories.NCMemoriesDataSourceFactory
import com.neilturner.aerialviews.providers.samba.SambaDataSourceFactory
import com.neilturner.aerialviews.providers.webdav.WebDavDataSourceFactory
import com.neilturner.aerialviews.providers.webdav.WebDavHostParser
import com.neilturner.aerialviews.providers.webdav.defaultPortFor
import com.neilturner.aerialviews.providers.youtube.NewPipeHelper
import com.neilturner.aerialviews.services.philips.CustomRendererFactory
import timber.log.Timber
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

object VideoPlayerHelper {
    private const val TEN_SECONDS = 10 * 1000
    private const val INTRO_SKIP_MS = 30_000L
    private const val OUTRO_GUARD_MS = 20_000L
    private const val VIVID_SATURATION = 1.35f
    private const val VIVID_LIGHTNESS = 0.02f
    private const val VIVID_CONTRAST = 0.08f

    fun toggleAudioTrack(
        player: ExoPlayer,
        disableAudio: Boolean,
    ) {
        player.trackSelectionParameters =
            player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, disableAudio)
                .build()
    }

    fun disableTextTrack(player: ExoPlayer) {
        player.trackSelectionParameters =
            player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
    }

    @OptIn(UnstableApi::class)
    fun getResizeMode(
        scale: VideoScale?,
        forceCrop: Boolean = false,
    ): Int =
        if (forceCrop || scale == VideoScale.SCALE_TO_FIT_WITH_CROPPING) {
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        } else {
            AspectRatioFrameLayout.RESIZE_MODE_FIT
        }

    fun getVideoScalingMode(
        scale: VideoScale?,
        forceCrop: Boolean = false,
    ): Int =
        if (forceCrop || scale == VideoScale.SCALE_TO_FIT_WITH_CROPPING) {
            C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
        } else {
            C.VIDEO_SCALING_MODE_SCALE_TO_FIT
        }

    @OptIn(UnstableApi::class)
    fun buildAudioPlayer(context: Context): ExoPlayer {
        val loadControl =
            DefaultLoadControl
                .Builder()
                .setBufferDurationsMs(
                    10_000,
                    20_000,
                    3_000,
                    5_000,
                ).build()

        return ExoPlayer
            .Builder(context)
            .setLoadControl(loadControl)
            .build()
    }

    @OptIn(UnstableApi::class)
    fun buildPlayer(
        context: Context,
        prefs: GeneralPrefs,
    ): ExoPlayer {
        val parametersBuilder = Parameters.Builder()

        // Video effects bypass the tunneled playback path, so vivid mode needs it off
        if (prefs.enableTunneling && !prefs.vividVideo) {
            parametersBuilder
                .setTunnelingEnabled(true)
        }

        val trackSelector = DefaultTrackSelector(context)
        trackSelector.parameters = parametersBuilder.build()

        var rendererFactory: DefaultRenderersFactory = DefaultRenderersFactory(context)
        rendererFactory.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
        if (prefs.allowFallbackDecoders) {
            rendererFactory.setEnableDecoderFallback(true)
        }

        if (prefs.philipsDolbyVisionFix) {
            rendererFactory = CustomRendererFactory(context)
        }

        val loadControl =
            DefaultLoadControl
                .Builder()
                .setBufferDurationsMs(
                    if (prefs.reduceBufferMemory) 2_000 else 30_000,
                    if (prefs.reduceBufferMemory) 10_000 else 180_000,
                    if (prefs.reduceBufferMemory) 500 else 5_000,
                    if (prefs.reduceBufferMemory) 1_000 else 10_000,
                ).setTargetBufferBytes(C.LENGTH_UNSET)
                .build()

        val player =
            ExoPlayer
                .Builder(context)
                .setTrackSelector(trackSelector)
                .setLoadControl(loadControl)
                .setRenderersFactory(rendererFactory)
                .build()

        if (prefs.enableLogCapture) {
            player.addAnalyticsListener(EventLogger())
            player.addAnalyticsListener(PlaybackDiagnosticsListener(context))
        }

        if (prefs.playsVideoAudio) {
            player.volume =
                prefs.videoVolume.toFloat() / 100
        } else {
            player.volume = 0f
        }

        // https://medium.com/androiddevelopers/prep-your-tv-app-for-android-12-9a859d9bb967
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && prefs.refreshRateSwitching) {
            player.videoChangeFrameRateStrategy = C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
        }

        player.videoScalingMode = getVideoScalingMode(prefs.videoScale)

        if (prefs.vividVideo) {
            Timber.i("Applying vivid video effect (saturation + contrast boost)")
            player.setVideoEffects(
                listOf(
                    HslAdjustment
                        .Builder()
                        .adjustSaturation(VIVID_SATURATION)
                        .adjustLightness(VIVID_LIGHTNESS)
                        .build(),
                    Contrast(VIVID_CONTRAST),
                ),
            )
        }

        player.setPlaybackSpeed(prefs.playbackSpeed.toFloat())
        return player
    }

    @OptIn(UnstableApi::class)
    fun setupMediaSource(
        context: Context,
        player: ExoPlayer,
        media: AerialMedia,
        startPositionMs: Long = C.TIME_UNSET,
    ) {
        val mediaItem = MediaItem.fromUri(media.uri)
        val mediaSource =
            if (media.source == AerialMediaSource.YOUTUBE) {
                val dataSourceFactory =
                    DefaultHttpDataSource
                        .Factory()
                        .setDefaultRequestProperties(NewPipeHelper.playbackRequestHeaders())
                        .setAllowCrossProtocolRedirects(true)
                        .setConnectTimeoutMs(TimeUnit.SECONDS.toMillis(30).toInt())
                        .setReadTimeoutMs(TimeUnit.SECONDS.toMillis(30).toInt())

                Timber.i(
                    "Created YouTube media source (merged=%s, video=%s, audio=%s)",
                    media.audioStreamUrl.isNotBlank(),
                    media.uri,
                    media.audioStreamUrl.ifBlank { "none" },
                )
                buildYouTubeMediaSource(media, dataSourceFactory)
            } else {
                createMediaSource(context, mediaItem, media.source)
            }
        setMediaSourceWithOptionalStart(player, mediaSource, startPositionMs)
    }

    @OptIn(UnstableApi::class)
    fun createAudioMediaSource(
        context: Context,
        track: MusicTrack,
    ) = createMediaSource(context, MediaItem.fromUri(track.uri), track.source)

    @OptIn(UnstableApi::class)
    private fun createMediaSource(
        context: Context,
        mediaItem: MediaItem,
        source: AerialMediaSource,
    ) = when (source) {
        AerialMediaSource.SAMBA -> {
            ProgressiveMediaSource
                .Factory(SambaDataSourceFactory())
                .createMediaSource(mediaItem)
        }

        AerialMediaSource.RTSP -> {
            RtspMediaSource
                .Factory()
                .setDebugLoggingEnabled(true)
                .setForceUseRtpTcp(true)
                .createMediaSource(mediaItem)
        }

        AerialMediaSource.IMMICH -> {
            val dataSourceFactory =
                DefaultHttpDataSource
                    .Factory()
                    .setAllowCrossProtocolRedirects(true)
                    .setConnectTimeoutMs(TimeUnit.SECONDS.toMillis(30).toInt())
                    .setReadTimeoutMs(TimeUnit.SECONDS.toMillis(30).toInt())

            // Add necessary headers for Immich
            if (ImmichMediaPrefs.authType == ImmichAuthType.API_KEY) {
                dataSourceFactory.setDefaultRequestProperties(
                    mapOf("X-API-Key" to ImmichMediaPrefs.apiKey),
                )
            }

            // If SSL validation is disabled, we need to set the appropriate flags
            if (!ImmichMediaPrefs.validateSsl) {
                System.setProperty("javax.net.ssl.trustAll", "true")
            }

            Timber.d("Setting up Immich media source with URI: ${mediaItem.localConfiguration?.uri}")
            ProgressiveMediaSource
                .Factory(dataSourceFactory)
                .createMediaSource(mediaItem)
        }

        AerialMediaSource.NCMEMORIES -> {
            // If SSL validation is disabled, we need to set the appropriate flags
            if (!NCMemoriesMediaPrefs.validateSsl) {
                System.setProperty("javax.net.ssl.trustAll", "true")
            }

            Timber.d("Setting up Nextcloud Memories media source with URI: ${mediaItem.localConfiguration?.uri}")
            ProgressiveMediaSource
                .Factory(NCMemoriesDataSourceFactory())
                .createMediaSource(mediaItem)
        }

        AerialMediaSource.WEBDAV -> {
            val validateSsl = getWebDavValidateSslFromUri(mediaItem.localConfiguration!!.uri)
            ProgressiveMediaSource
                .Factory(WebDavDataSourceFactory(validateSsl))
                .createMediaSource(mediaItem)
        }

        else -> {
            val dataSourceFactory = DefaultDataSource.Factory(context)
            ProgressiveMediaSource
                .Factory(dataSourceFactory)
                .createMediaSource(mediaItem)
        }
    }

    @OptIn(UnstableApi::class)
    private fun buildYouTubeMediaSource(
        media: AerialMedia,
        dataSourceFactory: DefaultHttpDataSource.Factory,
    ): MediaSource {
        val videoItem = MediaItem.fromUri(media.uri)
        val videoUrl = media.uri.toString()

        if (media.audioStreamUrl.isNotBlank()) {
            val videoSource =
                ProgressiveMediaSource
                    .Factory(dataSourceFactory)
                    .createMediaSource(videoItem)
            val audioSource =
                ProgressiveMediaSource
                    .Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(media.audioStreamUrl))
            return MergingMediaSource(videoSource, audioSource)
        }

        return when {
            videoUrl.contains("manifest/dash", ignoreCase = true) ||
                videoUrl.contains(".mpd", ignoreCase = true) ->
                DashMediaSource
                    .Factory(dataSourceFactory)
                    .createMediaSource(videoItem)

            videoUrl.contains("manifest/hls", ignoreCase = true) ||
                videoUrl.contains(".m3u8", ignoreCase = true) ->
                HlsMediaSource
                    .Factory(dataSourceFactory)
                    .createMediaSource(videoItem)

            else ->
                ProgressiveMediaSource
                    .Factory(dataSourceFactory)
                    .createMediaSource(videoItem)
        }
    }

    private fun setMediaSourceWithOptionalStart(
        player: ExoPlayer,
        mediaSource: MediaSource,
        startPositionMs: Long,
    ) {
        if (startPositionMs > 0L) {
            player.setMediaSource(mediaSource, startPositionMs)
        } else {
            player.setMediaSource(mediaSource)
        }
    }

    fun calculatePlaybackParameters(
        player: ExoPlayer,
        media: AerialMedia,
        prefs: GeneralPrefs,
    ): Pair<Long, Long> {
        val type = media.source
        val metadataDurationMs = media.metadata.exif.durationSeconds?.toLong()?.times(1000) ?: 0L
        val effectiveDuration = if (player.duration > 0) player.duration else metadataDurationMs

        val playbackPolicy = resolvePlaybackPolicy(type, prefs)
        val maxVideoLength = playbackPolicy.maxVideoLengthMs
        val isLengthLimited = maxVideoLength >= TEN_SECONDS
        val isShortVideo = effectiveDuration < maxVideoLength && effectiveDuration > 0

        if (type == AerialMediaSource.RTSP) {
            Timber.i("Calculating RTSP stream length...")
            val duration = if (isLengthLimited) maxVideoLength else 0L
            return Pair(0, duration)
        }

        if (!isLengthLimited && playbackPolicy.randomStartEnabled) {
            Timber.i("Calculating random start position...")
            val range = GeneralPrefs.randomStartPositionRange.toInt()
            return calculateRandomStartPosition(effectiveDuration, range)
        }

        if (isShortVideo && isLengthLimited && prefs.loopShortVideos) {
            Timber.i("Calculating looping short video...")
            return calculateLoopingVideo(effectiveDuration, maxVideoLength)
        }

        if (!isShortVideo && isLengthLimited) {
            when (playbackPolicy.limitMode) {
                LimitLongerVideos.LIMIT -> {
                    Timber.i("Calculating long video type... obey limit, play until time limit")
                    val duration =
                        if (maxVideoLength >= effectiveDuration) {
                            Timber.i("Using video duration as limit (shorter than max!)")
                            effectiveDuration
                        } else {
                            Timber.i("Using user limit")
                            maxVideoLength
                        }
                    return Pair(0, duration)
                }

                LimitLongerVideos.SEGMENT -> {
                    Timber.i("Calculating long video type... play random segment")
                    return calculateRandomSegment(effectiveDuration, maxVideoLength)
                }

                else -> {
                    Timber.i("Calculating long video type... ignore limit, play full video")
                    return Pair(0, effectiveDuration)
                }
            }
        }

        // Use normal start + end/duration
        Timber.i("Calculating normal video type...")
        return Pair(0, effectiveDuration)
    }

    private fun resolvePlaybackPolicy(
        mediaSource: AerialMediaSource,
        prefs: GeneralPrefs,
    ): PlaybackPolicy {
        if (mediaSource != AerialMediaSource.YOUTUBE) {
            return PlaybackPolicy(
                maxVideoLengthMs = prefs.maxVideoLength.toLongOrNull()?.times(1000L) ?: 0L,
                limitMode = prefs.limitLongerVideos,
                randomStartEnabled = prefs.randomStartPosition,
            )
        }

        val youtubeMode = YouTubeVideoPrefs.playbackLengthMode.trim().lowercase()
        val youtubeMaxLengthMs = YouTubeVideoPrefs.playbackMaxMinutes.toLong() * 60L * 1000L
        return when (youtubeMode) {
            "full" ->
                PlaybackPolicy(
                    maxVideoLengthMs = 0L,
                    limitMode = LimitLongerVideos.IGNORE,
                    randomStartEnabled = false,
                )
            "segment" ->
                PlaybackPolicy(
                    maxVideoLengthMs = youtubeMaxLengthMs,
                    limitMode = LimitLongerVideos.SEGMENT,
                    randomStartEnabled = true,
                )
            else ->
                PlaybackPolicy(
                    maxVideoLengthMs = youtubeMaxLengthMs,
                    limitMode = LimitLongerVideos.LIMIT,
                    randomStartEnabled = false,
                )
        }
    }

    private data class PlaybackPolicy(
        val maxVideoLengthMs: Long,
        val limitMode: LimitLongerVideos?,
        val randomStartEnabled: Boolean,
    )

    private fun calculateRandomStartPosition(
        duration: Long,
        range: Int,
    ): Pair<Long, Long> {
        if (duration <= 0 || range < 5) {
            Timber.e("Invalid duration or range: duration=$duration, range=$range%")
            return Pair(0, duration)
        }
        val seekPosition = (duration * range / 100.0).toLong()
        val randomPosition = Random.nextLong(seekPosition)

        val percent = (randomPosition.toFloat() / duration.toFloat() * 100).toInt()
        Timber.i("Start at ${randomPosition.milliseconds} ($percent%, from 0%-%$range)")

        return Pair(randomPosition, duration)
    }

    private fun calculateRandomSegment(
        duration: Long,
        maxLength: Long,
    ): Pair<Long, Long> {
        if (duration <= 0 || maxLength < TEN_SECONDS) {
            Timber.e("Invalid duration or max length: duration=$duration, maxLength=$maxLength")
            return Pair(0, duration.coerceAtLeast(0L))
        }

        val effectiveMax = maxLength.coerceAtMost(duration)
        val totalGuards = INTRO_SKIP_MS + OUTRO_GUARD_MS

        if (duration > effectiveMax + totalGuards) {
            val minStart = INTRO_SKIP_MS
            val maxStart = duration - effectiveMax - OUTRO_GUARD_MS
            val randomStart = Random.nextLong(minStart, maxStart + 1L)
            val segmentEnd = randomStart + effectiveMax
            Timber.i("Fluid random segment: ${randomStart.milliseconds} - ${segmentEnd.milliseconds} (duration: ${duration.milliseconds}, max: ${effectiveMax.milliseconds})")
            return Pair(randomStart, segmentEnd)
        } else if (duration > totalGuards) {
            val segmentStart = INTRO_SKIP_MS
            val segmentEnd = (duration - OUTRO_GUARD_MS).coerceAtLeast(segmentStart)
            Timber.i("Protected window segment: ${segmentStart.milliseconds} - ${segmentEnd.milliseconds} (duration: ${duration.milliseconds})")
            return Pair(segmentStart, segmentEnd)
        } else {
            Timber.i("Short video, playing full: 0 - ${duration.milliseconds}")
            return Pair(0, duration)
        }
    }

    private fun getWebDavValidateSslFromUri(uri: android.net.Uri): Boolean {
        val host = uri.host?.lowercase() ?: return true
        val port = if (uri.port == -1) null else uri.port

        if (WebDavMediaPrefs.hostName.isNotBlank()) {
            val parsed = runCatching { WebDavHostParser.parse(WebDavMediaPrefs.hostName) }.getOrNull()
            if (parsed != null && parsed.host.equals(host, ignoreCase = true)) {
                val prefPort = parsed.port ?: defaultPortFor(WebDavMediaPrefs.scheme ?: SchemeType.HTTP)
                if (port == null || port == prefPort) return WebDavMediaPrefs.validateSsl
            }
        }

        if (WebDavMediaPrefs2.hostName.isNotBlank()) {
            val parsed = runCatching { WebDavHostParser.parse(WebDavMediaPrefs2.hostName) }.getOrNull()
            if (parsed != null && parsed.host.equals(host, ignoreCase = true)) {
                val prefPort = parsed.port ?: defaultPortFor(WebDavMediaPrefs2.scheme ?: SchemeType.HTTP)
                if (port == null || port == prefPort) return WebDavMediaPrefs2.validateSsl
            }
        }

        return true
    }

    private fun calculateLoopingVideo(
        duration: Long,
        maxLength: Long,
    ): Pair<Long, Long> {
        if (duration <= 0 || maxLength < TEN_SECONDS) {
            Timber.e("Invalid duration or video length: duration=$duration, maxLength=$maxLength%")
            return Pair(0, duration)
        }
        val loopCount = ceil(maxLength / duration.toDouble()).toInt()
        val targetDuration = duration * loopCount
        Timber.i(
            "Looping $loopCount times (video is ${duration.milliseconds}, total is ${targetDuration.milliseconds}, limit is ${maxLength.milliseconds})",
        )
        return Pair(0, targetDuration)
    }
}
