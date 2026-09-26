package com.nrknithin.fintrack.ui.screens.onboarding

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asFlow
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.nrknithin.fintrack.data.database.entity.AccountBalanceEntity
import com.nrknithin.fintrack.data.manager.SmsScanManager
import com.nrknithin.fintrack.data.manager.SmsScanParamsCalculator
import com.nrknithin.fintrack.data.preferences.UserPreferencesRepository
import com.nrknithin.fintrack.ui.components.AvatarHelper
import com.nrknithin.fintrack.data.repository.AccountBalanceRepository
import com.nrknithin.fintrack.utils.CurrencyFormatter
import com.nrknithin.fintrack.worker.OptimizedSmsReaderWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.math.BigDecimal
import javax.inject.Inject

enum class OnBoardingStep {
    WELCOME,
    PROFILE,
    PERMISSIONS,
    SCAN_PERIOD,
    SMS_SCAN,
    ACCOUNT_SETUP
}

/**
 * How far back the SMS scan reads — the same choice as Settings → SMS Scan Period.
 * Asked before the first scan, since picking a longer period afterwards means a rescan.
 */
data class ScanPeriodChoice(
    val allTime: Boolean = true,
    val months: Int = 3,
    val useCustomDate: Boolean = false,
    val customDateMillis: Long? = null
) {
    val isComplete: Boolean get() = !useCustomDate || customDateMillis != null
}

data class OnBoardingUiState(
    val currentStep: OnBoardingStep = OnBoardingStep.WELCOME,
    val userName: String = "",
    val selectedAvatarIndex: Int = 0,
    val profileImageUri: Uri? = null,
    val selectedBackgroundColor: Int = 0,
    val smsPermissionGranted: Boolean = false,
    val smsPermissionSkipped: Boolean = false,
    val scanPeriod: ScanPeriodChoice = ScanPeriodChoice(),
    // The period the last onboarding scan used, so a Back → change → Continue rescans.
    val scannedPeriod: ScanPeriodChoice? = null,
    val isScanning: Boolean = false,
    val scanTotal: Int = 0,
    val scanProcessed: Int = 0,
    val scanParsed: Int = 0,
    val scanSaved: Int = 0,
    val scanTimeElapsed: Long = 0L,
    val scanEstimatedRemaining: Long = 0L,
    val scanCompleted: Boolean = false,
    val accounts: List<AccountBalanceEntity> = emptyList(),
    val selectedAccountKey: String? = null,
    val isCompleting: Boolean = false
)

