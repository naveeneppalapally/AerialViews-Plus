package com.neilturner.aerialviews.providers.youtube

import android.content.SharedPreferences

internal class FakeYouTubeCacheDao(
    private val entries: MutableList<YouTubeCacheEntity>,
) : YouTubeCacheDao {
    var invalidatedStreamUrlCount: Int = 0

    override suspend fun getAll(): List<YouTubeCacheEntity> = entries.toList()

    override suspend fun getAllGood(): List<YouTubeCacheEntity> = entries.filterNot { it.isBad }

    override suspend fun countGoodEntries(): Int = entries.count { !it.isBad }

    override suspend fun getValidEntries(now: Long): List<YouTubeCacheEntity> =
        entries.filter { !it.isBad && it.streamUrlExpiresAt > now }

    override suspend fun insertAll(entries: List<YouTubeCacheEntity>) {
        this.entries.removeAll { existing -> entries.any { it.videoId == existing.videoId } }
        this.entries.addAll(entries)
    }

    override suspend fun insertIgnore(entries: List<YouTubeCacheEntity>): List<Long> =
        entries.map { entry ->
            if (this.entries.any { it.videoId == entry.videoId }) {
                -1L
            } else {
                this.entries += entry
                1L
            }
        }

    override suspend fun clearAll() {
        entries.clear()
    }

    override suspend fun clearAllGood() {
        entries.removeAll { !it.isBad }
    }

    override suspend fun updateStreamUrl(videoId: String, newUrl: String, newAudioUrl: String, newExpiresAt: Long) {
        updateEntry(videoId) { entry ->
            entry.copy(
                streamUrl = newUrl,
                audioStreamUrl = newAudioUrl,
                streamUrlExpiresAt = newExpiresAt,
                isBad = false,
            )
        }
    }

    override suspend fun invalidateAllStreamUrls(): Int {
        val matchingEntries = entries.filterNot { it.isBad }
        invalidatedStreamUrlCount = matchingEntries.size
        entries.replaceAll { entry ->
            if (entry.isBad) {
                entry
            } else {
                entry.copy(streamUrl = "", audioStreamUrl = "", streamUrlExpiresAt = 0L)
            }
        }
        return invalidatedStreamUrlCount
    }

    override suspend fun getOldestCachedAt(): Long? = entries.minOfOrNull { it.searchCachedAt }

    override suspend fun getNewestCachedAt(): Long? = entries.maxOfOrNull { it.searchCachedAt }

    override suspend fun getByVideoPageUrl(videoPageUrl: String): YouTubeCacheEntity? =
        entries.firstOrNull { it.videoPageUrl == videoPageUrl }

    override suspend fun markAsBad(videoId: String): Int {
        val before = entries.firstOrNull { it.videoId == videoId } ?: return 0
        if (before.isBad) {
            return 0
        }
        updateEntry(videoId) { it.copy(isBad = true) }
        return 1
    }

    override suspend fun markAsPlayed(videoId: String, timestamp: Long) {
        updateEntry(videoId) { it.copy(lastPlayedAt = timestamp) }
    }

    override suspend fun resetPlayHistory() {
        entries.replaceAll { it.copy(lastPlayedAt = 0L) }
    }

    override suspend fun updateConsumedSegmentsMask(videoId: String, mask: Long): Int {
        val before = entries.firstOrNull { it.videoId == videoId } ?: return 0
        updateEntry(videoId) { it.copy(consumedSegmentsMask = mask) }
        return 1
    }

    override suspend fun resetAllConsumedSegments(): Int {
        val count = entries.size
        entries.replaceAll { it.copy(consumedSegmentsMask = 0L) }
        return count
    }

    override suspend fun deleteByNotInCategories(allowedCategoryKeys: List<String>): Int {
        val before = entries.size
        entries.removeAll { !it.isBad && it.categoryKey.isNotBlank() && it.categoryKey !in allowedCategoryKeys }
        return before - entries.size
    }

    override suspend fun deleteByVideoIds(videoIds: List<String>): Int {
        val before = entries.size
        entries.removeAll { it.videoId in videoIds }
        return before - entries.size
    }

    override suspend fun getUnwatchedEntry(cutoff: Long): YouTubeCacheEntity? =
        entries.firstOrNull { !it.isBad && (it.lastPlayedAt == 0L || it.lastPlayedAt < cutoff) }

    override suspend fun getLeastRecentlyPlayed(): YouTubeCacheEntity? =
        entries.filterNot { it.isBad }.minByOrNull { it.lastPlayedAt }

    internal fun updateEntry(
        videoId: String,
        transform: (YouTubeCacheEntity) -> YouTubeCacheEntity,
    ) {
        val index = entries.indexOfFirst { it.videoId == videoId }
        if (index >= 0) {
            entries[index] = transform(entries[index])
        }
    }
}

internal class FakeYouTubeWatchHistoryDao : YouTubeWatchHistoryDao {
    private val history = mutableListOf<YouTubeWatchHistoryEntity>()
    private var nextHistoryId = 1L

    override suspend fun insert(entry: YouTubeWatchHistoryEntity) {
        history += entry.copy(historyId = nextHistoryId++)
    }

    override suspend fun recentHistory(limit: Int): List<YouTubeWatchHistoryEntity> =
        history
            .sortedWith(compareByDescending<YouTubeWatchHistoryEntity> { it.playedAt }.thenByDescending { it.historyId })
            .take(limit)

    override suspend fun trimToLimit(limit: Int) {
        val retained = recentHistory(limit).map { it.historyId }.toSet()
        history.removeAll { it.historyId !in retained }
    }

    override suspend fun deleteOlderThan(cutoff: Long) {
        history.removeAll { it.playedAt < cutoff }
    }

    fun lastPlayedVideoId(): String = history.lastOrNull()?.videoId ?: error("Playback was not recorded")
}

internal class InMemorySharedPreferences(
    initialValues: MutableMap<String, Any?> = mutableMapOf(),
) : SharedPreferences {
    private val values = initialValues.toMutableMap()

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()

    override fun getString(key: String?, defValue: String?): String? =
        values[key] as? String ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        when (val value = values[key]) {
            is Set<*> -> value.filterIsInstance<String>().toMutableSet()
            else -> defValues
        }

    override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue

    override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue

    override fun contains(key: String?): Boolean = key != null && values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = Editor(values)

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
    }

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
    }

    private class Editor(
        private val values: MutableMap<String, Any?>,
    ) : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private val removals = mutableSetOf<String>()
        private var clearRequested = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor = applyChange(key, value)

        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor =
            applyChange(key, values?.toSet())

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = applyChange(key, value)

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = applyChange(key, value)

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = applyChange(key, value)

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = applyChange(key, value)

        override fun remove(key: String?): SharedPreferences.Editor {
            key?.let { removals += it }
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clearRequested = true
            return this
        }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            if (clearRequested) {
                values.clear()
            }
            removals.forEach(values::remove)
            values.putAll(pending)
        }

        internal fun applyChange(
            key: String?,
            value: Any?,
        ): SharedPreferences.Editor {
            if (key != null) {
                pending[key] = value
            }
            return this
        }
    }
}
