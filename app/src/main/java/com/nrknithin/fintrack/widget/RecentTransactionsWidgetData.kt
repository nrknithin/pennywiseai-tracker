package com.nrknithin.fintrack.widget

import com.nrknithin.fintrack.data.database.entity.TransactionType
import java.math.BigDecimal

data class RecentTransactionItem(
    val title: String,
    val subtitle: String,
    val amount: BigDecimal,
    val currency: String,
    val transactionType: TransactionType
)

data class RecentTransactionsWidgetData(
    val totalSpent: BigDecimal = BigDecimal.ZERO,
    val currency: String = "INR",
    val transactions: List<RecentTransactionItem> = emptyList()
)
