package com.neilturner.aerialviews.ui.sources

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.neilturner.aerialviews.providers.youtube.YouTubeFeature
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import timber.log.Timber

class YouTubeSettingsViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val repository = YouTubeFeature.repository(application)
    private val _refreshState = MutableStateFlow<RefreshState>(RefreshState.Idle)
    private val _cacheSize = MutableStateFlow(-1)
    private var backgroundRefreshJob: Job? = null

    val refreshState: StateFlow<RefreshState> = _refreshState.asStateFlow()
    val cacheSize: StateFlow<Int> = _cacheSize.asStateFlow()
    val settingsUiState: StateFlow<YouTubeSettingsUiState> = combine(
        repository.isRefreshingFlow,
        repository.cacheLoadingProgress.onStart { emit(null) },
        _cacheSize,
    ) { isRefreshing, progressPair, cacheCount ->
        val progress = progressPair?.let { (current, total) -> YouTubeRefreshProgress(current, total) }
        YouTubeSettingsUiState(
            stage = deriveStage(isRefreshing, progressPair),
            isRefreshing = isRefreshing,
            cacheCount = cacheCount,
            progress = progress,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        YouTubeSettingsUiState(
            stage = YouTubeRefreshStage.IDLE,
            isRefreshing = false,
            cacheCount = _cacheSize.value,
            progress = null,
        ),
    )

    private val _events = Channel<YouTubeSettingsEvent>(capacity = Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    sealed interface YouTubeSettingsEvent {
        data class CategoryRemoved(
            val removedCount: Int,
            val remainingCount: Int,
        ) : YouTubeSettingsEvent

        data class CategoryAdded(
            val addedCount: Int,
            val totalCount: Int,
        ) : YouTubeSettingsEvent

        data object AllCategoriesDisabled : YouTubeSettingsEvent
        data object LibraryFullOnCategory : YouTubeSettingsEvent
        data object RefreshAlreadyInProgress : YouTubeSettingsEvent

        data class BotBlocked(
            val cooldownMinutes: Long,
        ) : YouTubeSettingsEvent
    }

    init {
        YouTubeFeature.preWarmIfNeeded(viewModelScope)
        viewModelScope.launch {
            repository.cacheCount.collect { count ->
                _cacheSize.value = count
            }
        }
        viewModelScope.launch {
            repository.refreshEvents.collect { event ->
                when (event) {
                    com.neilturner.aerialviews.providers.youtube.YouTubeSourceRepository.RefreshEvent.AlreadyInProgress -> {
                        _events.send(YouTubeSettingsEvent.RefreshAlreadyInProgress)
                    }
                    is com.neilturner.aerialviews.providers.youtube.YouTubeSourceRepository.RefreshEvent.BotBlocked -> {
                        _events.send(YouTubeSettingsEvent.BotBlocked(event.cooldownMinutes))
                    }
                }
            }
        }
        refreshCacheSize()
    }

    fun refreshIfCachePending() {
        if (_refreshState.value == RefreshState.Loading) {
            return
        }

        viewModelScope.launch {
            if (repository.getCacheSize() <= 0) {
                refreshInBackground()
            }
        }
    }

    fun refreshNow() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.triggerFullLibraryRebuild()
        }
    }

    fun refreshInBackground(forceSearchRefresh: Boolean = true) {
        YouTubeFeature.requestImmediateRefresh(getApplication(), forceSearchRefresh)
    }

    fun onCategoryChanged() {
        backgroundRefreshJob?.cancel()
        backgroundRefreshJob =
            viewModelScope.launch {
                // Debounce: rapid toggles coalesce into one delta refresh run
                // against the final prefs state, instead of serializing one
                // multi-minute backfill per toggle on the refresh mutex.
                kotlinx.coroutines.delay(CATEGORY_TOGGLE_DEBOUNCE_MS)
                try {
                    val result = repository.applyCategoryDeltaRefresh()
                    Timber.i(
                        "Category delta result: removedCategories=%d removedRows=%d postRemoval=%d inserted=%d final=%d allDisabled=%s",
                        result.removedCategoriesCount,
                        result.removedCount,
                        result.countAfterRemoval,
                        result.insertedCount,
                        result.finalCount,
                        result.allCategoriesDisabled,
                    )
                    // Single source of truth: toast only the actual outcome.
                    // (The old optimistic pre-toast could contradict this.)
                    if (result.removedCategoriesCount > 0 || result.removedCount > 0) {
                        _events.send(
                            YouTubeSettingsEvent.CategoryRemoved(
                                removedCount = result.removedCount,
                                remainingCount = result.countAfterRemoval,
                            ),
                        )
                    }
                    if (result.insertedCount > 0) {
                        _events.send(
                            YouTubeSettingsEvent.CategoryAdded(
                                addedCount = result.insertedCount,
                                totalCount = result.finalCount,
                            ),
                        )
                    }
                    if (result.allCategoriesDisabled) {
                        _events.send(YouTubeSettingsEvent.AllCategoriesDisabled)
                    } else if (result.insertedCount == 0 && result.libraryFull) {
                        // User tried to turn on a category but we didn't insert anything because cache is full
                        _events.send(YouTubeSettingsEvent.LibraryFullOnCategory)
                    }
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    Timber.e(exception, "Failed to apply YouTube category delta refresh")
                }
            }
    }

    fun scheduleBackgroundRefresh(delayMs: Long = 750L, forceSearchRefresh: Boolean = true) {
        backgroundRefreshJob?.cancel()
        backgroundRefreshJob =
            viewModelScope.launch {
                if (delayMs > 0) {
                    kotlinx.coroutines.delay(delayMs)
                }
                refreshInBackground(forceSearchRefresh)
            }
    }

    fun refreshCacheSize() {
        viewModelScope.launch {
            _cacheSize.value = repository.getCacheSize()
        }
    }

    fun setDisplayedCacheSize(count: Int) {
        _cacheSize.value = count.coerceAtLeast(0)
    }

    fun clearRefreshState() {
        _refreshState.value = RefreshState.Idle
    }

    private fun deriveStage(isRefreshing: Boolean, progress: Pair<Int, Int>?): YouTubeRefreshStage =
        when {
            !isRefreshing -> YouTubeRefreshStage.IDLE
            progress == null -> YouTubeRefreshStage.FINALIZING
            progress.first < 0 || progress.second < 0 -> YouTubeRefreshStage.SEARCHING
            else -> YouTubeRefreshStage.EXTRACTING
        }

    companion object {
        private const val CATEGORY_TOGGLE_DEBOUNCE_MS = 1500L
    }
}

sealed interface RefreshState {
    data object Idle : RefreshState

    data object Loading : RefreshState

    data class Success(
        val count: Int,
    ) : RefreshState

    data object Error : RefreshState
}

data class YouTubeSettingsUiState(
    val stage: YouTubeRefreshStage,
    val isRefreshing: Boolean,
    val cacheCount: Int,
    val progress: YouTubeRefreshProgress?,
)

enum class YouTubeRefreshStage {
    IDLE,
    SEARCHING,
    EXTRACTING,
    FINALIZING,
}

data class YouTubeRefreshProgress(
    val current: Int,
    val total: Int,
)
