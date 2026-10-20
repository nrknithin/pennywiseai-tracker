package com.nrknithin.fintrack.presentation.loans

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nrknithin.fintrack.data.database.entity.BankLoanEntity
import com.nrknithin.fintrack.data.repository.BankLoanRepository
import com.nrknithin.fintrack.domain.loan.BankLoanProgress
import com.nrknithin.fintrack.domain.loan.BankLoanSchedule
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.LocalDate
import javax.inject.Inject

data class BankLoanDetailUiState(
    val isLoading: Boolean = true,
    val loan: BankLoanEntity? = null,
    val progress: BankLoanProgress? = null,
    val isDeleted: Boolean = false
)

@HiltViewModel
class BankLoanDetailViewModel @Inject constructor(
    private val repository: BankLoanRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val loanId: Long = savedStateHandle.get<Long>("loanId") ?: -1L
    private val deleted = MutableStateFlow(false)

    val uiState: StateFlow<BankLoanDetailUiState> = combine(repository.observe(loanId), deleted) { loan, isDeleted ->
        BankLoanDetailUiState(
            isLoading = false,
            loan = loan,
            progress = loan?.let { BankLoanSchedule.progress(it.terms, LocalDate.now()) },
            isDeleted = isDeleted
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BankLoanDetailUiState())

    fun close() = viewModelScope.launch { repository.close(loanId) }

    fun reopen() = viewModelScope.launch { repository.reopen(loanId) }

    fun delete() = viewModelScope.launch {
        repository.delete(loanId)
        deleted.value = true
    }

    fun updateFromStatement(outstanding: BigDecimal, asOf: LocalDate) = viewModelScope.launch {
        repository.updateFromStatement(loanId, outstanding, asOf)
    }
}
