package com.nrknithin.fintrack.auth

import android.app.Activity
import com.nrknithin.fintrack.data.preferences.UserPreferencesRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The only class that knows the gate-opening rules. Everything above it (the ViewModel,
 * the screen) is presentation; everything below it (the gateway) is a Google adapter.
 */
@Singleton
class AuthRepository @Inject constructor(
    private val authGateway: AuthGateway,
    private val userPreferencesRepository: UserPreferencesRepository
) {

    val isAvailable: Boolean get() = authGateway.isAvailable

    /**
     * Runs the chooser. On [AuthResult.Success] the gate is opened and the Google name
     * adopted in ONE atomic DataStore write, BEFORE this function returns — so a caller
     * that sees Success can rely on the write having landed, and a process death mid-flow
     * always leaves the gate shut rather than half-open.
     *
     * A blank/absent Google display name is NOT written. `user_name` keeps its existing
     * value (default "User") and the onboarding field stays editable, which is the only
     * thing that keeps OnBoardingBottomBar's `enabled = uiState.userName.isNotBlank()`
     * from dead-ending once the field is locked.
     */
    suspend fun signIn(activity: Activity): AuthResult {
        val result = authGateway.signIn(activity)
        if (result is AuthResult.Success) {
            userPreferencesRepository.applyGoogleIdentity(
                displayName = result.identity.displayName,
                accountKey = result.identity.accountKey
            )
        }
        return result
    }

    /**
     * Closes the gate and forgets the Google name. `user_name`, the avatar, the background
     * colour and the entire Room database are deliberately left untouched — signing out is
     * not a factory reset.
     */
    suspend fun signOut() {
        userPreferencesRepository.forgetGoogleIdentity()
    }
}
