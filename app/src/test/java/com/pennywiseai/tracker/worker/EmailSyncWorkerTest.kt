package com.pennywiseai.tracker.worker

import androidx.work.ListenableWorker
import com.pennywiseai.tracker.data.database.dao.EmailMessageDao
import com.pennywiseai.tracker.data.database.dao.SyncRunDao
import com.pennywiseai.tracker.data.database.entity.EmailMessageEntity
import com.pennywiseai.tracker.data.database.entity.SyncChannel
import com.pennywiseai.tracker.data.database.entity.SyncRunEntity
import com.pennywiseai.tracker.data.database.entity.SyncTrigger
import com.pennywiseai.tracker.data.email.BANK_SENDER
import com.pennywiseai.tracker.data.email.ConnectionTest
import com.pennywiseai.tracker.data.email.EmailAccountConfig
import com.pennywiseai.tracker.data.email.EmailAccountState
import com.pennywiseai.tracker.data.email.EmailBatchCommitter
import com.pennywiseai.tracker.data.email.EmailBatchCounts
import com.pennywiseai.tracker.data.email.FakeEmailAccountConfigStore
import com.pennywiseai.tracker.data.email.FakeEmailCredentialStore
import com.pennywiseai.tracker.data.email.FakeMailboxClient
import com.pennywiseai.tracker.data.email.FetchedMessage
import com.pennywiseai.tracker.data.email.MailboxException
import com.pennywiseai.tracker.data.email.TEST_NOW
import com.pennywiseai.tracker.data.email.UNRELATED_SENDER
import com.pennywiseai.tracker.data.email.UnreadableMessage
import com.pennywiseai.tracker.data.email.mailboxMessage
import com.pennywiseai.tracker.data.email.testConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Spec 11 section 11.3, driven end to end against the in-memory mailbox.
 *
 * [EmailSyncRunner] is the whole of the worker except its `Result` mapping, which is asserted
 * here too through `EmailSyncWorker.resultFor` - a `CoroutineWorker` cannot be built without
 * an Android `Context`, and the behaviour worth guarding is not WorkManager's plumbing but
 * what happens to mail: that the watermark never runs ahead of a commit (R-11-07), that a
 * rebuilt folder resets it (R-11-08), and that a revoked password stops dead (AC-11-03).
 *
 * Every address here is an example.com placeholder and every amount is invented; `PiiLintTest`
 * scans this directory (spec 06 section 6.2).
 */
class EmailSyncWorkerTest {

    /** Fixed, so the lookback window and every duration in `sync_runs` are deterministic. */
    private val clock: Clock = Clock.fixed(TEST_NOW, ZoneOffset.UTC)

    /**
     * The domain of [BANK_SENDER], handed to the runner as one of the user's extra senders.
     *
     * The compiled registry only knows real banks, so this is also what proves the
     * `extraSenders` half of step 6: without it the fake server matches nothing at all.
     */
    private val bankDomain = BANK_SENDER.substringAfter("@")

    // -- a full sync ------------------------------------------------------------------------

