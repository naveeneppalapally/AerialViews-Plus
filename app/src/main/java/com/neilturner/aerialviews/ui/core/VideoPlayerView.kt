package com.neilturner.aerialviews.ui.core

import android.content.Context
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import androidx.core.net.toUri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.neilturner.aerialviews.R
import com.neilturner.aerialviews.models.enums.AerialMediaSource
import com.neilturner.aerialviews.models.enums.ProgressBarLocation
import com.neilturner.aerialviews.models.enums.ProgressBarType
import com.neilturner.aerialviews.models.prefs.GeneralPrefs
import com.neilturner.aerialviews.models.prefs.YouTubeVideoPrefs
import com.neilturner.aerialviews.models.videos.AerialMedia
import com.neilturner.aerialviews.providers.youtube.YouTubeFeature
import com.neilturner.aerialviews.providers.youtube.YouTubePlaybackUrls
import com.neilturner.aerialviews.services.philips.PhilipsMediaCodecAdapterFactory
import com.neilturner.aerialviews.ui.controls.ProgressBarEvent
import com.neilturner.aerialviews.ui.controls.ProgressState
import com.neilturner.aerialviews.ui.helpers.LocaleHelper
import com.neilturner.aerialviews.ui.helpers.PermissionHelper
import com.neilturner.aerialviews.ui.helpers.RefreshRateHelper
import com.neilturner.aerialviews.ui.helpers.ToastHelper
import com.neilturner.aerialviews.ui.helpers.VolumeHelper
import com.neilturner.aerialviews.utils.FirebaseHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.kosert.flowbus.GlobalBus
import timber.log.Timber
import kotlin.time.Duration.Companion.milliseconds

