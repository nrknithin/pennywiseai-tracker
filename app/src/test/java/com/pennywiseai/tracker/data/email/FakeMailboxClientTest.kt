package com.pennywiseai.tracker.data.email

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant

/**
 * The fake is the fixture every sync test runs against, so its own behaviour has to be
 * pinned: a fake that quietly returns unsorted UIDs, or that never fails, would let a broken
 * worker pass.
 *
 * These tests also state the parts of the [MailboxSession] contract the real client is held
 * to — empty search terms fetch nothing, a vanished UID is absent rather than null, results
 * come back in the order asked for.
 */
class FakeMailboxClientTest {

    private fun <T> session(
        client: FakeMailboxClient,
        config: EmailAccountConfig = testConfig(),
        block: suspend (MailboxSession) -> T
    ): T = runBlocking { client.withSession(config, TEST_PASSWORD, block) }

    // -- searching ----------------------------------------------------------------------

    @Test
    fun `search returns only senders a term matches`() {
        val client = FakeMailboxClient(
            messages = listOf(
                mailboxMessage(uid = 1),
                mailboxMessage(uid = 2, from = UNRELATED_SENDER),
                mailboxMessage(uid = 3)
            )
        )

        val uids = session(client) { it.searchUids(TEST_SEARCH_TERMS, sinceUid = 0, receivedSince = null, limit = 100) }

        // R-11-03: the unrelated message is never even a candidate, so it is never downloaded.
        assertEquals(listOf(1L, 3L), uids)
    }

    @Test
    fun `search with no terms returns nothing rather than the whole mailbox`() {
        val client = FakeMailboxClient(messages = listOf(mailboxMessage(uid = 1)))

        val uids = session(client) { it.searchUids(emptyList(), sinceUid = 0, receivedSince = null, limit = 100) }

        // A FROM-less IMAP search matches everything. Answering "nothing" is the only safe
        // reading of R-11-03 if the registry ever comes back empty.
        assertEquals(emptyList<Long>(), uids)
    }

    @Test
    fun `search above a watermark ignores the received-since bound`() {
        val old = TEST_NOW.minusSeconds(400L * 24 * 3600)
        val client = FakeMailboxClient(
            messages = listOf(
                mailboxMessage(uid = 5, receivedAt = old),
                mailboxMessage(uid = 6, receivedAt = old)
            )
        )

        val uids = session(client) {
            // An incremental run is bounded by the UID window, not by the lookback: spec 11
            // §11.3 step 7 only applies the date term on the lastUid == 0 branch.
            it.searchUids(TEST_SEARCH_TERMS, sinceUid = 5, receivedSince = TEST_NOW, limit = 100)
        }

        assertEquals(listOf(6L), uids)
    }

    @Test
    fun `first run drops mail older than the lookback cutoff`() {
        val cutoff: Instant = TEST_NOW.minusSeconds(30L * 24 * 3600)
        val client = FakeMailboxClient(
            messages = listOf(
                mailboxMessage(uid = 1, receivedAt = cutoff.minusSeconds(1)),
                mailboxMessage(uid = 2, receivedAt = cutoff),
                mailboxMessage(uid = 3, receivedAt = TEST_NOW)
            )
        )

        val uids = session(client) { it.searchUids(TEST_SEARCH_TERMS, sinceUid = 0, receivedSince = cutoff, limit = 100) }

        // The bound is inclusive, as IMAP's SINCE is.
        assertEquals(listOf(2L, 3L), uids)
    }

    @Test
    fun `search caps at the limit and leaves the rest for the follow-up run`() {
        val client = FakeMailboxClient(messages = (1L..10L).map { mailboxMessage(uid = it) })

        val uids = session(client) { it.searchUids(TEST_SEARCH_TERMS, sinceUid = 0, receivedSince = null, limit = 4) }

        assertEquals(listOf(1L, 2L, 3L, 4L), uids)
    }

    // -- fetching -----------------------------------------------------------------------

    @Test
    fun `fetch returns messages in the order asked for and records what it handed out`() {
        val client = FakeMailboxClient(messages = (1L..3L).map { mailboxMessage(uid = it) })

        val fetched = session(client) { it.fetch(listOf(3L, 1L, 2L), maxBodyKb = 64) }

        assertEquals(listOf(3L, 1L, 2L), fetched.map { it.header.uid })
        assertEquals(listOf(3L, 1L, 2L), client.fetchedUids)
    }

