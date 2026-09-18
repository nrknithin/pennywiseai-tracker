package com.nrknithin.fintrack.domain.usecase

import com.nrknithin.fintrack.data.database.dao.SyncRunDao
import com.nrknithin.fintrack.data.database.entity.SyncChannel
import com.nrknithin.fintrack.data.database.entity.SyncRunEntity
import com.nrknithin.fintrack.data.database.entity.SyncTrigger
import com.nrknithin.fintrack.data.email.EmailAccountConfig
import com.nrknithin.fintrack.data.email.EmailAccountConfigStore
import com.nrknithin.fintrack.data.email.EmailCredentialStore
import com.nrknithin.fintrack.data.email.ImapMailboxClient
import com.nrknithin.fintrack.data.email.MailboxException
import com.nrknithin.fintrack.data.email.MailboxSession
import com.nrknithin.fintrack.data.email.SenderSighting
import com.nrknithin.fintrack.data.repository.SenderOverrideRepository
import com.nrknithin.fintrack.domain.engine.DiscoveryReport
import com.nrknithin.fintrack.domain.engine.SenderDiscovery
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/** How one discovery pass ended. One value per branch, so the UI never has to infer. */
sealed interface DiscoveryOutcome {

    /**
     * It ran. [report] may hold zero discoveries — that is a real answer, and a different one
     * from [NotSupported].
     *
     * @param proposed how many senders were written to `sender_overrides` this pass. Not the
     *   same as `report.discovered.size` minus anything: a sender already proposed last week
     *   is recorded again (its evidence refreshed) and counted here, because "recorded" is
     *   what this number means. Screen 47 counts the PROPOSED rows itself for "new to you".
     */
    data class Completed(val report: DiscoveryReport, val proposed: Int) : DiscoveryOutcome

    /**
     * Not a Gmail server: no `X-GM-EXT-1`, so there is no `X-GM-RAW` to ask with. Screen 47
     * explains it; nothing here is an error and nothing is retried.
     */
    data object NotSupported : DiscoveryOutcome

    /** No address, or no stored app password. Nothing was contacted. */
    data object NotConfigured : DiscoveryOutcome

    /** `settings.email.discoveryEnabled` is off. The owner's choice, not a failure. */
    data object Disabled : DiscoveryOutcome

    /**
     * Connection, auth or protocol failure. [detail] is a class name or a constant, never a
     * server message — spec 04 §4.1.
     */
    data class Failed(val detail: String) : DiscoveryOutcome
}

/**
 * One discovery pass: raw-search, peek envelopes, group, write proposals.
 *
 * Shaped so that screen 47's button is one line (`SenderDiscoveryWorker.enqueueNow(context)`)
 * whenever 47 lands, and so this class can be driven end to end against `FakeMailboxClient`
 * with no device and no credential — the same arrangement that makes `EmailSyncRunner`
 * testable, and for the same reason: everything here that can be wrong is decided in Kotlin,
 * not in Android.
 *
 * **It opens its own READ_ONLY session and never advances `settings.email.lastSyncedUid`.**
 * The raw-search UID set overlaps the sync's window without being bounded by it — it is
 * selected by a Gmail category, not by a UID range — and `EmailSyncWorker`'s watermark is
 * only safe to advance over a contiguous range it fetched itself (R-11-07). A discovery pass
 * that touched the watermark would move it past mail whose bodies were never downloaded, and
 * no later run would look below it again.
 *
 * **It never writes `settings.email.state`.** A discovery hiccup must not put an AUTH_FAILED
 * or NETWORK_ERROR banner on Home for a mail channel that is working perfectly well; the
 * evidence of a failed scan is its `sync_runs` row and nothing else.
 */
