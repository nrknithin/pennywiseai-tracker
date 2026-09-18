package com.pennywiseai.tracker.data.database.dao

import androidx.room.*
import com.pennywiseai.tracker.data.database.entity.DueEntity
import com.pennywiseai.tracker.data.database.entity.DueKind
import com.pennywiseai.tracker.data.database.entity.DueStatus
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/**
 * Dues — the "what is pending" question the app exists to answer (spec 03 §3.2, spec 20).
 *
 * Two invariants live above this DAO rather than in it, and both are enforced in
 * `DueRepository`:
 *  - a transaction settles at most one due, and a due is settled by exactly one transaction
 *    (spec 01 rule 3) — Room cannot express "unique when non-null" in an index;
 *  - a reading of a bill merges into an existing due rather than inserting a second row
 *    (spec 20 §20.4) — which is why [findMergeCandidates] exists and [insert] does not
 *    replace on conflict.
 */
@Dao
interface DueDao {

    /**
     * ABORT rather than REPLACE on conflict. `due_id` is unique, and a REPLACE would delete
     * the existing row and insert a new one with a new primary key — silently orphaning its
     * `due_sources` rows and any settlement already recorded against it. A caller that means
     * to update must call [update], and a caller unsure which it wants must ask
     * [getByDueId] first.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(due: DueEntity): Long

    @Update
    suspend fun update(due: DueEntity)

    @Delete
    suspend fun delete(due: DueEntity)

    @Query("SELECT * FROM dues WHERE id = :id")
    suspend fun getById(id: Long): DueEntity?

    @Query("SELECT * FROM dues WHERE id = :id")
    fun observeById(id: Long): Flow<DueEntity?>

    @Query("SELECT * FROM dues WHERE due_id = :dueId")
    suspend fun getByDueId(dueId: String): DueEntity?

    // ---------------------------------------------------------------- pending

    /**
     * What the Pending tab shows: everything still owed, soonest first, so anything overdue
     * naturally sits at the top (spec 33).
     */
    @Query(
        """
        SELECT * FROM dues
        WHERE status = 'OPEN'
        ORDER BY due_date ASC, amount DESC
        """
    )
    fun observeOpen(): Flow<List<DueEntity>>

    @Query(
        """
        SELECT * FROM dues
        WHERE status = 'OPEN' AND due_date <= :through
        ORDER BY due_date ASC
        """
    )
    fun observeOpenThrough(through: LocalDate): Flow<List<DueEntity>>

    @Query("SELECT * FROM dues WHERE status = 'OPEN' AND due_date < :asOf ORDER BY due_date ASC")
    fun observeOverdue(asOf: LocalDate): Flow<List<DueEntity>>

    @Query("SELECT COUNT(*) FROM dues WHERE status = 'OPEN'")
    fun observeOpenCount(): Flow<Int>

    /**
     * Money committed before the next payday — what makes the Accounts screen able to show a
     * "free" balance rather than just a bank balance (spec 01 §1.2, spec 25).
     */
    @Query(
        """
        SELECT COALESCE(SUM(CAST(amount AS REAL)), 0)
        FROM dues
        WHERE status = 'OPEN' AND currency = :currency AND due_date <= :through
        """
    )
    fun observeCommittedTotal(through: LocalDate, currency: String = "INR"): Flow<Double>

    // ---------------------------------------------------------------- settlement

    /**
     * Candidate dues a transaction might settle (spec 20 §20.5).
     *
     * Deliberately wide: it filters only on status and a date window, and leaves amount
     * tolerance, instrument matching and scoring to `DueMatcher`, which is pure Kotlin and
     * unit-tested. Putting that logic in SQL would make it untestable without a database and
     * impossible to explain to the user on the due detail screen.
     */
    @Query(
        """
        SELECT * FROM dues
        WHERE status = 'OPEN'
          AND due_date BETWEEN :from AND :to
        ORDER BY due_date ASC
        """
    )
    suspend fun findSettlementCandidates(from: LocalDate, to: LocalDate): List<DueEntity>

    /**
     * Every existing due a newly parsed one might merge into (spec 20 §20.4 step 2): same kind,
     * same issuer, same card, with a due date within a tolerance — CRED and the issuer do not
     * always agree on the date to the day.
     *
     * **The set, not a row.** Step 2 gathers candidates and step 4 chooses between them ("the
     * candidate with the most sources, ties to earliest created"), and a payment confirmation
     * (step 7) is matched over a window two months wide where two consecutive statements are
     * both plausible. This query used to end `LIMIT 1`, which made SQL the chooser and fixed
     * its answer at *earliest due date* — so a confirmation dated in late September landed on
     * August's bill and October's never got its `PAYMENT_CONFIRMED` source. Returning the whole
     * set is what puts the choice back in `DueMergeEngine`, where it is explainable and tested.
     *
     * The ordering is only for determinism — `due_date` then `id`, so the same mailbox
     * processed twice hands the engine the same list and the engine's own tie-breaks decide.
     * A caller that wants one row calls `DueMergeEngine.chooseTarget`; there is deliberately
     * no single-row variant of this query any more.
     */
    @Query(
        """
        SELECT * FROM dues
        WHERE status = 'OPEN'
          AND kind = :kind
          AND LOWER(issuer) = LOWER(:issuer)
          AND (
                (:accountLast4 IS NULL AND account_last4 IS NULL)
             OR (account_last4 IS NOT NULL AND :accountLast4 IS NOT NULL
                 AND account_last4 = :accountLast4)
          )
          AND due_date BETWEEN :fromDate AND :toDate
        ORDER BY due_date ASC, id ASC
        """
    )
    suspend fun findMergeCandidates(
        kind: DueKind,
        issuer: String,
        accountLast4: String?,
        fromDate: LocalDate,
        toDate: LocalDate
    ): List<DueEntity>

    /** Guards the one-transaction-one-due invariant before a settlement is written. */
    @Query("SELECT * FROM dues WHERE settled_transaction_id = :transactionId LIMIT 1")
    suspend fun getSettledBy(transactionId: Long): DueEntity?

    // ---------------------------------------------------------------- history

    @Query("SELECT * FROM dues WHERE status != 'OPEN' ORDER BY due_date DESC LIMIT :limit")
    fun observeSettled(limit: Int = 100): Flow<List<DueEntity>>

    @Query("SELECT * FROM dues WHERE reference = :reference ORDER BY due_date DESC")
    suspend fun getByReference(reference: String): List<DueEntity>

    @Query("SELECT * FROM dues WHERE account_last4 = :last4 ORDER BY due_date DESC")
    fun observeForAccount(last4: String): Flow<List<DueEntity>>

    @Query("SELECT * FROM dues WHERE status = :status ORDER BY due_date DESC")
    suspend fun getByStatus(status: DueStatus): List<DueEntity>

    @Query("SELECT * FROM dues WHERE email_message_id = :emailMessageId")
    suspend fun getByEmailMessageId(emailMessageId: Long): List<DueEntity>

    @Query("SELECT COUNT(*) FROM dues")
    suspend fun count(): Int

    @Query("DELETE FROM dues")
    suspend fun deleteAll()
}
