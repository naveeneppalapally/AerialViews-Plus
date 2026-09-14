package com.neilturner.aerialviews.providers.youtube

/**
 * Single source of truth for the YouTube library counter (Phase 1).
 *
 * Legacy flows (cacheLoadingProgress, cacheCount, isRefreshingFlow) were
 * removed in Phase 4; this flow is now the only counter source.
 *
 * Contract:
 * - [persistedCount] is ALWAYS a Room-committed count, never an in-memory
 *   candidate accumulation.
 * - Only [Populating] and [Removing] carry a target denominator (200).
 *   [Searching] and [CategoryPending] render the same way (persisted / 200
 *   loading) — query progress (completed/total) stays in the state for
 *   logs/diagnostics only and is never shown as the library count.
 */
sealed interface YouTubeLibraryState {
    /** The actual number of valid videos currently persisted in SQLite. */
    val persistedCount: Int

    /** Source switch is toggled OFF. */
    data class Disabled(
        override val persistedCount: Int,
    ) : YouTubeLibraryState

    /** Normal resting state. Screensaver plays from these videos. */
    data class Idle(
        override val persistedCount: Int,
        val lastRefreshedAt: Long,
    ) : YouTubeLibraryState

    /**
     * Category toggle occurred, awaiting debounce before delta refresh.
     * Keeps UI honest during the debounce without reverting to Idle.
     */
    data class CategoryPending(
        override val persistedCount: Int,
    ) : YouTubeLibraryState

    /**
     * Actively deleting database rows belonging to disabled categories.
     * persistedCount reflects the exact count remaining immediately after
     * SQLite delete.
     */
    data class Removing(
        override val persistedCount: Int,
        val removedCount: Int,
        val targetCount: Int = TARGET_COUNT,
    ) : YouTubeLibraryState

    /**
     * Executing searches across category query formulas.
     * persistedCount is the currently persisted video count (rendered as
     * persisted / 200 loading). queriesCompleted/queriesTotal track the
     * query cursor for logs only — never as the library counter.
     * A zero total means the query plan is still being built.
     */
    data class Searching(
        override val persistedCount: Int,
        val candidatesFound: Int,
        val queriesCompleted: Int = 0,
        val queriesTotal: Int = 0,
    ) : YouTubeLibraryState

    /**
     * Inserting vetted videos into Room database.
     * STRICT CONTRACT: persistedCount is ALWAYS obtained from
     * `cacheDao.countGoodEntries()` AFTER a database insertion completes.
     */
    data class Populating(
        override val persistedCount: Int,
        val targetCount: Int = TARGET_COUNT,
    ) : YouTubeLibraryState

    /**
     * YouTube IP bot check is active. Serves intact cache.
     */
    data class BotBlocked(
        override val persistedCount: Int,
        val cooldownMinutes: Long,
    ) : YouTubeLibraryState

    /**
     * Refresh encountered network error or timeout. Rollback preserves
     * existing library.
     */
    data class Failed(
        override val persistedCount: Int,
        val error: Throwable?,
    ) : YouTubeLibraryState

    companion object {
        /** Mirrors `YouTubeSourceRepository.TARGET_CACHE_SIZE` (private there). */
        const val TARGET_COUNT = 200
    }
}
