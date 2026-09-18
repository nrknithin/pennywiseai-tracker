package com.nrknithin.fintrack.data.email

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The 16-character Gmail app password, as a type that is awkward to leak.
 *
 * [toString] is overridden to dots, so an instance is safe inside a log line, a crash
 * message, an exception, or a `data class` that happens to contain one — the accident this
 * type exists to prevent is not a malicious caller but a `Log.d("cfg=$config")` added during
 * a debugging session.
 *
 * There is no `getValue()`. The only ways out are two `internal` functions named so that
 * every use of them is obvious in review: [revealForImapLogin] and [revealForStorage].
 */
@JvmInline
value class AppPassword private constructor(private val value: String) {

    override fun toString(): String = MASKED

    /**
     * ONLY `JavaMailImapClient` may call this — it is the single place the credential is
     * handed to the network layer (spec 04 §4.1). `PiiLintTest` guards the call sites.
     */
    internal fun revealForImapLogin(): String = value

    /**
     * ONLY [EncryptedEmailCredentialStore] may call this.
     *
     * Deviation, recorded in `docs/FORK-CHANGES.md`: the design says the sole exit is
     * [revealForImapLogin]. But the store is handed an [AppPassword] and has to write its
     * characters into `EncryptedSharedPreferences`, so *some* exit for persistence has to
     * exist. Giving it its own name keeps the IMAP rule ("this identifier appears in exactly
     * one call site") checkable, which a shared accessor would destroy.
     */
    internal fun revealForStorage(): String = value

    companion object {
        /** Google issues app passwords as four groups of four characters. */
        const val LENGTH = 16

        // Escaped rather than written literally so the mask survives any tool that
        // re-encodes this file. U+2022 BULLET, sixteen of them.
        private val MASKED: String = "AppPassword(" + "\u2022".repeat(LENGTH) + ")"

        /**
         * Strips whitespace — Google prints the code in four groups and every user pastes
         * the spaces along with it — and rejects anything that is not [LENGTH] characters or
         * that contains a control character.
         *
         * Null means "not a valid app password": the caller shows the field error (screen 47
         * E22) and stores nothing. It deliberately does not explain *why* it is invalid,
         * because any such message would be built from the credential.
         */
        fun parse(raw: String): AppPassword? {
            val stripped = buildString(raw.length) {
                for (ch in raw) if (!ch.isWhitespace()) append(ch)
            }
            if (stripped.length != LENGTH) return null
            if (stripped.any { it.isISOControl() }) return null
            return AppPassword(stripped)
        }
    }
}

/**
 * Spec 04 §4.1 / constraint C1: the password is stored in `EncryptedSharedPreferences` only —
 * never DataStore, never Room, never a backup, never a log, never shown after entry.
 *
 * The API has no getter on purpose. A caller borrows the password for the length of a block
 * and never holds a reference to it; "is one stored?" is a separate question with a Boolean
 * answer, which is all screen 47 needs in order to render sixteen dots.
 */
interface EmailCredentialStore {

    /** True when a syntactically valid password is stored. Never reveals anything about it. */
    suspend fun hasPassword(): Boolean

    /**
     * Overwrites any existing password. Returns after the write is durable, so a
     * `testConnection` that follows immediately reads the new value rather than the old one.
     */
    suspend fun store(password: AppPassword)

    /**
     * Runs [block] with the stored password, or returns null when none is stored — which the
     * worker reports as "Email not configured" and state
     * [EmailAccountState.NOT_CONFIGURED].
     *
     * The password must not escape [block]; the type makes that close to useless anyway,
     * since it cannot be printed and its two accessors are `internal`.
     */
    suspend fun <T> withPassword(block: suspend (AppPassword) -> T): T?

    /**
     * "Disconnect" (screen 47 E13). Clears the credential only — messages, transactions and
     * dues stay, because a setting change never deletes data (spec 11 AC-11-05).
     */
    suspend fun clear()
}

/**
 * `MasterKey` AES256-GCM, hardware-backed where available, in a prefs file named
 * `fin_email_credentials`, excluded from Android auto-backup and device transfer by
 * `data_extraction_rules.xml` / `backup_rules.xml`.
 *
 * All file access is on [Dispatchers.IO]; the first access builds the keystore-backed
 * `SharedPreferences` under a [Mutex], because `EncryptedSharedPreferences.create` is
 * expensive and is not safe to race.
 */
@Singleton
class EncryptedEmailCredentialStore @Inject constructor(
    @ApplicationContext private val context: Context
) : EmailCredentialStore {

    private val openLock = Mutex()

    @Volatile
    private var cached: SharedPreferences? = null

    override suspend fun hasPassword(): Boolean = read() != null

    override suspend fun store(password: AppPassword) {
        val raw = password.revealForStorage()
        withContext(Dispatchers.IO) {
            // commit(), not apply(): screen 47 runs "Test connection" the moment this
            // returns, and the worker may start on another thread. apply() only promises
            // the write eventually.
            prefs().edit().putString(KEY_PASSWORD, raw).commit()
        }
    }

    override suspend fun <T> withPassword(block: suspend (AppPassword) -> T): T? {
        val password = read() ?: return null
        return block(password)
    }

    override suspend fun clear() {
        withContext(Dispatchers.IO) {
            prefs().edit().remove(KEY_PASSWORD).commit()
        }
    }

    /**
     * A stored value that no longer parses (truncated file, a build that stored something
     * else) is treated as absent rather than passed to the server, where it would produce an
     * AUTH_FAILED the user cannot explain.
     */
    private suspend fun read(): AppPassword? {
        val stored = withContext(Dispatchers.IO) { prefs().getString(KEY_PASSWORD, null) }
        return stored?.let { AppPassword.parse(it) }
    }

    private suspend fun prefs(): SharedPreferences {
        cached?.let { return it }
        return openLock.withLock {
            cached ?: withContext(Dispatchers.IO) { openOrRecreate() }.also { cached = it }
        }
    }

    private fun openOrRecreate(): SharedPreferences = try {
        open()
    } catch (_: Exception) {
        // The master key is gone or no longer matches the file: a device transfer, a
        // keystore reset, or a restore of somebody else's file. The bytes are permanently
        // undecryptable, so the only honest recovery is to drop them and let screen 47 ask
        // for the password again. Silently retrying forever would strand the user in an
        // error they cannot clear from the UI.
        context.deleteSharedPreferences(PREFS_FILE_NAME)
        open()
    }

    private fun open(): SharedPreferences {
        val masterKey = MasterKey.Builder(context, MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            PREFS_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private companion object {
        /**
         * Its own file, not the app's default prefs: the backup and device-transfer rules
         * exclude it by name, and a file that holds exactly one secret is easy to audit.
         */
        const val PREFS_FILE_NAME = "fin_email_credentials"

        const val MASTER_KEY_ALIAS = "fin_email_master_key"

        /** The only entry in the file. */
        const val KEY_PASSWORD = "app_password"
    }
}
