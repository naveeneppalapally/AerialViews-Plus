package com.neilturner.aerialviews.providers.youtube

import android.content.Context

class NewPipeStreamExtractor(
    private val appContext: Context,
) : StreamExtractor {
    override suspend fun extractPlaybackStreams(
        videoPageUrl: String,
        preferredQuality: String,
        preferVideoOnly: Boolean,
        allowAdaptiveManifests: Boolean,
        preferAdaptiveManifests: Boolean,
        preferManifests: Boolean,
    ): YouTubePlaybackUrls =
        NewPipeHelper.extractPlaybackStreams(
            videoPageUrl = videoPageUrl,
            context = appContext,
            preferredQuality = preferredQuality,
            preferVideoOnly = preferVideoOnly,
            allowAdaptiveManifests = allowAdaptiveManifests,
            preferAdaptiveManifests = preferAdaptiveManifests,
            preferManifests = preferManifests,
        ).let { playback ->
            YouTubeThrottling.noteExtractionSuccess()
            YouTubePlaybackUrls(videoUrl = playback.videoUrl, audioUrl = playback.audioUrl)
        }
}
