package com.nrknithin.fintrack.presentation.loans

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nrknithin.fintrack.R
import com.nrknithin.fintrack.data.database.entity.AccountBalanceEntity
import com.nrknithin.fintrack.data.database.entity.BankLoanType
import com.nrknithin.fintrack.ui.components.CustomTitleTopAppBar
import com.nrknithin.fintrack.ui.components.cards.FinTrackCardV2
import com.nrknithin.fintrack.ui.effects.overScrollVertical
import com.nrknithin.fintrack.ui.theme.Dimensions
import com.nrknithin.fintrack.ui.theme.Spacing
import com.nrknithin.fintrack.utils.CurrencyFormatter
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BankLoanEditScreen(
    onNavigateBack: () -> Unit = {},
    viewModel: BankLoanEditViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val form = uiState.form
    val scrollBehaviorSmall = TopAppBarDefaults.pinnedScrollBehavior()
    val scrollBehaviorLarge = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val hazeState = remember { HazeState() }
    var pickDate by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.saved) {
        if (uiState.saved) onNavigateBack()
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehaviorLarge.nestedScrollConnection),
        containerColor = Color.Transparent,
        topBar = {
            CustomTitleTopAppBar(
                scrollBehaviorSmall = scrollBehaviorSmall,
                scrollBehaviorLarge = scrollBehaviorLarge,
                title = stringResource(if (uiState.isEditing) R.string.bank_loan_edit_title else R.string.bank_loan_add_title),
                hasBackButton = true,
                navigationContent = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.accounts_back))
                    }
                },
                hazeState = hazeState
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(hazeState)
                .background(MaterialTheme.colorScheme.background)
                .padding(paddingValues)
                .padding(Dimensions.Padding.content)
                .imePadding()
                .overScrollVertical()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            OutlinedTextField(
                value = form.name,
                onValueChange = { v -> viewModel.update { it.copy(name = v) } },
                label = { Text(stringResource(R.string.bank_loan_field_name)) },
                placeholder = { Text(stringResource(R.string.bank_loan_field_name_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = form.lender,
                onValueChange = { v -> viewModel.update { it.copy(lender = v) } },
                label = { Text(stringResource(R.string.bank_loan_field_lender)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            TypeDropdown(form.type) { t -> viewModel.update { it.copy(type = t) } }

            FinTrackCardV2(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(
                        stringResource(R.string.bank_loan_terms_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    NumberField(form.emi, R.string.bank_loan_field_emi) { v -> viewModel.update { it.copy(emi = v) } }
                    NumberField(form.principal, R.string.bank_loan_field_principal) { v -> viewModel.update { it.copy(principal = v) } }
                    NumberField(form.rate, R.string.bank_loan_field_rate) { v -> viewModel.update { it.copy(rate = v) } }
                    NumberField(form.tenure, R.string.bank_loan_field_tenure, KeyboardType.Number) { v -> viewModel.update { it.copy(tenure = v) } }
                    val resolved = form.resolved
                    Text(
                        if (resolved != null) {
                            stringResource(
                                R.string.bank_loan_terms_resolved,
                                CurrencyFormatter.formatCurrency(resolved.emi, "INR"),
                                resolved.annualRatePct.stripTrailingZeros().toPlainString(),
                                resolved.tenureMonths,
                                CurrencyFormatter.formatCurrency(resolved.principal, "INR")
                            )
                        } else {
                            stringResource(R.string.bank_loan_terms_unresolved)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            OutlinedButton(onClick = { pickDate = true }, modifier = Modifier.fillMaxWidth()) {
                Text("${stringResource(R.string.bank_loan_field_first_emi)}: ${form.firstEmiDate.bankLoanDate()}")
            }
            NumberField(form.gst, R.string.bank_loan_field_gst, supporting = R.string.bank_loan_field_gst_hint) { v ->
                viewModel.update { it.copy(gst = v) }
            }
            AccountDropdown(accounts, form.accountBankName, form.accountLast4) { account ->
                viewModel.update { it.copy(accountBankName = account?.bankName, accountLast4 = account?.accountLast4) }
            }

            if (!form.canSave && form.name.isNotEmpty()) {
                Text(
                    stringResource(R.string.bank_loan_error_required),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Button(onClick = viewModel::save, enabled = form.canSave, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.bank_loan_save))
            }
        }
    }

    if (pickDate) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = form.firstEmiDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis ->
                        val date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                        viewModel.update { it.copy(firstEmiDate = date) }
                    }
                    pickDate = false
                }) { Text(stringResource(R.string.bank_loan_save)) }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text(stringResource(R.string.accounts_action_cancel)) } }
        ) {
            DatePicker(state = state)
        }
    }
}

@Composable
private fun NumberField(
    value: String,
    label: Int,
    keyboardType: KeyboardType = KeyboardType.Decimal,
    supporting: Int? = null,
    onChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        supportingText = supporting?.let { { Text(stringResource(it)) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth()
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TypeDropdown(selected: BankLoanType, onSelect: (BankLoanType) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = stringResource(selected.labelRes),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.bank_loan_field_type)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            BankLoanType.entries.forEach { type ->
                DropdownMenuItem(
                    text = { Text(stringResource(type.labelRes)) },
                    onClick = { onSelect(type); expanded = false }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountDropdown(
    accounts: List<AccountBalanceEntity>,
    bankName: String?,
    last4: String?,
    onSelect: (AccountBalanceEntity?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = accounts.firstOrNull { it.bankName == bankName && it.accountLast4 == last4 }
    val label = selected?.displayLabel
        ?: bankName?.let { AccountBalanceEntity.accountLabel(it, last4) }
        ?: stringResource(R.string.bank_loan_field_account_none)
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.bank_loan_field_account)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.bank_loan_field_account_none)) },
                onClick = { onSelect(null); expanded = false }
            )
            accounts.forEach { account ->
                DropdownMenuItem(
                    text = { Text(account.displayLabel) },
                    onClick = { onSelect(account); expanded = false }
                )
            }
        }
    }
}
