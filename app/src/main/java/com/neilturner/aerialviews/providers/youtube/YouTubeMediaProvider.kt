package com.neilturner.aerialviews.providers.youtube

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import com.neilturner.aerialviews.models.enums.AerialMediaSource
import com.neilturner.aerialviews.models.enums.AerialMediaType
import com.neilturner.aerialviews.models.enums.ProviderSourceType
import com.neilturner.aerialviews.models.prefs.YouTubeVideoPrefs
import com.neilturner.aerialviews.models.videos.AerialMedia
import com.neilturner.aerialviews.models.videos.AerialExifMetadata
import com.neilturner.aerialviews.models.videos.AerialMediaMetadata
import com.neilturner.aerialviews.providers.MediaProvider
import com.neilturner.aerialviews.providers.ProviderFetchResult
import com.neilturner.aerialviews.data.network.NetworkHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

class YouTubeMediaProvider(
    context: Context,
    private val repository: YouTubeSourceRepository = YouTubeFeature.repository(context),
) : MediaProvider(context) {
    private val providerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val type = ProviderSourceType.REMOTE

    override val enabled: Boolean
        get() = YouTubeVideoPrefs.enabled

    // Quality/category/strategy changes must invalidate the playlist cache;
    // the base implementation returns a constant, which kept stale playlists
    // (and stale stream URLs) alive across settings changes.
    override fun settingsHash(): String =
        YouTubeVideoPrefs.settingsHash() + "|selector=v" + YouTubeSourceRepository.STREAM_SELECTION_STRATEGY_VERSION

    override suspend fun fetch(): ProviderFetchResult {
        // Startup must always prefer immediately playable local cache entries.
        // Signature/version refresh can run in background, but should not block initial playback.
        val startupCachedEntries = repository.getCachedVideosSnapshot()
        if (startupCachedEntries.isEmpty()) {
            return ProviderFetchResult.Success(media = fetchInitialMedia(), summary = "")
        }

        Log.i(TAG, "Using local startup cache entries=${startupCachedEntries.size}")
        repository.preWarmInBackground()
        return ProviderFetchResult.Success(media = startupCachedEntries.toAerialMedia(), summary = "")
    }

    override suspend fun fetchMetadata(media: List<AerialMedia>): List<AerialMedia> = media

    private suspend fun fetchInitialMedia(): List<AerialMedia> {
        YouTubeFeature.markCountPending()

        if (!NetworkHelper.isInternetAvailable(context)) {
            Timber.tag(TAG).w("Cache empty and network unavailable for first-run YouTube fetch")
            return emptyList()
        }

        repository.preWarmInBackground()

        val warmedCacheEntries = waitForStartupCacheWarm()
        if (warmedCacheEntries.isNotEmpty()) {
            Log.i(TAG, "Using warmed startup cache entries=${warmedCacheEntries.size}")
            repository.preWarmInBackground()
            return warmedCacheEntries.toAerialMedia()
        }

        val bootstrapMedia = buildBootstrapMedia()
        if (bootstrapMedia.isEmpty()) {
            return emptyList()
        }

        bootstrapMedia
            .take(BOOTSTRAP_PRE_RESOLVE_COUNT)
            .forEach { bootstrapItem ->
                repository.preResolveVideo(bootstrapItem.uri.toString(), providerScope)
            }
        Log.i(TAG, "Startup bootstrap URLs queued for pre-resolve count=${bootstrapMedia.take(BOOTSTRAP_PRE_RESOLVE_COUNT).size}")
        Log.i(TAG, "Using bootstrap startup playlist size=${bootstrapMedia.size}")
        return bootstrapMedia
    }

    // Cold start holds the loading screen briefly for a viable playlist
    // instead of flashing 2 bootstrap clips: mid-session prebuild (see
    // ScreenController) upgrades small playlists as refresh lands entries.
    // Partial snapshots survive the timeout — 3 real videos beat bootstrap.
    private suspend fun waitForStartupCacheWarm(): List<YouTubeCacheEntity> {
        var latest = emptyList<YouTubeCacheEntity>()
        withTimeoutOrNull(STARTUP_CACHE_WARM_WAIT_MS) {
            while (latest.size < MIN_STARTUP_CACHE_ENTRIES) {
                latest = repository.getCachedVideosSnapshot()
                if (latest.size < MIN_STARTUP_CACHE_ENTRIES) {
                    delay(INITIAL_CACHE_POLL_INTERVAL_MS)
                }
            }
        }
        return latest
    }

    private fun List<YouTubeCacheEntity>.toAerialMedia(): List<AerialMedia> {
        if (isEmpty()) {
            return emptyList()
        }

        firstOrNull()?.let { firstEntry ->
            if (repository.playbackUrl(firstEntry) == firstEntry.videoPageUrl) {
                repository.preResolveVideo(firstEntry.videoPageUrl, providerScope)
            } else {
                repository.preResolveNext(providerScope)
            }
        }

        val directPlaybackCount =
            count { entry ->
                repository.playbackUrl(entry) != entry.videoPageUrl
            }
        Timber.tag(TAG).i(
            "Preparing %s cached YouTube playlist items (freshDirect=%s, directWindow=%s)",
            size,
            directPlaybackCount,
            DIRECT_PLAYBACK_WINDOW,
        )

        return mapIndexed { index, entry ->
            toAerialMedia(
                entry = entry,
                useDirectPlaybackUrl = index < DIRECT_PLAYBACK_WINDOW,
            )
        }
    }

    private fun buildBootstrapMedia(): MutableList<AerialMedia> =
        STARTUP_BOOTSTRAP_VIDEO_URLS.shuffled().map { videoPageUrl ->
            val videoId = extractBootstrapVideoId(videoPageUrl)
            AerialMedia(
                uri = videoPageUrl.toUri(),
                type = AerialMediaType.VIDEO,
                source = AerialMediaSource.YOUTUBE,
                metadata =
                    AerialMediaMetadata(
                        shortDescription = "YouTube Startup Bootstrap",
                        exif =
                            AerialExifMetadata(
                                description = videoId,
                                durationSeconds = 0,
                            ),
                    ),
            )
        }.toMutableList()

    private fun extractBootstrapVideoId(videoPageUrl: String): String =
        Regex("[?&]v=([^&#]+)")
            .find(videoPageUrl)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?: videoPageUrl

    private fun toAerialMedia(
        entry: YouTubeCacheEntity,
        useDirectPlaybackUrl: Boolean,
    ): AerialMedia {
        val playbackUrl = if (useDirectPlaybackUrl) {
            repository.playbackUrl(entry)
        } else {
            entry.videoPageUrl
        }
        val audioPlaybackUrl =
            if (playbackUrl != entry.videoPageUrl) {
                repository.playbackAudioUrl(entry)
            } else {
                ""
            }
        
        return AerialMedia(
            uri = playbackUrl.toUri(),
            type = AerialMediaType.VIDEO,
            source = AerialMediaSource.YOUTUBE,
            streamUrl = if (playbackUrl != entry.videoPageUrl) playbackUrl else "",
            audioStreamUrl = audioPlaybackUrl,
            metadata =
                AerialMediaMetadata(
                    shortDescription = entry.title,
                    exif =
                        AerialExifMetadata(
                            description = entry.videoId,
                            durationSeconds = entry.durationSeconds,
                        ),
                ),
        )
    }

    companion object {
        private const val STARTUP_CACHE_WARM_WAIT_MS = 15_000L
        private const val MIN_STARTUP_CACHE_ENTRIES = 6
        private const val INITIAL_CACHE_POLL_INTERVAL_MS = 250L
        private const val BOOTSTRAP_PRE_RESOLVE_COUNT = 2
        private const val DIRECT_PLAYBACK_WINDOW = 12
        private const val TAG = "YouTubeMedia"
        private val STARTUP_BOOTSTRAP_VIDEO_URLS =
            listOf(
                "https://www.youtube.com/watch?v=BJDa5KCwX-k", // Space: NASA 4K ISS aurora & Earth limb (Gemini: 95%)
                "https://www.youtube.com/watch?v=Z6dQT6jcVuc", // Space: Milky Way night sky timelapse (Gemini: 95%)
                "https://www.youtube.com/watch?v=1K6pNNEyJiI", // Space: Cygnus & Canadarm2 robotic arm (Gemini: 92%)
                "https://www.youtube.com/watch?v=DhUj_TlqyfM", // Winter: Snowfall on plum blossoms (Gemini: 92%)
                "https://www.youtube.com/watch?v=4sFxA80m9cw", // Winter: Snow-covered pine forest aerial (Gemini: 92%)
                "https://www.youtube.com/watch?v=GtvT6XIk-sg", // Cities: NYC Midtown Manhattan 4K drone (Gemini: 88%)
                "https://www.youtube.com/watch?v=u14It3zOcrk", // Weather: Baltic Sea storm clouds & golden sunset (Gemini: 88%)
                "https://www.youtube.com/watch?v=O2X3fJZic2s", // Weather: Stormy ocean waves & dramatic clouds (Gemini: 88%)
                "https://www.youtube.com/watch?v=kD-eN-Iw7dw", // Drone: Autumn Norway forest & mountains (Gemini: 85%)
                "https://www.youtube.com/watch?v=1wo5i8STpGE", // Drone: Norway lake & wooden cabin aerial (Gemini: 85%)
                "https://www.youtube.com/watch?v=ObuLT6imvrw", // Cities: Tokyo golden hour skyline panorama (Gemini: 85%)
                "https://www.youtube.com/watch?v=GnaOHWO2VcU", // Cities: NYC nighttime illuminated skyline (Gemini: 85%)
                "https://www.youtube.com/watch?v=Y_GRiXA8WiI", // Ocean: Raja Ampat coral garden & sweetlips (Gemini: 85%)
                "https://www.youtube.com/watch?v=JXHbBZNcxp0", // Winter: Snow-covered forest river flowing (Gemini: 85%)
                "https://www.youtube.com/watch?v=7qcWowgUjKo", // Space: Dark night sky starry peaks timelapse (Gemini: 85%)
                "https://www.youtube.com/watch?v=52Uocxi4--A", // Ocean: Bonaire underwater coral & spotted pufferfish (Gemini: 78%)
            )
    }
}
