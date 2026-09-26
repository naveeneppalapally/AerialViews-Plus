package com.neilturner.aerialviews.providers.youtube

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "youtube_cache")
data class YouTubeCacheEntity(
    @PrimaryKey
    val videoId: String,
    val videoPageUrl: String,
    val streamUrl: String,
    val audioStreamUrl: String = "",
    val title: String,
    val uploaderName: String = "",
    val durationSeconds: Int = 0,
    val categoryKey: String = "",
    val streamUrlExpiresAt: Long,
    val searchCachedAt: Long,
    val searchQuery: String? = null,
    val isBad: Boolean = false,
    val lastPlayedAt: Long = 0L,
    val consumedSegmentsMask: Long = 0L,
)
