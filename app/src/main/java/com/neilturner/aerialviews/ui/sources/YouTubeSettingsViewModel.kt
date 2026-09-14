package com.neilturner.aerialviews.ui.sources

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.neilturner.aerialviews.providers.youtube.YouTubeFeature
import com.neilturner.aerialviews.providers.youtube.YouTubeLibraryState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import timber.log.Timber

class YouTubeSettingsViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val repository = YouTubeFeature.repository(application)
    private var backgroundRefreshJob: Job? = null

    /**
     * Single source of truth for the library counter (Phase 2, sole owner
     * since Phase 4). The Fragment renders this flow passively; one-shot
     * toasts still travel over [events].
     */
    val libraryState: StateFlow<YouTubeLibraryState> =
        repository.libraryState

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
    }

    fun refreshIfCachePending() {
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
        // Instant feel: CategoryPending draws immediately, then a read-only
        // preview drops the counter to the post-delete count within
        // milliseconds — no debounce wait. The debounced delta below confirms
        // with the real DB-committed count and handles backfill.
        repository.noteCategoryPending()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.previewCategoryRemoval() }
        }
        backgroundRefreshJob?.cancel()
        backgroundRefreshJob =
            viewModelScope.launch {
                // Short debounce: coalesces double-taps into one delta run
                // against final prefs. Deletion itself is cheap (instant path
                // above); this window only delays the confirm + backfill.
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

    companion object {
        private const val CATEGORY_TOGGLE_DEBOUNCE_MS = 350L
    }
}