    @Test
    fun `a full sync logs known senders, advances the watermark and closes the run row`() {
        val beyondLookback = TEST_NOW.minusSeconds(200L * 24 * 3600)
        val harness = harness(
            messages = listOf(
                mailboxMessage(uid = 1),
                mailboxMessage(uid = 2),
                mailboxMessage(uid = 3, from = UNRELATED_SENDER),
                mailboxMessage(uid = 4, receivedAt = beyondLookback),
                mailboxMessage(uid = 5)
            )
        )

        val outcome = harness.run()

        // R-11-03 / R-11-04: an unrelated sender and a message older than the lookback are
        // never downloaded, so they cost no bytes and no battery.
        assertTrue(outcome is EmailSyncOutcome.Completed)
        val report = (outcome as EmailSyncOutcome.Completed).report
        assertEquals(3, report.fetched)
        assertEquals(3, report.counts.logged)
        assertEquals(5L, report.lastUid)
        assertFalse(report.hitCap)
        assertEquals(listOf(1L, 2L, 5L), harness.client.fetchedUids)
        assertEquals(listOf(1L, 2L, 5L), harness.committer.logged.keys.toList())

        // Step 20: state OK, last sync recorded, watermark persisted.
        assertEquals(EmailAccountState.OK, harness.configStore.value.state)
        assertNotNull(harness.configStore.value.lastSyncAt)
        assertEquals(5L, harness.configStore.value.lastSyncedUid)

        // Step 2 and 20: exactly one row, opened then closed, with the counts on it.
        assertEquals(1, harness.syncRuns.rows.size)
        val row = harness.syncRuns.rows.single()
        assertEquals(SyncChannel.EMAIL, row.channel)
        assertEquals(SyncTrigger.PERIODIC, row.trigger)
        assertNotNull(row.finishedAt)
        assertNull(row.errorMessage)
        assertEquals(3, row.fetched)
        assertEquals(5L, row.lastUid)

        // The credential is borrowed once for the run, not once per message.
        assertEquals(1, harness.credentials.borrowCount)

        assertTrue(
            EmailSyncWorker.resultFor(outcome, runAttemptCount = 0)
                is ListenableWorker.Result.Success
        )
    }

    @Test
    fun `a second run downloads only what arrived above the watermark`() {
        val harness = harness(messages = (1L..3L).map { mailboxMessage(uid = it) })

        harness.run()
        val idle = harness.run()

        assertEquals(0, (idle as EmailSyncOutcome.Completed).report.fetched)
        assertEquals(listOf(1L, 2L, 3L), harness.client.fetchedUids)

        harness.client.messages = harness.client.messages + mailboxMessage(uid = 4)
        val incremental = harness.run()

        assertEquals(1, (incremental as EmailSyncOutcome.Completed).report.fetched)
        assertEquals(4L, harness.configStore.value.lastSyncedUid)
        assertEquals(3, harness.syncRuns.rows.size)
    }

    // -- AC-11-02: resume ---------------------------------------------------------------------

    @Test
    fun `an interrupted run resumes from the last committed batch and leaves no gaps`() {
        val harness = harness(
            messages = (1L..120L).map { mailboxMessage(uid = it) },
            config = testConfig(maxMessagesPerSync = 500, batchSize = 50)
        )
        // Dies ten messages into the second batch of fifty.
        harness.client.failAfter = 60
        harness.client.failWith = { MailboxException.Network(IOException()) }

        val interrupted = harness.run()

        assertTrue(interrupted is EmailSyncOutcome.NetworkError)
        // Only the first batch committed, so only the first batch is logged and the watermark
        // sits exactly on its last UID.
        assertEquals(50, harness.committer.logged.size)
        assertEquals(50L, harness.configStore.value.lastSyncedUid)
        assertEquals(EmailAccountState.NETWORK_ERROR, harness.configStore.value.state)
        // Evidence survives the failure: the row is closed, with the error and the counts.
        val failedRow = harness.syncRuns.rows.single()
        assertNotNull(failedRow.finishedAt)
        assertTrue(failedRow.errorMessage!!.startsWith("NETWORK_ERROR:"))
        assertEquals(50, failedRow.fetched)
        assertEquals(50L, failedRow.lastUid)
        // Spec 02 section 2.5: an IOException is worth trying again.
        assertTrue(
            EmailSyncWorker.resultFor(interrupted, runAttemptCount = 0)
                is ListenableWorker.Result.Retry
        )

        harness.client.failAfter = null
        val resumed = harness.run()

        assertTrue(resumed is EmailSyncOutcome.Completed)
        assertEquals((1L..120L).toList(), harness.committer.logged.keys.sorted())
        assertEquals(120L, harness.configStore.value.lastSyncedUid)
        assertEquals(EmailAccountState.OK, harness.configStore.value.state)

        // Nothing that was committed is ever downloaded twice.
        assertTrue(
            "committed messages were re-downloaded",
            (1L..50L).all { uid -> harness.client.fetchedUids.count { it == uid } == 1 }
        )
        // The uncommitted tail of the failed batch IS downloaded again. That is the real price
        // of a resume, and stating it here stops anyone "fixing" it by moving the watermark
        // before the commit - which is the bug R-11-07 exists to prevent.
        assertTrue((51L..60L).all { uid -> harness.client.fetchedUids.count { it == uid } == 2 })
    }

