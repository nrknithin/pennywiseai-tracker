package com.nrknithin.fintrack.data.email

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads and writes [EmailAccountConfig].
 *
 * An interface, not a class, so the sync worker's tests can run against an in-memory double
 * with no DataStore, no `Context` and no Robolectric — the worker's windowing, batching and
 * resume behaviour is the thing under test there, not preference storage.
 */
interface EmailAccountConfigStore {

    /**
     * Emits on every change. Already [EmailAccountConfig.coerced].
     *
     * Never fails: storage that cannot be read emits the defaults instead of throwing, so a
     * collector — the worker, the Home banner, screen 47 — has one less error state to hold.
     */
    val config: Flow<EmailAccountConfig>

    suspend fun current(): EmailAccountConfig

    /**
     * Read-modify-write. [transform] receives the coerced current value and its result is
     * coerced again before it is stored, so a caller cannot persist an out-of-range setting
     * even by accident.
     */
    suspend fun update(transform: (EmailAccountConfig) -> EmailAccountConfig): EmailAccountConfig

    /**
     * Advanced once per committed batch (spec 11 R-11-07).
     *
     * A separate method rather than `update { it.copy(...) }` on purpose: a batch commit
     * happens up to a hundred times in one run and must never rewrite the user's settings
     * with a snapshot the worker read minutes earlier — a user who changes the sync interval
     * mid-run would otherwise see it silently reverted.
     */
    suspend fun setWatermark(uidValidity: Long, lastSyncedUid: Long)

    suspend fun setState(state: EmailAccountState)

    suspend fun markSynced(at: Instant)

    /**
     * Folder or address changed → re-scan the lookback window (screen 47 E30/E43 warn first).
     *
     * Clears the folder identity as well as the UID: UIDs are only meaningful within one
     * UIDVALIDITY, so keeping the old validity while zeroing the UID would claim knowledge
     * of a folder we are no longer looking at.
     */
    suspend fun resetWatermark()
}

/**
 * Fin's own preferences file, separate from upstream's `user_preferences`.
 *
 * Spec 05 §5.6 asks the fork to add files rather than edit upstream ones; a separate
 * DataStore also means a rebase that touches `UserPreferencesRepository` cannot touch email
 * settings, and that "Forget all email data" can drop one file.
 */
private val Context.finEmailSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "fin_email_settings",
    // A truncated or garbled preferences file — a kill during a write, a restore of half a
    // file, a full disk — otherwise throws `CorruptionException` (an `IOException`) out of
    // *every* read and *every* write, for good. Reading past it is not enough on its own:
    // `edit()` reads before it writes, so without this handler the settings could never be
    // repaired from screen 47 either. Replacing the file loses the address, the folder and
    // the watermark, all of which the user can re-enter and the next sync rebuilds — and
    // R-11-08 recognises the already-logged messages a reset watermark re-fetches.
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

/**
 * The preference keys and the mapping to and from [EmailAccountConfig].
 *
 * Split out of the store so that the part with no Android in it — which is all of the part
 * that can be wrong — is unit-testable against an in-memory [MutablePreferences].
 */
internal object EmailAccountConfigCodec {

    /**
     * Lists are stored as one string joined by this separator rather than as a
     * `stringSetPreferencesKey`: a set loses the order screen 47 E61 displays chips in, and
     * neither a domain nor an email address can contain a semicolon. It is also the exact
     * shape spec 28 §28.3 writes the default in (`godaddy.com;pgsharp.com`).
     */
    const val SENDER_SEPARATOR = ";"

