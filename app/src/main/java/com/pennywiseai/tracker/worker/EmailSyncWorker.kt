package com.pennywiseai.tracker.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.pennywiseai.parser.core.email.EmailSenderRegistry
import com.pennywiseai.tracker.data.database.dao.EmailMessageDao
import com.pennywiseai.tracker.data.database.dao.SyncRunDao
import com.pennywiseai.tracker.data.database.entity.SyncChannel
import com.pennywiseai.tracker.data.database.entity.SyncRunEntity
import com.pennywiseai.tracker.data.database.entity.SyncTrigger
import com.pennywiseai.tracker.data.email.EmailAccountConfig
import com.pennywiseai.tracker.data.email.EmailAccountConfigStore
import com.pennywiseai.tracker.data.email.EmailAccountState
import com.pennywiseai.tracker.data.email.EmailBatchCommitter
import com.pennywiseai.tracker.data.email.EmailBatchCounts
import com.pennywiseai.tracker.data.email.EmailCredentialStore
import com.pennywiseai.tracker.data.email.ImapMailboxClient
import com.pennywiseai.tracker.data.email.MailboxException
import com.pennywiseai.tracker.data.email.MailboxSession
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * One run of the email channel - spec 11 section 11.3, steps 1 to 22.
 *
 * The worker is the thin Android half: it reads the trigger out of its input data, shows a
 * foreground notification when a human is waiting on this run, reports progress, and turns
 * the outcome into a Result (retry or not). Everything that decides *what happens to mail*
 * lives in [EmailSyncRunner] below, which has no Android type in it at all - that is what
 * lets the resume, the UIDVALIDITY reset and the auth path be tested as JVM unit tests
 * against FakeMailboxClient, with no device, no Robolectric and no credential.
 */
