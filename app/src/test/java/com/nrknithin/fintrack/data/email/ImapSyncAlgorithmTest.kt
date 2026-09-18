package com.nrknithin.fintrack.data.email

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant

/**
 * Spec 11 §11.3 steps 5–21 driven against [FakeMailboxClient], with no network, no
 * credential store and no Room.
 *
 * `EmailSyncWorker` is the production owner of this algorithm; [SyncDriver] below is a
 * deliberately small restatement of it, because the mailbox client's whole reason to be an
 * interface is that these properties — resume, UIDVALIDITY reset, the cap and the follow-up —
 * can be asserted before a worker, a database or a device exists. When the worker lands it
 * runs the same client against the same fake and these become its regression net.
 */
class ImapSyncAlgorithmTest {

    // -- the algorithm under test --------------------------------------------------------

    /** Stands in for the watermark settings plus the `email_messages` table. */
    class SyncState {
        var lastSyncedUid: Long = 0
        var uidValidity: Long = 0

        /** Unique on (mailbox, uid), like the entity's index. */
        val logged = LinkedHashMap<Long, FetchedMessage>()

        /** The second identity key of R-11-08: survives a folder rebuild. */
        val loggedMessageIds = mutableSetOf<String>()

        var recognisedDuplicates = 0
        var watermarkResets = 0
    }

    data class SyncResult(
        val fetched: Int,
        val loggedNow: Int,
        /** Step 21: a full window means there is probably more, so the worker re-runs. */
        val hitCap: Boolean,
        val error: MailboxException? = null
    )

    object SyncDriver {
        fun run(
            client: FakeMailboxClient,
            state: SyncState,
            config: EmailAccountConfig,
            searchTerms: List<String> = TEST_SEARCH_TERMS,
            now: Instant = TEST_NOW
        ): SyncResult = runBlocking {
            var fetched = 0
            var loggedNow = 0
            var candidates = 0
            var failure: MailboxException? = null

            try {
                client.withSession(config, TEST_PASSWORD) { session ->
                    // Step 5. Gmail rebuilt the folder: every UID we hold is meaningless.
                    if (session.uidValidity != state.uidValidity) {
                        // Counted only when a real watermark is thrown away. A first sync
                        // moves from the unset 0 to the server's value, which discards
                        // nothing and is not what R-11-08 is about.
                        if (state.lastSyncedUid > 0) state.watermarkResets++
                        state.uidValidity = session.uidValidity
                        state.lastSyncedUid = 0
                    }

                    // Steps 6–9.
                    val uids = session.searchUids(
                        searchTerms = searchTerms,
                        sinceUid = state.lastSyncedUid,
                        receivedSince = config.lookbackCutoff(now),
                        limit = config.maxMessagesPerSync
                    )
                    candidates = uids.size

                    // Steps 10–18: one commit per batch, watermark last.
                    for (batch in uids.chunked(config.batchSize)) {
                        val messages = session.fetch(batch, config.maxBodyKb)
                        fetched += messages.size
                        for (message in messages) {
                            if (logIfNew(state, message)) loggedNow++
                        }
                        // R-11-07: the batch is committed, so the watermark may move to the
                        // highest UID *asked for* — a UID the server no longer has is done
                        // with, and leaving it below the watermark would re-search it forever.
                        state.lastSyncedUid = maxOf(state.lastSyncedUid, batch.maxOrNull() ?: 0L)
                    }
                }
            } catch (e: MailboxException) {
                // The worker turns this into a state plus retry/failure; here we only need to
                // know the run stopped and what it had already committed.
                failure = e
            }

            SyncResult(
                fetched = fetched,
                loggedNow = loggedNow,
                hitCap = candidates == config.maxMessagesPerSync,
                error = failure
            )
        }

        /** R-11-05 plus R-11-08: log before parsing, and recognise a message already logged. */
        private fun logIfNew(state: SyncState, message: FetchedMessage): Boolean {
            val id = message.header.messageId
            val known = state.logged.containsKey(message.header.uid) ||
                (id != null && id in state.loggedMessageIds)
            if (known) {
                state.recognisedDuplicates++
                return false
            }
            state.logged[message.header.uid] = message
            if (id != null) state.loggedMessageIds += id
            return true
        }
    }

    // -- R-11-03 / R-11-04: what a first run fetches ---------------------------------------