    private object Keys {
        val ADDRESS = stringPreferencesKey("email_address")
        val HOST = stringPreferencesKey("email_host")
        val PORT = intPreferencesKey("email_port")
        val FOLDER = stringPreferencesKey("email_folder")
        val INITIAL_LOOKBACK_DAYS = intPreferencesKey("email_initial_lookback_days")
        val SYNC_INTERVAL_MINUTES = intPreferencesKey("email_sync_interval_minutes")
        val SYNC_ONLY_ON_WIFI = booleanPreferencesKey("email_sync_only_on_wifi")
        val MAX_MESSAGES_PER_SYNC = intPreferencesKey("email_max_messages_per_sync")
        val BATCH_SIZE = intPreferencesKey("email_batch_size")
        val ALERT_WINDOW_MINUTES = intPreferencesKey("email_alert_window_minutes")
        val SAME_DAY_FALLBACK = booleanPreferencesKey("email_same_day_fallback")
        val EXTRA_SENDERS = stringPreferencesKey("email_extra_senders")
        val IGNORED_SENDERS = stringPreferencesKey("email_ignored_senders")
        val MAX_BODY_KB = intPreferencesKey("email_max_body_kb")
        val RETAIN_BODIES_DAYS = intPreferencesKey("email_retain_bodies_days")
        val LAST_SYNCED_UID = longPreferencesKey("email_last_synced_uid")
        val UID_VALIDITY = longPreferencesKey("email_uid_validity")
        val LAST_SYNC_AT = longPreferencesKey("email_last_sync_at")
        val STATE = stringPreferencesKey("email_state")

        // Sender discovery. Same `email_` prefix as everything above, so "Forget all email
        // data" drops one file and takes these with it.
        val DISCOVERY_ENABLED = booleanPreferencesKey("email_discovery_enabled")
        val DISCOVERY_QUERY = stringPreferencesKey("email_discovery_query")
        val DISCOVERY_LOOKBACK_DAYS = intPreferencesKey("email_discovery_lookback_days")
        val DISCOVERY_MAX_HEADERS = intPreferencesKey("email_discovery_max_headers")
        val LAST_DISCOVERY_AT = longPreferencesKey("email_last_discovery_at")
    }

    /** Missing keys fall back to the documented default, never to zero. */
    fun read(prefs: Preferences): EmailAccountConfig {
        val defaults = EmailAccountConfig()
        return EmailAccountConfig(
            address = prefs[Keys.ADDRESS] ?: defaults.address,
            host = prefs[Keys.HOST] ?: defaults.host,
            port = prefs[Keys.PORT] ?: defaults.port,
            folder = prefs[Keys.FOLDER] ?: defaults.folder,
            initialLookbackDays = prefs[Keys.INITIAL_LOOKBACK_DAYS] ?: defaults.initialLookbackDays,
            syncIntervalMinutes = prefs[Keys.SYNC_INTERVAL_MINUTES] ?: defaults.syncIntervalMinutes,
            syncOnlyOnWifi = prefs[Keys.SYNC_ONLY_ON_WIFI] ?: defaults.syncOnlyOnWifi,
            maxMessagesPerSync = prefs[Keys.MAX_MESSAGES_PER_SYNC] ?: defaults.maxMessagesPerSync,
            batchSize = prefs[Keys.BATCH_SIZE] ?: defaults.batchSize,
            alertWindowMinutes = prefs[Keys.ALERT_WINDOW_MINUTES] ?: defaults.alertWindowMinutes,
            sameDayFallback = prefs[Keys.SAME_DAY_FALLBACK] ?: defaults.sameDayFallback,
            extraSenders = decodeSenders(prefs[Keys.EXTRA_SENDERS], defaults.extraSenders),
            ignoredSenders = decodeSenders(prefs[Keys.IGNORED_SENDERS], defaults.ignoredSenders),
            maxBodyKb = prefs[Keys.MAX_BODY_KB] ?: defaults.maxBodyKb,
            retainBodiesDays = prefs[Keys.RETAIN_BODIES_DAYS] ?: defaults.retainBodiesDays,
            lastSyncedUid = prefs[Keys.LAST_SYNCED_UID] ?: defaults.lastSyncedUid,
            uidValidity = prefs[Keys.UID_VALIDITY] ?: defaults.uidValidity,
            lastSyncAt = prefs[Keys.LAST_SYNC_AT]?.let(Instant::ofEpochMilli),
            state = EmailAccountState.fromStored(prefs[Keys.STATE]),
            discoveryEnabled = prefs[Keys.DISCOVERY_ENABLED] ?: defaults.discoveryEnabled,
            discoveryQuery = prefs[Keys.DISCOVERY_QUERY] ?: defaults.discoveryQuery,
            discoveryLookbackDays =
                prefs[Keys.DISCOVERY_LOOKBACK_DAYS] ?: defaults.discoveryLookbackDays,
            discoveryMaxHeaders = prefs[Keys.DISCOVERY_MAX_HEADERS] ?: defaults.discoveryMaxHeaders,
            lastDiscoveryAt = prefs[Keys.LAST_DISCOVERY_AT]?.let(Instant::ofEpochMilli)
        )
    }

