package com.nrknithin.fintrack.presentation.loans

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nrknithin.fintrack.R
import com.nrknithin.fintrack.data.database.entity.BankLoanEntity
import com.nrknithin.fintrack.data.database.entity.BankLoanStatus
import com.nrknithin.fintrack.domain.loan.BankLoanProgress
import com.nrknithin.fintrack.domain.loan.Instalment
import com.nrknithin.fintrack.ui.components.CustomTitleTopAppBar
import com.nrknithin.fintrack.ui.components.cards.FinTrackCardV2
import com.nrknithin.fintrack.ui.effects.overScrollVertical
import com.nrknithin.fintrack.ui.effects.rememberOverscrollFlingBehavior
import com.nrknithin.fintrack.ui.theme.Dimensions
import com.nrknithin.fintrack.ui.theme.Spacing
import com.nrknithin.fintrack.utils.CurrencyFormatter
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BankLoanDetailScreen(
    onNavigateBack: () -> Unit = {},
    onEdit: (Long) -> Unit = {},
    viewModel: BankLoanDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollBehaviorSmall = TopAppBarDefaults.pinnedScrollBehavior()
    val scrollBehaviorLarge = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val hazeState = remember { HazeState() }
    var showDelete by remember { mutableStateOf(false) }
    var showClose by remember { mutableStateOf(false) }
    var showStatement by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.isDeleted) {
        if (uiState.isDeleted) onNavigateBack()
    }
    val loan = uiState.loan

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehaviorLarge.nestedScrollConnection),
        containerColor = Color.Transparent,
        topBar = {
            CustomTitleTopAppBar(
                scrollBehaviorSmall = scrollBehaviorSmall,
                scrollBehaviorLarge = scrollBehaviorLarge,
                title = loan?.name?.ifBlank { loan.lender } ?: stringResource(R.string.loans_tab_bank),
                hasBackButton = true,
                navigationContent = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.accounts_back))
                    }
                },
                actionContent = {
                    if (loan != null) {
                        var showMenu by remember { mutableStateOf(false) }
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.loan_detail_more))
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.bank_loan_action_edit)) },
                                onClick = { showMenu = false; onEdit(loan.id) },
                                leadingIcon = { Icon(Icons.Default.Edit, null) }
                            )
                            if (loan.status == BankLoanStatus.ACTIVE) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.bank_loan_action_update_statement)) },
                                    onClick = { showMenu = false; showStatement = true },
                                    leadingIcon = { Icon(Icons.Default.ReceiptLong, null) }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.bank_loan_action_close)) },
                                    onClick = { showMenu = false; showClose = true },
                                    leadingIcon = { Icon(Icons.Default.CheckCircle, null) }
                                )
                            } else {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.bank_loan_action_reopen)) },
                                    onClick = { showMenu = false; viewModel.reopen() },
                                    leadingIcon = { Icon(Icons.Default.Refresh, null) }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.bank_loan_action_delete), color = MaterialTheme.colorScheme.error) },
                                onClick = { showMenu = false; showDelete = true },
                                leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }
                            )
                        }
                    }
                },
                hazeState = hazeState
            )
        }
    ) { paddingValues ->
        if (uiState.isLoading || loan == null) {
            Box(modifier = Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        val progress = uiState.progress
        val lazyListState = rememberLazyListState()
        LazyColumn(
            state = lazyListState,
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(hazeState)
                .background(MaterialTheme.colorScheme.background)
                .overScrollVertical(),
            contentPadding = PaddingValues(
                start = Dimensions.Padding.content,
                end = Dimensions.Padding.content,
                top = Dimensions.Padding.content + paddingValues.calculateTopPadding(),
                bottom = paddingValues.calculateBottomPadding() + Spacing.md
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            flingBehavior = rememberOverscrollFlingBehavior { lazyListState }
        ) {
            item { BankLoanHeaderCard(loan, progress) }
            item { BankLoanTermsCard(loan, progress) }
            if (progress != null && progress.schedule.isNotEmpty()) {
                item {
                    Text(
                        stringResource(R.string.bank_loan_detail_schedule),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Spacing.sm)
                    )
                }
                item { ScheduleHeaderRow() }
                items(progress.schedule, key = { it.number }) { instalment ->
                    ScheduleRow(instalment, loan.currency, paid = !instalment.dueDate.isAfter(progress.today))
                }
            }
        }
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text(stringResource(R.string.bank_loan_delete_title)) },
            text = { Text(stringResource(R.string.bank_loan_delete_message)) },
            confirmButton = {
                TextButton(onClick = { showDelete = false; viewModel.delete() }) {
                    Text(stringResource(R.string.bank_loan_action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text(stringResource(R.string.accounts_action_cancel)) } }
        )
    }
    if (showClose) {
        AlertDialog(
            onDismissRequest = { showClose = false },
            title = { Text(stringResource(R.string.bank_loan_action_close)) },
            text = { Text(stringResource(R.string.bank_loan_close_message)) },
            confirmButton = {
                TextButton(onClick = { showClose = false; viewModel.close() }) { Text(stringResource(R.string.bank_loan_action_close)) }
            },
            dismissButton = { TextButton(onClick = { showClose = false }) { Text(stringResource(R.string.accounts_action_cancel)) } }
        )
    }
    if (showStatement && loan != null) {
        StatementDialog(
            initial = uiState.progress?.outstanding,
            onDismiss = { showStatement = false },
            onSave = { amount, date ->
                showStatement = false
                viewModel.updateFromStatement(amount, date)
            }
        )
    }
}

@Composable
private fun BankLoanHeaderCard(loan: BankLoanEntity, progress: BankLoanProgress?) {
    FinTrackCardV2(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(
                listOf(loan.lender, stringResource(loan.loanType.labelRes)).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (progress == null) {
                Text(stringResource(R.string.bank_loan_needs_terms), style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            Text(
                stringResource(R.string.bank_loan_detail_outstanding),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                CurrencyFormatter.formatCurrency(progress.outstanding, loan.currency),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold
            )
            if (progress.totalEmis > 0) {
                LinearProgressIndicator(
                    progress = { progress.emisPaid.toFloat() / progress.totalEmis },
                    modifier = Modifier.fillMaxWidth().height(Spacing.xs).clip(CircleShape),
                    trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
                Text(
                    stringResource(R.string.bank_loan_emis_progress, progress.emisPaid, progress.totalEmis),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(Spacing.xs))
            progress.nextInstalment?.let {
                LabelValue(
                    stringResource(R.string.bank_loan_detail_next_emi),
                    "${CurrencyFormatter.formatCurrency(it.payable, loan.currency)} · ${it.dueDate.bankLoanDate()}"
                )
            } ?: LabelValue(stringResource(R.string.bank_loan_paid_off), "")
            progress.payoffDate?.let { LabelValue(stringResource(R.string.bank_loan_detail_ends), it.bankLoanDate()) }
            LabelValue(
                stringResource(R.string.bank_loan_detail_interest_left),
                CurrencyFormatter.formatCurrency(progress.remainingInterest, loan.currency)
            )
            if (progress.remainingGst.signum() > 0) {
                LabelValue(
                    stringResource(R.string.bank_loan_detail_gst_left),
                    CurrencyFormatter.formatCurrency(progress.remainingGst, loan.currency)
                )
            }
        }
    }
}

@Composable
private fun BankLoanTermsCard(loan: BankLoanEntity, progress: BankLoanProgress?) {
    FinTrackCardV2(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            val emi = progress?.emi ?: loan.emiAmount
            LabelValue(stringResource(R.string.bank_loan_detail_emi), CurrencyFormatter.formatCurrency(emi, loan.currency))
            progress?.let {
                LabelValue(stringResource(R.string.bank_loan_detail_principal), CurrencyFormatter.formatCurrency(it.principal, loan.currency))
                LabelValue(
                    stringResource(R.string.bank_loan_detail_rate),
                    stringResource(R.string.bank_loan_detail_rate_value, it.annualRatePct.stripTrailingZeros().toPlainString())
                )
            }
            if (loan.accountBankName != null) {
                LabelValue(
                    stringResource(R.string.bank_loan_detail_account),
                    listOfNotNull(loan.accountBankName, loan.accountLast4?.let { "••$it" }).joinToString(" ")
                )
            }
            if (loan.statementOutstanding != null && loan.statementDate != null) {
                Text(
                    stringResource(
                        R.string.bank_loan_detail_statement_note,
                        CurrencyFormatter.formatCurrency(loan.statementOutstanding, loan.currency),
                        loan.statementDate.bankLoanDate()
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun LabelValue(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ScheduleHeaderRow() {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.sm)) {
        listOf(
            R.string.bank_loan_detail_schedule_due to 1.3f,
            R.string.bank_loan_detail_emi to 1f,
            R.string.bank_loan_detail_schedule_interest to 1f,
            R.string.bank_loan_detail_schedule_balance to 1.2f
        ).forEach { (label, weight) ->
            Text(
                stringResource(label),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(weight)
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun ScheduleRow(instalment: Instalment, currency: String, paid: Boolean) {
    val color = if (paid) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    val style = MaterialTheme.typography.bodySmall
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.sm, vertical = Spacing.xxs)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(instalment.dueDate.bankLoanDate(), style = style, color = color, modifier = Modifier.weight(1.3f))
            Text(CurrencyFormatter.formatCurrency(instalment.emi, currency), style = style, color = color, modifier = Modifier.weight(1f))
            Text(CurrencyFormatter.formatCurrency(instalment.interest, currency), style = style, color = color, modifier = Modifier.weight(1f))
            Text(CurrencyFormatter.formatCurrency(instalment.balanceAfter, currency), style = style, color = color, modifier = Modifier.weight(1.2f))
        }
        if (instalment.gst.signum() > 0) {
            Text(
                stringResource(R.string.bank_loan_detail_schedule_gst, CurrencyFormatter.formatCurrency(instalment.gst, currency)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatementDialog(
    initial: BigDecimal?,
    onDismiss: () -> Unit,
    onSave: (BigDecimal, LocalDate) -> Unit
) {
    var amount by remember { mutableStateOf(initial?.toPlainString() ?: "") }
    var date by remember { mutableStateOf(LocalDate.now()) }
    var pickDate by remember { mutableStateOf(false) }
    val parsed = amount.trim().toBigDecimalOrNull()?.takeIf { it.signum() >= 0 }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bank_loan_statement_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(stringResource(R.string.bank_loan_statement_body), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text(stringResource(R.string.bank_loan_statement_amount)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedButton(onClick = { pickDate = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("${stringResource(R.string.bank_loan_statement_date)}: ${date.bankLoanDate()}")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { parsed?.let { onSave(it, date) } }, enabled = parsed != null) {
                Text(stringResource(R.string.bank_loan_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.accounts_action_cancel)) } }
    )

    if (pickDate) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    pickDate = false
                }) { Text(stringResource(R.string.bank_loan_save)) }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text(stringResource(R.string.accounts_action_cancel)) } }
        ) {
            DatePicker(state = state)
        }
    }
}
