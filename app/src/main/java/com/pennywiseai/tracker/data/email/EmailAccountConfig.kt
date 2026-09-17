package com.pennywiseai.tracker.data.email

import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Connection health of the one configured mailbox.
 *
 * Drives the banners of spec 11 §11.7 and the status card of screen 47 (S47.E10). Persisted
 * as a name rather than an ordinal so that inserting a value later cannot silently re-label
 * every stored state; an unknown value reads back as [NOT_CONFIGURED], which is the only
 * state that is safe to guess wrong (it asks the user to connect rather than claiming a
 * connection that may not exist).
 */
enum class EmailAccountState {
    /** No address, or no stored app password. Home shows "Connect Gmail to see bills and dues". */
    NOT_CONFIGURED,

    /** Last run finished cleanly. */
    OK,

    /**
     * The server rejected the app password (revoked, or replaced in the Google account).
     * Spec 11 AC-11-03: the worker must not retry in this state — retrying a revoked
     * credential only hardens the lockout.
     */
    AUTH_FAILED,

    /** Transient: no network, TLS failure, timeout. The worker retries with backoff. */
    NETWORK_ERROR,

    /** The configured folder is gone (renamed, or a non-Gmail provider). Screen 47 offers a picker. */
    FOLDER_MISSING;

    companion object {
        val FALLBACK = EmailAccountState.NOT_CONFIGURED

        /**
         * Reads a persisted name. Never throws: a hand-edited DataStore file, a restored
         * backup from a newer build, or a renamed constant must not crash the worker on the
         * very first line of a run.
         */
        fun fromStored(value: String?): EmailAccountState =
            EmailAccountState.entries.firstOrNull { it.name == value } ?: FALLBACK
    }
}

/**
 * Everything spec 11 §11.2 lets the user set, plus the two internal watermarks.
 *
 * One immutable record read by the sync worker, the ingestor and screen 47 alike, so no
 * component gets to invent its own default. Every field carries a Kotlin default for the
 * same reason upstream `CLAUDE.md` constraint 3 demands it of serialised entities: a value
 * that goes missing must degrade to the documented default, not to zero.
 *
 * **Carries no password.** The app password lives only in [EmailCredentialStore] (spec 04
 * §4.1, constraint C1); putting it here would put it in DataStore, in a log line from any
 * `toString()`, and one day in a backup.
 */
