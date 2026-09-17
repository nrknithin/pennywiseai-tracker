package com.pennywiseai.tracker.data.database.dao

import androidx.room.*
import com.pennywiseai.tracker.data.database.entity.SourceChannel
import com.pennywiseai.tracker.data.database.entity.TransactionSourceEntity
import kotlinx.coroutines.flow.Flow

/**
 * The messages behind each transaction (spec 03 §3.3, spec 14, screen 45).
 *
 * Every twin that dedup absorbed lands here instead of becoming a second transaction, which
 * is what keeps "one real event = one transaction" true while still being able to show the
 * user every message that described it.
 */
@Dao
interface TransactionSourceDao {

    /**
     * IGNORE on conflict: (channel, source_ref) is unique, so re-processing a message that
     * was already attributed is a no-op rather than a duplicate row. Returns -1 when the
     * source was already recorded.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(source: TransactionSourceEntity): Long

    @Update
    suspend fun update(source: TransactionSourceEntity)

    @Query("SELECT * FROM transaction_sources WHERE transaction_id = :transactionId ORDER BY received_at ASC")
    suspend fun getForTransaction(transactionId: Long): List<TransactionSourceEntity>

    @Query("SELECT * FROM transaction_sources WHERE transaction_id = :transactionId ORDER BY received_at ASC")
    fun observeForTransaction(transactionId: Long): Flow<List<TransactionSourceEntity>>

    @Query("SELECT * FROM transaction_sources WHERE channel = :channel AND source_ref = :sourceRef")
    suspend fun getBySourceRef(channel: SourceChannel, sourceRef: String): TransactionSourceEntity?

    /**
     * Whether this transaction has already absorbed a message from the same channel produced
     * by the same template.
     *
     * This is the precise form of spec 14 §14.3's false-merge guard. The spec states it as
     * "email never twins another email", but HDFC really does send two different templates
     * for one card charge — H7 "charge without OTP" and H8 "e-mandate paid" — and spec 10
     * §10.8 requires those to collapse. Comparing the template as well as the channel admits
     * that pair while still keeping two independent alerts from the same template apart,
     * which is what the guard was actually protecting against.
     */
    @Query(
        """
        SELECT COUNT(*) FROM transaction_sources
        WHERE transaction_id = :transactionId
          AND channel = :channel
          AND parser_id IS NOT NULL
          AND parser_id = :parserId
        """
    )
    suspend fun countSameTemplate(
        transactionId: Long,
        channel: SourceChannel,
        parserId: String
    ): Int

    @Query("SELECT COUNT(*) FROM transaction_sources WHERE transaction_id = :transactionId AND channel = :channel")
    suspend fun countSameChannel(transactionId: Long, channel: SourceChannel): Int

    @Query("SELECT * FROM transaction_sources WHERE body_hash = :bodyHash")
    suspend fun getByBodyHash(bodyHash: String): List<TransactionSourceEntity>

    /** Re-points sources when two transactions are merged by hand (spec 14 §14.6). */
    @Query("UPDATE transaction_sources SET transaction_id = :toId WHERE transaction_id = :fromId")
    suspend fun reassign(fromId: Long, toId: Long): Int

    @Query("DELETE FROM transaction_sources WHERE transaction_id = :transactionId")
    suspend fun deleteForTransaction(transactionId: Long)

    @Query("DELETE FROM transaction_sources")
    suspend fun deleteAll()
}