@HiltWorker
class EmailSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val runner: EmailSyncRunner
) : CoroutineWorker(appContext, workerParams) {

    /** Required for expedited work, and the notification a user-initiated run promotes to. */
    override suspend fun getForegroundInfo(): ForegroundInfo =
        ForegroundInfo(NOTIFICATION_ID, buildSyncNotification(applicationContext, fetched = 0))

    override suspend fun doWork(): ListenableWorker.Result {
        val trigger = SyncTrigger.fromStored(inputData.getString(KEY_TRIGGER))

        // Spec 02 section 2.5: setForeground only for user-initiated runs. It can legitimately
        // fail (Android 12+ refuses a foreground service started from the background), and a
        // sync that cannot show a notification must still sync, so failure is swallowed.
        if (trigger.isUserInitiated) showProgressNotification(fetched = 0)

        val outcome = runner.run(
            trigger = trigger,
            shouldStop = { isStopped },
            onProgress = { progress -> reportProgress(trigger, progress) }
        )

        // Step 21. A full window means the mailbox almost certainly holds more above the new
        // watermark, so the next run starts now rather than at the next period.
        if (outcome is EmailSyncOutcome.Completed &&
            outcome.report.hitCap &&
            !outcome.report.stopped
        ) {
            enqueueFollowUp(applicationContext, trigger, outcome.report.onlyOnWifi)
        }

        Log.i(TAG, "Email sync finished: " + outcome.summary() + " (attempt " + runAttemptCount + ")")
        return resultFor(outcome, runAttemptCount)
    }

    /** Screen 47 E12 reads these to render the running count. */
    private suspend fun reportProgress(trigger: SyncTrigger, progress: EmailSyncProgress) {
        try {
            setProgress(
                workDataOf(
                    PROGRESS_FETCHED to progress.fetched,
                    PROGRESS_CANDIDATES to progress.candidates
                )
            )
        } catch (_: Exception) {
            // Progress is a courtesy to the UI; it must never end a sync.
        }
        if (trigger.isUserInitiated) showProgressNotification(progress.fetched)
    }

    private suspend fun showProgressNotification(fetched: Int) {
        try {
            setForeground(
                ForegroundInfo(NOTIFICATION_ID, buildSyncNotification(applicationContext, fetched))
            )
        } catch (_: Exception) {
            // See doWork: a refused foreground service must not stop the sync.
        }
    }

    companion object {
        private const val TAG = "EmailSyncWorker"

        /** Periodic schedule of spec 02 section 2.5. */
        const val WORK_NAME_PERIODIC = "email_sync_periodic"

        /**
         * Every non-periodic run shares one unique name, so that "Sync now", a boot catch-up,
         * an FCM tick (R-11-13) and the follow-up of step 21 queue behind each other instead
         * of racing over the same watermark.
         */
        const val WORK_NAME_ONE_SHOT = "email_sync_now"

        /** Input: the [SyncTrigger] name; absent means [SyncTrigger.PERIODIC]. */
        const val KEY_TRIGGER = "email_sync_trigger"

        const val PROGRESS_FETCHED = "email_progress_fetched"
        const val PROGRESS_CANDIDATES = "email_progress_candidates"

        /** Output: which branch of 11.3 the run ended on, for the snackbar of R-11-09. */
        const val KEY_OUTCOME = "email_sync_outcome"
        const val KEY_FETCHED = "email_sync_fetched"
        const val KEY_LAST_UID = "email_sync_last_uid"

        const val OUTCOME_OK = "OK"
        const val OUTCOME_NOT_CONFIGURED = "NOT_CONFIGURED"
        const val OUTCOME_AUTH_FAILED = "AUTH_FAILED"
        const val OUTCOME_FOLDER_MISSING = "FOLDER_MISSING"
        const val OUTCOME_NETWORK_ERROR = "NETWORK_ERROR"
        const val OUTCOME_ERROR = "ERROR"

        /**
         * Spec 02 section 2.5: "max 3 retries per enqueue". WorkManager has no such setting,
         * so the worker counts for itself - runAttemptCount is 0 on the first execution, so
         * this retries on attempts 0, 1 and 2 and gives up on attempt 3.
         */
        const val MAX_RETRIES = 3

        /** Spec 02 section 2.5 and 11.3: exponential, 30 s first. */
        private const val BACKOFF_SECONDS = 30L

        private const val NOTIFICATION_ID = 9101
        private const val NOTIFICATION_CHANNEL_ID = "email_sync_channel"

        /**
         * Maps the algorithm's ending onto WorkManager's three answers.
         *
         * AUTH_FAILED is a failure, never a retry: spec 02 section 2.7 and AC-11-03 both say
         * a revoked app password must not be tried again, because repeating a rejected
         * credential only hardens the lockout. The user replaces the password and screen 47
         * enqueues a fresh run.
         */
        internal fun resultFor(
            outcome: EmailSyncOutcome,
            runAttemptCount: Int
        ): ListenableWorker.Result = when (outcome) {
            EmailSyncOutcome.NotConfigured ->
                // Nothing to do and nothing wrong: no mailbox has been connected yet.
                ListenableWorker.Result.success(outcomeData(OUTCOME_NOT_CONFIGURED))

            is EmailSyncOutcome.Completed ->
                if (outcome.report.stopped) {
                    // WorkManager took the worker away mid-run. What committed is committed;
                    // come back for the rest.
                    ListenableWorker.Result.retry()
                } else {
                    ListenableWorker.Result.success(outcomeData(OUTCOME_OK, outcome.report))
                }

            is EmailSyncOutcome.AuthFailed ->
                ListenableWorker.Result.failure(outcomeData(OUTCOME_AUTH_FAILED, outcome.report))

            is EmailSyncOutcome.FolderMissing ->
                // Retrying cannot conjure a folder; screen 47 offers the picker instead.
                ListenableWorker.Result.failure(outcomeData(OUTCOME_FOLDER_MISSING, outcome.report))

            is EmailSyncOutcome.NetworkError ->
                if (runAttemptCount < MAX_RETRIES) {
                    ListenableWorker.Result.retry()
                } else {
                    ListenableWorker.Result.failure(
                        outcomeData(OUTCOME_NETWORK_ERROR, outcome.report)
                    )
                }

            is EmailSyncOutcome.Failed ->
                // An unexpected failure is not known to be transient, and the run row already
                // records its class. Repeating it three times would only repeat the damage.
                ListenableWorker.Result.failure(outcomeData(OUTCOME_ERROR, outcome.report))
        }

        private fun outcomeData(outcome: String, report: EmailSyncReport? = null): Data =
            workDataOf(
                KEY_OUTCOME to outcome,
                KEY_FETCHED to (report?.fetched ?: 0),
                KEY_LAST_UID to (report?.lastUid ?: 0L)
            )

        // --- Scheduling ------------------------------------------------------------------

        /**
         * The periodic schedule (spec 02 section 2.5). KEEP, so re-enqueuing on every app
         * start or boot does not reset an interval that is already counting down.
         */
        fun enqueuePeriodic(context: Context, config: EmailAccountConfig) {
            schedule(context, config, ExistingPeriodicWorkPolicy.KEEP)
        }

        /**
         * Spec 28 section 28.3: an email setting changed, so the schedule is rebuilt in place
         * with UPDATE - the work keeps its identity and its next run time is recomputed.
         */
        fun reschedulePeriodic(context: Context, config: EmailAccountConfig) {
            schedule(context, config, ExistingPeriodicWorkPolicy.UPDATE)
        }

        /**
         * "Sync now" (R-11-09), the onboarding first sync (31), a boot catch-up, or an FCM
         * tick (R-11-13). KEEP is what R-11-13 asks for: a tick that arrives while a run is
         * already queued or running is ignored rather than doubled.
         */
        fun syncNow(
            context: Context,
            trigger: SyncTrigger = SyncTrigger.MANUAL,
            onlyOnWifi: Boolean = false
        ) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME_ONE_SHOT,
                ExistingWorkPolicy.KEEP,
                oneTimeRequest(trigger, onlyOnWifi)
            )
        }

        /**
         * Step 21's immediate follow-up.
         *
         * APPEND_OR_REPLACE, not KEEP and not REPLACE: KEEP would drop it (this very run
         * still holds [WORK_NAME_ONE_SHOT] when the trigger was manual) and REPLACE would
         * cancel the run that is enqueueing it. Appending runs it the moment this one ends.
         */
        internal fun enqueueFollowUp(context: Context, trigger: SyncTrigger, onlyOnWifi: Boolean) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME_ONE_SHOT,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                oneTimeRequest(trigger, onlyOnWifi)
            )
        }

        private fun schedule(
            context: Context,
            config: EmailAccountConfig,
            policy: ExistingPeriodicWorkPolicy
        ) {
            val request = PeriodicWorkRequestBuilder<EmailSyncWorker>(
                config.syncIntervalMinutes.toLong(), TimeUnit.MINUTES
            )
                .setConstraints(constraints(config.syncOnlyOnWifi))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .setInputData(workDataOf(KEY_TRIGGER to SyncTrigger.PERIODIC.name))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME_PERIODIC, policy, request)
        }

        private fun oneTimeRequest(trigger: SyncTrigger, onlyOnWifi: Boolean): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<EmailSyncWorker>()
                .setConstraints(constraints(onlyOnWifi))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .setInputData(workDataOf(KEY_TRIGGER to trigger.name))
                // Expedited where the quota allows it, an ordinary run where it does not -
                // never a refused enqueue, which would silently skip the sync.
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()

        private fun constraints(onlyOnWifi: Boolean): Constraints = Constraints.Builder()
            .setRequiredNetworkType(if (onlyOnWifi) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()

        /**
         * Low-priority, silent, ongoing - spec 02 section 2.5 wants the user to see that the
         * sync they asked for is running, not to be interrupted by it.
         */
        fun buildSyncNotification(context: Context, fetched: Int): Notification {
            // minSdk is 26+ for this project, so the channel can always be created.
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        NOTIFICATION_CHANNEL_ID,
                        "Mail sync",
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
            }
            return NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_popup_sync)
                .setContentTitle("Syncing mail")
                // Counts only. A subject or a sender here would put mail content on the lock
                // screen (spec 04 section 4.1).
                .setContentText(if (fetched > 0) "$fetched fetched" else "Connecting")
                .setProgress(0, 0, true)
                .setOngoing(true)
                .setSilent(true)
                .build()
        }
    }
}

