package com.nrknithin.fintrack.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.nrknithin.fintrack.domain.loan.BankLoanTerms
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * A loan from a bank or lender, repaid in EMIs — kept apart from person-to-person
 * lending ([LoanEntity]), which has no schedule and is settled by linking transactions.
 *
 * Progress follows the schedule: an EMI counts as paid on its due date, and the
 * outstanding balance comes from the terms ([BankLoanTerms] via
 * [com.nrknithin.fintrack.domain.loan.BankLoanSchedule]). A figure from the lender's
 * statement ([statementOutstanding] as of [statementDate]) re-anchors it after a
 * prepayment or drift. EMI debits stay ordinary spending — nothing is linked or excluded.
 *
 * Only [emiAmount] is required; with any two of [principal], [annualRatePct] and
 * [tenureMonths] the full schedule can be derived.
 *
 * The loan owns its EMI reminder: [subscriptionId] points at the expected payment it
 * created, which it keeps in step and ends when the loan closes or is paid off.
 *
 * ## Backup contract (#414)
 * Every field has a Kotlin default so an older backup that omits a key still restores
 * (docs/backup-format.md, enforced by `BackupSchemaGuardTest`).
 */
@Entity(
    tableName = "bank_loans",
    indices = [Index(value = ["status"])]
)
@Serializable
data class BankLoanEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    /** Stable identity across backups; merge-restore dedups on it. */
    @ColumnInfo(name = "uid", defaultValue = "")
    val uid: String = java.util.UUID.randomUUID().toString(),

    @ColumnInfo(name = "name", defaultValue = "")
    val name: String = "",

    @ColumnInfo(name = "lender", defaultValue = "")
    val lender: String = "",

    @ColumnInfo(name = "loan_type", defaultValue = "PERSONAL")
    val loanType: BankLoanType = BankLoanType.PERSONAL,

    @ColumnInfo(name = "principal")
    @Contextual
    val principal: BigDecimal? = null,

    @ColumnInfo(name = "annual_rate_pct")
    @Contextual
    val annualRatePct: BigDecimal? = null,

    @ColumnInfo(name = "tenure_months")
    val tenureMonths: Int? = null,

    @ColumnInfo(name = "emi_amount", defaultValue = "0")
    @Contextual
    val emiAmount: BigDecimal = BigDecimal.ZERO,

    /** Due date of the first EMI; later EMIs fall on the same day of the month. */
    @ColumnInfo(name = "first_emi_date")
    @Contextual
    val firstEmiDate: LocalDate = LocalDate.now(),

    /** Tax on each month's interest, paid on top of the EMI (card EMIs: 18%). */
    @ColumnInfo(name = "gst_on_interest_pct", defaultValue = "0")
    @Contextual
    val gstOnInterestPct: BigDecimal = BigDecimal.ZERO,

    @ColumnInfo(name = "currency", defaultValue = "INR")
    val currency: String = "INR",

    /** The account the EMI is paid from, keyed like every account (bank + last 4). */
    @ColumnInfo(name = "account_bank_name")
    val accountBankName: String? = null,

    @ColumnInfo(name = "account_last4")
    val accountLast4: String? = null,

    @ColumnInfo(name = "statement_outstanding")
    @Contextual
    val statementOutstanding: BigDecimal? = null,

    @ColumnInfo(name = "statement_date")
    @Contextual
    val statementDate: LocalDate? = null,

    @ColumnInfo(name = "status", defaultValue = "ACTIVE")
    val status: BankLoanStatus = BankLoanStatus.ACTIVE,

    /** The EMI reminder (a [SubscriptionEntity]) this loan created and maintains. */
    @ColumnInfo(name = "subscription_id")
    val subscriptionId: Long? = null,

    @ColumnInfo(name = "note")
    val note: String? = null,

    @ColumnInfo(name = "created_at")
    @Contextual
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @ColumnInfo(name = "updated_at")
    @Contextual
    val updatedAt: LocalDateTime = LocalDateTime.now(),

    @ColumnInfo(name = "closed_at")
    @Contextual
    val closedAt: LocalDateTime? = null
) {
    val terms: BankLoanTerms
        get() = BankLoanTerms(
            principal = principal,
            annualRatePct = annualRatePct,
            tenureMonths = tenureMonths,
            emi = emiAmount.takeIf { it.signum() > 0 },
            firstEmiDate = firstEmiDate,
            gstOnInterestPct = gstOnInterestPct,
            statementOutstanding = statementOutstanding,
            statementDate = statementDate
        )
}

@Serializable
enum class BankLoanType {
    PERSONAL,
    HOME,
    VEHICLE,
    EDUCATION,
    CARD_EMI,
    GOLD,
    OTHER
}

@Serializable
enum class BankLoanStatus {
    ACTIVE,
    CLOSED
}