@HiltViewModel
class OnBoardingViewModel @Inject constructor(
    private val userPreferencesRepository: UserPreferencesRepository,
    private val smsScanManager: SmsScanManager,
    private val accountBalanceRepository: AccountBalanceRepository,
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(OnBoardingUiState())
    val uiState: StateFlow<OnBoardingUiState> = _uiState.asStateFlow()

    val avatarDrawables = AvatarHelper.avatarDrawables

    val backgroundColors = listOf(
        0xFFDC8A78.toInt(), // Rosewater
        0xFFDD7878.toInt(), // Flamingo
        0xFFEA76CB.toInt(), // Pink
        0xFF8839EF.toInt(), // Mauve
        0xFFD20F39.toInt(), // Red
        0xFFFE640B.toInt(), // Peach
        0xFFDF8E1D.toInt(), // Yellow
        0xFF40A02B.toInt(), // Green
        0xFF179299.toInt(), // Teal
        0xFF04A5E5.toInt(), // Sky
        0xFF209FB5.toInt(), // Sapphire
        0xFF1E66F5.toInt(), // Blue
        0xFF7287FD.toInt(), // Lavender
        0xFF6C6F85.toInt(), // Subtext0
        0xFF8C8FA1.toInt(), // Overlay1
        0xFFACB0BE.toInt()  // Overlay2
    )

    init {
        viewModelScope.launch {
            val allTime = userPreferencesRepository.smsScanAllTime.first()
            val choice = ScanPeriodChoice(
                allTime = allTime,
                months = userPreferencesRepository.smsScanMonths.first(),
                useCustomDate = !allTime && userPreferencesRepository.smsScanUseCustomDate.first(),
                customDateMillis = userPreferencesRepository.smsScanCustomDate.first()
            )
            _uiState.update { it.copy(scanPeriod = choice) }
        }
    }

    fun updateUserName(name: String) {
        _uiState.update { it.copy(userName = name) }
    }

    fun selectAvatar(index: Int) {
        _uiState.update { it.copy(selectedAvatarIndex = index, profileImageUri = null) }
    }

    fun selectProfileImage(uri: Uri) {
        viewModelScope.launch {
            val savedUri = saveImageToInternalStorage(uri)
            _uiState.update { it.copy(profileImageUri = savedUri ?: uri, selectedAvatarIndex = -1) }
        }
    }

    private suspend fun saveImageToInternalStorage(sourceUri: Uri): Uri? = withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(sourceUri) ?: return@withContext null
            val file = File(context.filesDir, "profile_image.jpg")
            file.outputStream().use { output -> inputStream.use { input -> input.copyTo(output) } }
            file.toUri()
        } catch (_: Exception) {
            null
        }
    }

    fun selectBackgroundColor(colorIndex: Int) {
        _uiState.update { it.copy(selectedBackgroundColor = colorIndex) }
    }

    fun onSmsPermissionResult(granted: Boolean) {
        _uiState.update {
            it.copy(
                smsPermissionGranted = granted,
                smsPermissionSkipped = !granted
            )
        }
    }

    fun skipSmsPermission() {
        _uiState.update { it.copy(smsPermissionSkipped = true) }
    }

    fun navigateToStep(step: OnBoardingStep) {
        _uiState.update { it.copy(currentStep = step) }
    }

    fun goToNextStep() {
        val currentStep = _uiState.value.currentStep
        val nextStep = when (currentStep) {
            OnBoardingStep.WELCOME -> OnBoardingStep.PROFILE
            OnBoardingStep.PROFILE -> OnBoardingStep.PERMISSIONS
            OnBoardingStep.PERMISSIONS -> {
                if (_uiState.value.smsPermissionGranted) {
                    OnBoardingStep.SCAN_PERIOD
                } else {
                    // Skip the scan steps if permissions not granted
                    OnBoardingStep.ACCOUNT_SETUP
                }
            }
            OnBoardingStep.SCAN_PERIOD -> OnBoardingStep.SMS_SCAN
            OnBoardingStep.SMS_SCAN -> OnBoardingStep.ACCOUNT_SETUP
            OnBoardingStep.ACCOUNT_SETUP -> OnBoardingStep.ACCOUNT_SETUP // Already last
        }
        _uiState.update { it.copy(currentStep = nextStep) }
    }

    fun goToPreviousStep() {
        val currentStep = _uiState.value.currentStep
        val previousStep = when (currentStep) {
            OnBoardingStep.WELCOME -> OnBoardingStep.WELCOME
            OnBoardingStep.PROFILE -> OnBoardingStep.WELCOME
            OnBoardingStep.PERMISSIONS -> OnBoardingStep.PROFILE
            OnBoardingStep.SCAN_PERIOD -> OnBoardingStep.PERMISSIONS
            OnBoardingStep.SMS_SCAN -> OnBoardingStep.SCAN_PERIOD
            OnBoardingStep.ACCOUNT_SETUP -> {
                if (_uiState.value.smsPermissionGranted) {
                    OnBoardingStep.SMS_SCAN
                } else {
                    OnBoardingStep.PERMISSIONS
                }
            }
        }
        _uiState.update { it.copy(currentStep = previousStep) }
    }

    fun selectScanAllTime() {
        _uiState.update { it.copy(scanPeriod = it.scanPeriod.copy(allTime = true, useCustomDate = false)) }
    }

    fun selectScanMonths(months: Int) {
        _uiState.update {
            it.copy(scanPeriod = it.scanPeriod.copy(allTime = false, useCustomDate = false, months = months))
        }
    }

    /** [pickerMillis] is the DatePicker's UTC-midnight value for the chosen day. */
    fun selectScanCustomDate(pickerMillis: Long) {
        val startOfDay = SmsScanParamsCalculator.normalizePickerDateToLocalStartOfDay(pickerMillis)
        _uiState.update {
            it.copy(
                scanPeriod = it.scanPeriod.copy(
                    allTime = false,
                    useCustomDate = true,
                    customDateMillis = startOfDay
                )
            )
        }
    }

    /**
     * Saves the chosen period where the scan worker reads it, then moves on to the scan.
     * Coming back after a finished scan with a different period re-arms the scan step.
     */
    fun confirmScanPeriod() {
        viewModelScope.launch {
            val state = _uiState.value
            val period = state.scanPeriod
            val periodChanged = state.scannedPeriod != null && state.scannedPeriod != period
            userPreferencesRepository.updateSmsScanAllTime(period.allTime)
            userPreferencesRepository.updateSmsScanUseCustomDate(period.useCustomDate)
            userPreferencesRepository.updateSmsScanMonths(period.months)
            period.customDateMillis?.let { userPreferencesRepository.updateSmsScanCustomDate(it) }
            if (periodChanged) {
                // Zero timestamp = the next scan reads the whole new period.
                userPreferencesRepository.setLastScanTimestamp(0L)
            }
            _uiState.update {
                it.copy(
                    currentStep = OnBoardingStep.SMS_SCAN,
                    scanCompleted = it.scanCompleted && !periodChanged
                )
            }
        }
    }

    fun startSmsScan() {
        smsScanManager.startSmsLoggingScan()
        _uiState.update {
            it.copy(isScanning = true, scanCompleted = false, scannedPeriod = it.scanPeriod)
        }
        observeScanProgress()
    }

    private fun observeScanProgress() {
        val workManager = WorkManager.getInstance(context)
        viewModelScope.launch {
            workManager.getWorkInfosForUniqueWorkLiveData(OptimizedSmsReaderWorker.WORK_NAME)
                .asFlow()
                .collect { workInfos ->
                    val workInfo = workInfos.firstOrNull() ?: return@collect

                    val progress = workInfo.progress
                    val total = progress.getInt(OptimizedSmsReaderWorker.PROGRESS_TOTAL, 0)
                    val processed = progress.getInt(OptimizedSmsReaderWorker.PROGRESS_PROCESSED, 0)
                    val parsed = progress.getInt(OptimizedSmsReaderWorker.PROGRESS_PARSED, 0)
                    val saved = progress.getInt(OptimizedSmsReaderWorker.PROGRESS_SAVED, 0)
                    val elapsed = progress.getLong(OptimizedSmsReaderWorker.PROGRESS_TIME_ELAPSED, 0L)
                    val remaining = progress.getLong(OptimizedSmsReaderWorker.PROGRESS_ESTIMATED_TIME_REMAINING, 0L)

                    when (workInfo.state) {
                        WorkInfo.State.RUNNING -> {
                            _uiState.update {
                                it.copy(
                                    isScanning = true,
                                    scanTotal = total,
                                    scanProcessed = processed,
                                    scanParsed = parsed,
                                    scanSaved = saved,
                                    scanTimeElapsed = elapsed,
                                    scanEstimatedRemaining = remaining
                                )
                            }
                        }
                        WorkInfo.State.SUCCEEDED -> {
                            val outputTotal = workInfo.outputData.getInt(OptimizedSmsReaderWorker.PROGRESS_TOTAL, total)
                            val outputProcessed = workInfo.outputData.getInt(OptimizedSmsReaderWorker.PROGRESS_PROCESSED, processed)
                            val outputParsed = workInfo.outputData.getInt(OptimizedSmsReaderWorker.PROGRESS_PARSED, parsed)
                            val outputSaved = workInfo.outputData.getInt(OptimizedSmsReaderWorker.PROGRESS_SAVED, saved)
                            _uiState.update {
                                it.copy(
                                    isScanning = false,
                                    scanCompleted = true,
                                    scanTotal = outputTotal,
                                    scanProcessed = outputProcessed,
                                    scanParsed = outputParsed,
                                    scanSaved = outputSaved
                                )
                            }
                            loadAccounts()
                        }
                        WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> {
                            _uiState.update {
                                it.copy(isScanning = false, scanCompleted = true)
                            }
                            loadAccounts()
                        }
                        else -> { /* ENQUEUED, BLOCKED */ }
                    }
                }
        }
    }

    fun loadAccounts() {
        viewModelScope.launch {
            val accounts = accountBalanceRepository.getAllLatestBalances().first()
                .filter { !it.isCreditCard && it.balance != BigDecimal.ZERO }
            _uiState.update { state ->
                state.copy(
                    accounts = accounts,
                    selectedAccountKey = if (accounts.size == 1) {
                        "${accounts[0].bankName}_${accounts[0].accountLast4}"
                    } else {
                        state.selectedAccountKey
                    }
                )
            }
        }
    }

    fun selectAccount(accountKey: String) {
        _uiState.update { it.copy(selectedAccountKey = accountKey) }
    }

    fun completeOnboarding(onComplete: () -> Unit) {
        _uiState.update { it.copy(isCompleting = true) }
        viewModelScope.launch {
            val state = _uiState.value
            userPreferencesRepository.updateUserName(state.userName.trim())
            if (state.profileImageUri != null) {
                userPreferencesRepository.updateProfileImageUri(state.profileImageUri.toString())
            } else if (state.selectedAvatarIndex in avatarDrawables.indices) {
                userPreferencesRepository.updateProfileImageUri("avatar://${state.selectedAvatarIndex}")
            }
            if (state.selectedBackgroundColor >= 0 && state.selectedBackgroundColor < backgroundColors.size) {
                userPreferencesRepository.updateProfileBackgroundColor(backgroundColors[state.selectedBackgroundColor])
            }
            if (state.selectedAccountKey != null) {
                userPreferencesRepository.updateMainAccountKey(state.selectedAccountKey)
                // The main account defines the user's default currency. Manual accounts
                // store the currency the user chose; SMS-tracked accounts fall back to the
                // bank parser's currency (their stored value may be the INR default even
                // for a non-INR bank).
                val mainAccount = state.accounts.firstOrNull {
                    "${it.bankName}_${it.accountLast4}" == state.selectedAccountKey
                }
                if (mainAccount != null) {
                    val currency = CurrencyFormatter.resolveAccountCurrency(
                        sourceType = mainAccount.sourceType,
                        storedCurrency = mainAccount.currency,
                        bankName = mainAccount.bankName
                    )
                    // Won't override a currency the user explicitly picked in Settings.
                    userPreferencesRepository.applyMainAccountCurrency(currency)
                }
            }
            userPreferencesRepository.updateHasCompletedOnboarding(true)
            _uiState.update { it.copy(isCompleting = false) }
            onComplete()
        }
    }

}
