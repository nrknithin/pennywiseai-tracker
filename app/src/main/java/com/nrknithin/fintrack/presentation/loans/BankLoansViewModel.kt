package com.nrknithin.fintrack.presentation.loans

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nrknithin.fintrack.data.database.entity.BankLoanEntity
import com.nrknithin.fintrack.data.database.entity.BankLoanStatus
import com.nrknithin.fintrack.data.repository.BankLoanRepository
import com.nrknithin.fintrack.domain.loan.BankLoanProgress
import com.nrknithin.fintrack.domain.loan.BankLoanSchedule
import com.nrknithin.fintrack.utils.Money
import com.nrknithin.fintrack.utils.sumByCurrency
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import javax.inject.Inject

/** A loan and where its schedule stands today (null when the terms are too thin). */
data class BankLoanRow(val loan: BankLoanEntity, val progress: BankLoanProgress?)

data class BankLoansUiState(
    val isLoading: Boolean = true,
    val active: List<BankLoanRow> = emptyList(),
    val closed: List<BankLoanRow> = emptyList(),
    /** Per currency — loans in different currencies are never added together. */
    val outstanding: Map<String, Money> = emptyMap(),
    val monthlyEmi: Map<String, Money> = emptyMap()
)

@HiltViewModel
class BankLoansViewModel @Inject constructor(
    repository: BankLoanRepository
) : ViewModel() {

    val uiState: StateFlow<BankLoansUiState> = repository.observeAll()
        .map { loans ->
            val today = LocalDate.now()
            val rows = loans.map { BankLoanRow(it, BankLoanSchedule.progress(it.terms, today)) }
            val (active, closed) = rows.partition { it.loan.status == BankLoanStatus.ACTIVE }
            BankLoansUiState(
                isLoading = false,
                active = active,
                closed = closed,
                outstanding = active.mapNotNull { row -> row.progress?.let { row.loan.currency to it.outstanding } }
                    .sumByCurrency({ it.first }, { it.second }),
                monthlyEmi = active.sumByCurrency(
                    { it.loan.currency },
                    { it.progress?.nextInstalment?.payable ?: it.loan.emiAmount }
                )
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BankLoansUiState())
}