    fun write(prefs: MutablePreferences, config: EmailAccountConfig) {
        prefs[Keys.ADDRESS] = config.address
        prefs[Keys.HOST] = config.host
        prefs[Keys.PORT] = config.port
        prefs[Keys.FOLDER] = config.folder
        prefs[Keys.INITIAL_LOOKBACK_DAYS] = config.initialLookbackDays
        prefs[Keys.SYNC_INTERVAL_MINUTES] = config.syncIntervalMinutes
        prefs[Keys.SYNC_ONLY_ON_WIFI] = config.syncOnlyOnWifi
        prefs[Keys.MAX_MESSAGES_PER_SYNC] = config.maxMessagesPerSync
        prefs[Keys.BATCH_SIZE] = config.batchSize
        prefs[Keys.ALERT_WINDOW_MINUTES] = config.alertWindowMinutes
        prefs[Keys.SAME_DAY_FALLBACK] = config.sameDayFallback
        prefs[Keys.EXTRA_SENDERS] = encodeSenders(config.extraSenders)
        prefs[Keys.IGNORED_SENDERS] = encodeSenders(config.ignoredSenders)
        prefs[Keys.MAX_BODY_KB] = config.maxBodyKb
        prefs[Keys.RETAIN_BODIES_DAYS] = config.retainBodiesDays
        prefs[Keys.DISCOVERY_ENABLED] = config.discoveryEnabled
        prefs[Keys.DISCOVERY_QUERY] = config.discoveryQuery
        prefs[Keys.DISCOVERY_LOOKBACK_DAYS] = config.discoveryLookbackDays
        prefs[Keys.DISCOVERY_MAX_HEADERS] = config.discoveryMaxHeaders
        writeWatermark(prefs, config.uidValidity, config.lastSyncedUid)
        writeLastSyncAt(prefs, config.lastSyncAt)
        writeLastDiscoveryAt(prefs, config.lastDiscoveryAt)
        writeState(prefs, config.state)
    }

    fun writeWatermark(prefs: MutablePreferences, uidValidity: Long, lastSyncedUid: Long) {
        prefs[Keys.UID_VALIDITY] = uidValidity.coerceAtLeast(0L)
        prefs[Keys.LAST_SYNCED_UID] = lastSyncedUid.coerceAtLeast(0L)
    }

    fun writeState(prefs: MutablePreferences, state: EmailAccountState) {
        prefs[Keys.STATE] = state.name
    }

    /** Null removes the key, so "never synced" stays distinguishable from "synced at epoch 0". */
    fun writeLastSyncAt(prefs: MutablePreferences, at: Instant?) {
        if (at == null) {
            prefs.remove(Keys.LAST_SYNC_AT)
        } else {
            prefs[Keys.LAST_SYNC_AT] = at.toEpochMilli()
        }
    }

    /** Null removes the key, so "never scanned" stays distinguishable from "scanned at epoch 0". */
    fun writeLastDiscoveryAt(prefs: MutablePreferences, at: Instant?) {
        if (at == null) {
            prefs.remove(Keys.LAST_DISCOVERY_AT)
        } else {
            prefs[Keys.LAST_DISCOVERY_AT] = at.toEpochMilli()
        }
    }

