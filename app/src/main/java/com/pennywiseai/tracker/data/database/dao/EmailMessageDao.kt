package com.pennywiseai.tracker.data.database.dao

import androidx.room.*
import com.pennywiseai.tracker.data.database.entity.EmailMessageEntity
import kotlinx.coroutines.flow.Flow

/**
 * The email log (spec 03 §3.2, screen 48).
 *
 * Every fetched message is written here *before* it is parsed, so this table is also the
 * sync's memory: it is what makes a run resumable and what "Re-process unmatched" replays.
 */
@Dao
interface EmailMessageDao {

    /**
     * Inserts a freshly fetched message. IGNORE rather than REPLACE on conflict: the unique
     * index is (mailbox, uid), and a message already logged has been — or is being —
     * processed. Replacing it would discard its `transaction_id` and `skip_reason` and make
     * a re-fetch look like a new message (spec 11 R-11-08).
     *
     * Returns -1 when the row already existed.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(message: EmailMessageEntity): Long

    @Update
    suspend fun update(message: EmailMessageEntity)

    @Query("SELECT * FROM email_messages WHERE id = :id")
    suspend fun getById(id: Long): EmailMessageEntity?

    @Query("SELECT * FROM email_messages WHERE mailbox = :mailbox AND uid = :uid")
    suspend fun getByUid(mailbox: String, uid: Long): EmailMessageEntity?

    /**
     * Second identity check, used when UIDVALIDITY changed and every UID was renumbered
     * (spec 11 R-11-08). Message-ID survives that, so already-logged mail is recognised
     * instead of being logged and parsed a second time.
     */
    @Query("SELECT * FROM email_messages WHERE message_id = :messageId LIMIT 1")
    suspend fun getByMessageId(messageId: String): EmailMessageEntity?

    @Query("SELECT COUNT(*) FROM email_messages WHERE mailbox = :mailbox AND uid = :uid")
    suspend fun countByUid(mailbox: String, uid: Long): Int

    /** The watermark, recovered from the data rather than trusted from a setting. */
    @Query("SELECT MAX(uid) FROM email_messages WHERE mailbox = :mailbox")
    suspend fun maxUid(mailbox: String): Long?

    @Query("SELECT COUNT(*) FROM email_messages")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM email_messages")
    fun countFlow(): Flow<Int>

    @Query("SELECT * FROM email_messages ORDER BY received_at DESC LIMIT :limit OFFSET :offset")
    fun observeRecent(limit: Int, offset: Int = 0): Flow<List<EmailMessageEntity>>

    /**
     * Messages that produced nothing and could produce something later — after the user adds
     * a sender override, or after the app gains a parser (spec 11 R-11-10).
     *
     * Deliberately excludes `IGNORE:` and `NON_TXN`: those were understood and rejected on
     * purpose, and replaying them would just re-reject them.
     */
    @Query(
        """
        SELECT * FROM email_messages
        WHERE processed = 0
           OR skip_reason LIKE 'NO_PARSER%'
           OR skip_reason LIKE 'UNKNOWN_SENDER%'
           OR skip_reason LIKE 'ERROR:%'
        ORDER BY received_at ASC
        """
    )
    suspend fun getUnmatched(): List<EmailMessageEntity>

    @Query("SELECT * FROM email_messages WHERE skip_reason LIKE :pattern ORDER BY received_at DESC")
    fun observeBySkipReason(pattern: String): Flow<List<EmailMessageEntity>>

    @Query("SELECT * FROM email_messages WHERE transaction_id = :transactionId")
    suspend fun getByTransactionId(transactionId: Long): List<EmailMessageEntity>

    /**
     * Body trimming for `settings.email.retainBodiesDays` (default 400). The row and its
     * outcome are kept; only the bulk of the stored text goes, so the log stays complete
     * while the database stops growing without bound.
     *
     * Truncated to 512 characters rather than emptied: spec 11 §11.2 says only "body
     * trimming" while spec 04 §4.3 says "trims bodies older than N days to their first 512
     * chars (keeps the twin hash)". The longer reading is the one that loses no data — those
     * 512 characters are what screen 48's "show source" and R-11-10's re-processing have left
     * to work with once a body ages out, and `body_hash` is a column of its own, so the twin
     * hash survives either way. See docs/FORK-CHANGES.md.
     *
     * Idempotent, and that is what the `length(body) > 512` guard is for: after one sweep no
     * row below the cutoff still matches, so a run that trims nothing writes nothing.
     * Returns the number of rows actually shortened.
     */
    @Query(
        """
        UPDATE email_messages
        SET body = substr(body, 1, 512)
        WHERE received_at < :before AND length(body) > 512
        """
    )
    suspend fun trimBodiesBefore(before: String): Int

    @Query("DELETE FROM email_messages")
    suspend fun deleteAll()
}
