package com.nrknithin.fintrack.navigation

import kotlinx.serialization.Serializable

// Define navigation destinations using Kotlin Serialization
@Serializable
object AppLock

/**
 * The sign-in gate. Start destination whenever the locally-persisted
 * `auth_signed_in` flag is false and the build actually has a gateway.
 * Sits ahead of [OnBoarding] — see StartDestinationResolver.
 */
@Serializable
object SignIn

@Serializable
object OnBoarding

@Serializable
object Permission

@Serializable
object Home

@Serializable
object Transactions

@Serializable
object Settings

@Serializable
object Categories

@Serializable
object Analytics

@Serializable
object Chat

@Serializable
data class TransactionDetail(val transactionId: Long)

@Serializable
data class AddTransaction(val sourceTransactionId: Long? = null)

@Serializable
data class AccountDetail(val bankName: String, val accountLast4: String)

@Serializable
object UnrecognizedSms

@Serializable
object Faq

@Serializable
object Rules

@Serializable
data class CreateRule(val ruleId: String? = null, val duplicateFromId: String? = null)

@Serializable
object ExchangeRates

@Serializable
object BudgetGroups

@Serializable
data class BudgetGroupEdit(val groupId: Long = -1L)

/**
 * Per-budget history drill-down. The (year, month) selects the period;
 * the screen renders the per-window list (Weekly sub-list or Monthly /
 * One-time window) with each row labelled "Live" or "Frozen as of …".
 */
@Serializable
data class BudgetHistory(
    val groupId: Long,
    val year: Int,
    val month: Int
)

@Serializable
object Loans

@Serializable
object RecurringTransactions

@Serializable
data class LoanDetail(val loanId: Long)

@Serializable
object TransactionGroups

@Serializable
data class TransactionGroupDetail(val groupId: Long)

@Serializable
object ImportStatement

@Serializable
data class TransactionsWithFilter(
    val category: String,
    val period: String? = null,
    val currency: String? = null
)