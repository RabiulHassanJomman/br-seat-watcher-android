package com.iqbal.seatwatcher.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface WatchDao {
    @Query("SELECT * FROM watches ORDER BY createdAt")
    fun observeAll(): Flow<List<WatchEntity>>

    @Query("SELECT * FROM watches ORDER BY createdAt")
    suspend fun all(): List<WatchEntity>

    @Query("SELECT * FROM watches WHERE enabled = 1 ORDER BY createdAt")
    suspend fun enabled(): List<WatchEntity>

    @Query("SELECT * FROM watches WHERE id = :id")
    suspend fun get(id: Long): WatchEntity?

    @Query("SELECT * FROM watches WHERE id = :id")
    fun observe(id: Long): Flow<WatchEntity?>

    @Insert
    suspend fun insert(w: WatchEntity): Long

    @androidx.room.Update
    suspend fun update(w: WatchEntity)

    @Query("UPDATE watches SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("DELETE FROM watches WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM watches WHERE enabled = 1")
    suspend fun enabledCount(): Int
}

@Dao
abstract class StateDao {
    @Query("SELECT * FROM watch_state")
    abstract fun observeAll(): Flow<List<WatchStateEntity>>

    @Query("SELECT * FROM watch_state WHERE watchId = :id")
    abstract fun observe(id: Long): Flow<WatchStateEntity?>

    @Query("SELECT * FROM watch_state WHERE watchId = :id")
    abstract suspend fun get(id: Long): WatchStateEntity?

    @Upsert
    abstract suspend fun upsert(s: WatchStateEntity)

    @Query("SELECT * FROM seen_counts WHERE watchId = :id")
    abstract suspend fun counts(id: Long): List<SeenCountEntity>

    @Query("DELETE FROM seen_counts WHERE watchId = :id")
    abstract suspend fun clearCounts(id: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertCounts(c: List<SeenCountEntity>)

    @Transaction
    open suspend fun replaceCounts(id: Long, c: List<SeenCountEntity>) {
        clearCounts(id)
        insertCounts(c)
    }

    /** Clears engine state so the next check is a baseline. Keeps the last snapshot for display. */
    @Transaction
    open suspend fun resetEngine(id: Long) {
        clearCounts(id)
        val s = get(id) ?: WatchStateEntity(watchId = id)
        upsert(s.copy(baselineSet = false, searchKey = "", lastNotifiedAt = 0, snoozeUntil = 0))
    }

    @Query("DELETE FROM watch_state WHERE watchId = :id")
    abstract suspend fun deleteState(id: Long)
}

@Dao
interface AlertLogDao {
    @Query("SELECT * FROM alert_log ORDER BY at DESC LIMIT 500")
    fun observe(): Flow<List<AlertLogEntity>>

    @Insert
    suspend fun insert(a: AlertLogEntity)

    @Query("DELETE FROM alert_log")
    suspend fun clear()
}

@Database(
    entities = [WatchEntity::class, SeenCountEntity::class, WatchStateEntity::class, AlertLogEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun watches(): WatchDao
    abstract fun state(): StateDao
    abstract fun alerts(): AlertLogDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "seatwatcher.db")
                .addMigrations(MIGRATION_1_2)
                .build()

        /** v2: per-watch "also alert when seats increase". Existing watches keep the old behaviour. */
        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE watches ADD COLUMN alertOnIncrease INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
