package com.nrknithin.fintrack.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.nrknithin.fintrack.data.database.entity.SyncTrigger
import com.nrknithin.fintrack.data.email.EmailAccountConfig
import com.nrknithin.fintrack.domain.usecase.DiscoverSendersUseCase
import com.nrknithin.fintrack.domain.usecase.DiscoveryOutcome
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * The discovery pass on a schedule and on demand — its own work, not a passenger on
 * [EmailSyncWorker].
 *
 * Three reasons it is separate, and each one would be a bug if it were not.
 *
 * *Cadence.* The measurement this feature is built on found roughly six new senders across a
 * year. At `EmailSyncWorker`'s default 30-minute period that would be ~17,500 raw searches to
 * learn what 52 learn.
 *
 * *The watermark.* See the class KDoc of [DiscoverSendersUseCase]: a discovery pass must not
 * share a session with the sync, because its UID set is not the contiguous range the
 * watermark is only safe to advance over (R-11-07).
 *
 * *Failure isolation.* `X-GM-RAW` is a Gmail extension. Anything it threw inside
 * `EmailSyncWorker` would be classified as a NETWORK_ERROR and start the retry-with-backoff
 * path — and put an amber banner on Home — against a mailbox that is working.
 *
 * The rest of the deviation is recorded in docs/FORK-CHANGES.md.
 */
@HiltWorker
class SenderDiscoveryWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val discover: DiscoverSendersUseCase
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): ListenableWorker.Result {
        val trigger = SyncTrigger.fromStored(inputData.getString(KEY_TRIGGER))
        val outcome = discover(trigger)
        Log.i(TAG, "Sender discovery finished: " + outcome.summary() + " (attempt " + runAttemptCount + ")")
        return resultFor(outcome, runAttemptCount)
    }

    companion object {
        private const val TAG = "SenderDiscoveryWorker"

        const val WORK_NAME_PERIODIC = "sender_discovery_periodic"
        const val WORK_NAME_ONE_SHOT = "sender_discovery_now"

        /** Input: the [SyncTrigger] name; absent means [SyncTrigger.PERIODIC]. */
        const val KEY_TRIGGER = "sender_discovery_trigger"

        /** Output: which branch the pass ended on, for screen 47's snackbar. */
        const val KEY_OUTCOME = "sender_discovery_outcome"
        const val KEY_SCANNED = "sender_discovery_scanned"
        const val KEY_PROPOSED = "sender_discovery_proposed"

        const val OUTCOME_OK = "OK"
        const val OUTCOME_NOT_SUPPORTED = "NOT_SUPPORTED"
        const val OUTCOME_NOT_CONFIGURED = "NOT_CONFIGURED"
        const val OUTCOME_DISABLED = "DISABLED"
        const val OUTCOME_ERROR = "ERROR"

        /**
         * One retry and no more. A scan is not urgent and nothing downstream is waiting on it:
         * the next weekly run sees the same window, so a transient failure costs a week of
         * freshness rather than any data at all.
         */
        const val MAX_RETRIES = 1

        private const val BACKOFF_MINUTES = 15L
        private const val PERIOD_DAYS = 7L

        /**
         * Weekly, KEEP.
         *
         * Not daily and emphatically not every sync — see the class KDoc for the arithmetic.
         * `setRequiresBatteryNotLow` and CONNECTED (UNMETERED when the owner has asked for
         * wifi-only syncs) because nothing here is urgent: a scan deferred to tonight is a
         * scan that still finds the same senders.
         */
        fun schedule(context: Context, config: EmailAccountConfig) {
            val request = PeriodicWorkRequestBuilder<SenderDiscoveryWorker>(
                PERIOD_DAYS, TimeUnit.DAYS
            )
                .setConstraints(constraints(config.syncOnlyOnWifi))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
                .setInputData(workDataOf(KEY_TRIGGER to SyncTrigger.PERIODIC.name))
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME_PERIODIC,
                // KEEP, so a process start does not reset a week that is already counting
                // down — the same rule EmailSyncWorker.enqueuePeriodic follows, and it matters
                // more here: a device restarted daily would otherwise never reach day seven.
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        /**
         * Screen 47's "Find new senders" button, in one line.
         *
         * [ExistingWorkPolicy.KEEP] so a double tap is ignored rather than racing the running
         * scan over the same proposals. Not expedited: the owner is looking at a settings
         * screen, not waiting on a number, and an expedited quota spent here is one the sync
         * cannot spend.
         */
        fun enqueueNow(context: Context, trigger: SyncTrigger = SyncTrigger.MANUAL) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME_ONE_SHOT,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<SenderDiscoveryWorker>()
                    .setConstraints(constraints(onlyOnWifi = false))
                    .setBackoffCriteria(
                        BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES
                    )
                    .setInputData(workDataOf(KEY_TRIGGER to trigger.name))
                    .build()
            )
        }

        /** Discovery switched off, or the mailbox disconnected. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME_PERIODIC)
        }

        /**
         * Only [DiscoveryOutcome.Failed] is worth retrying, and only once.
         *
         * Every other ending is a `success` even when nothing was found: "this is not a Gmail
         * mailbox" and "discovery is switched off" are answers, not faults, and returning
         * `failure` for them would put a failed job in front of the owner for a decision they
         * made on purpose.
         */
        internal fun resultFor(
            outcome: DiscoveryOutcome,
            runAttemptCount: Int
        ): ListenableWorker.Result = when (outcome) {
            is DiscoveryOutcome.Completed -> ListenableWorker.Result.success(
                workDataOf(
                    KEY_OUTCOME to OUTCOME_OK,
                    KEY_SCANNED to outcome.report.scannedCount,
                    KEY_PROPOSED to outcome.proposed
                )
            )

            DiscoveryOutcome.NotSupported -> ListenableWorker.Result.success(
                outcomeData(OUTCOME_NOT_SUPPORTED)
            )

            DiscoveryOutcome.NotConfigured -> ListenableWorker.Result.success(
                outcomeData(OUTCOME_NOT_CONFIGURED)
            )

            DiscoveryOutcome.Disabled -> ListenableWorker.Result.success(
                outcomeData(OUTCOME_DISABLED)
            )

            is DiscoveryOutcome.Failed ->
                if (runAttemptCount < MAX_RETRIES) {
                    ListenableWorker.Result.retry()
                } else {
                    ListenableWorker.Result.failure(outcomeData(OUTCOME_ERROR))
                }
        }

        private fun outcomeData(outcome: String): Data = workDataOf(
            KEY_OUTCOME to outcome,
            KEY_SCANNED to 0,
            KEY_PROPOSED to 0
        )

        private fun constraints(onlyOnWifi: Boolean): Constraints = Constraints.Builder()
            .setRequiredNetworkType(if (onlyOnWifi) NetworkType.UNMETERED else NetworkType.CONNECTED)
            // Nothing here is urgent, and a header scan on a nearly flat battery is exactly
            // the kind of background work a user would be right to resent.
            .setRequiresBatteryNotLow(true)
            .build()
    }
}

/** A log line with counts and no content — no sender, no subject (spec 04 §4.1). */
internal fun DiscoveryOutcome.summary(): String = when (this) {
    is DiscoveryOutcome.Completed ->
        "ok, scanned=${report.scannedCount} known=${report.knownCount} proposed=$proposed"
    DiscoveryOutcome.NotSupported -> "not supported (no X-GM-EXT-1)"
    DiscoveryOutcome.NotConfigured -> "not configured"
    DiscoveryOutcome.Disabled -> "disabled"
    is DiscoveryOutcome.Failed -> "failed ($detail)"
}
