package com.nrknithin.fintrack.ui.screens.auth

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nrknithin.fintrack.auth.AuthFailure
import com.nrknithin.fintrack.auth.AuthRepository
import com.nrknithin.fintrack.auth.AuthResult
import com.nrknithin.fintrack.data.preferences.UserPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class SignInPhase { Idle, InProgress, Failed, Completed }

data class SignInUiState(
    val phase: SignInPhase = SignInPhase.Idle,
    val failure: AuthFailure? = null,
    /** Package name for APP_NOT_REGISTERED. Never anything user-identifying. */
    val diagnostic: String? = null,
    val hasCompletedOnboarding: Boolean = false,
    /**
     * Completed sign-in attempts that did not succeed — failures AND cancellations.
     * Cancellations count because DEVELOPER_ERROR can arrive as one (see
     * GoogleAuthGateway.classify); gating the escape on [SignInPhase.Failed] alone would
     * make it unreachable in exactly the case it exists for.
     */
    val attempts: Int = 0
) {
    /**
     * The anti-lockout escape, and the only softening of the "gate is not skippable" rule
     * anywhere in this design.
     *
     * Offered ONLY to an install that already has data behind the gate, and ONLY after a
     * real attempt has failed. It persists nothing, so the gate is shown again on the next
     * cold start — a Google-console misconfiguration costs one extra tap per launch
     * instead of the database. A fresh install has nothing behind the gate and never
     * sees it.
     */
    val canContinueWithoutSigningIn: Boolean
        get() = hasCompletedOnboarding && attempts > 0 && phase != SignInPhase.InProgress
}

@HiltViewModel
class SignInViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    userPreferencesRepository: UserPreferencesRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SignInUiState())
    val uiState: StateFlow<SignInUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            userPreferencesRepository.userPreferences.collect { prefs ->
                _uiState.update { it.copy(hasCompletedOnboarding = prefs.hasCompletedOnboarding) }
            }
        }
    }

    fun signIn(activity: Activity) {
        if (_uiState.value.phase == SignInPhase.InProgress) return // no second chooser
        _uiState.update { it.copy(phase = SignInPhase.InProgress, failure = null, diagnostic = null) }
        viewModelScope.launch {
            when (val result = authRepository.signIn(activity)) {
                // The DataStore write has already returned by the time Success arrives.
                is AuthResult.Success ->
                    _uiState.update { it.copy(phase = SignInPhase.Completed) }
                AuthResult.Cancelled ->
                    // A dismissed chooser is not an error and shows no banner — but it
                    // still counts as an attempt, so a picker that closes itself every
                    // time cannot strand a user who already has data behind the gate.
                    _uiState.update { it.copy(phase = SignInPhase.Idle, attempts = it.attempts + 1) }
                is AuthResult.Failure -> _uiState.update {
                    it.copy(
                        phase = SignInPhase.Failed,
                        failure = result.reason,
                        diagnostic = result.diagnostic,
                        attempts = it.attempts + 1
                    )
                }
            }
        }
    }

    fun retry(activity: Activity) = signIn(activity)

    /** No-op unless [SignInUiState.canContinueWithoutSigningIn]. Writes nothing. */
    fun continueWithoutSigningIn() {
        if (!_uiState.value.canContinueWithoutSigningIn) return
        _uiState.update { it.copy(phase = SignInPhase.Completed) }
    }

    companion object {
        /** User-facing copy. Literal strings, per the repo's Compose idiom. */
        fun messageFor(failure: AuthFailure, diagnostic: String?): String = when (failure) {
            AuthFailure.NO_GOOGLE_ACCOUNT ->
                "No Google account on this device. Add one in Android Settings, then try again."
            AuthFailure.OFFLINE ->
                "You're offline. FinTrack needs one connection to sign you in — after that it works offline."
            AuthFailure.PLAY_SERVICES_UNAVAILABLE ->
                "Google Play services is unavailable or out of date on this device."
            AuthFailure.APP_NOT_REGISTERED ->
                "This build isn't registered for Google sign-in. Add an Android OAuth client for " +
                    "package ${diagnostic ?: "this app"} with this build's signing certificate."
            AuthFailure.NOT_CONFIGURED ->
                "Google sign-in isn't configured in this build."
            AuthFailure.UNKNOWN ->
                "Sign-in didn't complete. Please try again."
        }
    }
}
