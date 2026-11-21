package com.batterymaster.ai.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

@Dao
interface SampleDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(sample: SampleEntity): Long

    @Query("SELECT * FROM samples ORDER BY timestamp DESC LIMIT 1")
    suspend fun latest(): SampleEntity?

    @Query("SELECT * FROM samples ORDER BY timestamp DESC LIMIT 1")
    fun observeLatest(): Flow<SampleEntity?>

    @Query("SELECT * FROM samples WHERE timestamp >= :from AND timestamp <= :to ORDER BY timestamp")
    suspend fun between(from: Long, to: Long): List<SampleEntity>

    @Query("SELECT * FROM samples WHERE timestamp >= :from AND timestamp <= :to ORDER BY timestamp")
    fun observeBetween(from: Long, to: Long): Flow<List<SampleEntity>>

    @Query("SELECT * FROM samples WHERE id > :afterId ORDER BY id LIMIT :limit")
    suspend fun page(afterId: Long, limit: Int): List<SampleEntity>

    @RawQuery(observedEntities = [SampleEntity::class])
    fun observeBuckets(query: SupportSQLiteQuery): Flow<List<BucketRow>>

    @Query("SELECT MIN(timestamp) FROM samples")
    suspend fun firstTimestamp(): Long?

    @Query("SELECT COUNT(*) FROM samples")
    fun observeCount(): Flow<Int>

    @Query(
        "SELECT COUNT(*) AS n, " +
            "SUM(CASE WHEN temperatureC > :warmC THEN 1 ELSE 0 END) AS warm, " +
            "MAX(temperatureC) AS maxC, AVG(temperatureC) AS avgC " +
            "FROM samples WHERE timestamp >= :from AND temperatureC IS NOT NULL",
    )
    suspend fun temperatureStats(from: Long, warmC: Float): TempStatsRow

    @Query("DELETE FROM samples WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long): Int

    @Query("DELETE FROM samples")
    suspend fun deleteAll()
}

data class TempStatsRow(val n: Int, val warm: Int?, val maxC: Float?, val avgC: Float?)

@Dao
interface SessionDao {
    @Insert
    suspend fun insert(session: SessionEntity): Long

    @Update
    suspend fun update(session: SessionEntity)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM sessions WHERE endTime IS NULL ORDER BY startTime DESC LIMIT 1")
    suspend fun open(): SessionEntity?

    @Query("SELECT * FROM sessions WHERE endTime IS NULL ORDER BY startTime DESC LIMIT 1")
    fun observeOpen(): Flow<SessionEntity?>

    @Query("SELECT * FROM sessions WHERE type = :type ORDER BY startTime DESC LIMIT :limit")
    fun observeRecent(type: String, limit: Int): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE startTime >= :since ORDER BY startTime DESC")
    fun observeSince(since: Long): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE startTime >= :since ORDER BY startTime DESC")
    suspend fun since(since: Long): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE id = :id")
    fun observe(id: Long): Flow<SessionEntity?>

    @Query("SELECT * FROM sessions ORDER BY startTime")
    suspend fun all(): List<SessionEntity>

    @Query("DELETE FROM sessions WHERE endTime IS NOT NULL AND endTime < :before")
    suspend fun deleteOlderThan(before: Long): Int

    @Query("DELETE FROM sessions")
    suspend fun deleteAll()
}

@Dao
interface CapacityDao {
    @Insert
    suspend fun insertAll(items: List<CapacityEstimateEntity>)

    @Query("SELECT * FROM capacity_estimates ORDER BY timestamp")
    fun observeAll(): Flow<List<CapacityEstimateEntity>>

    @Query("SELECT * FROM capacity_estimates ORDER BY timestamp")
    suspend fun all(): List<CapacityEstimateEntity>

    @Query("SELECT * FROM capacity_estimates WHERE sessionId = :sessionId")
    fun observeForSession(sessionId: Long): Flow<List<CapacityEstimateEntity>>

    @Query("DELETE FROM capacity_estimates")
    suspend fun deleteAll()
}

@Dao
interface ProtectionDao {
    @Insert
    suspend fun insert(event: ProtectionEventEntity)

    @Query("SELECT * FROM protection_events ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ProtectionEventEntity>>

    @Query("DELETE FROM protection_events")
    suspend fun deleteAll()
}
