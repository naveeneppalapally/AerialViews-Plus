package com.neilturner.aerialviews.providers.youtube

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.Log
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.neilturner.aerialviews.models.prefs.AmazonVideoPrefs
import com.neilturner.aerialviews.models.prefs.AppleVideoPrefs
import com.neilturner.aerialviews.models.prefs.Comm1VideoPrefs
import com.neilturner.aerialviews.models.prefs.Comm2VideoPrefs
import com.neilturner.aerialviews.models.prefs.CustomFeedPrefs
import com.neilturner.aerialviews.models.prefs.ImmichMediaPrefs
import com.neilturner.aerialviews.models.prefs.LocalMediaPrefs
import com.neilturner.aerialviews.models.prefs.NCMemoriesMediaPrefs
import com.neilturner.aerialviews.models.prefs.SambaMediaPrefs
import com.neilturner.aerialviews.models.prefs.SambaMediaPrefs2
import com.neilturner.aerialviews.models.prefs.WebDavMediaPrefs
import com.neilturner.aerialviews.models.prefs.WebDavMediaPrefs2
import com.neilturner.aerialviews.models.prefs.YouTubeVideoPrefs
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

object YouTubeFeature {
    const val DAILY_REFRESH_WORK_NAME = "youtube_daily_refresh"
    const val STREAM_REFRESH_WORK_NAME = "youtube_stream_refresh"
    const val STARTUP_REFRESH_WORK_NAME = "youtube_startup_refresh"
    const val ON_DEMAND_REFRESH_WORK_NAME = "youtube_on_demand_refresh"

    @Volatile
    private var repository: YouTubeSourceRepository? = null
    private var initialized = false

    fun isInitialized() = initialized
    private val featureScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        val appContext = context.applicationContext
        featureScope.launch(Dispatchers.IO) {
            try {
                repository(appContext)
                initializePreferences(appContext)
                scheduleAutomaticRefresh(appContext)
                NewPipeHelper.init()
                Timber.tag(TAG).d("NewPipe initialized")
            } catch (exception: Exception) {
                Timber.tag(TAG).e(exception, "YouTube feature initialization failed")
            }
        }

