package com.neilturner.aerialviews.providers.youtube

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [YouTubeCacheEntity::class, YouTubeWatchHistoryEntity::class],
    version = 8,
    exportSchema = false,
)
abstract class YouTubeCacheDatabase : RoomDatabase() {
    abstract fun youtubeCacheDao(): YouTubeCacheDao
    abstract fun youtubeWatchHistoryDao(): YouTubeWatchHistoryDao

    companion object {
        private const val DATABASE_NAME = "youtube_cache.db"
        private val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE youtube_cache ADD COLUMN searchQuery TEXT",
                    )
                }
            }
        private val MIGRATION_2_3 =
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE youtube_cache ADD COLUMN uploaderName TEXT NOT NULL DEFAULT ''",
                    )
                }
            }
        private val MIGRATION_3_4 =
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE youtube_cache ADD COLUMN isBad INTEGER NOT NULL DEFAULT 0",
                    )
                    db.execSQL(
                        "ALTER TABLE youtube_cache ADD COLUMN lastPlayedAt INTEGER NOT NULL DEFAULT 0",
                    )
                }
            }
        private val MIGRATION_4_5 =
            object : Migration(4, 5) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE youtube_cache ADD COLUMN durationSeconds INTEGER NOT NULL DEFAULT 0",
                    )
                    db.execSQL(
                        "ALTER TABLE youtube_cache ADD COLUMN categoryKey TEXT NOT NULL DEFAULT ''",
                    )
                }
            }
        private val MIGRATION_5_6 =
            object : Migration(5, 6) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS youtube_watch_history (" +
                            "historyId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "videoId TEXT NOT NULL, " +
                            "playedAt INTEGER NOT NULL" +
                            ")",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_youtube_watch_history_playedAt " +
                            "ON youtube_watch_history(playedAt)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_youtube_watch_history_videoId " +
                            "ON youtube_watch_history(videoId)",
                    )
                }
            }
        private val MIGRATION_6_7 =
            object : Migration(6, 7) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE youtube_cache ADD COLUMN audioStreamUrl TEXT NOT NULL DEFAULT ''",
                    )
                }
            }
        private val MIGRATION_7_8 =
            object : Migration(7, 8) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE youtube_cache ADD COLUMN consumedSegmentsMask INTEGER NOT NULL DEFAULT 0",
                    )
                }
            }

        @Volatile
        private var instance: YouTubeCacheDatabase? = null

        fun getInstance(context: Context): YouTubeCacheDatabase =
            instance
                ?: synchronized(this) {
                    instance
                        ?: Room
                            .databaseBuilder(
                                context.applicationContext,
                                YouTubeCacheDatabase::class.java,
                                DATABASE_NAME,
                            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                            // All upgrade paths have explicit migrations; only wipe on
                            // downgrade (e.g. beta back to stable), never on upgrade.
                            // A full destructive fallback here silently discarded the
                            // cache and the 7-day watch history, causing repeats.
                            .fallbackToDestructiveMigrationOnDowngrade()
                            .build()
                            .also { instance = it }
                }
    }
}