    @Test
    fun `fetch skips a UID that vanished between search and fetch`() {
        val client = FakeMailboxClient(messages = listOf(mailboxMessage(uid = 1), mailboxMessage(uid = 3)))

        val fetched = session(client) { it.fetch(listOf(1L, 2L, 3L), maxBodyKb = 64) }

        // The user deleted UID 2 mid-run. It is absent, not a null and not an exception —
        // otherwise one deleted mail would cost the whole batch.
        assertEquals(listOf(1L, 3L), fetched.map { it.header.uid })
    }

    @Test
    fun `failAfter stops the fake part-way through a batch`() {
        val client = FakeMailboxClient(
            messages = (1L..5L).map { mailboxMessage(uid = it) },
            failAfter = 2,
            failWith = { MailboxException.Network(IOException()) }
        )

        assertThrows(MailboxException.Network::class.java) {
            session(client) { it.fetch(listOf(1L, 2L, 3L, 4L, 5L), maxBodyKb = 64) }
        }

        // Two messages really did cross the wire before the failure; the caller saw none of
        // them because its batch never committed.
        assertEquals(listOf(1L, 2L), client.fetchedUids)
    }

    // -- session lifecycle and error mapping ---------------------------------------------

    @Test
    fun `a revoked password throws Auth before anything is fetched`() {
        val client = FakeMailboxClient(
            messages = (1L..3L).map { mailboxMessage(uid = it) },
            connectionTest = ConnectionTest.AuthFailed
        )

        assertThrows(MailboxException.Auth::class.java) {
            session(client) { it.searchUids(TEST_SEARCH_TERMS, 0, null, 100) }
        }

        assertEquals(emptyList<Long>(), client.fetchedUids)
        assertEquals(0, client.sessionsOpened)
    }

    @Test
    fun `a missing folder throws FolderMissing carrying the folder name`() {
        val client = FakeMailboxClient(
            connectionTest = ConnectionTest.FolderMissing("[Gmail]/Archive", listOf("INBOX"))
        )

        val thrown = assertThrows(MailboxException.FolderMissing::class.java) {
            session(client) { it.messageCount }
        }

        assertEquals("[Gmail]/Archive", thrown.folder)
    }

    @Test
    fun `a network failure throws Network`() {
        val client = FakeMailboxClient(connectionTest = ConnectionTest.NetworkError("SocketTimeoutException"))

        assertThrows(MailboxException.Network::class.java) {
            session(client) { it.messageCount }
        }
    }

    @Test
    fun `the session reports the folder, its uidvalidity and its size`() {
        val client = FakeMailboxClient(
            messages = (1L..4L).map { mailboxMessage(uid = it) },
            uidValidity = 42
        )
        val config = testConfig(folder = "[Gmail]/All Mail")

        val snapshot = session(client, config) { Triple(it.mailbox, it.uidValidity, it.messageCount) }

        assertEquals("[Gmail]/All Mail", snapshot.first)
        assertEquals(42L, snapshot.second)
        assertEquals(4, snapshot.third)
    }

    @Test
    fun `uidvalidity is snapshotted at open so a mid-run change is not seen`() {
        val client = FakeMailboxClient(uidValidity = 1)

        val seen = session(client) { openSession ->
            client.uidValidity = 2
            openSession.uidValidity
        }

        // A real server answers UIDVALIDITY once, in the SELECT response. Re-reading it would
        // let a test observe a reset the worker could not have seen.
        assertEquals(1L, seen)
        assertEquals(2L, session(client) { it.uidValidity })
    }

    @Test
    fun `listFolders answers the configured folder list`() {
        val client = FakeMailboxClient(
            connectionTest = ConnectionTest.Ok("[Gmail]/All Mail", 12, listOf("INBOX", "[Gmail]/All Mail"))
        )

        assertEquals(listOf("INBOX", "[Gmail]/All Mail"), session(client) { it.listFolders() })
    }

    @Test
    fun `testConnection answers the configured verdict without opening a session`() {
        val ok = ConnectionTest.Ok("[Gmail]/All Mail", 7, listOf("INBOX"))
        val client = FakeMailboxClient(connectionTest = ok)

        val result = runBlocking { client.testConnection(testConfig(), TEST_PASSWORD) }

        assertEquals(ok, result)
        assertEquals(0, client.sessionsOpened)
        assertTrue(client.fetchedUids.isEmpty())
    }
}
