package com.pennywiseai.tracker.data.database.dao

import androidx.room.*
import com.pennywiseai.tracker.data.database.entity.DueSourceEntity
import kotlinx.coroutines.flow.Flow

/**
 * The notices behind each due (spec 03 §3.3, spec 20 §20.4, screen 34).
 *
 * One card bill can be announced by the issuer, CRED, Amazon Pay and BillDesk, each with its
 * own amount and date. They merge into one due; every reading is kept here so the detail
 * screen can show that four senders agreed — or that one of them disagreed.
 */
@Dao
interface DueSourceDao {

    @Insert
    suspend fun insert(source: DueSourceEntity): Long

    @Update
    suspend fun update(source: DueSourceEntity)

    @Query("SELECT * FROM due_sources WHERE due_id = :dueId ORDER BY received_at ASC")
    suspend fun getForDue(dueId: Long): List<DueSourceEntity>

    @Query("SELECT * FROM due_sources WHERE due_id = :dueId ORDER BY received_at ASC")
    fun observeForDue(dueId: Long): Flow<List<DueSourceEntity>>

    @Query("SELECT * FROM due_sources WHERE email_message_id = :emailMessageId")
    suspend fun getByEmailMessageId(emailMessageId: Long): List<DueSourceEntity>

    /**
     * Whether this email has already been recorded against this due, so re-processing a
     * message does not stack duplicate notices. There is no unique index to lean on here:
     * a due legitimately has several notices from the same sender over time (a statement,
     * then a reminder, then an overdue notice), so uniqueness is per message, not per sender.
     */
    @Query("SELECT COUNT(*) FROM due_sources WHERE due_id = :dueId AND email_message_id = :emailMessageId")
    suspend fun countForEmail(dueId: Long, emailMessageId: Long): Int

    @Query("SELECT COUNT(*) FROM due_sources WHERE due_id = :dueId")
    suspend fun countForDue(dueId: Long): Int

    @Query("DELETE FROM due_sources WHERE due_id = :dueId")
    suspend fun deleteForDue(dueId: Long)

    @Query("DELETE FROM due_sources")
    suspend fun deleteAll()
}