    @Test
    fun `the watermark does not advance when the batch transaction fails`() {
        val harness = harness(
            messages = (1L..120L).map { mailboxMessage(uid = it) },
            config = testConfig(maxMessagesPerSync = 500, batchSize = 50)
        )
        // The mail arrives fine; the database refuses the second batch.
        harness.committer.failOnBatch = 2

        val outcome = harness.run()

        assertTrue(outcome is EmailSyncOutcome.Failed)
        assertEquals(50, harness.committer.logged.size)
        // The whole point: the watermark stands on the last COMMITTED batch, so the next run
        // re-fetches 51..100 rather than skipping them forever.
        assertEquals(50L, harness.configStore.value.lastSyncedUid)
        assertEquals(listOf(1L to 0L, 1L to 50L), harness.configStore.watermarkWrites)
        assertTrue(harness.syncRuns.rows.single().errorMessage!!.startsWith("ERROR:"))
        // A database fault says nothing about the mailbox, so no connection banner is raised.
        assertTrue(harness.configStore.stateWrites.isEmpty())
        assertTrue(
            EmailSyncWorker.resultFor(outcome, runAttemptCount = 0)
                is ListenableWorker.Result.Failure
        )
    }

    @Test
    fun `a message the client cannot read costs that message and no other`() {
        val harness = harness(messages = (1L..5L).map { mailboxMessage(uid = it) })
        harness.client.unreadableUids = setOf(3L)

        val outcome = harness.run() as EmailSyncOutcome.Completed

        // Four messages arrive; the fifth is handed to the committer as an unreadable UID, so
        // the Email Log gets a row for it and sync_runs counts it (spec 02 section 2.7).
        assertEquals(4, outcome.report.fetched)
        assertEquals(listOf(3L), harness.committer.unreadableSeen)
        assertEquals(1, harness.syncRuns.rows.single().errors)
        // R-11-07 still holds in the other direction: the batch dealt with every UID it was
        // given, so the watermark passes the unreadable one instead of sticking on it and
        // re-fetching the same broken message on every run forever.
        assertEquals(5L, harness.configStore.value.lastSyncedUid)
    }

    // -- R-11-08: UIDVALIDITY -----------------------------------------------------------------

    @Test
    fun `a uidvalidity change resets the watermark and re-scans without duplicating messages`() {
        val original = (1L..5L).map { mailboxMessage(uid = it) }
        val harness = harness(messages = original)

        harness.run()
        assertEquals(5L, harness.configStore.value.lastSyncedUid)
        assertEquals(1L, harness.configStore.value.uidValidity)

        // Gmail rebuilt the folder: same mail, new UIDs, new UIDVALIDITY.
        harness.client.uidValidity = 2
        harness.client.messages = original.map { renumber(it, it.header.uid + 100) }

        val outcome = harness.run() as EmailSyncOutcome.Completed

        assertTrue(outcome.report.watermarkReset)
        assertEquals(2L, harness.configStore.value.uidValidity)
        // The mail really is downloaded again - with the old UIDs meaningless there is no
        // other way to see it - but message_id recognition keeps the log at five rows.
        assertEquals(5, outcome.report.fetched)
        assertEquals(0, outcome.report.counts.logged)
        assertEquals(5, outcome.report.counts.alreadyKnown)
        assertEquals(5, harness.committer.logged.size)
        assertEquals(105L, harness.configStore.value.lastSyncedUid)

        // The reset is written before any batch moves the watermark, and it is written with
        // the server's new validity - never the stale one.
        assertEquals(
            listOf(1L to 0L, 1L to 5L, 2L to 0L, 2L to 105L),
            harness.configStore.watermarkWrites
        )
    }