@Singleton
class DiscoverSendersUseCase @Inject constructor(
    private val configStore: EmailAccountConfigStore,
    private val credentialStore: EmailCredentialStore,
    private val mailbox: ImapMailboxClient,
    private val discovery: SenderDiscovery,
    private val overrides: SenderOverrideRepository,
    private val syncRuns: SyncRunDao,
    private val clock: Clock
) {

    suspend operator fun invoke(trigger: SyncTrigger): DiscoveryOutcome {
        val config = configStore.current()
        if (!config.discoveryEnabled) return DiscoveryOutcome.Disabled
        // Both halves, as in step 1 of the sync: the address lives in settings and the
        // password in the keystore, and either one missing means there is nothing to open.
        if (!config.hasAddress || !credentialStore.hasPassword()) {
            return DiscoveryOutcome.NotConfigured
        }

        // Opened before the first byte crosses the network, so a scan killed mid-run leaves a
        // row with a null finished_at rather than leaving no trace (spec 11 §11.3 step 2's
        // rule, applied to this channel). Nothing above this line writes a row: a log full of
        // "disabled" rows would bury the scans that ran.
        val startedAt = LocalDateTime.now(clock)
        val startedAtInstant = clock.instant()
        val runId = syncRuns.insert(
            SyncRunEntity(channel = SyncChannel.DISCOVERY, startedAt = startedAt, trigger = trigger)
        )

        val outcome = try {
            credentialStore.withPassword { password ->
                mailbox.withSession(config, password) { session -> scan(session, config) }
            } ?: DiscoveryOutcome.NotConfigured // "Disconnect" landed while this was starting.
        } catch (e: MailboxException) {
            // Auth, folder and network all become Failed here, deliberately. The sync worker
            // distinguishes them because each drives a different banner and a different retry
            // rule; a discovery scan has no banner and retries at most once, so a third
            // vocabulary would be three names for "the scan did not happen".
            DiscoveryOutcome.Failed(detailOf(e))
        } catch (e: CancellationException) {
            // The open row is the evidence that this pass was killed; writing a tidy ending
            // would erase it.
            throw e
        } catch (e: Exception) {
            DiscoveryOutcome.Failed(e::class.java.simpleName)
        }

        finishRun(runId, startedAt, startedAtInstant, trigger, outcome)

        if (outcome is DiscoveryOutcome.Completed) {
            // Only a pass that really scanned moves the stamp. Marking a NotSupported or a
            // Failed pass as "last scanned now" would tell screen 47 the mailbox had been
            // swept when it had not.
            configStore.update { it.copy(lastDiscoveryAt = clock.instant()) }
        }
        return outcome
    }

    /** The pass itself, inside one open READ_ONLY folder. */
    private suspend fun scan(
        session: MailboxSession,
        config: EmailAccountConfig
    ): DiscoveryOutcome {
        // Asked once, from the capability list cached at connect. On a non-Gmail server there
        // is no X-GM-RAW and therefore no way to ask Gmail's classifier anything, and that is
        // a different answer from "asked, found nothing".
        if (!session.supportsGmailRawSearch) return DiscoveryOutcome.NotSupported

        val uids = session.searchRaw(
            query = config.discoveryQuery,
            receivedSince = config.discoveryCutoff(clock.instant()),
            limit = config.discoveryMaxHeaders
        ) ?: return DiscoveryOutcome.NotSupported

        // Chunked rather than one FETCH of up to ten thousand: envelopes are small but they
        // are all held at once, and a single enormous FETCH is also the one shape a server is
        // most likely to answer slowly. The chunk size is local because nothing else has an
        // opinion about it — it is not the sync's commit unit and must not be confused with
        // it, since there is nothing here to commit.
        val sightings = ArrayList<SenderSighting>(uids.size)
        for (chunk in uids.chunked(PEEK_CHUNK)) sightings += session.peekSenders(chunk)

        val report = discovery.discover(
            sightings = sightings,
            // Accepted overrides count as known, so a second pass stops re-proposing what the
            // owner already took...
            overrides = overrides.mappings(),
            // ...and declined ones are dropped entirely, so it stops re-proposing what they
            // refused. Two inputs, because those are two different states.
            declined = overrides.declinedKeys()
        )

        return DiscoveryOutcome.Completed(report, overrides.record(report))
    }

    /**
     * Closes the `sync_runs` row.
     *
     * The counters are `SyncChannel.DISCOVERY`'s reading of the existing columns: `fetched` =
     * sightings peeked, `skipped` = sightings whose sender was already known, `parsed_txn` =
     * proposals recorded. Everything else stays zero, because a discovery pass creates no
     * transaction, no due and no receipt.
     */
    private suspend fun finishRun(
        runId: Long,
        startedAt: LocalDateTime,
        startedAtInstant: Instant,
        trigger: SyncTrigger,
        outcome: DiscoveryOutcome
    ) {
        val report = (outcome as? DiscoveryOutcome.Completed)?.report
        syncRuns.update(
            SyncRunEntity(
                id = runId,
                channel = SyncChannel.DISCOVERY,
                startedAt = startedAt,
                finishedAt = LocalDateTime.now(clock),
                trigger = trigger,
                fetched = report?.scannedCount ?: 0,
                skipped = report?.knownCount ?: 0,
                parsedTxn = (outcome as? DiscoveryOutcome.Completed)?.proposed ?: 0,
                // `last_uid` stays null: discovery is not watermarked, and writing the
                // highest UID it happened to see would look like a watermark to anything
                // reading this table (spec 01 constraint C7 — unknown is null).
                errorMessage = outcome.errorMessage(),
                durationMs = Duration.between(startedAtInstant, clock.instant()).toMillis()
            )
        )
    }

    /** `sync_runs.error_message` — a class or a constant, never a server string. */
    private fun DiscoveryOutcome.errorMessage(): String? = when (this) {
        is DiscoveryOutcome.Completed -> null
        DiscoveryOutcome.NotSupported -> "NOT_SUPPORTED"
        DiscoveryOutcome.NotConfigured -> "NOT_CONFIGURED"
        // Unreachable — the switch is read before the row is opened — but exhaustive, so that
        // adding an outcome later is a compile error here rather than a silent null.
        DiscoveryOutcome.Disabled -> "DISABLED"
        is DiscoveryOutcome.Failed -> "ERROR:$detail"
    }

    private fun detailOf(error: MailboxException): String = when (error) {
        is MailboxException.Auth -> "AUTH_FAILED"
        is MailboxException.FolderMissing -> "FOLDER_MISSING"
        // The cause's CLASS only. Its message can quote the login (spec 04 §4.1).
        is MailboxException.Network ->
            error.cause?.let { it::class.java.simpleName } ?: "IOException"
    }

    private companion object {
        /** UIDs per ENVELOPE fetch. See [scan] for why this is not the sync's batch size. */
        const val PEEK_CHUNK = 500
    }
}
