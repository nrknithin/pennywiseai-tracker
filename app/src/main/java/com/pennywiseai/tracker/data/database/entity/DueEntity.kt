package com.pennywiseai.tracker.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Money owed, with a date (spec 03 §3.2, spec 20).
 *
 * A due is an **expectation**, never a fact — that distinction is the spine of the whole app
 * (spec 01 rule 1). A bill email creates a due; a debit alert creates a transaction; settlement
 * is what joins them. Nothing in this table means money actually moved.
 *
 * Every field has a default because this table reaches the backup (spec 01 constraint C4).
 */
@Entity(
    tableName = "dues",
    indices = [
        // The identity of a due. Three senders may describe the same bill — the issuer, CRED
        // and Amazon Pay — and all three must land on one row (spec 20 §20.4).
        Index(value = ["due_id"], unique = true),
        // What the Pending tab reads: open dues ordered by date (spec 02 §2.6).
        Index(value = ["status", "due_date"]),
        Index(value = ["settled_transaction_id"]),
        Index(value = ["account_last4"])
    ]
)
@Serializable
data class DueEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    /**
     * Stable identity across re-reads of the same bill:
     * `md5(issuer|kind|reference-or-account|dueDate|currency)` for a parsed due,
     * `C:<commitmentId>:<cycleKey>` for one generated from a commitment (M2),
     * a UUID for a manual one.
     */
    @ColumnInfo(name = "due_id")
    val dueId: String = "",

    /** Who wants the money, as the user would name them. */
    @ColumnInfo(name = "issuer")
    val issuer: String = "",

    @ColumnInfo(name = "kind")
    val kind: DueKind = DueKind.OTHER,

    /**
     * The total owed. May be zero for a "nothing due this month" statement, which the
     * repository then auto-[DueStatus.DISMISSED]s with a note rather than showing as pending.
     */
    @ColumnInfo(name = "amount")
    @Contextual
    val amount: BigDecimal = BigDecimal.ZERO,

    @ColumnInfo(name = "currency")
    val currency: String = "INR",

    /** What the issuer will accept as a minimum — card statements only. */
    @ColumnInfo(name = "minimum_due")
    @Contextual
    val minimumDue: BigDecimal? = null,

    @ColumnInfo(name = "due_date")
    @Contextual
    val dueDate: LocalDate = LocalDate.EPOCH,

    /** The card or account being billed; what lets two senders' readings merge. */
    @ColumnInfo(name = "account_last4")
    val accountLast4: String? = null,

    /** The issuer's own identifier: invoice number, domain name, mandate id, loan account. */
    @ColumnInfo(name = "reference")
    val reference: String? = null,

    /** Human-readable billing period, e.g. "Aug 2026" or "1 Jul-31 Jul". */
    @ColumnInfo(name = "period_label")
    val periodLabel: String? = null,

    /** True for e-mandates and standing instructions: money leaves without the user acting. */
    @ColumnInfo(name = "auto_pay")
    val autoPay: Boolean = false,

    /** What will be debited when [autoPay], as printed by the issuer. */
    @ColumnInfo(name = "auto_pay_instrument")
    val autoPayInstrument: String? = null,

    @ColumnInfo(name = "status")
    val status: DueStatus = DueStatus.OPEN,

    /**
     * The one transaction that settled this due. Spec 01 rule 3 makes the relationship
     * strictly one-to-one — a due is settled by exactly one transaction, and a transaction
     * settles at most one due. Room cannot express "unique when non-null" in an index, so
     * the repository enforces it; see `DueRepository`.
     */
    @ColumnInfo(name = "settled_transaction_id")
    val settledTransactionId: Long? = null,

    @ColumnInfo(name = "settled_at")
    @Contextual
    val settledAt: LocalDateTime? = null,

    @ColumnInfo(name = "source")
    val source: DueSource = DueSource.EMAIL,

    /** The first email that created this due; any others are recorded in `due_sources` (M2). */
    @ColumnInfo(name = "email_message_id")
    val emailMessageId: Long? = null,

    @ColumnInfo(name = "note")
    val note: String? = null,

    @ColumnInfo(name = "profile_id")
    val profileId: Long = 1,

    @ColumnInfo(name = "created_at")
    @Contextual
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @ColumnInfo(name = "updated_at")
    @Contextual
    val updatedAt: LocalDateTime = LocalDateTime.now()
)