    @Test
    fun `an unchanged uidvalidity never resets the watermark`() {
        val harness = harness(messages = (1L..3L).map { mailboxMessage(uid = it) })

        harness.run()
        val second = harness.run() as EmailSyncOutcome.Completed

        // The first run only learned the server's UIDVALIDITY; it threw no watermark away.
        assertFalse(second.report.watermarkReset)
        assertEquals(3L, harness.configStore.value.lastSyncedUid)
    }

    // -- AC-11-03: auth ------------------------------------------------------------------------

    @Test
    fun `a revoked app password sets AUTH_FAILED, logs the run and never retries`() {
        val harness = harness(messages = (1L..5L).map { mailboxMessage(uid = it) })
        harness.client.connectionTest = ConnectionTest.AuthFailed

        val outcome = harness.run(trigger = SyncTrigger.MANUAL)

        assertTrue(outcome is EmailSyncOutcome.AuthFailed)
        assertEquals(listOf(EmailAccountState.AUTH_FAILED), harness.configStore.stateWrites)
        // Nothing was fetched, nothing logged, and the watermark was not touched.
        assertTrue(harness.client.fetchedUids.isEmpty())
        assertTrue(harness.committer.logged.isEmpty())
        assertTrue(harness.configStore.watermarkWrites.isEmpty())
        assertEquals(0L, harness.configStore.value.lastSyncedUid)
        assertNull(harness.configStore.value.lastSyncAt)

        val row = harness.syncRuns.rows.single()
        assertEquals(SyncTrigger.MANUAL, row.trigger)
        assertNotNull(row.finishedAt)
        assertEquals("AUTH_FAILED", row.errorMessage)
        assertEquals(0, row.fetched)

        // The load-bearing assertion: a failure, at every attempt count. A retry here would
        // keep presenting a rejected credential to Google and harden the lockout.
        for (attempt in 0..EmailSyncWorker.MAX_RETRIES + 1) {
            val result = EmailSyncWorker.resultFor(outcome, runAttemptCount = attempt)
            assertFalse("attempt $attempt retried", result is ListenableWorker.Result.Retry)
            assertTrue("attempt $attempt did not fail", result is ListenableWorker.Result.Failure)
        }
    }

    @Test
    fun `a second auth failure does not rewrite the state`() {
        val harness = harness(messages = (1L..2L).map { mailboxMessage(uid = it) })
        harness.client.connectionTest = ConnectionTest.AuthFailed

        harness.run()
        harness.run()

        // Spec 11 section 11.7: one notification per state CHANGE, so the state is written
        // once however many runs fail the same way.
        assertEquals(listOf(EmailAccountState.AUTH_FAILED), harness.configStore.stateWrites)
        assertEquals(2, harness.syncRuns.rows.size)
    }

    @Test
    fun `a missing folder stops the run, keeps the watermark and does not retry`() {
        val harness = harness(messages = (1L..5L).map { mailboxMessage(uid = it) })

        harness.run()
        val watermark = harness.configStore.value.lastSyncedUid

        harness.client.connectionTest =
            ConnectionTest.FolderMissing(EmailAccountConfig.DEFAULT_FOLDER, listOf("INBOX"))
        val outcome = harness.run()

        assertTrue(outcome is EmailSyncOutcome.FolderMissing)
        // A folder that went missing is not a reason to drop what is already known.
        assertEquals(watermark, harness.configStore.value.lastSyncedUid)
        assertEquals(5, harness.committer.logged.size)
        assertEquals(EmailAccountState.FOLDER_MISSING, harness.configStore.value.state)
        assertTrue(
            EmailSyncWorker.resultFor(outcome, runAttemptCount = 0)
                is ListenableWorker.Result.Failure
        )
    }

