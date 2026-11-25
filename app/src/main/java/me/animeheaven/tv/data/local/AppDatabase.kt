package me.animeheaven.tv.data.local

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Immutable
@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey val animeId: String,
    val title: String,
    val coverUrl: String,
    val lastEpisodeLabel: String,
    val addedAt: Long
)

@Immutable
@Entity(
    tableName = "watch_progress",
    indices = [
        Index(value = ["animeId", "updatedAt"]),
        Index(value = ["updatedAt"])
    ]
)
data class WatchProgressEntity(
    @PrimaryKey val episodeKey: String,
    val animeId: String,
    val animeTitle: String,
    val coverUrl: String,
    val episodeLabel: String,
    val positionMs: Long,
    val durationMs: Long,
    val isWatched: Boolean,
    val nextEpisodeKey: String? = null,
    val nextEpisodeLabel: String? = null,
    val updatedAt: Long
)

@Entity(tableName = "search_history")
data class SearchHistoryEntity(
    @PrimaryKey val query: String,
    val searchedAt: Long
)

@Entity(
    tableName = "web_cache",
    indices = [Index(value = ["fetchedAt"])]
)
data class WebCacheEntity(
    @PrimaryKey val cacheKey: String,
    val payload: String,
    val fetchedAt: Long,
    val parsedJson: String? = null
)

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<BookmarkEntity>>

    @Query("SELECT animeId FROM bookmarks")
    fun observeBookmarkedIds(): Flow<List<String>>

    @Query("SELECT animeId FROM bookmarks ORDER BY addedAt DESC")
    suspend fun getAllIdsOnce(): List<String>

    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE animeId = :animeId)")
    fun observeIsBookmarked(animeId: String): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE animeId = :animeId)")
    suspend fun isBookmarked(animeId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE animeId = :animeId")
    suspend fun deleteById(animeId: String)
}

@Dao
interface WatchProgressDao {
    @Query("SELECT * FROM watch_progress ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<WatchProgressEntity>>

    @Query(CONTINUE_WATCHING_QUERY)
    fun observeLatestPerAnime(minResumeMs: Long): Flow<List<WatchProgressEntity>>

    @Query(CONTINUE_WATCHING_QUERY)
    suspend fun getLatestPerAnimeOnce(minResumeMs: Long): List<WatchProgressEntity>

    @Query("SELECT * FROM watch_progress WHERE animeId = :animeId ORDER BY updatedAt DESC")
    fun observeByAnime(animeId: String): Flow<List<WatchProgressEntity>>

    @Query("SELECT * FROM watch_progress WHERE episodeKey = :episodeKey LIMIT 1")
    suspend fun getByEpisodeKey(episodeKey: String): WatchProgressEntity?

    @Query("SELECT * FROM watch_progress ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun getRecentOnce(limit: Int = 128): List<WatchProgressEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: WatchProgressEntity)

    @Query("DELETE FROM watch_progress")
    suspend fun clearAll()
}

private const val CONTINUE_WATCHING_QUERY = """
    SELECT wp.* FROM watch_progress wp
    WHERE wp.animeId != ''
      AND wp.episodeKey = (
          SELECT latest.episodeKey FROM watch_progress latest
          WHERE latest.animeId = wp.animeId
          ORDER BY latest.updatedAt DESC, latest.episodeKey DESC LIMIT 1
      )
      AND ((wp.isWatched = 0 AND wp.positionMs > :minResumeMs)
           OR (wp.isWatched = 1 AND length(trim(wp.nextEpisodeKey)) > 0))
    ORDER BY wp.updatedAt DESC, wp.episodeKey DESC
    LIMIT 30
"""

@Dao
interface SearchHistoryDao {
    @Query("SELECT * FROM search_history ORDER BY searchedAt DESC LIMIT 10")
    fun observeRecent(): Flow<List<SearchHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SearchHistoryEntity)

    @Query("DELETE FROM search_history WHERE `query` = :query")
    suspend fun delete(query: String)

    @Query("DELETE FROM search_history")
    suspend fun clearAll()
}

@Dao
interface WebCacheDao {
    @Query("SELECT * FROM web_cache WHERE cacheKey = :cacheKey LIMIT 1")
    suspend fun get(cacheKey: String): WebCacheEntity?

    @Query("SELECT * FROM web_cache WHERE cacheKey IN (:keys)")
    suspend fun getByKeys(keys: List<String>): List<WebCacheEntity>

    @Query(
        "SELECT * FROM web_cache WHERE cacheKey LIKE 'detail:%' AND parsedJson IS NOT NULL " +
            "ORDER BY fetchedAt DESC LIMIT :limit"
    )
    suspend fun getRecentDetails(limit: Int = 12): List<WebCacheEntity>

    @Query(
        "SELECT * FROM web_cache WHERE cacheKey LIKE 'gate:%' AND parsedJson IS NOT NULL " +
            "ORDER BY fetchedAt DESC LIMIT :limit"
    )
    suspend fun getRecentStreams(limit: Int = 48): List<WebCacheEntity>

    @Query(
        "SELECT * FROM web_cache WHERE cacheKey LIKE 'vmeta:%' AND parsedJson IS NOT NULL " +
            "ORDER BY fetchedAt DESC LIMIT :limit"
    )
    suspend fun getRecentVideoMeta(limit: Int = 128): List<WebCacheEntity>

    @Query("SELECT * FROM web_cache WHERE cacheKey LIKE 'artwork:ah:%' AND fetchedAt >= :since ORDER BY fetchedAt DESC LIMIT :limit")
    suspend fun getRecentArtwork(since: Long, limit: Int = 128): List<WebCacheEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: WebCacheEntity)

    @Query("DELETE FROM web_cache WHERE cacheKey = :cacheKey")
    suspend fun delete(cacheKey: String)

    @Query("DELETE FROM web_cache WHERE fetchedAt < :cutoffMs")
    suspend fun pruneOlderThan(cutoffMs: Long)

    @Query("DELETE FROM web_cache")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM web_cache")
    suspend fun countCachedPages(): Int

    @Query("SELECT COALESCE(SUM(LENGTH(payload) + COALESCE(LENGTH(parsedJson), 0)), 0) FROM web_cache")
    suspend fun totalPayloadBytes(): Long
}

@Database(
    entities = [
        BookmarkEntity::class,
        WatchProgressEntity::class,
        SearchHistoryEntity::class,
        WebCacheEntity::class
    ],
    version = 3,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun watchProgressDao(): WatchProgressDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun webCacheDao(): WebCacheDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `web_cache` (" +
                        "`cacheKey` TEXT NOT NULL, " +
                        "`payload` TEXT NOT NULL, " +
                        "`fetchedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`cacheKey`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_web_cache_fetchedAt` ON `web_cache` (`fetchedAt`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_watch_progress_animeId_updatedAt` " +
                        "ON `watch_progress` (`animeId`, `updatedAt`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_watch_progress_updatedAt` " +
                        "ON `watch_progress` (`updatedAt`)"
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `web_cache` ADD COLUMN `parsedJson` TEXT DEFAULT NULL")
            }
        }

        fun build(context: Context): AppDatabase = Room.databaseBuilder(
            context.applicationContext,
            AppDatabase::class.java,
            "animeheaven_tv.db"
        )
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
            .addCallback(object : Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    super.onOpen(db)
                    // Safe with WAL mode: avoids per-transaction fsync stalls on TV eMMC flash
                    db.execSQL("PRAGMA synchronous = NORMAL")
                    db.execSQL("PRAGMA temp_store = MEMORY")
                    db.execSQL("PRAGMA cache_size = -4000")
                }
            })
            .build()
    }
}