        featureScope.launch(Dispatchers.IO) {
            delay(INITIAL_PREWARM_DELAY_MS)
            preWarmIfNeeded(this)
        }
    }

    fun preWarmIfNeeded(scope: CoroutineScope) {
        val currentRepository = repository ?: return
        scope.launch(Dispatchers.IO) {
            try {
                val cacheSize = currentRepository.getCacheSize()
                if (cacheSize < MIN_PREWARM_CACHE_SIZE) {
                    Timber.tag(TAG).d("Cache cold (%s videos), pre-warming", cacheSize)
                    // skipIfPopulated: the startup worker (or another prewarm)
                    // may already be filling; re-check under the lock instead
                    // of queueing a duplicate 25-query search behind it.
                    currentRepository.refreshSearchResults(skipIfPopulated = true)
                    Timber.tag(TAG).d("Pre-warm complete")
                } else {
                    Timber.tag(TAG).d("Cache warm (%s videos), skipping pre-warm", cacheSize)
                }
            } catch (exception: Exception) {
                Timber.tag(TAG).w(exception, "Pre-warm failed")
            }
        }
    }

    fun repository(context: Context): YouTubeSourceRepository {
        val appContext = context.applicationContext
        return repository
            ?: synchronized(this) {
                repository
                    ?: YouTubeSourceRepository(
                        context = appContext,
                        cacheDao = YouTubeCacheDatabase.getInstance(appContext).youtubeCacheDao(),
                        watchHistoryDao = YouTubeCacheDatabase.getInstance(appContext).youtubeWatchHistoryDao(),
                        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(appContext),
                    ).also { repository = it }
            }
    }

    fun requestImmediateRefresh(
        context: Context,
        forceSearchRefresh: Boolean = true,
    ) {
        if (forceSearchRefresh) {
            markCountPending()
        }

        val constraints =
            Constraints
                .Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

        val request =
            OneTimeWorkRequestBuilder<YouTubeRefreshWorker>()
                .setConstraints(constraints)
                .setInputData(
                    workDataOf(YouTubeRefreshWorker.KEY_FORCE_SEARCH_REFRESH to forceSearchRefresh),
                )
                .addTag(ON_DEMAND_REFRESH_WORK_NAME)
                .build()

        val workManager = WorkManager.getInstance(context.applicationContext)

        workManager
            .enqueueUniqueWork(
                ON_DEMAND_REFRESH_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
    }

    fun markCountPending() {
        YouTubeVideoPrefs.count = PENDING_CACHE_COUNT
    }

    fun markQualitySelectionExplicit(context: Context) {
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext).edit {
            putBoolean(KEY_QUALITY_USER_SELECTED, true)
        }
    }

    fun isOnlyYouTubeSourceEnabled(): Boolean =
        YouTubeVideoPrefs.enabled &&
            !AppleVideoPrefs.enabled &&
            !AmazonVideoPrefs.enabled &&
            !Comm1VideoPrefs.enabled &&
            !Comm2VideoPrefs.enabled &&
            !LocalMediaPrefs.enabled &&
            !SambaMediaPrefs.enabled &&
            !SambaMediaPrefs2.enabled &&
            !WebDavMediaPrefs.enabled &&
            !WebDavMediaPrefs2.enabled &&
            !ImmichMediaPrefs.enabled &&
            !NCMemoriesMediaPrefs.enabled &&
            !CustomFeedPrefs.enabled

    private fun scheduleAutomaticRefresh(context: Context) {
        val workManager = WorkManager.getInstance(context)
        val constraints =
            Constraints
                .Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

        val dailyRequest =
            PeriodicWorkRequestBuilder<YouTubeRefreshWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(millisecondsUntilNextThreeAm(), TimeUnit.MILLISECONDS)
                .setConstraints(constraints)
                .setInputData(
                    workDataOf(YouTubeRefreshWorker.KEY_FORCE_SEARCH_REFRESH to true),
                )
                .addTag(DAILY_REFRESH_WORK_NAME)
                .build()

        val streamRequest =
            PeriodicWorkRequestBuilder<YouTubeRefreshWorker>(STREAM_REFRESH_INTERVAL_MINUTES, TimeUnit.MINUTES)
                .setInitialDelay(STREAM_REFRESH_INITIAL_DELAY_MINUTES, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setInputData(
                    workDataOf(YouTubeRefreshWorker.KEY_FORCE_SEARCH_REFRESH to false),
                )
                .addTag(STREAM_REFRESH_WORK_NAME)
                .build()

        val startupRequest =
            OneTimeWorkRequestBuilder<YouTubeRefreshWorker>()
                .setInitialDelay(STARTUP_REFRESH_INITIAL_DELAY_SECONDS, TimeUnit.SECONDS)
                .setConstraints(constraints)
                .setInputData(
                    workDataOf(YouTubeRefreshWorker.KEY_FORCE_SEARCH_REFRESH to false),
                )
                .addTag(STARTUP_REFRESH_WORK_NAME)
                .build()

        workManager.enqueueUniquePeriodicWork(
            DAILY_REFRESH_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            dailyRequest,
        )
        workManager.enqueueUniquePeriodicWork(
            STREAM_REFRESH_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            streamRequest,
        )
        workManager.enqueueUniqueWork(
            STARTUP_REFRESH_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            startupRequest,
        )
    }

    private fun initializePreferences(context: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val hasInitializedQuality = prefs.getBoolean(KEY_QUALITY_INITIALIZED, false)
        val configuredQuality = prefs.getString(YouTubeSourceRepository.KEY_QUALITY, null)?.trim()
        val userSelectedQuality = prefs.getBoolean(KEY_QUALITY_USER_SELECTED, false)
        val displayHeight = maxDisplayHeight(context)
        val deviceDefaultQuality = defaultQualityForDisplay(displayHeight)
        val resolvedQuality =
            when {
                configuredQuality.isNullOrBlank() -> deviceDefaultQuality
                configuredQuality.equals("4k", ignoreCase = true) -> UHD_QUALITY
                !userSelectedQuality -> deviceDefaultQuality
                else -> configuredQuality
            }

        Log.i(
            TAG,
            "YouTube quality init: displayHeight=${displayHeight}p configured=$configuredQuality userSelected=$userSelectedQuality resolved=$resolvedQuality",
        )

        if (!hasInitializedQuality || resolvedQuality != configuredQuality) {
            prefs.edit {
                putString(YouTubeSourceRepository.KEY_QUALITY, resolvedQuality)
                putBoolean(KEY_QUALITY_INITIALIZED, true)
            }
        }

        if (!prefs.contains(YouTubeSourceRepository.KEY_ENABLED)) {
            prefs.edit {
                putBoolean(YouTubeSourceRepository.KEY_ENABLED, true)
            }
        }

        if (!prefs.contains(YouTubeSourceRepository.KEY_MIX_WEIGHT)) {
            prefs.edit {
                putString(YouTubeSourceRepository.KEY_MIX_WEIGHT, YouTubeSourceRepository.DEFAULT_MIX_WEIGHT)
            }
        }

        prefs.edit {
            if (!prefs.contains(YouTubeSourceRepository.KEY_CATEGORY_NATURE)) putBoolean(YouTubeSourceRepository.KEY_CATEGORY_NATURE, true)
            if (!prefs.contains(YouTubeSourceRepository.KEY_CATEGORY_ANIMALS)) putBoolean(YouTubeSourceRepository.KEY_CATEGORY_ANIMALS, true)
            if (!prefs.contains(YouTubeSourceRepository.KEY_CATEGORY_DRONE)) putBoolean(YouTubeSourceRepository.KEY_CATEGORY_DRONE, true)
            if (!prefs.contains(YouTubeSourceRepository.KEY_CATEGORY_CITIES)) putBoolean(YouTubeSourceRepository.KEY_CATEGORY_CITIES, true)
            if (!prefs.contains(YouTubeSourceRepository.KEY_CATEGORY_SPACE)) putBoolean(YouTubeSourceRepository.KEY_CATEGORY_SPACE, true)
            if (!prefs.contains(YouTubeSourceRepository.KEY_CATEGORY_OCEAN)) putBoolean(YouTubeSourceRepository.KEY_CATEGORY_OCEAN, true)
            if (!prefs.contains(YouTubeSourceRepository.KEY_CATEGORY_WEATHER)) putBoolean(YouTubeSourceRepository.KEY_CATEGORY_WEATHER, true)
            if (!prefs.contains(YouTubeSourceRepository.KEY_CATEGORY_WINTER)) putBoolean(YouTubeSourceRepository.KEY_CATEGORY_WINTER, true)
        }
    }

    internal fun defaultQualityForDisplay(displayHeight: Int): String {
        return when {
            displayHeight >= UHD_HEIGHT -> UHD_QUALITY
            displayHeight >= QHD_HEIGHT -> QHD_QUALITY
            // 720p-and-below screens still default to 1080p: native 720p
            // YouTube renditions are low-bitrate and look soft; 1080p
            // downscaled is visibly better and always available.
            else -> FULL_HD_QUALITY
        }
    }

    private fun maxDisplayHeight(context: Context): Int {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return FULL_HD_HEIGHT
        return displayManager.displays
            .flatMap { display ->
                buildList {
                    display.mode?.physicalHeight?.let(::add)
                    addAll(display.supportedModes.map { mode -> mode.physicalHeight })
                }
            }.maxOrNull()
            ?: FULL_HD_HEIGHT
    }

    private fun millisecondsUntilNextThreeAm(): Long {
        val now = ZonedDateTime.now()
        var nextRun = now.with(LocalTime.of(3, 0)).withSecond(0).withNano(0)
        if (!nextRun.isAfter(now)) {
            nextRun = nextRun.plusDays(1)
        }
        return Duration.between(now, nextRun).toMillis().coerceAtLeast(0L)
    }

    private const val STREAM_REFRESH_INTERVAL_MINUTES = 330L
    private const val STREAM_REFRESH_INITIAL_DELAY_MINUTES = 15L
    private const val STARTUP_REFRESH_INITIAL_DELAY_SECONDS = 90L
    private const val MIN_PREWARM_CACHE_SIZE = 10
    private const val INITIAL_PREWARM_DELAY_MS = 10_000L
    private const val KEY_QUALITY_INITIALIZED = "yt_quality_initialized"
    private const val KEY_QUALITY_USER_SELECTED = "yt_quality_user_selected"
    private const val PENDING_CACHE_COUNT = "-1"
    private const val TAG = "YouTubeFeature"
    private const val UHD_QUALITY = "2160p"
    private const val QHD_QUALITY = "1440p"
    private const val FULL_HD_QUALITY = "1080p"
    private const val HD_QUALITY = "720p"
    private const val UHD_HEIGHT = 2160
    private const val QHD_HEIGHT = 1440
    private const val FULL_HD_HEIGHT = 1080
}