data class EmailAccountConfig(
    /** Login user. Empty until the user connects. */
    val address: String = "",
    val host: String = DEFAULT_HOST,
    val port: Int = DEFAULT_PORT,
    /** The folder scanned READ_ONLY. Gmail's All Mail sees Promotions and Updates too (R-11-02). */
    val folder: String = DEFAULT_FOLDER,
    /** 30 / 90 / 180 / 365, or 0 meaning "all mail". Bounds the FIRST sync only. */
    val initialLookbackDays: Int = DEFAULT_LOOKBACK_DAYS,
    val syncIntervalMinutes: Int = DEFAULT_SYNC_INTERVAL_MINUTES,
    val syncOnlyOnWifi: Boolean = false,
    val maxMessagesPerSync: Int = DEFAULT_MAX_MESSAGES_PER_SYNC,
    /** Commit unit of spec 11 R-11-07. Fixed at 50; a field so tests can shrink it. */
    val batchSize: Int = DEFAULT_BATCH_SIZE,
    /** SMS ⇄ email twin window, spec 14 §14.3 L2/L3. */
    val alertWindowMinutes: Int = DEFAULT_ALERT_WINDOW_MINUTES,
    val sameDayFallback: Boolean = true,
    /** Domains or addresses added to the server-side `OR(FROM …)` search (R-11-03). */
    val extraSenders: List<String> = DEFAULT_EXTRA_SENDERS,
    /** Senders the user never wants parsed; passed to `IgnoreRules.evaluate` as extras. */
    val ignoredSenders: List<String> = emptyList(),
    val maxBodyKb: Int = DEFAULT_MAX_BODY_KB,
    val retainBodiesDays: Int = DEFAULT_RETAIN_BODIES_DAYS,
    /** Internal watermark: highest UID whose batch has committed (R-11-07). */
    val lastSyncedUid: Long = 0,
    /** Internal: folder identity. A change means Gmail rebuilt the folder (R-11-08). */
    val uidValidity: Long = 0,
    val lastSyncAt: Instant? = null,
    val state: EmailAccountState = EmailAccountState.NOT_CONFIGURED
) {

    /**
     * Address present and plausibly shaped. The second half of "is this account usable?" —
     * whether a password is stored — is answered by [EmailCredentialStore.hasPassword],
     * because this record deliberately cannot hold the credential.
     */
    val hasAddress: Boolean
        get() = isPlausibleAddress(address)

    /**
     * Oldest message the FIRST sync should look at, or null when [initialLookbackDays] is 0
     * and the whole folder is in scope.
     *
     * Only the first sync uses this; once [lastSyncedUid] is non-zero the window is the UID
     * range, not a date (R-11-04).
     */
    fun lookbackCutoff(now: Instant): Instant? =
        if (initialLookbackDays <= 0) null
        else now.minus(initialLookbackDays.toLong(), ChronoUnit.DAYS)

    /**
     * Clamps every field to the range in spec 11 §11.2.
     *
     * Called on every read, not just on write, so that a hand-edited preferences file, a
     * restored backup written by a different build, or a future setting whose range narrows
     * can never make the worker misbehave — a `syncIntervalMinutes` of 0 would otherwise ask
     * WorkManager for an impossible schedule, and a `maxBodyKb` of 0 would store empty
     * bodies that hash equal to each other.
     */
    fun coerced(): EmailAccountConfig = copy(
        address = address.trim(),
        host = host.trim().lowercase().ifBlank { DEFAULT_HOST },
        // An out-of-range port is a typo, not a preference: fall back rather than clamp, so
        // a stray 0 does not silently become port 1 and fail with a confusing error.
        port = if (port in PORT_RANGE) port else DEFAULT_PORT,
        folder = folder.trim().ifBlank { DEFAULT_FOLDER },
        initialLookbackDays = coerceLookback(initialLookbackDays),
        syncIntervalMinutes = syncIntervalMinutes.coerceIn(SYNC_INTERVAL_RANGE),
        maxMessagesPerSync = maxMessagesPerSync.coerceIn(MAX_MESSAGES_RANGE),
        batchSize = batchSize.coerceIn(BATCH_SIZE_RANGE),
        alertWindowMinutes = alertWindowMinutes.coerceIn(ALERT_WINDOW_RANGE),
        extraSenders = normaliseSenders(extraSenders),
        ignoredSenders = normaliseSenders(ignoredSenders),
        maxBodyKb = maxBodyKb.coerceIn(MAX_BODY_KB_RANGE),
        // Spec 11 §11.2 gives this as "30–∞": there is a floor (below which the twin hash
        // would be trimmed away before an SMS could ever match it) but no ceiling.
        retainBodiesDays = retainBodiesDays.coerceAtLeast(RETAIN_BODIES_MIN_DAYS),
        lastSyncedUid = lastSyncedUid.coerceAtLeast(0L),
        uidValidity = uidValidity.coerceAtLeast(0L)
    )

    companion object {
        const val DEFAULT_HOST = "imap.gmail.com"
        const val DEFAULT_PORT = 993
        const val DEFAULT_FOLDER = "[Gmail]/All Mail"
        const val DEFAULT_BATCH_SIZE = 50
        const val DEFAULT_LOOKBACK_DAYS = 180
        const val DEFAULT_SYNC_INTERVAL_MINUTES = 30
        const val DEFAULT_MAX_MESSAGES_PER_SYNC = 500
        const val DEFAULT_ALERT_WINDOW_MINUTES = 30
        const val DEFAULT_MAX_BODY_KB = 64
        const val DEFAULT_RETAIN_BODIES_DAYS = 400

        /** Spec 11 §11.2 seeds these two; both are domains the registry does not compile in. */
        val DEFAULT_EXTRA_SENDERS: List<String> = listOf("godaddy.com", "pgsharp.com")

        /** Screen 47 E42. `0` is "all mail" and is therefore the WIDEST choice, not the narrowest. */
        val LOOKBACK_CHOICES: List<Int> = listOf(30, 90, 180, 365, 0)

        /** Screen 47 E40 offers these; any value inside [SYNC_INTERVAL_RANGE] is accepted. */
        val SYNC_INTERVAL_CHOICES: List<Int> = listOf(15, 30, 60, 120, 360, 720)

        val SYNC_INTERVAL_RANGE: IntRange = 15..720
        val MAX_MESSAGES_RANGE: IntRange = 50..5000
        val ALERT_WINDOW_RANGE: IntRange = 5..240
        val MAX_BODY_KB_RANGE: IntRange = 16..512

        /**
         * Not in spec 11 §11.2's table, which fixes the batch at 50. It is a range here only
         * so a test can shrink it to 1 or 2 and still exercise the per-batch commit of
         * R-11-07; the upper bound keeps one Room transaction from growing unbounded.
         */
        val BATCH_SIZE_RANGE: IntRange = 1..500

        /** Spec 11 §11.2 writes the range as "30–∞". */
        const val RETAIN_BODIES_MIN_DAYS = 30

        val PORT_RANGE: IntRange = 1..65535

        /**
         * Cheap shape check for screen 47's address field (S47.E21) and for [hasAddress].
         *
         * Deliberately not a regex: spec 02 §2.1 keeps text-understanding regex in
         * `:parser-core`, and a full RFC 5322 validator would reject addresses that real
         * servers accept. All this needs to decide is "is it worth attempting a login".
         */
        fun isPlausibleAddress(value: String): Boolean {
            val trimmed = value.trim()
            if (trimmed.isEmpty()) return false
            if (trimmed.any { it.isWhitespace() }) return false
            val at = trimmed.indexOf('@')
            // A local part, exactly one '@', and a domain with an interior dot.
            if (at <= 0 || at != trimmed.lastIndexOf('@')) return false
            val domain = trimmed.substring(at + 1)
            val dot = domain.indexOf('.')
            return dot > 0 && dot < domain.length - 1
        }

        /**
         * Snaps a lookback to one of [LOOKBACK_CHOICES].
         *
         * A value between two choices widens to the next one up, and anything past 365 days
         * becomes 0 = all mail: the rule for this fork is that an odd stored value must
         * never cause the first sync to look at LESS mail than the user asked for, because
         * mail outside the window is simply never seen again (the watermark moves past it).
         * A negative value is not a narrower window, it is nonsense, so it falls back to the
         * documented default.
         */
        private fun coerceLookback(days: Int): Int {
            if (days in LOOKBACK_CHOICES) return days
            if (days < 0) return DEFAULT_LOOKBACK_DAYS
            return LOOKBACK_CHOICES.firstOrNull { it > 0 && it >= days } ?: 0
        }

        /**
         * Sender lists are compared and sent to the IMAP server case-insensitively, so they
         * are stored lower-cased and de-duplicated. Order is preserved: screen 47 E61 shows
         * them as chips in the order the user added them.
         */
        private fun normaliseSenders(raw: List<String>): List<String> =
            raw.asSequence()
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
                .distinct()
                .toList()
    }
}