    fun encodeSenders(senders: List<String>): String = senders.joinToString(SENDER_SEPARATOR)

    /**
     * An absent key means "never set" and yields [fallback]; an empty string means the user
     * deliberately removed every entry and yields an empty list. Collapsing the two would
     * make the seeded `godaddy.com` / `pgsharp.com` chips reappear every time the user
     * deleted them.
     */
    fun decodeSenders(raw: String?, fallback: List<String>): List<String> {
        if (raw == null) return fallback
        if (raw.isEmpty()) return emptyList()
        return raw.split(SENDER_SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }
    }
}

/**
 * The read side of the store as a pure flow transform: recover, then decode.
 *
 * Recovery first. DataStore's documented contract is that `data` throws [IOException] into
 * the collector when the file cannot be read, and its documented answer is to catch that in
 * the flow and emit [emptyPreferences] — which [EmailAccountConfigCodec.read] turns into the
 * documented defaults rather than into zeros. Without it, one unreadable file takes down the
 * sync worker, the Home banner and screen 47 together, none of which can do anything useful
 * with a disk error.
 *
 * Only [IOException] is swallowed; anything else is rethrown untouched, because a cancelled
 * read is not a corrupt file.
 *
 * It is an extension on `Flow<Preferences>` rather than a method so that it can be tested
 * against a hand-built flow, with no `Context` and no DataStore file: the behaviour worth
 * pinning down is what reaches the collector when the source throws.
 */
internal fun Flow<Preferences>.asEmailAccountConfig(): Flow<EmailAccountConfig> =
    catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }.map { EmailAccountConfigCodec.read(it).coerced() }

/**
 * DataStore-backed implementation, in its own `fin_email_settings` store.
 *
 * Every read goes through [EmailAccountConfig.coerced] so that a value written by an older
 * build, a restored backup or a hand edit is bounded before the worker ever sees it, and
 * through [asEmailAccountConfig] so that a file which cannot be read at all reads as the
 * defaults instead of throwing at the collector.
 *
 * Writes still throw. That asymmetry is deliberate: a reader with no settings has something
 * sensible to show (the "not configured" state), while a writer that silently lost the
 * user's new app password or the batch watermark would be lying to it. An [IOException] out
 * of [update] or [setWatermark] is the same signal as any other IO failure in a run — spec
 * 11 §11.3's last line, `Result.retry()`.
 */
@Singleton
class DataStoreEmailAccountConfigStore @Inject constructor(
    @ApplicationContext private val context: Context
) : EmailAccountConfigStore {

    private val dataStore: DataStore<Preferences>
        get() = context.finEmailSettingsDataStore

    override val config: Flow<EmailAccountConfig> =
        context.finEmailSettingsDataStore.data.asEmailAccountConfig()

    override suspend fun current(): EmailAccountConfig = config.first()

    override suspend fun update(
        transform: (EmailAccountConfig) -> EmailAccountConfig
    ): EmailAccountConfig {
        // edit() returns the committed snapshot, so the caller is handed exactly what was
        // stored rather than what it hoped would be stored.
        val committed = dataStore.edit { prefs ->
            val next = transform(EmailAccountConfigCodec.read(prefs).coerced()).coerced()
            EmailAccountConfigCodec.write(prefs, next)
        }
        return EmailAccountConfigCodec.read(committed).coerced()
    }

    override suspend fun setWatermark(uidValidity: Long, lastSyncedUid: Long) {
        dataStore.edit { EmailAccountConfigCodec.writeWatermark(it, uidValidity, lastSyncedUid) }
    }

    override suspend fun setState(state: EmailAccountState) {
        dataStore.edit { EmailAccountConfigCodec.writeState(it, state) }
    }

    override suspend fun markSynced(at: Instant) {
        dataStore.edit { EmailAccountConfigCodec.writeLastSyncAt(it, at) }
    }

    override suspend fun resetWatermark() {
        dataStore.edit { EmailAccountConfigCodec.writeWatermark(it, 0L, 0L) }
    }
}
