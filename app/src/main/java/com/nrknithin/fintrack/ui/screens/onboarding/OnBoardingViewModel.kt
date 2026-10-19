package com.nrknithin.fintrack.ui.screens.onboarding

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asFlow
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.nrknithin.fintrack.data.manager.SmsScanManager
import com.nrknithin.fintrack.data.preferences.UserPreferencesRepository
import com.nrknithin.fintrack.ui.components.AvatarHelper
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
import javax.inject.Inject

/**
 * ACCOUNT_SETUP (pick a primary account) was removed: the owner uses different accounts
 * for different purposes, so there is no primary one to pick, and the step also could not
 * work once the scan stopped blocking - accounts are DISCOVERED by that scan. Settings
 * still offers the same choice, with the same currency side effect
 * (SettingsViewModel.setMainAccount), for anyone who does want one.
 */
enum class OnBoardingStep {
    WELCOME,
    PROFILE,
    PERMISSIONS,
    SMS_SCAN
}

data class OnBoardingUiState(
    val currentStep: OnBoardingStep = OnBoardingStep.WELCOME,
    val userName: String = "",
    /** The name came from Google and the PROFILE field is not editable. */
    val isNameLocked: Boolean = false,
    val selectedAvatarIndex: Int = 0,
    val profileImageUri: Uri? = null,
    val selectedBackgroundColor: Int = 0,
    val smsPermissionGranted: Boolean = false,
    val smsPermissionSkipped: Boolean = false,
    val isScanning: Boolean = false,
    val scanTotal: Int = 0,
    val scanProcessed: Int = 0,
    val scanParsed: Int = 0,
    val scanSaved: Int = 0,
    val scanTimeElapsed: Long = 0L,
    val scanEstimatedRemaining: Long = 0L,
    val scanCompleted: Boolean = false,
    val isCompleting: Boolean = false
)

@HiltViewModel
class OnBoardingViewModel @Inject constructor(
    private val userPreferencesRepository: UserPreferencesRepository,
    private val smsScanManager: SmsScanManager,
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
        // Seed the profile name BEFORE the PROFILE step can render. Without this,
        // uiState.userName stays "" and OnBoardingBottomBar's PROFILE branch
        // (enabled = uiState.userName.isNotBlank()) is permanently disabled — onboarding
        // would dead-end on a field the user cannot type into.
        viewModelScope.launch {
            val googleName = userPreferencesRepository.userPreferences.first()
                .authDisplayName?.trim().orEmpty()
            if (googleName.isNotBlank()) {
                _uiState.update { it.copy(userName = googleName, isNameLocked = true) }
            }
            // Google returned no usable name -> the field stays editable and unlocked.
            // That is the only reason Continue remains reachable in that case.
        }
    }

    /**
     * Ignored while the name came from Google. Guarded here, not only in the composable,
     * so the rule holds for every caller.
     */
    fun updateUserName(name: String) {
        if (_uiState.value.isNameLocked) return
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
                // SMS_SCAN is the last step now, so with no permission there is nothing
                // left to show: PERMISSIONS becomes the end of the flow and its own
                // button completes onboarding.
                OnBoardingStep.SMS_SCAN
            }
            OnBoardingStep.SMS_SCAN -> OnBoardingStep.SMS_SCAN // Already last
        }
        _uiState.update { it.copy(currentStep = nextStep) }
    }

    fun goToPreviousStep() {
        val currentStep = _uiState.value.currentStep
        val previousStep = when (currentStep) {
            OnBoardingStep.WELCOME -> OnBoardingStep.WELCOME
            OnBoardingStep.PROFILE -> OnBoardingStep.WELCOME
            OnBoardingStep.PERMISSIONS -> OnBoardingStep.PROFILE
            OnBoardingStep.SMS_SCAN -> OnBoardingStep.PERMISSIONS
        }
        _uiState.update { it.copy(currentStep = previousStep) }
    }

    fun startSmsScan() {
        smsScanManager.startSmsLoggingScan()
        _uiState.update { it.copy(isScanning = true, scanCompleted = false) }
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
                        }
                        WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> {
                            _uiState.update {
                                it.copy(isScanning = false, scanCompleted = true)
                            }
                        }
                        else -> { /* ENQUEUED, BLOCKED */ }
                    }
                }
        }
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
            userPreferencesRepository.updateHasCompletedOnboarding(true)
            _uiState.update { it.copy(isCompleting = false) }
            onComplete()
        }
    }

}