/** MANUAL and FOREGROUND are the two a person is waiting on; the rest are background. */
private val SyncTrigger.isUserInitiated: Boolean
    get() = this == SyncTrigger.MANUAL || this == SyncTrigger.FOREGROUND

/** What one run did, carried out of [EmailSyncRunner] into sync_runs and the UI. */
data class EmailSyncReport(
    /** Messages actually downloaded this run. */
    val fetched: Int = 0,
    /** UIDs the server matched - the size of the window, before the batches ran. */
    val candidates: Int = 0,
    /** The committed watermark. Never ahead of what email_messages holds (R-11-07). */
    val lastUid: Long = 0,
    /** Step 21: the window came back full, so there is probably more waiting. */
    val hitCap: Boolean = false,
    /** WorkManager stopped the worker between batches. */
    val stopped: Boolean = false,
    /** R-11-08: a real watermark was thrown away because the folder was rebuilt. */
    val watermarkReset: Boolean = false,
    val counts: EmailBatchCounts = EmailBatchCounts.EMPTY,
    /**
     * The user's wifi-only setting, carried so the follow-up run of step 21 keeps the same
     * network constraint without a second settings read.
     */
    val onlyOnWifi: Boolean = false
)

/** Emitted after each committed batch, so screen 47 can count up while the run goes on. */
data class EmailSyncProgress(val fetched: Int, val candidates: Int)