    @Test
    fun `a network failure retries until the attempt budget is spent`() {
        val outcome = EmailSyncOutcome.NetworkError("SocketTimeoutException", EmailSyncReport())

        for (attempt in 0 until EmailSyncWorker.MAX_RETRIES) {
            assertTrue(
                "attempt $attempt should retry",
                EmailSyncWorker.resultFor(outcome, attempt) is ListenableWorker.Result.Retry
            )
        }
        // Spec 02 section 2.5: three retries per enqueue, then stop and leave the row behind.
        assertTrue(
            EmailSyncWorker.resultFor(outcome, EmailSyncWorker.MAX_RETRIES)
                is ListenableWorker.Result.Failure
        )
    }

    // -- steps 9 and 21: the cap and the follow-up ---------------------------------------------

    @Test
    fun `a full window asks for a follow-up run and the remainder is picked up`() {
        val harness = harness(
            messages = (1L..120L).map { mailboxMessage(uid = it) },
            config = testConfig(maxMessagesPerSync = 50, batchSize = 10)
        )

        val first = harness.run() as EmailSyncOutcome.Completed
        assertTrue("a full window must ask for a follow-up run", first.report.hitCap)
        assertEquals(50, harness.committer.logged.size)
        assertEquals(50L, harness.configStore.value.lastSyncedUid)

        val second = harness.run() as EmailSyncOutcome.Completed
        assertTrue(second.report.hitCap)

        val third = harness.run() as EmailSyncOutcome.Completed
        assertFalse("a short window means the mailbox is drained", third.report.hitCap)
        assertEquals((1L..120L).toList(), harness.committer.logged.keys.toList())

        // Three runs, 120 messages, each downloaded exactly once.
        assertEquals(120, harness.client.fetchedUids.size)
        assertEquals(120, harness.client.fetchedUids.distinct().size)
        // Five batches of ten per capped run, each one its own commit (R-11-07).
        assertEquals(12, harness.committer.batches.size)
    }

    // -- step 1 and the stop signal --------------------------------------------------------------

    @Test
    fun `an unconfigured account contacts nothing and writes no run row`() {
        val harness = harness(
            messages = listOf(mailboxMessage(uid = 1)),
            credentials = FakeEmailCredentialStore()
        )

        val outcome = harness.run()

        assertEquals(EmailSyncOutcome.NotConfigured, outcome)
        assertEquals(0, harness.client.sessionsOpened)
        // A log full of "not configured" rows would bury the runs that really happened.
        assertTrue(harness.syncRuns.rows.isEmpty())
        assertEquals(EmailAccountState.NOT_CONFIGURED, harness.configStore.value.state)
        // The state was already NOT_CONFIGURED, so nothing was rewritten: spec 11 section
        // 11.7 wants one event per transition, and a settings write per skipped run would
        // wake every collector on screen 47 for nothing.
        assertTrue(harness.configStore.stateWrites.isEmpty())
        assertTrue(
            EmailSyncWorker.resultFor(outcome, runAttemptCount = 0)
                is ListenableWorker.Result.Success
        )
    }

    @Test
    fun `a missing address is enough to make the run not configured`() {
        val harness = harness(
            messages = listOf(mailboxMessage(uid = 1)),
            config = testConfig().copy(address = "")
        )

        assertEquals(EmailSyncOutcome.NotConfigured, harness.run())
        assertEquals(0, harness.client.sessionsOpened)
    }

    @Test
    fun `a stopped worker keeps what it committed and asks to come back`() {
        val harness = harness(
            messages = (1L..120L).map { mailboxMessage(uid = it) },
            config = testConfig(maxMessagesPerSync = 500, batchSize = 50)
        )

        // WorkManager pulls the worker after the first batch commits.
        val outcome = harness.run(shouldStop = { harness.committer.batches.isNotEmpty() })

        val report = (outcome as EmailSyncOutcome.Completed).report
        assertTrue(report.stopped)
        assertEquals(50, harness.committer.logged.size)
        assertEquals(50L, harness.configStore.value.lastSyncedUid)
        // A run cut short claims neither success nor a connection problem.
        assertNull(harness.configStore.value.lastSyncAt)
        assertTrue(harness.configStore.stateWrites.isEmpty())
        assertEquals("STOPPED", harness.syncRuns.rows.single().errorMessage)
        assertTrue(
            EmailSyncWorker.resultFor(outcome, runAttemptCount = 0)
                is ListenableWorker.Result.Retry
        )
    }

