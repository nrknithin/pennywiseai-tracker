package com.nrknithin.fintrack.data.repository

import com.nrknithin.fintrack.data.database.dao.BankLoanDao
import com.nrknithin.fintrack.data.database.dao.CategoryDao
import com.nrknithin.fintrack.data.database.dao.SubscriptionDao
import com.nrknithin.fintrack.data.database.entity.BankLoanEntity
import com.nrknithin.fintrack.data.database.entity.BankLoanStatus
import com.nrknithin.fintrack.data.database.entity.CategoryEntity
import com.nrknithin.fintrack.data.database.entity.SubscriptionDirection
import com.nrknithin.fintrack.data.database.entity.SubscriptionEntity
import com.nrknithin.fintrack.data.database.entity.SubscriptionState
import com.nrknithin.fintrack.domain.loan.BankLoanSchedule
import kotlinx.coroutines.flow.Flow
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bank loans and the EMI reminder each one owns.
 *
 * The reminder is an ordinary expected payment ([SubscriptionEntity]) so it shows with
 * the others and can be marked paid. [syncReminder] keeps it in step with the schedule —
 * next due date, that instalment's amount (a card EMI's GST changes monthly), paying
 * account — and ends it when the loan closes or the last EMI is past.
 */
@Singleton
class BankLoanRepository @Inject constructor(
    private val bankLoanDao: BankLoanDao,
    private val subscriptionDao: SubscriptionDao,
    private val categoryDao: CategoryDao
) {

    fun observeAll(): Flow<List<BankLoanEntity>> = bankLoanDao.observeAll()

    fun observe(id: Long): Flow<BankLoanEntity?> = bankLoanDao.observeById(id)

    suspend fun get(id: Long): BankLoanEntity? = bankLoanDao.getById(id)

    /** Inserts or updates [loan] and brings its reminder in line. Returns the loan's id. */
    suspend fun save(loan: BankLoanEntity): Long {
        val now = LocalDateTime.now()
        val id = if (loan.id == 0L) {
            bankLoanDao.insert(loan.copy(createdAt = now, updatedAt = now))
        } else {
            bankLoanDao.update(loan.copy(updatedAt = now))
            loan.id
        }
        syncReminder(id)
        return id
    }

    /** Re-anchors the balance on a figure read from the lender's statement. */
    suspend fun updateFromStatement(id: Long, outstanding: BigDecimal, asOf: LocalDate) {
        val loan = bankLoanDao.getById(id) ?: return
        save(loan.copy(statementOutstanding = outstanding, statementDate = asOf))
    }

    suspend fun close(id: Long) {
        val loan = bankLoanDao.getById(id) ?: return
        save(loan.copy(status = BankLoanStatus.CLOSED, closedAt = LocalDateTime.now()))
    }

    suspend fun reopen(id: Long) {
        val loan = bankLoanDao.getById(id) ?: return
        save(loan.copy(status = BankLoanStatus.ACTIVE, closedAt = null))
    }

    /** Deletes the loan and the EMI reminder it created. */
    suspend fun delete(id: Long) {
        val loan = bankLoanDao.getById(id) ?: return
        loan.subscriptionId?.let { subscriptionDao.deleteSubscriptionById(it) }
        bankLoanDao.delete(loan)
    }

    /** Run at startup: rolls every reminder forward and closes loans whose last EMI is past. */
    suspend fun syncAllReminders(today: LocalDate = LocalDate.now()) {
        bankLoanDao.getAll().forEach { syncReminder(it.id, today) }
    }

    private suspend fun syncReminder(id: Long, today: LocalDate = LocalDate.now()) {
        var loan = bankLoanDao.getById(id) ?: return
        val progress = BankLoanSchedule.progress(loan.terms, today)

        if (loan.status == BankLoanStatus.ACTIVE && progress?.isPaidOff == true) {
            loan = loan.copy(status = BankLoanStatus.CLOSED, closedAt = LocalDateTime.now(), updatedAt = LocalDateTime.now())
            bankLoanDao.update(loan)
        }

        val existing = loan.subscriptionId?.let { subscriptionDao.getSubscriptionById(it) }
        if (loan.status != BankLoanStatus.ACTIVE) {
            if (existing != null && existing.state == SubscriptionState.ACTIVE) {
                subscriptionDao.updateSubscriptionState(existing.id, SubscriptionState.ENDED)
            }
            return
        }

        // Never move the reminder backwards: an EMI marked paid early has already
        // advanced it to the following month.
        val scheduleNext = progress?.nextInstalment?.dueDate ?: nextDueByDay(loan.firstEmiDate, today)
        val keptNext = existing?.nextPaymentDate?.takeIf { existing.state == SubscriptionState.ACTIVE }
        val nextDate = if (keptNext != null && keptNext.isAfter(scheduleNext)) keptNext else scheduleNext
        val amount = progress?.schedule?.firstOrNull { it.dueDate == nextDate }?.payable
            ?: loan.emiAmount

        val reminder = (existing ?: SubscriptionEntity(
            merchantName = "",
            amount = amount,
            nextPaymentDate = nextDate
        )).copy(
            merchantName = loan.name.ifBlank { loan.lender },
            amount = amount,
            nextPaymentDate = nextDate,
            state = SubscriptionState.ACTIVE,
            bankName = loan.accountBankName,
            accountLast4 = loan.accountLast4,
            category = ensureEmiCategory(),
            currency = loan.currency,
            direction = SubscriptionDirection.EXPENSE,
            billingCycle = "Monthly",
            updatedAt = LocalDateTime.now()
        )
        if (existing == null) {
            val subscriptionId = subscriptionDao.insertSubscription(reminder)
            bankLoanDao.update(loan.copy(subscriptionId = subscriptionId))
        } else if (reminder.copy(updatedAt = existing.updatedAt) != existing) {
            subscriptionDao.updateSubscription(reminder)
        }
    }

    /** First EMI due after [today] when the terms are too thin for a schedule. */
    private fun nextDueByDay(firstEmiDate: LocalDate, today: LocalDate): LocalDate {
        var index = 0
        var due = firstEmiDate
        while (!due.isAfter(today)) {
            index++
            due = BankLoanSchedule.dueDate(firstEmiDate, index)
        }
        return due
    }

    private suspend fun ensureEmiCategory(): String {
        if (categoryDao.getCategoryByName(EMI_CATEGORY) == null) {
            categoryDao.insertCategory(CategoryEntity(name = EMI_CATEGORY, color = EMI_CATEGORY_COLOR))
        }
        return EMI_CATEGORY
    }

    companion object {
        const val EMI_CATEGORY = "Loans & EMI"
        private const val EMI_CATEGORY_COLOR = "#8E24AA"
    }
}
