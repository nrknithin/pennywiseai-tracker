package com.pennywiseai.tracker.data.database.dao

import androidx.room.*
import com.pennywiseai.tracker.data.database.entity.SyncChannel
import com.pennywiseai.tracker.data.database.entity.SyncRunEntity
import kotlinx.coroutines.flow.Flow

/**
 * One row per worker execution (spec 03 §3.3, screen 48).
 *
 * The row is written when a run *starts* and updated when it finishes, so a run killed
 * mid-sync leaves evidence rather than vanishing. A row with a null `finished_at` and an old
 * `started_at` is exactly how the app knows a previous run died.
 */
@Dao
interface SyncRunDao {

    @Insert
    suspend fun insert(run: SyncRunEntity): Long

    @Update
    suspend fun update(run: SyncRunEntity)

    @Query("SELECT * FROM sync_runs WHERE id = :id")
    suspend fun getById(id: Long): SyncRunEntity?

    @Query(
        """
        SELECT * FROM sync_runs
        WHERE channel = :channel AND finished_at IS NOT NULL AND error_message IS NULL
        ORDER BY started_at DESC
        LIMIT 1
        """
    )
    suspend fun getLastSuccessful(channel: SyncChannel): SyncRunEntity?

    @Query(
        """
        SELECT * FROM sync_runs
        WHERE channel = :channel AND finished_at IS NOT NULL AND error_message IS NULL
        ORDER BY started_at DESC
        LIMIT 1
        """
    )
    fun observeLastSuccessful(channel: SyncChannel): Flow<SyncRunEntity?>

    @Query("SELECT * FROM sync_runs WHERE channel = :channel ORDER BY started_at DESC LIMIT 1")
    fun observeLast(channel: SyncChannel): Flow<SyncRunEntity?>

    @Query("SELECT * FROM sync_runs ORDER BY started_at DESC LIMIT :limit")
    fun observeRecent(limit: Int = 50): Flow<List<SyncRunEntity>>

    @Query("SELECT * FROM sync_runs WHERE channel = :channel ORDER BY started_at DESC LIMIT :limit")
    fun observeRecentForChannel(channel: SyncChannel, limit: Int = 50): Flow<List<SyncRunEntity>>

    /**
     * Runs that started and never finished — a worker killed by the system, or a crash.
     * Closed out on the next start so the log does not accumulate phantom in-flight runs.
     */
    @Query("SELECT * FROM sync_runs WHERE finished_at IS NULL AND started_at < :before")
    suspend fun getAbandoned(before: String): List<SyncRunEntity>

    /**
     * Consecutive failures at the head of the log for a channel, used by the alert rules that
     * only fire after several in a row (spec 08 §8.9 uses three for cloud snapshots).
     */
    @Query(
        """
        SELECT error_message FROM sync_runs
        WHERE channel = :channel AND finished_at IS NOT NULL
        ORDER BY started_at DESC
        LIMIT :limit
        """
    )
    suspend fun recentOutcomes(channel: SyncChannel, limit: Int): List<String?>

    /** Log trimming — run history is diagnostics, not data worth keeping forever. */
    @Query("DELETE FROM sync_runs WHERE started_at < :before")
    suspend fun deleteBefore(before: String): Int

    @Query("DELETE FROM sync_runs")
    suspend fun deleteAll()
}