/** How a run ended. One per branch of spec 11 section 11.3 and spec 02 section 2.7. */
sealed interface EmailSyncOutcome {

    /** Step 1: no address, or no stored app password. Nothing was contacted or logged. */
    data object NotConfigured : EmailSyncOutcome

    data class Completed(val report: EmailSyncReport) : EmailSyncOutcome

    /** The server rejected the app password. Must not be retried (AC-11-03). */
    data class AuthFailed(val report: EmailSyncReport) : EmailSyncOutcome

    data class FolderMissing(val folder: String, val report: EmailSyncReport) : EmailSyncOutcome

    /** Any transient I/O failure; [detail] is a class name, never a server message. */
    data class NetworkError(val detail: String, val report: EmailSyncReport) : EmailSyncOutcome

    /** Anything else that escaped - a database failure, a bug. [detail] is a class name. */
    data class Failed(val detail: String, val report: EmailSyncReport) : EmailSyncOutcome
}

/** A log line with counts and no content. */
internal fun EmailSyncOutcome.summary(): String = when (this) {
    EmailSyncOutcome.NotConfigured -> "not configured"
    is EmailSyncOutcome.Completed ->
        "ok, fetched=${report.fetched} logged=${report.counts.logged} uid=${report.lastUid}"
    is EmailSyncOutcome.AuthFailed -> "auth failed"
    is EmailSyncOutcome.FolderMissing -> "folder missing"
    is EmailSyncOutcome.NetworkError -> "network error ($detail), uid=${report.lastUid}"
    is EmailSyncOutcome.Failed -> "failed ($detail), uid=${report.lastUid}"
}

/**
 * Spec 11 section 11.3 with the Android taken out: connect, window, batch, commit, advance.
 *
 * Injected into [EmailSyncWorker] rather than written inside it for one reason that matters
 * more than tidiness - a CoroutineWorker cannot be constructed in a JVM unit test without a
 * Context, and the properties this class has to get right (the watermark never moving before
 * a commit, a UIDVALIDITY change resetting it, an auth failure stopping dead) are exactly the
 * ones worth testing on every run.
 *
 * It owns the session and the watermark. [EmailBatchCommitter] owns the database. The boundary
 * between them is where R-11-07 is enforced.
 */
