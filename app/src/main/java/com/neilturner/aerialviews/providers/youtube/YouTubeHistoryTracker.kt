package com.neilturner.aerialviews.providers.youtube

import android.content.SharedPreferences
import androidx.core.content.edit
import java.util.ArrayDeque

/** Pre-refresh novelty snapshot: hard-exclusion set, demotion set. */
data class NoveltyTiers(
    val tier1: Set<String>,
    val tier2: Set<String>,
)

/**
 * Owns all playback/refresh history: the watch-history table plus the
 * SharedPreferences mirrors (play/theme/recent-refresh histories, last
 * channel, first-launch state). Previously these reads/writes were scattered
 * across the repository with two sources of truth; now there is one owner.
 */
class YouTubeHistoryTracker(
    private val cacheDao: YouTubeCacheDao,
    private val watchHistoryDao: YouTubeWatchHistoryDao,
    private val sharedPreferences: SharedPreferences,
) {
    suspend fun playHistory(): ArrayDeque<String> {
        val dbHistory = watchHistoryDao.recentHistory(PlaylistOrderer.MAX_PLAY_HISTORY)
        if (dbHistory.isNotEmpty()) {
            return ArrayDeque(dbHistory.asReversed().map { it.videoId })
        }
        return readHistory(KEY_PLAY_HISTORY)
    }

    fun themeHistory(): ArrayDeque<String> = readHistory(KEY_THEME_HISTORY)

    fun categoryHistory(): ArrayDeque<String> = readHistory(KEY_CATEGORY_HISTORY)

    fun recentRefreshIds(): ArrayDeque<String> = readHistory(KEY_RECENT_REFRESH_IDS)

    /**
     * Novelty tiers over the recent-refresh FIFO (oldest first): Tier 1 is
     * the most recent [TIER1_CAP] IDs (roughly the last full batch) and is
     * hard-excluded from the next batch; Tier 2 is the [TIER2_CAP] before
     * that and is only score-demoted. Anything older recirculates freely.
     */
    fun noveltyTiers(): NoveltyTiers {
        val ids = recentRefreshIds().toList()
        val tier1 = ids.takeLast(TIER1_CAP).toSet()
        val tier2 =
            if (ids.size > TIER1_CAP) {
                ids.dropLast(TIER1_CAP).takeLast(TIER2_CAP).toSet()
            } else {
                emptySet()
            }
        return NoveltyTiers(tier1 = tier1, tier2 = tier2)
    }

    fun recordRefreshHistory(entries: List<YouTubeCacheEntity>) {
        val history = recentRefreshIds()
        entries.forEach { entry ->
            history.remove(entry.videoId)
            history.addLast(entry.videoId)
        }

        while (history.size > MAX_RECENT_REFRESH_IDS) {
            history.removeFirst()
        }

        writeHistory(KEY_RECENT_REFRESH_IDS, history)
    }

    fun lastPlayedChannel(): String =
        sharedPreferences.getString(KEY_LAST_CHANNEL, "")?.trim().orEmpty()

    fun isFirstLaunchActive(): Boolean =
        sharedPreferences.getBoolean(KEY_FIRST_LAUNCH, true)

    fun firstLaunchIndex(): Int =
        sharedPreferences.getInt(KEY_FIRST_LAUNCH_INDEX, 0)

    suspend fun recordPlayback(entry: YouTubeCacheEntity) {
        val playedAt = System.currentTimeMillis()
        cacheDao.markAsPlayed(entry.videoId, playedAt)
        watchHistoryDao.insert(
            YouTubeWatchHistoryEntity(
                videoId = entry.videoId,
                playedAt = playedAt,
            ),
        )
        watchHistoryDao.trimToLimit(MAX_WATCH_HISTORY_ROWS)

        val history = playHistory()
        history.addLast(entry.videoId)
        PlaylistOrderer.trimHistory(history, PlaylistOrderer.MAX_PLAY_HISTORY)

        val themes = themeHistory()
        val theme = PlaylistOrderer.detectTheme(entry.title)
        themes.addLast(theme)
        PlaylistOrderer.trimHistory(themes, PlaylistOrderer.MAX_THEME_HISTORY)

        val categories = categoryHistory()
        val category = entry.categoryKey.ifBlank { entry.searchQuery.orEmpty() }
        if (category.isNotBlank()) {
            categories.addLast(category)
            PlaylistOrderer.trimHistory(categories, PlaylistOrderer.MAX_CATEGORY_HISTORY)
        }

        val firstLaunchStillActive = isFirstLaunchActive()
        val nextFirstLaunchIndex =
            if (firstLaunchStillActive) {
                (firstLaunchIndex() + 1).coerceAtMost(PlaylistOrderer.FIRST_LAUNCH_SEQUENCE.size)
            } else {
                firstLaunchIndex()
            }

        sharedPreferences.edit {
            putString(KEY_PLAY_HISTORY, history.joinToString(HISTORY_SEPARATOR))
            putString(KEY_THEME_HISTORY, themes.joinToString(HISTORY_SEPARATOR))
            putString(KEY_CATEGORY_HISTORY, categories.joinToString(HISTORY_SEPARATOR))
            putString(KEY_LAST_CATEGORY, category)
            putString(KEY_LAST_CHANNEL, entry.uploaderName)
            putInt(KEY_FIRST_LAUNCH_INDEX, nextFirstLaunchIndex)
            putBoolean(KEY_FIRST_LAUNCH, nextFirstLaunchIndex < PlaylistOrderer.FIRST_LAUNCH_SEQUENCE.size)
        }
    }

    suspend fun prunePlayHistory(cachedEntries: List<YouTubeCacheEntity>) {
        if (cachedEntries.isEmpty()) {
            return
        }
        // Drop watch-history rows outside the 7-day repeat window so ancient
        // plays stop excluding candidates from refresh discovery forever.
        // Count-based trimming already happens on record; this is the time bound.
        runCatching {
            watchHistoryDao.deleteOlderThan(System.currentTimeMillis() - RECENT_PLAYBACK_WINDOW_MS)
        }
    }

    private fun readHistory(key: String): ArrayDeque<String> {
        val rawHistory = sharedPreferences.getString(key, "").orEmpty()
        val parsedHistory =
            rawHistory
                .split(HISTORY_SEPARATOR)
                .map(String::trim)
                .filter(String::isNotBlank)
        return ArrayDeque(parsedHistory)
    }

    private fun writeHistory(
        key: String,
        values: ArrayDeque<String>,
    ) {
        sharedPreferences.edit {
            putString(key, values.joinToString(HISTORY_SEPARATOR))
        }
    }

    companion object {
        const val KEY_PLAY_HISTORY = "yt_play_history"
        const val KEY_LAST_CATEGORY = "yt_last_category"
        const val KEY_CATEGORY_HISTORY = "yt_category_history"
        const val KEY_THEME_HISTORY = "yt_theme_history"
        const val KEY_LAST_CHANNEL = "yt_last_channel"
        const val KEY_FIRST_LAUNCH = "yt_first_launch"
        const val KEY_FIRST_LAUNCH_INDEX = "yt_first_launch_index"
        const val KEY_RECENT_REFRESH_IDS = "yt_recent_refresh_ids"
        const val RECENT_PLAYBACK_WINDOW_MS = 7L * 24L * 60L * 60L * 1000L
        const val HISTORY_SEPARATOR = "|"
        // Novelty memory: Tier 1 (last full batch) is hard-excluded from the
        // next batch, Tier 2 (the ~2 batches before) is score-demoted, older
        // IDs recirculate. Stored as one FIFO; over-long legacy lists trim on
        // the next write.
        const val TIER1_CAP = 200
        const val TIER2_CAP = 400
        private const val MAX_WATCH_HISTORY_ROWS = 5_000
        private const val MAX_RECENT_REFRESH_IDS = TIER1_CAP + TIER2_CAP
    }
}
