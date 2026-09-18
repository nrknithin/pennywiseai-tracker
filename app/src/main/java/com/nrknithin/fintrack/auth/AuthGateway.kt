package com.nrknithin.fintrack.auth

import android.app.Activity

/**
 * What one confirmed Google sign-in yields.
 *
 * [idToken] is used in-process and discarded — never logged, never persisted. It is
 * retained on this type only so that, if Firestore sync is ever added, the seam is
 * `AuthRepository.signIn`'s success branch and nothing else has to move.
 *
 * No account identifier is carried. `GoogleIdTokenCredential.id` is the account's
 * EMAIL ADDRESS, not the OIDC `sub`, and storing it would put the owner's address in
 * DataStore for no current reader (CLAUDE.md #4).
 */
data class GoogleIdentity(
    val displayName: String?,
    val idToken: String
) {
    /**
     * Overridden so the generated one cannot interpolate a live Google ID token. Nothing
     * stringifies this today, but one `Timber.d("$result")` added later would put a real
     * credential into logcat — readable over adb on a debug build by any attached host.
     * PiiLintTest scans source, not runtime output, so it would never catch that.
     */
    override fun toString(): String =
        "GoogleIdentity(displayName=$displayName, idToken=<redacted>)"
}

/** Every way sign-in can end that is not a success. One screen message per value. */
enum class AuthFailure {
    /** No Google account on this device. Credential Manager cannot add one. */
    NO_GOOGLE_ACCOUNT,
    /** No usable connectivity. The FIRST sign-in genuinely needs the network. */
    OFFLINE,
    /** Play services missing, stale, or no credential provider configured. */
    PLAY_SERVICES_UNAVAILABLE,
    /**
     * DEVELOPER_ERROR / error 10: this (package name, signing SHA-1) pair is not
     * registered as an Android OAuth client. The screen prints the package name.
     */
    APP_NOT_REGISTERED,
    /** This build has no OAuth web client ID, or is the fdroid stub. */
    NOT_CONFIGURED,
    UNKNOWN
}

sealed interface AuthResult {
    data class Success(val identity: GoogleIdentity) : AuthResult

    /** The user dismissed the chooser. Not an error; no message is shown. */
    data object Cancelled : AuthResult

    /** @param diagnostic developer-facing only (a package name or an exception type). */
    data class Failure(val reason: AuthFailure, val diagnostic: String? = null) : AuthResult
}

/**
 * The one seam between the gate and Google. `standard` + `personal` bind
 * [com.nrknithin.fintrack.auth.GoogleAuthGateway] from `app/src/cloud/`; `fdroid` binds
 * [com.nrknithin.fintrack.auth.NoAuthGateway] from `app/src/nocloud/`. Nothing here
 * imports Credential Manager or Play services, so `app/src/main` compiles on all three
 * flavors — the same arrangement `billing/EntitlementSource` already uses.
 */
interface AuthGateway {

    /**
     * Constant for the process, cheap, no I/O. `false` makes the whole gate transparent.
     * It is read inside ThemeViewModel's preference `map{}`, which runs on every
     * preference write, so it must never probe PackageManager or the network.
     */
    val isAvailable: Boolean

    /** Requires a real Activity: Credential Manager cannot run on an application context. */
    suspend fun signIn(activity: Activity): AuthResult
}
