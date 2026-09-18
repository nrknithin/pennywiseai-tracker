package com.nrknithin.fintrack.auth

import android.app.Activity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * fdroid carries no Google Play libraries and must keep none, so there is nothing to sign
 * in with. isAvailable = false makes ThemeViewModel report the gate as already satisfied,
 * so fdroid's start destination is byte-for-byte what it is today rather than a screen
 * whose only button cannot work.
 */
@Singleton
class NoAuthGateway @Inject constructor() : AuthGateway {

    override val isAvailable: Boolean = false

    override suspend fun signIn(activity: Activity): AuthResult =
        AuthResult.Failure(AuthFailure.NOT_CONFIGURED)
}