    @Test
    fun `first run logs registry senders inside the lookback and nothing else`() {
        val beyondLookback = TEST_NOW.minusSeconds(200L * 24 * 3600)
        val client = FakeMailboxClient(
            messages = listOf(
                mailboxMessage(uid = 1),
                mailboxMessage(uid = 2),
                mailboxMessage(uid = 3, from = UNRELATED_SENDER),
                mailboxMessage(uid = 4, receivedAt = beyondLookback),
                mailboxMessage(uid = 5)
            )
        )
        val state = SyncState()

        val result = SyncDriver.run(client, state, testConfig(initialLookbackDays = 180))

        assertNull(result.error)
        assertEquals(listOf(1L, 2L, 5L), client.fetchedUids)
        assertEquals(listOf(1L, 2L, 5L), state.logged.keys.toList())
        assertEquals(5L, state.lastSyncedUid)
        assertFalse(result.hitCap)
    }

    @Test
    fun `lookback zero means the whole folder`() {
        val ancient = TEST_NOW.minusSeconds(3000L * 24 * 3600)
        val client = FakeMailboxClient(messages = listOf(mailboxMessage(uid = 1, receivedAt = ancient)))
        val state = SyncState()

        // 0 is the user's "all mail" choice; lookbackCutoff answers null and no date bound is sent.
        SyncDriver.run(client, state, testConfig(initialLookbackDays = 0))

        assertEquals(listOf(1L), state.logged.keys.toList())
    }

    @Test
    fun `a second run downloads only what arrived above the watermark`() {
        val client = FakeMailboxClient(messages = (1L..3L).map { mailboxMessage(uid = it) })
        val state = SyncState()
        val config = testConfig()

        SyncDriver.run(client, state, config)
        val idle = SyncDriver.run(client, state, config)

        assertEquals(0, idle.fetched)
        assertEquals(listOf(1L, 2L, 3L), client.fetchedUids)

        client.messages = client.messages + mailboxMessage(uid = 4)
        val incremental = SyncDriver.run(client, state, config)

        assertEquals(1, incremental.fetched)
        assertEquals(listOf(1L, 2L, 3L, 4L), client.fetchedUids)
        assertEquals(4L, state.lastSyncedUid)
    }

    // -- AC-11-02: resume from the last committed batch -------------------------------------

    @Test
    fun `an interrupted run resumes from the last committed batch and leaves no gaps`() {
        val client = FakeMailboxClient(
            messages = (1L..120L).map { mailboxMessage(uid = it) },
            // Dies 10 messages into the second batch of 50.
            failAfter = 60,
            failWith = { MailboxException.Network(IOException()) }
        )
        val state = SyncState()
        val config = testConfig(maxMessagesPerSync = 500, batchSize = 50)

        val interrupted = SyncDriver.run(client, state, config)

        assertTrue(interrupted.error is MailboxException.Network)
        // Only the first batch committed, so only the first batch is logged and the watermark
        // sits exactly on its last UID.
        assertEquals(50, state.logged.size)
        assertEquals(50L, state.lastSyncedUid)

        client.failAfter = null
        val resumed = SyncDriver.run(client, state, config)

        assertNull(resumed.error)
        assertEquals((1L..120L).toList(), state.logged.keys.sorted())
        assertEquals(120L, state.lastSyncedUid)

        // Nothing that was committed is ever downloaded twice.
        val committedInRunOne = (1L..50L).toList()
        assertTrue(
            "committed messages were re-downloaded",
            committedInRunOne.all { uid -> client.fetchedUids.count { it == uid } == 1 }
        )
        // The uncommitted tail of the failed batch is downloaded again — that is the real
        // price of a resume, and stating it here stops someone "fixing" it by advancing the
        // watermark before the commit, which is the bug R-11-07 exists to prevent.
        assertTrue((51L..60L).all { uid -> client.fetchedUids.count { it == uid } == 2 })
    }

    @Test
    fun `a run that dies before the first commit re-downloads everything and loses nothing`() {
        val client = FakeMailboxClient(
            messages = (1L..10L).map { mailboxMessage(uid = it) },
            failAfter = 0
        )
        val state = SyncState()
        val config = testConfig(batchSize = 5)

        val failed = SyncDriver.run(client, state, config)

        assertTrue(failed.error is MailboxException.Network)
        assertEquals(0, state.logged.size)
        assertEquals(0L, state.lastSyncedUid)

        client.failAfter = null
        SyncDriver.run(client, state, config)

        assertEquals((1L..10L).toList(), state.logged.keys.sorted())
    }

    // -- R-11-08: UIDVALIDITY change --------------------------------------------------------