@Suppress("SameParameterValue", "JoinDeclarationAndAssignment")
class VideoPlayerView
    @OptIn(UnstableApi::class)
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : PlayerView(context.applicationContext, attrs, defStyleAttr),
        Player.Listener {
        private val exoPlayer: ExoPlayer
        private var state = VideoState()

        private var listener: OnVideoPlayerEventListener? = null
        private var almostFinishedRunnable =
            Runnable {
                listener?.onVideoAlmostFinished()
            }
        private var canChangePlaybackSpeedRunnable = Runnable { this.canChangePlaybackSpeed = true }
        private var onErrorRunnable =
            Runnable {
                listener?.onVideoError()
            }
        private val refreshRateHelper by lazy { RefreshRateHelper(context) }
        private val mainScopeJob = SupervisorJob()
        private val mainScope = CoroutineScope(Dispatchers.Main + mainScopeJob)
        private var resolveVideoJob: Job? = null
        private var lastStaleUrlRetryVideoId: String? = null
        private var canChangePlaybackSpeed = true
        private var playbackSpeed = GeneralPrefs.playbackSpeed
        private var pausedTimestamp: Long = 0
        private var wasPlaying = false
        private var isDestroyed = false

        private val progressBar =
            GeneralPrefs.progressBarLocation != ProgressBarLocation.DISABLED && GeneralPrefs.progressBarType != ProgressBarType.PHOTOS

        private val volumeHelper =
            VolumeHelper(
                getVolume = { exoPlayer.volume },
                setVolume = { v -> exoPlayer.volume = v },
            )
        private var forcedMuted = false

        private var isMuted = !GeneralPrefs.playsVideoAudio

        init {
            exoPlayer = VideoPlayerHelper.buildPlayer(context, GeneralPrefs)

            player = exoPlayer
            player?.addListener(this)

            if (GeneralPrefs.loopUntilSkipped || GeneralPrefs.loopShortVideos) {
                player?.repeatMode = Player.REPEAT_MODE_ALL // Used for looping short videos
            } else {
                player?.repeatMode = Player.REPEAT_MODE_OFF // No looping
            }

            controllerAutoShow = false
            useController = false
            resizeMode = VideoPlayerHelper.getResizeMode(GeneralPrefs.videoScale)
        }

        fun release() {
            if (isDestroyed) return

            Timber.i("Releasing VideoPlayerView...")
            isDestroyed = true

            pause()
            resolveVideoJob?.cancel()
            mainScopeJob.cancel()

            // Clear surface first to stop rendering, then release the player
            exoPlayer.setVideoSurface(null)
            player?.release()
            player = null

            removeCallbacks(almostFinishedRunnable)
            removeCallbacks(canChangePlaybackSpeedRunnable)
            removeCallbacks(onErrorRunnable)

            cancelVolumeFade()
            listener = null
        }

        fun toggleLooping() {
            if (player?.repeatMode == Player.REPEAT_MODE_ALL) {
                player?.repeatMode = Player.REPEAT_MODE_OFF
                mainScope.launch {
                    ToastHelper.show(context, "Looping disabled")
                }
            } else {
                player?.repeatMode = Player.REPEAT_MODE_ALL
                mainScope.launch {
                    ToastHelper.show(context, "Looping enabled")
                }
            }
        }

        fun setVideo(media: AerialMedia) {
            state = VideoState() // Reset params for each video
            state.type = media.source
            state.currentMedia = media
            resolveVideoJob?.cancel()
            exoPlayer.stop()
            cancelVolumeFade()
            resetRotation()

            val forceCropForYouTube = media.source == AerialMediaSource.YOUTUBE
            resizeMode = VideoPlayerHelper.getResizeMode(GeneralPrefs.videoScale, forceCropForYouTube)
            exoPlayer.videoScalingMode = VideoPlayerHelper.getVideoScalingMode(GeneralPrefs.videoScale, forceCropForYouTube)
            if (forceCropForYouTube) {
                Log.i("VideoPlayerView", "Applying forced crop scaling for YouTube playback")
            }

            resolveVideoJob =
                mainScope.launch {
                    val playableMedia =
                        try {
                            resolveMediaForPlayback(media)
                        } catch (exception: Exception) {
                            Timber.e(exception, "Failed to resolve playable media for ${media.uri}")
                            post(onErrorRunnable)
                            return@launch
                        }

                    if (GeneralPrefs.philipsDolbyVisionFix) {
                        PhilipsMediaCodecAdapterFactory.mediaUrl = playableMedia.uri.toString()
                    }

                    Log.i("VideoPlayerView", "Preparing media source: ${playableMedia.uri}")
                    configureTrackSelection(playableMedia)
                    if (playableMedia.source == AerialMediaSource.YOUTUBE) {
                        val playbackParams = VideoPlayerHelper.calculatePlaybackParameters(exoPlayer, playableMedia, GeneralPrefs)
                        state.startPosition = playbackParams.first
                        state.endPosition = playbackParams.second
                    }
                    val initialStartPositionMs =
                        if (state.startPosition > 0L) state.startPosition else computeInitialYouTubeStartPosition(playableMedia)
                    if (initialStartPositionMs > 0L) {
                        Log.i("VideoPlayerView", "Applying initial YouTube start during prepare: ${initialStartPositionMs.milliseconds}")
                    }
                    VideoPlayerHelper.setupMediaSource(context, exoPlayer, playableMedia, initialStartPositionMs)

                    applyMuteState()

                    // Disable subtitles/text tracks by default
                    VideoPlayerHelper.disableTextTrack(exoPlayer)

                    player?.prepare()
                }
        }

        fun setForcedMute(enabled: Boolean) {
            forcedMuted = enabled
            applyMuteState()
        }

        fun increaseSpeed() = changeSpeed(true)

        fun decreaseSpeed() = changeSpeed(false)

        fun seekForward() = seek()

        fun seekBackward() = seek(true)

        fun toggleMute() {
            cancelVolumeFade()
            if (forcedMuted) {
                applyMuteState()
                return
            }
            if (isMuted) {
                if (GeneralPrefs.muteDisablesAudioTrack) {
                    VideoPlayerHelper.toggleAudioTrack(exoPlayer, false)
                }
                exoPlayer.volume = GeneralPrefs.videoVolume.toFloat() / 100
                isMuted = false
            } else {
                if (GeneralPrefs.muteDisablesAudioTrack) {
                    VideoPlayerHelper.toggleAudioTrack(exoPlayer, true)
                }
                exoPlayer.volume = 0f
                isMuted = true
            }
        }

        fun fadeOutAudio(duration: Long) {
            if (isMuted || forcedMuted) return
            volumeHelper.fadeOut(durationMs = duration)
        }

        private fun applyMuteState() {
            val shouldMute = forcedMuted || isMuted
            if (shouldMute) {
                if (GeneralPrefs.muteDisablesAudioTrack) {
                    VideoPlayerHelper.toggleAudioTrack(exoPlayer, true)
                }
                exoPlayer.volume = 0f
            } else {
                if (GeneralPrefs.muteDisablesAudioTrack) {
                    VideoPlayerHelper.toggleAudioTrack(exoPlayer, false)
                }
                exoPlayer.volume = GeneralPrefs.videoVolume.toFloat() / 100
            }
            isMuted = shouldMute && !forcedMuted
        }

        fun setOnPlayerListener(listener: OnVideoPlayerEventListener?) {
            this.listener = listener
        }

        fun start() {
            exoPlayer.playWhenReady = true
        }

        fun pause() {
            wasPlaying = exoPlayer.playWhenReady
            exoPlayer.playWhenReady = false
            pausedTimestamp = System.currentTimeMillis()
            removeCallbacks(almostFinishedRunnable)
        }

        fun resume() {
            if (wasPlaying) {
                exoPlayer.playWhenReady = true
                // Recalculate remaining time and restart timer
                setupAlmostFinishedRunnable()
            }
        }

        fun stop() {
            removeCallbacks(almostFinishedRunnable)
            exoPlayer.stop()
        }

        val currentPosition
            get() = exoPlayer.currentPosition.toInt()

        @OptIn(UnstableApi::class)
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_IDLE -> {
                    Timber.i("Idle...")
                }

                Player.STATE_ENDED -> {
                    Timber.i("Playback ended...")
                    if (player?.repeatMode == Player.REPEAT_MODE_OFF) {
                        listener?.onVideoAlmostFinished()
                    }
                }

                Player.STATE_READY -> {}

                Player.STATE_BUFFERING -> {
                    Timber.i("Buffering...")
                    if (progressBar) GlobalBus.post(ProgressBarEvent(ProgressState.PAUSE))
                }
            }

            if (playbackState == Player.STATE_READY) {
                emitTrackMetadata()
            }

            if (!state.prepared && playbackState == Player.STATE_READY) {
                Timber.i("Preparing...")

                // Waiting for... https://youtrack.jetbrains.com/issue/KT-19627/Object-name-based-destructuring
                val currentMedia = state.currentMedia
                if (currentMedia != null) {
                    if (state.endPosition <= 0L) {
                        val result = VideoPlayerHelper.calculatePlaybackParameters(exoPlayer, currentMedia, GeneralPrefs)
                        state.startPosition = result.first
                        state.endPosition = result.second
                    }
                    state.startPosition = adjustYouTubeStartForIntroSkip(state.startPosition, state.endPosition)
                }

                // Dynamically set repeat mode based on whether it's a short video that should loop
                val maxVideoLength = GeneralPrefs.maxVideoLength.toLong() * 1000
                val isLengthLimited = maxVideoLength >= 10000
                val isShortVideo = exoPlayer.duration in 1..<maxVideoLength
                player?.repeatMode =
                    if (GeneralPrefs.loopUntilSkipped || (isShortVideo && isLengthLimited && GeneralPrefs.loopShortVideos)) {
                        Player.REPEAT_MODE_ALL
                    } else {
                        Player.REPEAT_MODE_OFF
                    }

                if (state.startPosition > 0) {
                    val currentPosition = player?.currentPosition ?: 0L
                    val needsForwardSeek = currentPosition + START_POSITION_SEEK_TOLERANCE_MS < state.startPosition
                    if (needsForwardSeek) {
                        Timber.i("Seeking to ${state.startPosition.milliseconds}")
                        player?.seekTo(state.startPosition)
                    } else {
                        Timber.i(
                            "Skipping correction seek; current=${currentPosition.milliseconds}, target=${state.startPosition.milliseconds}",
                        )
                        // Keep progress bar timing aligned with actual decoder position.
                        state.startPosition = currentPosition
                    }
                }

                state.prepared = true
            }

            // Video is buffered, ready to play
            if (exoPlayer.playWhenReady && playbackState == Player.STATE_READY) {
                if (exoPlayer.isPlaying) {
                    Timber.i("Ready, Playing...")

                    if (GeneralPrefs.refreshRateSwitching &&
                        PermissionHelper.hasSystemOverlayPermission(
                            context,
                        )
                    ) {
                        // VideoPlayerHelper.setRefreshRate(context, exoPlayer.videoFormat?.frameRate)
                        refreshRateHelper.setRefreshRate(exoPlayer.videoFormat?.frameRate)
                    }

                    if (!state.ready) {
                        listener?.onVideoPrepared()
                        state.ready = true

                        // Pre-resolve next video immediately when current starts
                        mainScope.launch(Dispatchers.IO) {
                            YouTubeFeature.repository(context).preResolveNext(this)
                        }
                    }

                    setupAlmostFinishedRunnable()
                } else {
                    Timber.i("Preparing again...")
                }
            }
        }

        override fun onMediaItemTransition(
            mediaItem: MediaItem?,
            reason: Int,
        ) {
            when (reason) {
                Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> {
                    Timber.i("Reason: Playlist changed")
                }

                Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> {
                    Timber.i("Reason: Seek to new media item")
                }

                Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> {
                    Timber.i("Reason: Auto transition")
                }

                Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> {
                    state.loopCount++
                    Timber.i("Reason: Looping video, count: ${state.loopCount}")
                    if (exoPlayer.isPlaying) {
                        setupAlmostFinishedRunnable()
                    }
                }
            }
            super.onMediaItemTransition(mediaItem, reason)
        }

        override fun onPlayerError(error: PlaybackException) {
            super.onPlayerError(error)
            removeCallbacks(almostFinishedRunnable)
            Log.e("VideoPlayerView", "onPlayerError: ${error.errorCodeName} ${error.localizedMessage}", error)
            FirebaseHelper.crashlyticsException(error.cause)

            if (GeneralPrefs.showMediaErrorToasts) {
                mainScope.launch {
                    val errorMessage = error.localizedMessage ?: "Video playback error occurred"
                    ToastHelper.show(context, errorMessage)
                }
            }

            if (tryStaleYouTubeUrlRetry()) {
                return
            }
            post(onErrorRunnable)
        }

        /**
         * Direct googlevideo URLs go stale (~5.5h TTL) while sitting in a built
         * playlist. ExoPlayer surfaces that as a generic playback error. Retry
         * once with a freshly resolved stream before giving up and skipping.
         * Returns true if a retry was launched.
         */
        private fun tryStaleYouTubeUrlRetry(): Boolean {
            val failedMedia = state.currentMedia ?: return false
            if (failedMedia.source != AerialMediaSource.YOUTUBE) {
                return false
            }
            val failedUrl = failedMedia.uri.toString()
            if (isYouTubePageUrl(failedUrl)) {
                return false
            }
            val videoId = failedMedia.metadata.exif.description?.trim().orEmpty()
            if (videoId.isBlank() || videoId == lastStaleUrlRetryVideoId) {
                return false
            }
            lastStaleUrlRetryVideoId = videoId
            Log.i("VideoPlayerView", "Retrying stale YouTube direct URL with fresh resolve: $videoId")
            mainScope.launch {
                val resolved =
                    withContext(Dispatchers.IO) {
                        withTimeoutOrNull(YOUTUBE_STREAM_RESOLVE_TIMEOUT_MS) {
                            runCatching {
                                YouTubeFeature.repository(context).resolveVideoPlayback(
                                    "https://www.youtube.com/watch?v=$videoId",
                                )
                            }.getOrNull()
                        }
                    }
                if (resolved == null || isDestroyed) {
                    post(onErrorRunnable)
                    return@launch
                }
                setVideo(
                    failedMedia.copy(
                        uri = resolved.videoUrl.toUri(),
                        streamUrl = resolved.videoUrl,
                        audioStreamUrl = resolved.audioUrl,
                    ),
                )
                start()
            }
            return true
        }

        override fun onPlayerErrorChanged(error: PlaybackException?) {
            super.onPlayerErrorChanged(error)
            error?.let {
                Log.e("VideoPlayerView", "onPlayerErrorChanged: ${it.errorCodeName} ${it.localizedMessage}", it)
                Timber.e(it)
            }
        }

        @Suppress("DEPRECATION")
        @OptIn(UnstableApi::class)
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            super.onVideoSizeChanged(videoSize)

            val w = videoSize.width
            val h = videoSize.height
            val unapplied = videoSize.unappliedRotationDegrees
            Timber.i("Video size: ${w}x${h}, unappliedRotationDegrees=$unapplied")

            if (!GeneralPrefs.portraitVideoRotationEnabled) return

            if (GeneralPrefs.useTextureViewForVideo) {
                Timber.i("Portrait rotation fix: skipped (TextureView mode)")
                return
            }

            // Portrait detection: taller than wide (before any rotation)
            val isPortrait = h > w
            if (!isPortrait) {
                Timber.i("Portrait rotation fix: skipped (landscape video ${w}x${h})")
                resetRotation()
                return
            }

            val degrees = GeneralPrefs.portraitVideoRotationDegrees.toFloatOrNull() ?: return
            applyRotationFix(degrees)
        }

        private fun applyRotationFix(degrees: Float) {
            post {
                val containerW = width.toFloat()
                val containerH = height.toFloat()
                if (containerW == 0f || containerH == 0f) return@post

                // After a 90°/270° rotation the view's layout axes are swapped,
                // so scale by containerH/containerW to fill the screen width.
                val scale =
                    when (degrees) {
                        90f, 270f -> containerH / containerW
                        else -> 1f // 180° — aspect ratio unchanged, no scale needed
                    }

                Timber.i(
                    "Portrait rotation fix: applying ${degrees}° rotation, scale=$scale " +
                        "(container ${containerW.toInt()}x${containerH.toInt()})",
                )

                rotation = degrees
                scaleX = scale
                scaleY = scale
            }
        }

        private fun resetRotation() {
            rotation = 0f
            scaleX = 1f
            scaleY = 1f
        }

        private fun emitTrackMetadata() {
            val extractedMetadata = extractVideoMetadataFromTracks(exoPlayer.currentTracks)
            listener?.onVideoMetadataExtracted(extractedMetadata)
        }

        private suspend fun resolveMediaForPlayback(media: AerialMedia): AerialMedia =
            withContext(Dispatchers.IO) {
                if (media.source != AerialMediaSource.YOUTUBE) {
                    return@withContext media
                }

                val repository = YouTubeFeature.repository(context)
                val youtubeVideoId = media.metadata.exif.description?.takeIf { it.isNotBlank() }
                val mediaUrl = media.uri.toString()
                if (!isYouTubePageUrl(mediaUrl)) {
                    logFirstYouTubeResolveTiming(
                        durationMs = 0L,
                        mediaUrl = mediaUrl,
                        fromPreResolvedUrl = true,
                    )
                    youtubeVideoId?.let { videoId ->
                        repository.markAsPlayed(videoId)
                    }
                    repository.preResolveNext(mainScope)
                    return@withContext media
                }

                val resolveStartedAt = SystemClock.elapsedRealtime()
                val resolvedPlayback =
                    if (media.streamUrl.isNotBlank()) {
                        Log.i("VideoPlayerView", "Using cached streamUrl directly")
                        YouTubePlaybackUrls(
                            videoUrl = media.streamUrl,
                            audioUrl = media.audioStreamUrl,
                        )
                    } else {
                        withTimeoutOrNull(YOUTUBE_STREAM_RESOLVE_TIMEOUT_MS) {
                            repository.resolveVideoPlayback(mediaUrl)
                        } ?: throw IllegalStateException("Timed out resolving YouTube stream URL")
                    }
                val resolveDurationMs = SystemClock.elapsedRealtime() - resolveStartedAt
                logFirstYouTubeResolveTiming(
                    durationMs = resolveDurationMs,
                    mediaUrl = mediaUrl,
                    fromPreResolvedUrl = false,
                )
                media.copy(
                    uri = resolvedPlayback.videoUrl.toUri(),
                    streamUrl = resolvedPlayback.videoUrl,
                    audioStreamUrl = resolvedPlayback.audioUrl,
                )
            }

        private fun isYouTubePageUrl(url: String): Boolean {
            val normalizedUrl = url.lowercase()
            return normalizedUrl.contains("youtube.com/") || normalizedUrl.contains("youtu.be/")
        }

        private fun configureTrackSelection(media: AerialMedia) {
            val builder =
                exoPlayer.trackSelectionParameters
                    .buildUpon()
                    .setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
                    .setForceHighestSupportedBitrate(false)
                    .setMaxVideoBitrate(Int.MAX_VALUE)

            if (media.source == AerialMediaSource.YOUTUBE) {
                builder
                    .setForceHighestSupportedBitrate(true)
                    .setMaxVideoBitrate(Int.MAX_VALUE)
                when (YouTubeVideoPrefs.quality.lowercase()) {
                    "720p" -> {
                        builder
                            .setMaxVideoSize(Int.MAX_VALUE, 720)
                    }

                    "1080p" -> {
                        builder
                            .setMaxVideoSize(Int.MAX_VALUE, 1080)
                    }

                    "1440p" -> {
                        builder
                            .setMaxVideoSize(Int.MAX_VALUE, 1440)
                    }

                    "best",
                    "2160p",
                    "4k",
                    -> {
                        builder
                            .setMaxVideoSize(Int.MAX_VALUE, 2160)
                    }
                }
            }

            exoPlayer.trackSelectionParameters = builder.build()
        }

        private fun logFirstYouTubeResolveTiming(
            durationMs: Long,
            mediaUrl: String,
            fromPreResolvedUrl: Boolean,
        ) {
            synchronized(RESOLVE_LOG_LOCK) {
                if (firstYouTubeResolveTimingLogged) {
                    return
                }
                firstYouTubeResolveTimingLogged = true
            }
            val mode = if (fromPreResolvedUrl) "pre-resolved" else "resolved"
            Log.i(
                "VideoPlayerView",
                "YouTube first URL resolution: ${durationMs}ms mode=$mode url=$mediaUrl",
            )
        }

        private fun seek(backward: Boolean = false) {
            val interval = GeneralPrefs.seekInterval.toLong() * 1000
            val position = exoPlayer.currentPosition

            Timber.i("Seeking to $position/$interval (backward: $backward)")

            if (backward) {
                exoPlayer.seekTo(position - interval)
            } else {
                exoPlayer.seekTo(position + interval)
            }
        }

        private fun changeSpeed(increase: Boolean) {
            if (!canChangePlaybackSpeed) return
            if (!exoPlayer.playWhenReady || !exoPlayer.isPlaying) return // Must be playing a video
            if (exoPlayer.currentPosition <= CHANGE_PLAYBACK_START_END_DELAY) return // No speed change at the start of the video
            // No speed changes at the end of video
            if (exoPlayer.duration - exoPlayer.currentPosition <= CHANGE_PLAYBACK_START_END_DELAY) return

            canChangePlaybackSpeed = false
            postDelayed(canChangePlaybackSpeedRunnable, CHANGE_PLAYBACK_SPEED_DELAY)

            val currentSpeed = playbackSpeed
            var speedValues: Array<String>?
            var currentSpeedIdx: Int

            try {
                speedValues = resources.getStringArray(R.array.playback_speed_values)
                currentSpeedIdx = speedValues.indexOf(currentSpeed)
            } catch (ex: Exception) {
                // Track possible issues with playback speed values
                // Might be related to a certain language or locale?
                val (systemLocale, appLocale) = LocaleHelper.systemLanguageAndLocale(context)
                FirebaseHelper.crashlyticsLogKeys("current_system_locale", systemLocale)
                FirebaseHelper.crashlyticsLogKeys("current_app_locale", appLocale)
                FirebaseHelper.crashlyticsLogKeys("current_speed", currentSpeed)
                FirebaseHelper.crashlyticsException(ex)
                Timber.e(ex, "Exception while getting playback speed values")
                return
            }

            if (currentSpeedIdx == -1) {
                // No matching speed, likely a resource error or pref mismatch
                GeneralPrefs.playbackSpeed = "1" // Reset pref
                return
            }

            if (!increase && currentSpeedIdx == 0) return // we are at minimum speed already
            if (increase && currentSpeedIdx == speedValues.size - 1) return // we are at maximum speed already

            val newSpeed =
                if (increase) {
                    speedValues[currentSpeedIdx + 1]
                } else {
                    speedValues[currentSpeedIdx - 1]
                }

            playbackSpeed = newSpeed
            exoPlayer.setPlaybackSpeed(newSpeed.toFloat())
            GeneralPrefs.playbackSpeed = playbackSpeed

            setupAlmostFinishedRunnable()
            listener?.onVideoPlaybackSpeedChanged()
        }

        private fun cancelVolumeFade() {
            volumeHelper.cancel()
        }

        private fun setupAlmostFinishedRunnable() {
            removeCallbacks(almostFinishedRunnable)

            if (state.startPosition <= 0 && state.endPosition <= 0 && state.type != AerialMediaSource.RTSP) {
                postDelayed(almostFinishedRunnable, 2 * 1000)
                if (progressBar) GlobalBus.post(ProgressBarEvent(ProgressState.RESET))
                return
            }

            // Adjust the duration based on the playback speed
            // Take into account the current player position, speed changes, fade in/out

            // Basic duration and progress
            val duration = state.endPosition - state.startPosition

            val loopDuration =
                if (exoPlayer.duration != androidx.media3.common.C.TIME_UNSET && exoPlayer.duration > 0) {
                    state.loopCount * exoPlayer.duration
                } else {
                    0L
                }
            val progress = loopDuration + exoPlayer.currentPosition - state.startPosition
            val fadeDuration = GeneralPrefs.mediaFadeOutDuration.toLong()

            // Duration taking into account...
            val durationMinusSpeed = (duration / GeneralPrefs.playbackSpeed.toDouble()).toLong()
            val durationMinusSpeedAndProgress = durationMinusSpeed - progress
            var durationMinusSpeedAndProgressAndFade = durationMinusSpeedAndProgress - fadeDuration

            if (durationMinusSpeedAndProgressAndFade < 1000) {
                durationMinusSpeedAndProgressAndFade = 1000
            }

            Timber.i(
                "Duration: ${duration.milliseconds} (at 1x), Delay: ${durationMinusSpeedAndProgressAndFade.milliseconds} (at ${GeneralPrefs.playbackSpeed}x), Curr. position: ${progress.milliseconds}",
            )

            if (progressBar) {
                GlobalBus.post(
                    ProgressBarEvent(
                        ProgressState.START,
                        progress,
                        durationMinusSpeed,
                    ),
                )
            }

            if (GeneralPrefs.loopUntilSkipped || (state.type == AerialMediaSource.RTSP && state.endPosition == 0L)) {
                Timber.i("The video will only finish when skipped manually")
            } else {
                Timber.i("Video will finish in: ${durationMinusSpeedAndProgressAndFade.milliseconds}")
                postDelayed(almostFinishedRunnable, durationMinusSpeedAndProgressAndFade)
            }
        }

        private fun adjustYouTubeStartForIntroSkip(
            startPosition: Long,
            endPosition: Long,
        ): Long {
            if (state.type != AerialMediaSource.YOUTUBE) {
                return startPosition
            }

            val playableDuration = (endPosition - startPosition).coerceAtLeast(0L)
            if (playableDuration < YOUTUBE_INTRO_SKIP_MIN_DURATION_MS) {
                return startPosition
            }

            // If startPosition is already at or past the intro skip threshold,
            // the intro is already bypassed (e.g. by random segment selection).
            if (startPosition >= YOUTUBE_INTRO_SKIP_MS) {
                return startPosition
            }

            val introSkip = YOUTUBE_INTRO_SKIP_MS
            val maxAllowedStart = (endPosition - YOUTUBE_INTRO_SKIP_END_GUARD_MS).coerceAtLeast(startPosition)
            val adjustedStart = (startPosition + introSkip).coerceAtMost(maxAllowedStart)
            if (adjustedStart > startPosition) {
                Log.i("VideoPlayerView", "Skipping YouTube intro segment: +${introSkip.milliseconds}")
            }
            return adjustedStart
        }

        private fun computeInitialYouTubeStartPosition(media: AerialMedia): Long {
            if (media.source != AerialMediaSource.YOUTUBE) {
                return 0L
            }

            val knownDurationMs = (media.metadata.exif.durationSeconds ?: 0).toLong() * 1000L
            if (knownDurationMs <= 0L) {
                // Unknown duration: never blind-seek. A 30s skip into a short
                // clip lands past the end and loops error/skip.
                return 0L
            }
            if (knownDurationMs < YOUTUBE_INTRO_SKIP_MIN_DURATION_MS) {
                return 0L
            }

            val maxAllowedStart = (knownDurationMs - YOUTUBE_INTRO_SKIP_END_GUARD_MS).coerceAtLeast(0L)
            return YOUTUBE_INTRO_SKIP_MS.coerceAtMost(maxAllowedStart)
        }

        interface OnVideoPlayerEventListener {
            fun onVideoAlmostFinished()

            fun onVideoError()

            fun onVideoPrepared()

            fun onVideoPlaybackSpeedChanged()

            fun onVideoMetadataExtracted(metadata: ExtractedVideoMetadata)
        }

        companion object {
            const val CHANGE_PLAYBACK_SPEED_DELAY: Long = 2000
            const val CHANGE_PLAYBACK_START_END_DELAY: Long = 4000
            const val YOUTUBE_INTRO_SKIP_MIN_DURATION_MS: Long = 60_000
            const val YOUTUBE_INTRO_SKIP_MS: Long = 30_000
            const val YOUTUBE_INTRO_SKIP_END_GUARD_MS: Long = 20_000
            const val START_POSITION_SEEK_TOLERANCE_MS: Long = 5_000
            const val YOUTUBE_STREAM_RESOLVE_TIMEOUT_MS: Long = 15_000
            private val RESOLVE_LOG_LOCK = Any()

            @Volatile
            private var firstYouTubeResolveTimingLogged = false
        }
    }

data class VideoState(
    var ready: Boolean = false,
    var type: AerialMediaSource = AerialMediaSource.UNKNOWN,
    var prepared: Boolean = false,
    var loopCount: Int = 0,
    var startPosition: Long = 0L,
    var endPosition: Long = 0L,
    var currentMedia: AerialMedia? = null,
)