    // -- section 11.2: body retention ------------------------------------------------------------

    @Test
    fun `a completed run trims bodies once, at retainBodiesDays before now`() {
        val harness = harness(
            messages = (1L..120L).map { mailboxMessage(uid = it) },
            config = testConfig(maxMessagesPerSync = 500, batchSize = 50)
                .copy(retainBodiesDays = 90)
        )

        harness.run()

        // Off the per-batch path: three batches committed, one sweep. The sweep is a single
        // UPDATE over the whole table, so running it per batch would be 3x the work for the
        // same result - and 50x on a full window.
        assertEquals(3, harness.committer.batches.size)
        assertEquals(
            LocalDateTime.ofInstant(TEST_NOW, ZoneOffset.UTC).minusDays(90),
            LocalDateTime.parse(harness.emailMessages.trimCutoffs.single())
        )
    }

    @Test
    fun `the retention window defaults to the 400 days of spec 11 section 11_2`() {
        val harness = harness(messages = listOf(mailboxMessage(uid = 1)))

        harness.run()

        assertEquals(400, harness.configStore.value.retainBodiesDays)
        assertEquals(
            LocalDateTime.ofInstant(TEST_NOW, ZoneOffset.UTC).minusDays(400),
            LocalDateTime.parse(harness.emailMessages.trimCutoffs.single())
        )
    }

    @Test
    fun `a run that fetched nothing still trims, and trims again on the next run`() {
        val harness = harness(messages = listOf(mailboxMessage(uid = 1)))

        harness.run()
        val second = harness.run() as EmailSyncOutcome.Completed

        // Retention is measured in days, not in mail: a quiet mailbox still ages out. The
        // second sweep is safe to repeat because the query only touches rows still long
        // enough to shorten.
        assertEquals(0, second.report.fetched)
        assertEquals(2, harness.emailMessages.trimCutoffs.size)
    }

    @Test
    fun `a stopped run leaves stored bodies alone`() {
        val harness = harness(
            messages = (1L..120L).map { mailboxMessage(uid = it) },
            config = testConfig(maxMessagesPerSync = 500, batchSize = 50)
        )

        // WorkManager pulls the worker after the first batch commits, so 70 messages are
        // still unfetched. Shortening bodies on the way out of a half-finished sweep would
        // trim text the run has not finished processing.
        val outcome = harness.run(shouldStop = { harness.committer.batches.isNotEmpty() })

        assertTrue((outcome as EmailSyncOutcome.Completed).report.stopped)
        assertTrue(harness.emailMessages.trimCutoffs.isEmpty())
    }

    @Test
    fun `a failed run leaves stored bodies alone`() {
        val authFailed = harness(messages = (1L..5L).map { mailboxMessage(uid = it) })
        authFailed.client.connectionTest = ConnectionTest.AuthFailed
        assertTrue(authFailed.run() is EmailSyncOutcome.AuthFailed)
        assertTrue(authFailed.emailMessages.trimCutoffs.isEmpty())

        val folderGone = harness(messages = (1L..5L).map { mailboxMessage(uid = it) })
        folderGone.client.connectionTest =
            ConnectionTest.FolderMissing(EmailAccountConfig.DEFAULT_FOLDER, listOf("INBOX"))
        assertTrue(folderGone.run() is EmailSyncOutcome.FolderMissing)
        assertTrue(folderGone.emailMessages.trimCutoffs.isEmpty())

        val offline = harness(messages = (1L..5L).map { mailboxMessage(uid = it) })
        offline.client.connectionTest = ConnectionTest.NetworkError("SocketTimeoutException")
        assertTrue(offline.run() is EmailSyncOutcome.NetworkError)
        assertTrue(offline.emailMessages.trimCutoffs.isEmpty())

        val unconfigured = harness(
            messages = listOf(mailboxMessage(uid = 1)),
            credentials = FakeEmailCredentialStore()
        )
        assertEquals(EmailSyncOutcome.NotConfigured, unconfigured.run())
        assertTrue(unconfigured.emailMessages.trimCutoffs.isEmpty())
    }