    @Test
    fun `a uidvalidity change resets the watermark and re-scans without duplicating messages`() {
        val original = (1L..5L).map { mailboxMessage(uid = it) }
        val client = FakeMailboxClient(messages = original, uidValidity = 1)
        val state = SyncState()
        val config = testConfig()

        SyncDriver.run(client, state, config)
        assertEquals(5L, state.lastSyncedUid)
        assertEquals(1L, state.uidValidity)

        // Gmail rebuilt the folder: same mail, new UIDs, new UIDVALIDITY.
        client.uidValidity = 2
        client.messages = original.map { renumber(it, it.header.uid + 100) }

        val afterReset = SyncDriver.run(client, state, config)

        assertNull(afterReset.error)
        assertEquals(2L, state.uidValidity)
        assertEquals(1, state.watermarkResets)
        // The mail really is re-downloaded — with the old UIDs meaningless there is no other
        // way to see it — but Message-ID recognition keeps the log at five rows.
        assertEquals(5, afterReset.fetched)
        assertEquals(0, afterReset.loggedNow)
        assertEquals(5, state.recognisedDuplicates)
        assertEquals(5, state.logged.size)
        assertEquals(105L, state.lastSyncedUid)
    }

    @Test
    fun `an unchanged uidvalidity never resets the watermark`() {
        val client = FakeMailboxClient(messages = (1L..3L).map { mailboxMessage(uid = it) }, uidValidity = 7)
        val state = SyncState()
        val config = testConfig()

        SyncDriver.run(client, state, config)
        SyncDriver.run(client, state, config)

        // Two runs, no reset: the first run only learned the server's UIDVALIDITY, it did not
        // throw a watermark away.
        assertEquals(0, state.watermarkResets)
        assertEquals(3L, state.lastSyncedUid)
    }

    // -- §11.3 steps 9 and 21: the per-run cap ---------------------------------------------

    @Test
    fun `maxMessagesPerSync caps a run and the follow-up picks up the remainder`() {
        val client = FakeMailboxClient(messages = (1L..120L).map { mailboxMessage(uid = it) })
        val state = SyncState()
        val config = testConfig(maxMessagesPerSync = 50, batchSize = 10)

        val first = SyncDriver.run(client, state, config)
        assertTrue("a full window must ask for a follow-up run", first.hitCap)
        assertEquals(50, state.logged.size)
        assertEquals(50L, state.lastSyncedUid)

        val second = SyncDriver.run(client, state, config)
        assertTrue(second.hitCap)
        assertEquals(100L, state.lastSyncedUid)

        val third = SyncDriver.run(client, state, config)
        assertFalse("a short window means the mailbox is drained", third.hitCap)
        assertEquals((1L..120L).toList(), state.logged.keys.toList())

        // Three runs, 120 messages, each fetched exactly once.
        assertEquals(120, client.fetchedUids.size)
        assertEquals(120, client.fetchedUids.distinct().size)
    }

    // -- error states ----------------------------------------------------------------------

    @Test
    fun `a revoked app password stops the run before anything is logged`() {
        val client = FakeMailboxClient(
            messages = (1L..5L).map { mailboxMessage(uid = it) },
            connectionTest = ConnectionTest.AuthFailed
        )
        val state = SyncState()

        val result = SyncDriver.run(client, state, testConfig())

        // AC-11-03: the worker turns this into state AUTH_FAILED with no retry.
        assertTrue(result.error is MailboxException.Auth)
        assertEquals(0, state.logged.size)
        assertEquals(0L, state.lastSyncedUid)
        assertTrue(client.fetchedUids.isEmpty())
    }

    @Test
    fun `a missing folder stops the run and keeps the watermark`() {
        val client = FakeMailboxClient(messages = (1L..5L).map { mailboxMessage(uid = it) })
        val state = SyncState()
        val config = testConfig()

        SyncDriver.run(client, state, config)
        val watermark = state.lastSyncedUid

        client.connectionTest = ConnectionTest.FolderMissing(config.folder, listOf("INBOX"))
        val result = SyncDriver.run(client, state, config)

        assertTrue(result.error is MailboxException.FolderMissing)
        // Nothing is dropped because a folder went missing: a fixed folder name resumes here.
        assertEquals(watermark, state.lastSyncedUid)
        assertEquals(5, state.logged.size)
    }

    // -- helpers ---------------------------------------------------------------------------

    /** The same mail after a folder rebuild: new UID, same Message-ID and same body. */
    private fun renumber(message: FetchedMessage, uid: Long): FetchedMessage =
        message.copy(header = message.header.copy(uid = uid))
}