@Singleton
class EmailSyncRunner @Inject constructor(
    private val configStore: EmailAccountConfigStore,
    private val credentialStore: EmailCredentialStore,
    private val mailbox: ImapMailboxClient,
    private val committer: EmailBatchCommitter,
    private val syncRuns: SyncRunDao,
    /** Only for the retention sweep of spec 11 section 11.2; the log itself is the committer's. */
    private val emailMessages: EmailMessageDao,
    private val clock: Clock
) {

    /**
     * @param shouldStop polled between batches; a stopped worker commits nothing further.
     * @param onProgress called after each committed batch, never during one.
     */
    suspend fun run(
        trigger: SyncTrigger,
        shouldStop: () -> Boolean = { false },
        onProgress: suspend (EmailSyncProgress) -> Unit = {}
    ): EmailSyncOutcome {
        // Step 1. Both halves have to hold: the address lives in settings and the password in
        // the keystore, and either one missing means there is nothing to connect to.
        val config = configStore.current()
        if (!config.hasAddress || !credentialStore.hasPassword()) {
            applyState(EmailAccountState.NOT_CONFIGURED)
            // No sync_runs row: nothing ran, and a log full of "not configured" rows would
            // bury the runs that did.
            return EmailSyncOutcome.NotConfigured
        }

        // Step 2. The row is opened BEFORE the first byte crosses the network, so a run that
        // is killed mid-sync leaves a row with a null finished_at - which is how screen 48
        // shows "interrupted" instead of showing nothing at all.
        val startedAt = LocalDateTime.now(clock)
        val startedAtInstant = clock.instant()
        val runId = syncRuns.insert(
            SyncRunEntity(channel = SyncChannel.EMAIL, startedAt = startedAt, trigger = trigger)
        )

        // Mutable for exactly one reason: when the session throws half way through, the run
        // row still has to say what COMMITTED, not what the run hoped to do. A report
        // returned by value would be lost with the stack frame, and screen 48 would show a
        // failed run that fetched nothing while 50 messages sat in the log.
        val state = RunState(
            EmailSyncReport(lastUid = config.lastSyncedUid, onlyOnWifi = config.syncOnlyOnWifi)
        )
        var mailboxFailure: MailboxException? = null
        var unexpected: Throwable? = null
        var credentialGone = false

        try {
            // Steps 3, 4 and 19: connect, open READ_ONLY, always close. Every provider
            // failure arrives as a MailboxException, so the branches below are exhaustive.
            val borrowed = credentialStore.withPassword { password ->
                mailbox.withSession(config, password) { session ->
                    syncSession(session, config, state, shouldStop, onProgress)
                }
                true
            }
            if (borrowed == null) credentialGone = true
        } catch (e: MailboxException) {
            mailboxFailure = e
        } catch (e: CancellationException) {
            // The run row stays open on purpose: an unfinished row IS the evidence that this
            // run was killed, and writing a tidy ending would erase it.
            throw e
        } catch (e: Exception) {
            unexpected = e
        }

        val report = state.report
        val outcome = classify(mailboxFailure, unexpected, credentialGone, report)

        // Step 20, and the same row for every other ending: counts, watermark, error.
        finishRun(runId, startedAt, startedAtInstant, trigger, report, outcome.errorMessage())

        applyState(outcome.state())
        if (outcome is EmailSyncOutcome.Completed && !report.stopped) {
            configStore.markSynced(clock.instant())
            // Step 20's other half. Once per completed run, never per batch: the sweep is a
            // single UPDATE over a date-indexed column, and putting it on the batch path
            // would run it fifty times for one run's worth of mail while the folder is open.
            trimBodies(config)
        }

        // Step 22 - planRecomputer.markDirty() and alertEvaluator.requestRun() land with M3
        // and M7. The signal they will read is already folded across the run,
        // report.counts.changedMoney, so neither has to re-read what this run wrote. Nothing
        // is faked here in the meantime (spec 01 constraint C7).

        return outcome
    }

    /** Steps 5 to 18, inside one open folder. Every commit is published to [state] at once. */
    private suspend fun syncSession(
        session: MailboxSession,
        config: EmailAccountConfig,
        state: RunState,
        shouldStop: () -> Boolean,
        onProgress: suspend (EmailSyncProgress) -> Unit
    ) {
        // Step 5 (R-11-08). Gmail rebuilt the folder, so every UID we hold means nothing:
        // start again from 0 and let the message_id check keep the log from doubling. A
        // first sync also lands here - it moves from the unset 0 to the server's value,
        // which discards nothing, so it is not counted as a reset.
        var uidValidity = config.uidValidity
        if (session.uidValidity != uidValidity) {
            uidValidity = session.uidValidity
            configStore.setWatermark(uidValidity, 0L)
            // The watermarkReset argument is read from the value being replaced, so it sees
            // the watermark this run is throwing away, not the zero that replaces it.
            state.report = state.report.copy(
                lastUid = 0L,
                watermarkReset = state.report.lastUid > 0
            )
        }

        // Steps 6 to 9. The server does the filtering: only mail from a sender the app knows
        // is ever downloaded (R-11-03). sender_overrides joins this union in M2; until that
        // table exists, the registry plus the user's extra senders is the whole of it.
        val uids = session.searchUids(
            searchTerms = EmailSenderRegistry.searchTerms(config.extraSenders),
            sinceUid = state.report.lastUid,
            // Bounds the first run only; once there is a watermark the UID range is the
            // bound (R-11-04) and the client ignores this.
            receivedSince = config.lookbackCutoff(clock.instant()),
            limit = config.maxMessagesPerSync
        )
        state.report = state.report.copy(
            candidates = uids.size,
            hitCap = uids.size == config.maxMessagesPerSync
        )

        // Steps 10 to 18.
        var unreadableSeen = 0
        for (batch in uids.chunked(config.batchSize)) {
            if (shouldStop()) {
                state.report = state.report.copy(stopped = true)
                break
            }

            val messages = session.fetch(batch, config.maxBodyKb)

            // The session accumulates these across calls, so this batch's share is the tail
            // that appeared since the last one. They are committed with the batch: a UID the
            // client could not read is finished with, and the watermark is about to pass it.
            val unreadable = session.unreadable.drop(unreadableSeen)
            unreadableSeen = session.unreadable.size

            // Returns only after its Room transaction has committed.
            val counts = committer.commitBatch(session.mailbox, messages, unreadable, config)

            // Step 17 (R-11-07), and the single most dangerous line in this channel: the
            // watermark moves AFTER the commit, never before. Reversed, a crash here would
            // leave the watermark above mail that was never logged, and no later run would
            // ever look below it again - the mail would simply be gone.
            //
            // It moves to the highest UID *asked for*, not the highest received: a UID the
            // server no longer has is finished with, and leaving it under the watermark
            // would re-search it on every run forever.
            val current = state.report
            val committedUid = maxOf(current.lastUid, batch.maxOrNull() ?: current.lastUid)
            state.report = current.copy(
                fetched = current.fetched + messages.size,
                counts = current.counts + counts,
                lastUid = committedUid
            )
            configStore.setWatermark(uidValidity, committedUid)

            onProgress(
                EmailSyncProgress(
                    fetched = state.report.fetched,
                    candidates = state.report.candidates
                )
            )
        }

        // A message the client could not read during the *search* has no batch to ride along
        // with - and with no batch there is nothing to advance the watermark past, so this
        // commits the record of it and nothing else.
        val trailing = session.unreadable.drop(unreadableSeen)
        if (trailing.isNotEmpty() && !state.report.stopped) {
            val counts = committer.commitBatch(session.mailbox, emptyList(), trailing, config)
            state.report = state.report.copy(counts = state.report.counts + counts)
        }
    }

    /**
     * The run's progress, readable after an exception unwound the session.
     *
     * A plain holder rather than a returned value: [syncSession] can throw from any batch,
     * and what it had already committed is exactly what `sync_runs` and the watermark have to
     * agree about.
     */
    private class RunState(var report: EmailSyncReport)

    private fun classify(
        mailboxFailure: MailboxException?,
        unexpected: Throwable?,
        credentialGone: Boolean,
        report: EmailSyncReport
    ): EmailSyncOutcome = when {
        // The password was cleared while the run was starting ("Disconnect" mid-sync).
        credentialGone -> EmailSyncOutcome.NotConfigured

        mailboxFailure is MailboxException.Auth -> EmailSyncOutcome.AuthFailed(report)

        mailboxFailure is MailboxException.FolderMissing ->
            EmailSyncOutcome.FolderMissing(mailboxFailure.folder, report)

        mailboxFailure is MailboxException.Network ->
            // The cause's CLASS only. Its message can quote the login (spec 04 section 4.1).
            EmailSyncOutcome.NetworkError(
                mailboxFailure.cause?.let { it::class.java.simpleName } ?: "IOException",
                report
            )

        unexpected != null ->
            EmailSyncOutcome.Failed(unexpected::class.java.simpleName, report)

        else -> EmailSyncOutcome.Completed(report)
    }

    /** sync_runs.error_message - a class or a constant, never a server string. */
    private fun EmailSyncOutcome.errorMessage(): String? = when (this) {
        EmailSyncOutcome.NotConfigured -> "NOT_CONFIGURED"
        is EmailSyncOutcome.Completed ->
            // A run cut short by WorkManager is not an error, but it is not a complete sweep
            // either, and screen 48 has to be able to tell the two apart.
            if (report.stopped) "STOPPED" else null
        is EmailSyncOutcome.AuthFailed -> "AUTH_FAILED"
        is EmailSyncOutcome.FolderMissing -> "FOLDER_MISSING"
        is EmailSyncOutcome.NetworkError -> "NETWORK_ERROR:$detail"
        is EmailSyncOutcome.Failed -> "ERROR:$detail"
    }

    /** The banner state of spec 11 section 11.7 this ending implies, or null to leave it. */
    private fun EmailSyncOutcome.state(): EmailAccountState? = when (this) {
        EmailSyncOutcome.NotConfigured -> EmailAccountState.NOT_CONFIGURED
        is EmailSyncOutcome.Completed -> if (report.stopped) null else EmailAccountState.OK
        is EmailSyncOutcome.AuthFailed -> EmailAccountState.AUTH_FAILED
        is EmailSyncOutcome.FolderMissing -> EmailAccountState.FOLDER_MISSING
        is EmailSyncOutcome.NetworkError -> EmailAccountState.NETWORK_ERROR
        // A bug on our side says nothing about the mailbox: claiming NETWORK_ERROR would put
        // a banner in front of the user about a problem they cannot act on.
        is EmailSyncOutcome.Failed -> null
    }

    private suspend fun finishRun(
        runId: Long,
        startedAt: LocalDateTime,
        startedAtInstant: Instant,
        trigger: SyncTrigger,
        report: EmailSyncReport,
        errorMessage: String?
    ) {
        syncRuns.update(
            SyncRunEntity(
                id = runId,
                channel = SyncChannel.EMAIL,
                startedAt = startedAt,
                finishedAt = LocalDateTime.now(clock),
                trigger = trigger,
                fetched = report.fetched,
                parsedTxn = report.counts.parsedTxn,
                linkedTwin = report.counts.linkedTwin,
                duesUpserted = report.counts.duesUpserted,
                duesSettled = report.counts.duesSettled,
                receipts = report.counts.receipts,
                skipped = report.counts.skipped,
                errors = report.counts.errors,
                lastUid = report.lastUid,
                errorMessage = errorMessage,
                durationMs = Duration.between(startedAtInstant, clock.instant()).toMillis()
            )
        )
    }

    /**
     * Retention sweep for `settings.email.retainBodiesDays` (spec 11 section 11.2, default
     * 400 days; spec 04 section 4.3 for what survives it).
     *
     * Run only after a completed, unstopped sweep of the folder, for the same reason the
     * watermark only moves after a commit: a run that was cut short or that failed part way
     * has an unknown amount of mail still to fetch, and deleting text on the way out of a
     * half-finished run would shorten bodies whose messages this device has not finished
     * processing yet. Retention is measured in days - one skipped run costs nothing.
     *
     * A failure here is swallowed on purpose. By this point the mail is committed, the
     * watermark is persisted and `sync_runs` is closed; turning a successful sync into a
     * failed one because housekeeping could not write would make WorkManager retry the whole
     * fetch, and the bodies would simply be trimmed on the next run instead.
     */
    private suspend fun trimBodies(config: EmailAccountConfig) {
        // Belt and braces over EmailAccountConfig.coerced(): a retention of 0 arriving from a
        // store that did not coerce would put the cutoff at "now" and shorten every body in
        // the table, including the ones this very run just wrote.
        val days = config.retainBodiesDays
            .coerceAtLeast(EmailAccountConfig.RETAIN_BODIES_MIN_DAYS)
            .toLong()
        val cutoff = LocalDateTime.now(clock).minusDays(days)
        try {
            // `received_at` is stored as the text `Converters.fromLocalDateTime` writes, so
            // the cutoff has to be formatted the same way: ISO local date-time compares
            // lexicographically in chronological order, which is what `<` in the query needs.
            emailMessages.trimBodiesBefore(cutoff.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // See the KDoc: housekeeping never fails a sync that already succeeded.
        }
    }

    /**
     * Writes the state only when it changes, which is what makes spec 11 section 11.7's "one
     * notification per state change" possible: whatever watches this store sees one event per
     * transition, not one per failed run.
     */
    private suspend fun applyState(state: EmailAccountState?) {
        if (state == null) return
        if (configStore.current().state == state) return
        configStore.setState(state)
    }
}
