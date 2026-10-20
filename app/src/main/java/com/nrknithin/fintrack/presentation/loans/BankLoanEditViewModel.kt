package com.nrknithin.fintrack.presentation.loans

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nrknithin.fintrack.data.database.entity.AccountBalanceEntity
import com.nrknithin.fintrack.data.database.entity.BankLoanEntity
import com.nrknithin.fintrack.data.database.entity.BankLoanType
import com.nrknithin.fintrack.data.repository.AccountBalanceRepository
import com.nrknithin.fintrack.data.repository.BankLoanRepository
import com.nrknithin.fintrack.domain.loan.BankLoanSchedule
import com.nrknithin.fintrack.domain.loan.BankLoanTerms
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.LocalDate
import javax.inject.Inject

/** The form, held as typed text so partial input ("12.") survives recomposition. */
data class BankLoanForm(
    val name: String = "",
    val lender: String = "",
    val type: BankLoanType = BankLoanType.PERSONAL,
    val emi: String = "",
    val principal: String = "",
    val rate: String = "",
    val tenure: String = "",
    val gst: String = "",
    val firstEmiDate: LocalDate = LocalDate.now().plusMonths(1),
    val accountBankName: String? = null,
    val accountLast4: String? = null
) {
    val terms: BankLoanTerms
        get() = BankLoanTerms(
            principal = principal.decimal(),
            annualRatePct = rate.decimal(),
            tenureMonths = tenure.trim().toIntOrNull(),
            emi = emi.decimal(),
            firstEmiDate = firstEmiDate,
            gstOnInterestPct = gst.decimal() ?: BigDecimal.ZERO
        )

    val resolved: BankLoanSchedule.Resolved? get() = BankLoanSchedule.resolve(terms)

    /** Savable with a name and an EMI — typed, or worked out from the other terms. */
    val canSave: Boolean get() = name.isNotBlank() && (emi.decimal() != null || resolved != null)
}

private fun String.decimal(): BigDecimal? = trim().replace(",", "").toBigDecimalOrNull()?.takeIf { it.signum() >= 0 }

data class BankLoanEditUiState(
    val isEditing: Boolean = false,
    val form: BankLoanForm = BankLoanForm(),
    val saved: Boolean = false
)

@HiltViewModel
class BankLoanEditViewModel @Inject constructor(
    private val repository: BankLoanRepository,
    accountBalanceRepository: AccountBalanceRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val loanId: Long = savedStateHandle.get<Long>("loanId") ?: -1L
    private var original: BankLoanEntity? = null

    private val _uiState = MutableStateFlow(BankLoanEditUiState(isEditing = loanId > 0))
    val uiState: StateFlow<BankLoanEditUiState> = _uiState.asStateFlow()

    /** Accounts an EMI can be paid from. */
    val accounts: StateFlow<List<AccountBalanceEntity>> = accountBalanceRepository.getAllLatestBalances()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        if (loanId > 0) {
            viewModelScope.launch {
                val loan = repository.observe(loanId).first() ?: return@launch
                original = loan
                _uiState.update {
                    it.copy(
                        form = BankLoanForm(
                            name = loan.name,
                            lender = loan.lender,
                            type = loan.loanType,
                            emi = loan.emiAmount.takeIf { e -> e.signum() > 0 }?.toPlainString() ?: "",
                            principal = loan.principal?.toPlainString() ?: "",
                            rate = loan.annualRatePct?.stripTrailingZeros()?.toPlainString() ?: "",
                            tenure = loan.tenureMonths?.toString() ?: "",
                            gst = loan.gstOnInterestPct.takeIf { g -> g.signum() > 0 }?.stripTrailingZeros()?.toPlainString() ?: "",
                            firstEmiDate = loan.firstEmiDate,
                            accountBankName = loan.accountBankName,
                            accountLast4 = loan.accountLast4
                        )
                    )
                }
            }
        }
    }

    fun update(transform: (BankLoanForm) -> BankLoanForm) {
        _uiState.update { it.copy(form = transform(it.form)) }
    }

    fun save() {
        val form = _uiState.value.form
        if (!form.canSave) return
        val terms = form.terms
        // Keep what the user typed; the EMI falls back to the worked-out one.
        val emi = terms.emi ?: form.resolved?.emi ?: return
        viewModelScope.launch {
            val base = original ?: BankLoanEntity()
            repository.save(
                base.copy(
                    name = form.name.trim(),
                    lender = form.lender.trim(),
                    loanType = form.type,
                    principal = terms.principal,
                    annualRatePct = terms.annualRatePct,
                    tenureMonths = terms.tenureMonths,
                    emiAmount = emi,
                    firstEmiDate = form.firstEmiDate,
                    gstOnInterestPct = terms.gstOnInterestPct,
                    accountBankName = form.accountBankName,
                    accountLast4 = form.accountLast4
                )
            )
            _uiState.update { it.copy(saved = true) }
        }
    }
}
