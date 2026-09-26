package com.neilturner.aerialviews.providers.youtube

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface YouTubeCacheDao {
    @Query("SELECT * FROM youtube_cache ORDER BY title COLLATE NOCASE ASC")
    suspend fun getAll(): List<YouTubeCacheEntity>

    @Query("SELECT * FROM youtube_cache WHERE isBad = 0 ORDER BY title COLLATE NOCASE ASC")
    suspend fun getAllGood(): List<YouTubeCacheEntity>

    @Query("SELECT COUNT(*) FROM youtube_cache WHERE isBad = 0")
    suspend fun countGoodEntries(): Int

    @Query("SELECT * FROM youtube_cache WHERE isBad = 0 AND streamUrlExpiresAt > :now ORDER BY title COLLATE NOCASE ASC")
    suspend fun getValidEntries(now: Long): List<YouTubeCacheEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<YouTubeCacheEntity>)

    /**
     * Insert-only: existing rows (matched by primary key) are left untouched.
     * Returns the row id per entry, or -1 where a row already existed.
     * Incremental paths must use this (plus [updateStreamUrl] for stream
     * refreshes) instead of [insertAll]: REPLACE on conflict silently
     * overwrites a video's stable [YouTubeCacheEntity.categoryKey] when the
     * same video is rediscovered under another category, and toggle-off
     * removal can then no longer find it.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(entries: List<YouTubeCacheEntity>): List<Long>

    @Query("DELETE FROM youtube_cache")
    suspend fun clearAll()

    @Query("DELETE FROM youtube_cache WHERE isBad = 0")
    suspend fun clearAllGood()

    @Transaction
    suspend fun clearAndInsert(entries: List<YouTubeCacheEntity>) {
        clearAll()
        insertAll(entries)
    }

    @Query(
        "UPDATE youtube_cache SET streamUrl = :newUrl, audioStreamUrl = :newAudioUrl, streamUrlExpiresAt = :newExpiresAt, isBad = 0 " +
            "WHERE videoId = :videoId",
    )
    suspend fun updateStreamUrl(
        videoId: String,
        newUrl: String,
        newAudioUrl: String,
        newExpiresAt: Long,
    )

    @Query("UPDATE youtube_cache SET streamUrl = '', audioStreamUrl = '', streamUrlExpiresAt = 0 WHERE isBad = 0")
    suspend fun invalidateAllStreamUrls(): Int

    @Query("SELECT MIN(searchCachedAt) FROM youtube_cache")
    suspend fun getOldestCachedAt(): Long?

    @Query("SELECT MAX(searchCachedAt) FROM youtube_cache")
    suspend fun getNewestCachedAt(): Long?

    @Query("SELECT * FROM youtube_cache WHERE videoPageUrl = :videoPageUrl LIMIT 1")
    suspend fun getByVideoPageUrl(videoPageUrl: String): YouTubeCacheEntity?

    @Query("UPDATE youtube_cache SET isBad = 1 WHERE videoId = :videoId AND isBad = 0")
    suspend fun markAsBad(videoId: String): Int

    @Query("UPDATE youtube_cache SET lastPlayedAt = :timestamp WHERE videoId = :videoId")
    suspend fun markAsPlayed(
        videoId: String,
        timestamp: Long,
    )

    @Query("UPDATE youtube_cache SET lastPlayedAt = 0")
    suspend fun resetPlayHistory()

    @Query("UPDATE youtube_cache SET consumedSegmentsMask = :mask WHERE videoId = :videoId")
    suspend fun updateConsumedSegmentsMask(
        videoId: String,
        mask: Long,
    ): Int

    @Query("UPDATE youtube_cache SET consumedSegmentsMask = 0")
    suspend fun resetAllConsumedSegments(): Int

    @Query(
        "DELETE FROM youtube_cache " +
            "WHERE isBad = 0 AND categoryKey IS NOT NULL AND categoryKey != '' AND categoryKey NOT IN (:allowedCategoryKeys)",
    )
    suspend fun deleteByNotInCategories(allowedCategoryKeys: List<String>): Int

    @Query("DELETE FROM youtube_cache WHERE videoId IN (:videoIds)")
    suspend fun deleteByVideoIds(videoIds: List<String>): Int

    @Query(
        "SELECT * FROM youtube_cache " +
            "WHERE isBad = 0 AND (lastPlayedAt = 0 OR lastPlayedAt < :cutoff) " +
            "ORDER BY RANDOM() LIMIT 1",
    )
    suspend fun getUnwatchedEntry(cutoff: Long): YouTubeCacheEntity?

    @Query("SELECT * FROM youtube_cache WHERE isBad = 0 ORDER BY lastPlayedAt ASC LIMIT 1")
    suspend fun getLeastRecentlyPlayed(): YouTubeCacheEntity?
}
