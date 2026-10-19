package com.nrknithin.fintrack.auth

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.nrknithin.fintrack.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The real gate, compiled into `standard` + `personal` only. `fdroid` compiles
 * [NoAuthGateway] from `app/src/nocloud/` instead and carries no Play libraries at all.
 */
@Singleton
class GoogleAuthGateway @Inject constructor(
    @param:ApplicationContext private val context: Context
) : AuthGateway {

    /** Computed once. Must stay free of PackageManager / network probes — see AuthGateway. */
    override val isAvailable: Boolean = BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank()

    override suspend fun signIn(activity: Activity): AuthResult {
        if (!isAvailable) return AuthResult.Failure(AuthFailure.NOT_CONFIGURED)

        // GetSignInWithGoogleOption, not GetGoogleIdOption: this is an explicit
        // "Continue with Google" button on a first-run screen, so the full account
        // picker is correct. setFilterByAuthorizedAccounts(true) would throw
        // NoCredentialException for a first-time user and read as a bug.
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(
                GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_WEB_CLIENT_ID).build()
            )
            .build()

        val response = try {
            CredentialManager.create(context).getCredential(activity, request)
        } catch (e: GetCredentialException) {
            // ONE catch, and classify() decides. Catching the typed subclasses first is
            // the obvious shape and it is wrong here: Play services reports
            // DEVELOPER_ERROR — an unregistered (package, SHA-1) pair, which is the
            // failure this build hits first — by closing its activity with
            // RESULT_CANCELED, which arrives as GetCredentialCancellationException, or as
            // NoCredentialException carrying "...one tap: 10: Caller not whitelisted...".
            // Either would have been swallowed before classify() ever ran: the picker
            // would flash, nothing would happen, and no message would ever name the
            // package to register.
            return classify(e)
        }

        val credential = response.credential
        if (credential !is CustomCredential ||
            credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            return AuthResult.Failure(AuthFailure.UNKNOWN, "Unexpected credential type")
        }
        return try {
            val token = GoogleIdTokenCredential.createFrom(credential.data)
            AuthResult.Success(
                GoogleIdentity(
                    displayName = token.displayName?.takeIf { it.isNotBlank() }
                        ?: token.givenName?.takeIf { it.isNotBlank() },
                    idToken = token.idToken,
                    accountKey = hashAccountId(token.id)
                )
            )
        } catch (_: GoogleIdTokenParsingException) {
            AuthResult.Failure(AuthFailure.UNKNOWN, "Malformed Google ID token")
        }
    }

    /**
     * Order is deliberate. DEVELOPER_ERROR is the failure this repo is most likely to hit
     * (docs/START-HERE.md:149 — the `.debug` applicationIdSuffix means a second package),
     * and it is reported through the free-text message of a generic exception, so it is
     * matched first. `e.type` and the package name are safe to surface; `e.errorMessage`
     * is NOT surfaced to the user because it can echo an account identifier.
     */
    private fun classify(e: GetCredentialException): AuthResult {
        val message = e.errorMessage?.toString().orEmpty()
        // `(^|\W)10:` and not contains("10:"): unanchored, any message carrying a clock
        // time or an unrelated "error 410:" would send the owner into the Google Cloud
        // console over a transient network fault. A confidently wrong instruction is
        // worse than a generic one.
        val looksUnregistered = message.contains("DEVELOPER_ERROR", ignoreCase = true) ||
            message.contains("not whitelisted", ignoreCase = true) ||
            Regex("""(^|\W)10:""").containsMatchIn(message)
        return when {
            looksUnregistered ->
                AuthResult.Failure(AuthFailure.APP_NOT_REGISTERED, BuildConfig.APPLICATION_ID)
            e is GetCredentialProviderConfigurationException ->
                AuthResult.Failure(AuthFailure.PLAY_SERVICES_UNAVAILABLE)
            !hasNetwork() -> AuthResult.Failure(AuthFailure.OFFLINE)
            // A dismissed picker is not an error, and is checked AFTER the shapes above
            // because DEVELOPER_ERROR masquerades as one.
            e is GetCredentialCancellationException -> AuthResult.Cancelled
            e is NoCredentialException -> AuthResult.Failure(AuthFailure.NO_GOOGLE_ACCOUNT)
            else -> AuthResult.Failure(AuthFailure.UNKNOWN, e.type)
        }
    }

    /**
     * One-way hash of the account id, which is the account's EMAIL ADDRESS. This is the
     * only place that value is touched, and nothing but its digest leaves the function —
     * so no caller, no DataStore entry and no log can carry the address (CLAUDE.md #4).
     * Not salted on purpose: a per-install salt would change the digest on reinstall and
     * make a returning account look like a new one, which is exactly what it must detect.
     */
    private fun hashAccountId(id: String?): String {
        if (id.isNullOrBlank()) return ""
        return MessageDigest.getInstance("SHA-256")
            .digest(id.trim().lowercase().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun hasNetwork(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