    @Test
    fun `a database failure in the retention sweep does not fail a successful sync`() {
        val harness = harness(messages = (1L..5L).map { mailboxMessage(uid = it) })
        harness.emailMessages.failTrim = true

        val outcome = harness.run()

        // The mail is committed, the watermark is written and the run row is closed before
        // the sweep runs; turning that into a retry would re-fetch the whole window because
        // housekeeping could not write.
        assertTrue(outcome is EmailSyncOutcome.Completed)
        assertEquals(5, harness.committer.logged.size)
        assertEquals(5L, harness.configStore.value.lastSyncedUid)
        assertNull(harness.syncRuns.rows.single().errorMessage)
        assertTrue(
            EmailSyncWorker.resultFor(outcome, runAttemptCount = 0)
                is ListenableWorker.Result.Success
        )
    }

    // -- harness ---------------------------------------------------------------------------------

    private fun harness(
        messages: List<FetchedMessage>,
        config: EmailAccountConfig = testConfig(),
        credentials: FakeEmailCredentialStore = FakeEmailCredentialStore.configured()
    ): Harness = Harness(
        client = FakeMailboxClient(messages = messages),
        // extraSenders is what puts the fixture's domain into the server-side search (step 6).
        configStore = FakeEmailAccountConfigStore(config.copy(extraSenders = listOf(bankDomain))),
        credentials = credentials,
        clock = clock
    )

    private class Harness(
        val client: FakeMailboxClient,
        val configStore: FakeEmailAccountConfigStore,
        val credentials: FakeEmailCredentialStore,
        clock: Clock
    ) {
        val committer = RecordingCommitter()
        val syncRuns = RecordingSyncRunDao()
        val emailMessages = RecordingEmailMessageDao()

        private val runner = EmailSyncRunner(
            configStore = configStore,
            credentialStore = credentials,
            mailbox = client,
            committer = committer,
            syncRuns = syncRuns,
            emailMessages = emailMessages,
            clock = clock
        )

        fun run(
            trigger: SyncTrigger = SyncTrigger.PERIODIC,
            shouldStop: () -> Boolean = { false }
        ): EmailSyncOutcome = runBlocking { runner.run(trigger, shouldStop) }
    }

    /** The same mail after a folder rebuild: new UID, same Message-ID, same body. */
    private fun renumber(message: FetchedMessage, uid: Long): FetchedMessage =
        message.copy(header = message.header.copy(uid = uid))

    /**
     * `email_messages` in a map, with the two identity keys of R-11-08 and nothing else.
     *
     * [failOnBatch] models a transaction that rolls back: the batch was attempted and nothing
     * from it survives, which is exactly the state the watermark must respect.
     */
    private class RecordingCommitter : EmailBatchCommitter {
        val logged = LinkedHashMap<Long, FetchedMessage>()
        private val messageIds = mutableSetOf<String>()

        /** The UIDs of each batch handed over, in order - one entry per commit attempt. */
        val batches = mutableListOf<List<Long>>()

        /** UIDs the client could not read, as the worker passed them on. */
        val unreadableSeen = mutableListOf<Long?>()

        var failOnBatch: Int? = null

        override suspend fun commitBatch(
            mailbox: String,
            messages: List<FetchedMessage>,
            unreadable: List<UnreadableMessage>,
            config: EmailAccountConfig
        ): EmailBatchCounts {
            batches += messages.map { it.header.uid }
            // Recorded before the failure, like a transaction that was attempted and rolled
            // back: the batch happened, nothing from it survived.
            if (batches.size == failOnBatch) error("room refused the transaction")

            unreadableSeen += unreadable.map { it.uid }

            var loggedNow = 0
            var alreadyKnown = 0
            for (message in messages) {
                val messageId = message.header.messageId
                val known = logged.containsKey(message.header.uid) ||
                    (messageId != null && messageId in messageIds)
                if (known) {
                    alreadyKnown++
                    continue
                }
                logged[message.header.uid] = message
                if (messageId != null) messageIds += messageId
                loggedNow++
            }
            return EmailBatchCounts(
                logged = loggedNow,
                alreadyKnown = alreadyKnown,
                errors = unreadable.size
            )
        }
    }

