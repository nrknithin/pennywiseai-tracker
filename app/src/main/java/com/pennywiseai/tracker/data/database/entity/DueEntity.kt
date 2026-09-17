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
     * repository then auto-[DueStatus.DISMISSED]s rather than showing as pending.
     *
     * Read it together with [amountStated]: zero-and-unstated is *unknown*, not nil.
     */
    @ColumnInfo(name = "amount")
    @Contextual
    val amount: BigDecimal = BigDecimal.ZERO,

    /**
     * True when some sender has actually **stated** [amount]; false while the figure is
     * unknown and the column is holding its zero default.
     *
     * `dues.amount` is NOT NULL at v63 while `ParsedDue.amount` is nullable by design — a
     * plan-expiry or insurance reminder names a date and no figure — so the difference between
     * "nothing is owed" and "nobody said" has to live somewhere else. It used to live in the
     * `note` marker `Amount not stated`, which the user can edit; editing it turned an unknown
     * amount into a stated zero, which is spec 01 constraint C7 broken from the inside. The
     * decision is a column now, and the note is display text only (docs/FORK-CHANGES.md).
     *
     * Defaults to **true**, which is the same reading the marker had: only positive evidence
     * of ignorance means unknown. A row that arrives without the flag — a restored backup
     * written before it existed, a hand-built row — carries a figure somebody meant, and
     * calling it unstated would open it to being overwritten by the first aggregator copy that
     * names a different number (spec 20 §20.4's ladder exists to stop exactly that).
     */
    @ColumnInfo(name = "amount_stated", defaultValue = "1")
    val amountStated: Boolean = true,

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
     * True when **the app itself** closed this due under R-20-09 — a card statement that stated
     * zero, or a credit balance — rather than the user closing it.
     *
     * The distinction decides whether a later notice may re-open the row (spec 20 §20.7): the
     * app may take back its own dismissal when a real bill turns up, and must never take back
     * the user's. Like [amountStated] this was a `note` comparison (`Nothing due (statement)`)
     * until a user editing that string could freeze an auto-dismissed due forever.
     *
     * Only ever true alongside [DueStatus.DISMISSED]; re-opening a row clears it.
     */
    @ColumnInfo(name = "auto_dismissed", defaultValue = "0")
    val autoDismissed: Boolean = false,

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

    /**
     * Free text: the sender's own description, one of the app's stand-in captions, or whatever
     * the user typed over it. **Display only** — no rule reads it. Every decision that used to
     * be encoded here is a column of its own ([amountStated], [autoDismissed]).
     */
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
