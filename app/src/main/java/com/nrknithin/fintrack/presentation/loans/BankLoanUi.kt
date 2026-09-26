package com.nrknithin.fintrack.presentation.loans

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.nrknithin.fintrack.R
import com.nrknithin.fintrack.data.database.entity.BankLoanStatus
import com.nrknithin.fintrack.data.database.entity.BankLoanType
import com.nrknithin.fintrack.ui.components.FinTrackEmptyState
import com.nrknithin.fintrack.ui.components.cards.FinTrackCardV2
import com.nrknithin.fintrack.ui.theme.Dimensions
import com.nrknithin.fintrack.ui.theme.Spacing
import com.nrknithin.fintrack.utils.CurrencyFormatter
import java.time.LocalDate
import java.time.format.DateTimeFormatter

internal val bankLoanDateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")

internal fun LocalDate.bankLoanDate(): String = format(bankLoanDateFormat)

internal val BankLoanType.labelRes: Int
    get() = when (this) {
        BankLoanType.PERSONAL -> R.string.bank_loan_type_personal
        BankLoanType.HOME -> R.string.bank_loan_type_home
        BankLoanType.VEHICLE -> R.string.bank_loan_type_vehicle
        BankLoanType.EDUCATION -> R.string.bank_loan_type_education
        BankLoanType.CARD_EMI -> R.string.bank_loan_type_card_emi
        BankLoanType.GOLD -> R.string.bank_loan_type_gold
        BankLoanType.OTHER -> R.string.bank_loan_type_other
    }

internal val BankLoanType.icon: ImageVector
    get() = when (this) {
        BankLoanType.PERSONAL -> Icons.Default.Person
        BankLoanType.HOME -> Icons.Default.Home
        BankLoanType.VEHICLE -> Icons.Default.DirectionsCar
        BankLoanType.EDUCATION -> Icons.Default.School
        BankLoanType.CARD_EMI -> Icons.Default.CreditCard
        BankLoanType.GOLD -> Icons.Default.Diamond
        BankLoanType.OTHER -> Icons.Default.AccountBalance
    }

/** The Bank loans tab of the Loans screen: totals, active loans, then closed ones. */
internal fun LazyListScope.bankLoanItems(
    state: BankLoansUiState,
    onLoanClick: (Long) -> Unit
) {
    if (state.isLoading) return
    if (state.active.isEmpty() && state.closed.isEmpty()) {
        item(key = "bank-empty") {
            Box(modifier = Modifier.fillMaxWidth().padding(top = Spacing.xl), contentAlignment = Alignment.Center) {
                FinTrackEmptyState(
                    icon = Icons.Default.AccountBalance,
                    headline = stringResource(R.string.bank_loans_empty_title),
                    description = stringResource(R.string.bank_loans_empty_description)
                )
            }
        }
        return
    }
    if (state.active.isNotEmpty()) {
        item(key = "bank-summary") { BankLoansSummaryCard(state) }
        items(state.active, key = { "bank-${it.loan.id}" }) { row ->
            BankLoanCard(row, onClick = { onLoanClick(row.loan.id) })
        }
    }
    if (state.closed.isNotEmpty()) {
        item(key = "bank-closed-header") {
            Text(
                stringResource(R.string.bank_loans_section_closed),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.sm, bottom = Spacing.xs)
            )
        }
        items(state.closed, key = { "bank-${it.loan.id}" }) { row ->
            BankLoanCard(row, onClick = { onLoanClick(row.loan.id) })
        }
    }
}

@Composable
private fun BankLoansSummaryCard(state: BankLoansUiState) {
    FinTrackCardV2(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    stringResource(R.string.bank_loans_total_outstanding),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    CurrencyFormatter.formatByCurrency(state.outstanding),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    stringResource(R.string.bank_loans_total_emi),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    CurrencyFormatter.formatByCurrency(state.monthlyEmi),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun BankLoanCard(row: BankLoanRow, onClick: () -> Unit) {
    val loan = row.loan
    val progress = row.progress
    val closed = loan.status == BankLoanStatus.CLOSED
    FinTrackCardV2(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(Dimensions.Icon.avatar)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    loan.loanType.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(Dimensions.Icon.medium)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        loan.name.ifBlank { loan.lender },
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (progress != null && !closed) {
                        Text(
                            CurrencyFormatter.formatCurrency(progress.outstanding, loan.currency),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                Text(
                    listOf(loan.lender, stringResource(loan.loanType.labelRes)).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(Spacing.xs))
                val detail = when {
                    closed -> stringResource(if (progress?.isPaidOff == true) R.string.bank_loan_paid_off else R.string.bank_loan_closed)
                    progress?.nextInstalment != null -> stringResource(
                        R.string.bank_loan_next_emi,
                        CurrencyFormatter.formatCurrency(progress.nextInstalment!!.payable, loan.currency),
                        progress.nextInstalment!!.dueDate.bankLoanDate()
                    )
                    else -> stringResource(R.string.bank_loan_needs_terms)
                }
                Text(detail, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (progress != null && !closed && progress.totalEmis > 0) {
                    Spacer(modifier = Modifier.height(Spacing.xs))
                    LinearProgressIndicator(
                        progress = { progress.emisPaid.toFloat() / progress.totalEmis },
                        modifier = Modifier.fillMaxWidth().height(Spacing.xs).clip(CircleShape),
                        trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(Spacing.xxs))
                    Text(
                        stringResource(R.string.bank_loan_emis_progress, progress.emisPaid, progress.totalEmis),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