    /** `sync_runs` in a list, so a test can see the row while it is still open. */
    private class RecordingSyncRunDao : SyncRunDao {
        val rows = mutableListOf<SyncRunEntity>()

        override suspend fun insert(run: SyncRunEntity): Long {
            val id = rows.size + 1L
            rows += run.copy(id = id)
            return id
        }

        override suspend fun update(run: SyncRunEntity) {
            val index = rows.indexOfFirst { it.id == run.id }
            if (index >= 0) rows[index] = run else rows += run
        }

        override suspend fun getById(id: Long): SyncRunEntity? = rows.firstOrNull { it.id == id }

        override suspend fun getLastSuccessful(channel: SyncChannel): SyncRunEntity? =
            rows.lastOrNull { it.channel == channel && it.finishedAt != null && it.errorMessage == null }

        override fun observeLastSuccessful(channel: SyncChannel): Flow<SyncRunEntity?> = emptyFlow()

        override fun observeLast(channel: SyncChannel): Flow<SyncRunEntity?> = emptyFlow()

        override fun observeRecent(limit: Int): Flow<List<SyncRunEntity>> = emptyFlow()

        override fun observeRecentForChannel(
            channel: SyncChannel,
            limit: Int
        ): Flow<List<SyncRunEntity>> = emptyFlow()

        override suspend fun getAbandoned(before: String): List<SyncRunEntity> =
            rows.filter { it.finishedAt == null }

        override suspend fun recentOutcomes(channel: SyncChannel, limit: Int): List<String?> =
            rows.filter { it.channel == channel }.takeLast(limit).map { it.errorMessage }

        override suspend fun deleteBefore(before: String): Int = 0

        override suspend fun deleteAll() {
            rows.clear()
        }
    }

    /**
     * The email log as the *runner* sees it - which is only the retention sweep of spec 11
     * section 11.2. Everything the log is actually written through goes via
     * [EmailBatchCommitter], so the rest of this interface is deliberately inert here.
     */
    private class RecordingEmailMessageDao : EmailMessageDao {

        /** One entry per [trimBodiesBefore] call, in order, exactly as the runner formatted it. */
        val trimCutoffs = mutableListOf<String>()

        /** Models Room refusing the UPDATE - a full disk, a locked database. */
        var failTrim = false

        override suspend fun trimBodiesBefore(before: String): Int {
            trimCutoffs += before
            if (failTrim) error("room refused the update")
            return 0
        }

        override suspend fun insert(message: EmailMessageEntity): Long = 0
        override suspend fun update(message: EmailMessageEntity) = Unit
        override suspend fun getById(id: Long): EmailMessageEntity? = null
        override suspend fun getByUid(mailbox: String, uid: Long): EmailMessageEntity? = null
        override suspend fun getByMessageId(messageId: String): EmailMessageEntity? = null
        override suspend fun countByUid(mailbox: String, uid: Long): Int = 0
        override suspend fun maxUid(mailbox: String): Long? = null
        override suspend fun count(): Int = 0
        override fun countFlow(): Flow<Int> = emptyFlow()
        override fun observeRecent(limit: Int, offset: Int): Flow<List<EmailMessageEntity>> =
            emptyFlow()

        override suspend fun getUnmatched(): List<EmailMessageEntity> = emptyList()
        override fun observeBySkipReason(pattern: String): Flow<List<EmailMessageEntity>> =
            emptyFlow()

        override suspend fun getByTransactionId(transactionId: Long): List<EmailMessageEntity> =
            emptyList()

        override suspend fun deleteAll() = Unit
    }
}
