package com.neilturner.aerialviews.ui.sources

import android.os.Bundle
import android.text.format.DateUtils
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.SwitchPreference
import com.neilturner.aerialviews.R
import com.neilturner.aerialviews.models.prefs.YouTubeVideoPrefs
import com.neilturner.aerialviews.providers.youtube.YouTubeFeature
import com.neilturner.aerialviews.providers.youtube.YouTubeSourceRepository

import com.neilturner.aerialviews.services.Display
import com.neilturner.aerialviews.ui.helpers.DialogHelper
import com.neilturner.aerialviews.ui.controls.MenuStateFragment
import com.neilturner.aerialviews.ui.helpers.ToastHelper
import kotlinx.coroutines.launch

class YouTubeSettingsFragment : MenuStateFragment() {
    private val viewModel by viewModels<YouTubeSettingsViewModel>()
    private var refreshInProgress = false
    private val sharedPreferenceListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                YouTubeSourceRepository.KEY_COUNT -> {
                    viewModel.refreshCacheSize()
                }
                YouTubeSourceRepository.KEY_ENABLED -> {
                    if (!YouTubeVideoPrefs.enabled) {
                        refreshInProgress = false
                        viewModel.setDisplayedCacheSize(0)
                        updateVideoCount(staticCount = 0)
                        updateCacheCountPreference(cachedCount = 0, stage = YouTubeRefreshStage.IDLE)
                    } else {
                        viewModel.refreshCacheSize()
                    }
                    updateMixWeightLink()
                }
                YouTubeSourceRepository.KEY_MIX_WEIGHT -> {
                    updateMixWeightLink()
                }
                in CATEGORY_PREFERENCE_KEYS -> {
                    Log.d(TAG, "Category pref persisted: key=$key")
                    // Debounced + coalesced in the ViewModel; immediate
                    // started-toast here so toggle-ON (minutes of backfill)
                    // gives instant feedback.
                    view?.post { queueCategoryRefresh(showStartedToast = true) }
                        ?: queueCategoryRefresh(showStartedToast = true)
                }
            }
        }

    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        setPreferencesFromResource(R.xml.sources_youtube_settings, rootKey)
        setupPreferences()
        if (YouTubeVideoPrefs.enabled) {
            viewModel.refreshCacheSize()
        } else {
            viewModel.setDisplayedCacheSize(0)
        }
        // No refreshIfCachePending() here: onResume() runs immediately after
        // first creation and covers it. Kicking in both double-starts refresh.
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.refreshState.collect { state ->
                renderRefreshState(state)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.settingsUiState.collect { state ->
                renderSettingsState(state)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            YouTubeFeature.repository(requireContext()).cacheFullEvent.collect { isFull ->
                if (isFull) {
                    ToastHelper.show(
                        requireContext(),
                        R.string.youtube_cache_full_toast,
                        Toast.LENGTH_LONG,
                    )
                    YouTubeFeature.repository(requireContext()).consumeCacheFullEvent()
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.events.collect { event ->
                when (event) {
                    is YouTubeSettingsViewModel.YouTubeSettingsEvent.CategoryRemoved -> {
                        // Toast carries the transient message; the summary below
                        // always renders live state (loading progress, then the
                        // final count) so it never implies an early finish.
                        Log.i(
                            TAG,
                            "Showing category-removed toast: removed=${event.removedCount}, " +
                                "remainingAfterRemoval=${event.remainingCount}",
                        )
                        updateVideoCount(staticCount = event.remainingCount)
                        updateCacheCountPreference(
                            cachedCount = event.remainingCount,
                            stage = YouTubeRefreshStage.EXTRACTING,
                        )
                        ToastHelper.show(
                            requireContext(),
                            getString(R.string.youtube_videos_removed_toast, event.removedCount, event.remainingCount),
                            Toast.LENGTH_LONG,
                        )
                    }
                    is YouTubeSettingsViewModel.YouTubeSettingsEvent.CategoryAdded -> {
                        updateVideoCount(staticCount = event.totalCount)
                        updateCacheCountPreference(
                            cachedCount = event.totalCount,
                            stage = YouTubeRefreshStage.EXTRACTING,
                        )
                        ToastHelper.show(
                            requireContext(),
                            getString(R.string.youtube_videos_added_toast, event.addedCount, event.totalCount),
                            Toast.LENGTH_LONG,
                        )
                    }
                    YouTubeSettingsViewModel.YouTubeSettingsEvent.AllCategoriesDisabled -> {
                        ToastHelper.show(
                            requireContext(),
                            R.string.youtube_no_categories_selected_toast,
                            Toast.LENGTH_LONG,
                        )
                    }
                    YouTubeSettingsViewModel.YouTubeSettingsEvent.LibraryFullOnCategory -> {
                        ToastHelper.show(
                            requireContext(),
                            R.string.youtube_cache_full_on_category_toast,
                            Toast.LENGTH_LONG,
                        )
                    }
                    YouTubeSettingsViewModel.YouTubeSettingsEvent.RefreshAlreadyInProgress -> {
                        ToastHelper.show(
                            requireContext(),
                            R.string.youtube_refresh_already_in_progress_toast,
                            Toast.LENGTH_SHORT,
                        )
                    }
                    is YouTubeSettingsViewModel.YouTubeSettingsEvent.BotBlocked -> {
                        ToastHelper.show(
                            requireContext(),
                            getString(
                                R.string.youtube_refresh_rate_limited,
                                event.cooldownMinutes.coerceAtLeast(1L),
                            ),
                            Toast.LENGTH_LONG,
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateMixWeightLink()
        if (YouTubeVideoPrefs.enabled) {
            viewModel.refreshCacheSize()
        } else {
            viewModel.setDisplayedCacheSize(0)
        }
        if (YouTubeVideoPrefs.enabled && isCountPending()) {
            markRefreshInProgress()
            viewModel.refreshIfCachePending()
        }
    }

    override fun onStart() {
        super.onStart()
        PreferenceManager
            .getDefaultSharedPreferences(requireContext())
            .registerOnSharedPreferenceChangeListener(sharedPreferenceListener)
    }

    override fun onStop() {
        PreferenceManager
            .getDefaultSharedPreferences(requireContext())
            .unregisterOnSharedPreferenceChangeListener(sharedPreferenceListener)
        super.onStop()
    }

    private fun setupPreferences() {
        configureQualityPreference()
        configurePlaybackLengthPreferences()

        findPreference<SwitchPreference>("yt_enabled")?.setOnPreferenceChangeListener { _, newValue ->
            if (newValue == true) {
                viewLifecycleOwner.lifecycleScope.launch {
                    val existingCount =
                        runCatching {
                            YouTubeFeature.repository(requireContext()).getCacheSize()
                        }.getOrDefault(0)
                    if (existingCount > 0) {
                        refreshInProgress = false
                        viewModel.setDisplayedCacheSize(existingCount)
                        updateVideoCount(staticCount = existingCount)
                        updateCacheCountPreference(
                            cachedCount = existingCount,
                            stage = YouTubeRefreshStage.IDLE,
                        )
                        // Keep cache warm but avoid a forced full rebuild when enabling with existing data.
                        queueBackgroundRefresh(
                            R.string.youtube_refresh_started,
                            immediate = false,
                            forceSearchRefresh = false,
                        )
                    } else {
                        viewModel.setDisplayedCacheSize(0)
                        queueBackgroundRefresh(R.string.youtube_rebuilding_library, immediate = true)
                    }
                }
            } else {
                refreshInProgress = false
                viewModel.setDisplayedCacheSize(0)
                updateVideoCount(staticCount = 0)
                updateCacheCountPreference(cachedCount = 0, stage = YouTubeRefreshStage.IDLE)
            }
            true
        }

        findPreference<Preference>("yt_refresh_now")?.setOnPreferenceClickListener {
            // Instant feedback: the worker's first progress emit is seconds
            // away, and without this double-taps queue duplicate rebuilds.
            markRefreshInProgress()
            viewLifecycleOwner.lifecycleScope.launch {
                ToastHelper.show(requireContext(), R.string.youtube_rebuilding_library, Toast.LENGTH_LONG)
            }
            viewModel.refreshNow()
            true
        }

        CATEGORY_PREFERENCE_KEYS.forEach { key ->
            findPreference<SwitchPreference>(key)?.setOnPreferenceChangeListener { _, newValue ->
                Log.d(TAG, "Category toggle changed: key=$key newValue=$newValue")
                // Persist first; sharedPreferenceListener will trigger category refresh from committed state.
                true
            }
        }
    }

    private fun configureQualityPreference() {
        val qualityPreference = findPreference<ListPreference>("yt_quality") ?: return
        // Cap the offered list by the real panel height, not the form factor:
        // treating every TV as 4K-capable showed 4K/1440p on 720p panels where
        // picking them only stalls (chip must fetch ~15-20 Mbps and decode 4x
        // the pixels for a screen that can't show them). Picking logic and
        // defaults are untouched — this only hides unplayable options.
        val panelHeight =
            runCatching { Display.get(requireContext()) }
                .mapCatching { display ->
                    (
                        display.supportedModes.map { it.height } +
                            listOfNotNull(
                                display.physicalOutput?.height,
                                display.renderOutput.height,
                            )
                    ).maxOrNull() ?: 0
                }.getOrDefault(0)
        Log.d(TAG, "Quality list capped by panel height: ${panelHeight}p")

        when {
            panelHeight >= 2160 -> {
                qualityPreference.setEntries(R.array.youtube_quality_entries_uhd)
                qualityPreference.setEntryValues(R.array.youtube_quality_values_uhd)
            }
            panelHeight >= 1440 -> {
                qualityPreference.setEntries(R.array.youtube_quality_entries)
                qualityPreference.setEntryValues(R.array.youtube_quality_values)
            }
            else -> {
                qualityPreference.setEntries(R.array.youtube_quality_entries_1080p)
                qualityPreference.setEntryValues(R.array.youtube_quality_values_1080p)
            }
        }

        // Reset to highest supported value if current value is not supported by this display,
        // or if it is 720p (the old hardcoded default — Highest Available is always better)
        val supportedValues = qualityPreference.entryValues?.map { it.toString() }.orEmpty()
        val currentValue = qualityPreference.value
        if (currentValue !in supportedValues || currentValue == "720p") {
            val highestSupported = supportedValues.firstOrNull() ?: "best"
            qualityPreference.value = highestSupported
            Log.d(TAG, "Reset quality to $highestSupported (was: $currentValue, not optimal for this display)")
            // Fragment scope, not view scope: this runs in onCreatePreferences
            // before the view exists.
            lifecycleScope.launch {
                ToastHelper.show(
                    requireContext(),
                    getString(R.string.youtube_quality_reset_notice, qualityPreference.entry),
                    Toast.LENGTH_LONG,
                )
            }
        }

        qualityPreference.setOnPreferenceChangeListener { _, _ ->
            YouTubeFeature.markQualitySelectionExplicit(requireContext())
            queueBackgroundRefresh(R.string.youtube_rebuilding_library, immediate = true)
            true
        }
        qualityPreference.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
    }

    private fun configurePlaybackLengthPreferences() {
        val modePreference = findPreference<ListPreference>("yt_playback_length_mode")
        val maxMinutesPreference = findPreference<ListPreference>("yt_playback_max_minutes")
        modePreference?.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()

        fun applyMaxMinutesState(mode: String?) {
            val useFullPlayback = mode?.trim()?.equals("full", ignoreCase = true) == true
            maxMinutesPreference ?: return
            maxMinutesPreference.isEnabled = !useFullPlayback
            if (useFullPlayback) {
                maxMinutesPreference.summaryProvider = null
                maxMinutesPreference.summary = getString(R.string.youtube_playback_max_minutes_disabled_summary)
            } else {
                maxMinutesPreference.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            }
        }

        applyMaxMinutesState(modePreference?.value)
        modePreference?.setOnPreferenceChangeListener { _, newValue ->
            val mode = (newValue as? String).orEmpty()
            applyMaxMinutesState(mode)
            true
        }
    }

    private fun renderRefreshState(state: RefreshState) {
        when (state) {
            RefreshState.Idle -> {
            }

            RefreshState.Loading -> {
                markRefreshInProgress()
            }

            is RefreshState.Success -> {
                YouTubeVideoPrefs.count = state.count.toString()
                updateVideoCount()
                markRefreshComplete(state.count)
                viewModel.refreshCacheSize()
                viewLifecycleOwner.lifecycleScope.launch {
                    ToastHelper.show(
                        requireContext(),
                        getString(R.string.youtube_refresh_success, state.count),
                        Toast.LENGTH_LONG,
                    )
                }
                viewModel.clearRefreshState()
            }

            RefreshState.Error -> {
                markRefreshFailed()
                viewLifecycleOwner.lifecycleScope.launch {
                    ToastHelper.show(
                        requireContext(),
                        R.string.youtube_refresh_failed,
                        Toast.LENGTH_LONG,
                    )
                }
                viewModel.refreshCacheSize()
                viewModel.clearRefreshState()
            }
        }
    }

    private fun renderSettingsState(state: YouTubeSettingsUiState) {
        if (!YouTubeVideoPrefs.enabled) {
            refreshInProgress = false
            updateVideoCount(staticCount = 0)
            updateCacheCountPreference(
                cachedCount = 0,
                stage = YouTubeRefreshStage.IDLE,
            )
            return
        }

        refreshInProgress = state.isRefreshing
        val liveCount =
            state.progress
                ?.current
                ?.takeIf { progressCount ->
                    progressCount >= 0 && state.stage != YouTubeRefreshStage.IDLE
                }
        val targetCount = state.progress?.total
        // Searching progress rides negative (done,total) so the stage stays
        // SEARCHING while the count visibly advances per query chunk.
        val searchProgress =
            state.progress
                ?.takeIf { state.stage == YouTubeRefreshStage.SEARCHING && it.current < 0 && it.total < 0 }
                ?.let { Pair(-it.current, -it.total) }
        val effectiveStaticCount =
            when {
                state.isRefreshing && liveCount == null -> null
                else -> state.cacheCount
            }
        val counterValueForSummary = liveCount ?: effectiveStaticCount
        updateVideoCount(liveCount = liveCount, staticCount = effectiveStaticCount)
        updateCacheCountPreference(
            cachedCount = counterValueForSummary,
            stage = state.stage,
            targetCount = targetCount ?: YOUTUBE_LIBRARY_TARGET_COUNT,
            searchProgress = searchProgress,
        )
    }

    private fun updateVideoCount(liveCount: Int? = null, staticCount: Int? = null) {
        val targetPreference = findPreference<Preference>("yt_enabled") ?: return

        val displayCount =
            when {
                liveCount != null -> liveCount
                staticCount != null -> staticCount
                refreshInProgress -> null
                !YouTubeVideoPrefs.enabled -> 0
                else -> YouTubeVideoPrefs.count.toIntOrNull()
            }

        targetPreference.summary =
            if (refreshInProgress && liveCount == null && staticCount == null) {
                getString(R.string.youtube_cache_count_pending)
            } else if (displayCount != null && displayCount >= 0) {
                getString(R.string.videos_count, displayCount)
            } else {
                null
            }
    }

    private fun updateMixWeightLink() {
        val linkPreference = findPreference<Preference>("yt_mix_weight_link") ?: return
        // Mirrors SourcesFragment: mix level only applies in combined mode.
        val sourceMode =
            preferenceManager.sharedPreferences?.getString(KEY_SOURCE_MODE, SOURCE_MODE_COMBINED)
        if (!YouTubeVideoPrefs.enabled || sourceMode != SOURCE_MODE_COMBINED) {
            linkPreference.summary = getString(R.string.youtube_mix_weight_disabled_summary)
            return
        }
        val values = resources.getStringArray(R.array.youtube_mix_weight_values)
        val entries = resources.getStringArray(R.array.youtube_mix_weight_entries)
        val index = values.indexOf(YouTubeVideoPrefs.mixWeight).takeIf { it >= 0 } ?: 0
        val label = entries.getOrElse(index) { YouTubeVideoPrefs.mixWeight }
        linkPreference.summary = getString(R.string.youtube_mix_weight_link_summary, label)
    }

    private fun isCountPending(): Boolean =
        YouTubeVideoPrefs.count.toIntOrNull()?.let { it < 0 } ?: true

    private fun queueBackgroundRefresh(
        messageResId: Int,
        immediate: Boolean = false,
        forceSearchRefresh: Boolean = true,
    ) {
        markRefreshInProgress()
        // Never blank the displayed count here: these refreshes preserve the
        // existing library (warm/quality change), so keep showing the last
        // known count until live progress arrives. Zeroing read as data loss.
        view?.post {
            if (immediate) {
                viewModel.scheduleBackgroundRefresh(delayMs = 0L, forceSearchRefresh = forceSearchRefresh)
            } else {
                viewModel.scheduleBackgroundRefresh(forceSearchRefresh = forceSearchRefresh)
            }
        } ?: run {
            if (immediate) {
                viewModel.scheduleBackgroundRefresh(delayMs = 0L, forceSearchRefresh = forceSearchRefresh)
            } else {
                viewModel.scheduleBackgroundRefresh(forceSearchRefresh = forceSearchRefresh)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            ToastHelper.show(requireContext(), messageResId, Toast.LENGTH_LONG)
        }
    }

    private fun queueCategoryRefresh(showStartedToast: Boolean = true) {
        markRefreshInProgress()
        viewModel.onCategoryChanged()
        if (showStartedToast) {
            viewLifecycleOwner.lifecycleScope.launch {
                ToastHelper.show(requireContext(), R.string.youtube_categories_changed_notice, Toast.LENGTH_LONG)
            }
        }
    }

    private fun updateCacheCountPreference(
        cachedCount: Int?,
        stage: YouTubeRefreshStage,
        targetCount: Int = YOUTUBE_LIBRARY_TARGET_COUNT,
        emptyHint: String? = null,
        searchProgress: Pair<Int, Int>? = null,
    ) {
        val cacheCountPreference = findPreference<Preference>(PREFERENCE_CACHE_COUNT) ?: return
        cacheCountPreference.summary =
            when {
                stage == YouTubeRefreshStage.SEARCHING && searchProgress != null ->
                    getString(
                        R.string.youtube_refresh_searching_progress,
                        searchProgress.first.coerceAtMost(searchProgress.second),
                        searchProgress.second,
                    )
                stage == YouTubeRefreshStage.SEARCHING -> getString(R.string.youtube_refresh_searching)
                stage != YouTubeRefreshStage.IDLE &&
                    cachedCount != null &&
                    cachedCount >= 0 ->
                    getString(
                        R.string.youtube_cache_loading_overlay,
                        cachedCount.coerceAtMost(targetCount),
                        targetCount,
                    )
                cachedCount != null && cachedCount > 0 -> describeFreshLibrary(cachedCount)
                cachedCount != null && cachedCount == 0 && !emptyHint.isNullOrBlank() -> emptyHint
                cachedCount != null && cachedCount >= 0 ->
                    getString(R.string.youtube_cache_count_summary, cachedCount)
                stage != YouTubeRefreshStage.IDLE -> getString(R.string.youtube_cache_count_pending)
                else -> null
            }
    }

    private fun describeFreshLibrary(cachedCount: Int): String {
        val lastSearchAt =
            PreferenceManager
                .getDefaultSharedPreferences(requireContext())
                .getLong(YouTubeSourceRepository.KEY_LAST_SEARCH_AT, 0L)
        if (lastSearchAt <= 0L) {
            return getString(R.string.youtube_cache_count_summary, cachedCount)
        }
        val relative =
            DateUtils.getRelativeTimeSpanString(
                lastSearchAt,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
            ).toString()
        return getString(R.string.youtube_cache_count_summary_updated, cachedCount, relative)
    }

    private fun markRefreshInProgress() {
        refreshInProgress = true
        updateCacheCountPreference(null, YouTubeRefreshStage.FINALIZING)
    }

    private fun markRefreshComplete(cachedCount: Int) {
        refreshInProgress = false
        updateCacheCountPreference(cachedCount, YouTubeRefreshStage.IDLE)
    }

    private fun markRefreshFailed() {
        refreshInProgress = false
        val count = YouTubeVideoPrefs.count.toIntOrNull()
        updateCacheCountPreference(
            count,
            YouTubeRefreshStage.IDLE,
            // Empty after a failure is actionable; a bare "0" is not.
            emptyHint = getString(R.string.youtube_cache_empty_retry),
        )
    }

    companion object {
        private const val TAG = "YouTubeSettingsFragment"
        private const val PREFERENCE_CACHE_COUNT = "yt_cache_count"
        private const val YOUTUBE_LIBRARY_TARGET_COUNT = 200
        private const val KEY_SOURCE_MODE = "source_mode"
        private const val SOURCE_MODE_COMBINED = "combined"
        private val CATEGORY_PREFERENCE_KEYS =
            listOf(
                "yt_category_nature",
                "yt_category_animals",
                "yt_category_drone",
                "yt_category_ocean",
                "yt_category_space",
                "yt_category_cities",
                "yt_category_weather",
                "yt_category_winter",
            )
    }
}
