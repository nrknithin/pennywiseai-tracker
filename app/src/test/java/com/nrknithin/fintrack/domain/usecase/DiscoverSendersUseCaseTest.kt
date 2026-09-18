package com.nrknithin.fintrack.domain.usecase

import com.nrknithin.fintrack.data.database.FinTrackDatabase
import com.nrknithin.fintrack.data.database.RoomTestDb
import com.nrknithin.fintrack.data.database.dao.SenderOverrideDao
import com.nrknithin.fintrack.data.database.entity.SenderOverrideStatus
import com.nrknithin.fintrack.data.database.entity.SyncChannel
import com.nrknithin.fintrack.data.database.entity.SyncTrigger
import com.nrknithin.fintrack.data.email.ConnectionTest
import com.nrknithin.fintrack.data.email.EmailAccountConfig
import com.nrknithin.fintrack.data.email.EmailAccountState
import com.nrknithin.fintrack.data.email.FakeEmailAccountConfigStore
import com.nrknithin.fintrack.data.email.FakeEmailCredentialStore
import com.nrknithin.fintrack.data.email.FakeMailboxClient
import com.nrknithin.fintrack.data.email.MERCHANT_SENDER
import com.nrknithin.fintrack.data.email.MERCHANT_SENDER_ALT
import com.nrknithin.fintrack.data.email.TEST_DISCOVERY_QUERY
import com.nrknithin.fintrack.data.email.TEST_NOW
import com.nrknithin.fintrack.data.email.mailboxMessage
import com.nrknithin.fintrack.data.repository.SenderOverrideRepository
import com.nrknithin.fintrack.domain.engine.SenderDiscovery
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.ZoneOffset

/**
 * One discovery pass, end to end, with no device and no credential.
 *
 * The pass is the one place this feature can quietly do the wrong thing, and the two failures
 * worth pinning are both invisible from outside. It must **never advance the sync watermark**:
 * its UID set is chosen by a Gmail category rather than by a contiguous UID range, so a
 * watermark moved here would step over mail whose body was never downloaded, and no later run
 * would look below it again (R-11-07). And it must **never write `settings.email.state`**: a
 * Gmail-only extension failing must not put an AUTH_FAILED banner on Home for a mail channel
 * that is working perfectly well.
 *
 * Asserted against the real `sender_overrides` and `sync_runs` tables rather than fakes,
 * because what matters is the state the pass leaves the database in, not which methods it
 * called on the way.
 *
 * Every sender here is invented and uses a reserved example domain, except the one that has to
 * be a sender the compiled registry really knows — there is no way to test "already known"
 * without one (spec 06 §6.2).
 */
@RunWith(RobolectricTestRunner::class)
class DiscoverSendersUseCaseTest {

    private lateinit var db: FinTrackDatabase
    private lateinit var overrideDao: SenderOverrideDao

    private val clock: Clock = Clock.fixed(TEST_NOW, ZoneOffset.UTC)

    @Before
    fun openDatabase() {
        db = RoomTestDb.open()
        overrideDao = db.senderOverrideDao()
    }

    @After
    fun closeDatabase() {
        db.close()
    }

    // -------------------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------------------

    /**
     * An institution the compiled `EmailSenderRegistry` already resolves — a bank, not a
     * person, which is why `PiiLintTest` allows it and why it can stand for "the 467".
     */
    private val knownSender = "alerts@axis.bank.in"

    /**
     * The key both merchant mailboxes group under.
     *
     * Note that it is *not* `shop.example.net`: `SenderDisplayName.registrableDomain` keeps the
     * last two labels unless the last two are a listed multi-label suffix, so a subdomain
     * collapses to its parent. That is the heuristic working as documented — a merchant that
     * mails from `orders@` and `noreply@` under one parent is one sender, not two — and it is
     * asserted here rather than assumed, because the key discovery writes has to be the key
     * §11.6 later resolves against.
     */
    private val merchantDomain = "example.net"

    /**
     * A mailbox whose Purchases category holds three messages: two from one unknown merchant
     * and one from a bank the registry knows. The split is the whole measurement in miniature.
     */
    private fun gmailMailbox(supportsGmailRawSearch: Boolean = true) = FakeMailboxClient(
        messages = listOf(
            mailboxMessage(uid = 1, from = MERCHANT_SENDER),
            mailboxMessage(uid = 2, from = MERCHANT_SENDER_ALT),
            mailboxMessage(uid = 3, from = knownSender)
        ),
        supportsGmailRawSearch = supportsGmailRawSearch,
        rawSearchHits = mapOf(TEST_DISCOVERY_QUERY to setOf(1L, 2L, 3L)),
        // Only one of the two merchant mailboxes names itself. The group still gets the name,
        // which is the whole reason the phrase is carried out of the ENVELOPE at all.
        displayPhrases = mapOf(1L to "Shop Example")
    )

    private fun config(
        discoveryEnabled: Boolean = true,
        address: String = "someone@example.com",
        lastSyncedUid: Long = 400
    ) = EmailAccountConfig(
        address = address,
        discoveryEnabled = discoveryEnabled,
        discoveryQuery = TEST_DISCOVERY_QUERY,
        lastSyncedUid = lastSyncedUid,
        uidValidity = 1
    )

    private class Fixture(
        val client: FakeMailboxClient,
        val configStore: FakeEmailAccountConfigStore,
        val credentials: FakeEmailCredentialStore,
        private val useCase: DiscoverSendersUseCase
    ) {
        fun run(trigger: SyncTrigger = SyncTrigger.PERIODIC): DiscoveryOutcome =
            runBlocking { useCase(trigger) }
    }

    private fun fixture(
        client: FakeMailboxClient = gmailMailbox(),
        config: EmailAccountConfig = config(),
        credentials: FakeEmailCredentialStore = FakeEmailCredentialStore.configured()
    ): Fixture {
        val configStore = FakeEmailAccountConfigStore(config)
        return Fixture(
            client = client,
            configStore = configStore,
            credentials = credentials,
            useCase = DiscoverSendersUseCase(
                configStore = configStore,
                credentialStore = credentials,
                mailbox = client,
                discovery = SenderDiscovery(clock),
                overrides = repository(),
                syncRuns = db.syncRunDao(),
                clock = clock
            )
        )
    }

    private fun repository() = SenderOverrideRepository(overrideDao, clock)

    /** The `error_message` of every finished DISCOVERY run, newest first. */
    private fun discoveryRuns(): List<String?> = runBlocking {
        db.syncRunDao().recentOutcomes(SyncChannel.DISCOVERY, limit = 50)
    }

    private fun storedRows() = runBlocking { overrideDao.observeAll().first() }

    // -------------------------------------------------------------------------------------
    // The happy path
    // -------------------------------------------------------------------------------------

    @Test
    fun `a scan proposes the unknown senders and counts the known ones`() {
        val outcome = fixture().run()

        val completed = outcome as DiscoveryOutcome.Completed
        assertEquals(3, completed.report.scannedCount)
        assertEquals("The bank is already in the registry", 1, completed.report.knownCount)
        assertEquals(1, completed.report.discovered.size)
        assertEquals(1, completed.proposed)

        val proposed = runBlocking { overrideDao.byKey(merchantDomain) }
        assertNotNull("The merchant's registrable domain is what gets proposed", proposed)
        assertEquals(SenderOverrideStatus.PROPOSED, proposed!!.status)
        assertEquals("Shop Example", proposed.displayName)
        assertEquals("Both mailboxes under the domain count", 2, proposed.messageCount)
    }

    @Test
    fun `a proposal does not widen the search until the owner accepts it`() {
        fixture().run()

        assertTrue(
            "A proposal the owner has not seen must not reach the OR(FROM …) of R-11-03",
            runBlocking { overrideDao.active() }.isEmpty()
        )

        runBlocking { repository().accept(merchantDomain) }

        assertEquals(
            listOf(merchantDomain),
            runBlocking { overrideDao.active() }.map { it.addressOrDomain }
        )
    }

    @Test
    fun `the pass reads envelopes and never a body`() {
        val fixture = fixture()
        fixture.run()

        assertEquals(listOf(1L, 2L, 3L), fixture.client.peekedUids)
        assertTrue(
            "Discovery may look at who wrote, never at what they wrote — and a body fetch " +
                "would also set the Seen flag in the owner's real mailbox (constraint C2)",
            fixture.client.fetchedUids.isEmpty()
        )
    }

    @Test
    fun `the sync watermark is untouched`() {
        val fixture = fixture(config = config(lastSyncedUid = 400))

        fixture.run()

        assertTrue(
            "A discovery pass must never write a watermark: its UID set is not the contiguous " +
                "range R-11-07 makes one safe over",
            fixture.configStore.watermarkWrites.isEmpty()
        )
        assertEquals(400L, fixture.configStore.value.lastSyncedUid)
    }

    @Test
    fun `a completed pass records when it ran`() {
        val fixture = fixture()
        assertNull(fixture.configStore.value.lastDiscoveryAt)

        fixture.run()

        assertEquals(TEST_NOW, fixture.configStore.value.lastDiscoveryAt)
    }

    @Test
    fun `a second scan re-proposes nothing and leaves one row`() {
        val fixture = fixture()
        fixture.run()
        fixture.run()

        assertEquals(1, storedRows().size)
        assertEquals(1, runBlocking { overrideDao.countByStatus(SenderOverrideStatus.PROPOSED) })
    }

    @Test
    fun `a declined sender is not offered again`() {
        val fixture = fixture()
        fixture.run()
        runBlocking { repository().decline(merchantDomain) }

        val second = fixture.run() as DiscoveryOutcome.Completed

        assertTrue(
            "A declined sender is dropped from the report entirely — not proposed, and not " +
                "counted as known either, because the owner refusing it is not the registry " +
                "knowing it",
            second.report.discovered.isEmpty()
        )
        assertEquals(1, second.report.knownCount)
        assertEquals(
            SenderOverrideStatus.DECLINED,
            runBlocking { overrideDao.byKey(merchantDomain) }!!.status
        )
    }

    @Test
    fun `an accepted sender counts as known rather than being offered again`() {
        val fixture = fixture()
        fixture.run()
        runBlocking { repository().accept(merchantDomain) }

        val second = fixture.run() as DiscoveryOutcome.Completed

        assertTrue(second.report.discovered.isEmpty())
        assertEquals("Every sender in the window is now known", 3, second.report.knownCount)
    }

    // -------------------------------------------------------------------------------------
    // The endings that are not failures
    // -------------------------------------------------------------------------------------

    @Test
    fun `a plain IMAP server is not supported and is not an error`() {
        val fixture = fixture(client = gmailMailbox(supportsGmailRawSearch = false))

        assertEquals(DiscoveryOutcome.NotSupported, fixture.run())
        assertTrue(storedRows().isEmpty())
        assertNull(
            "A pass that could not run has not swept the mailbox, so it must not claim to have",
            fixture.configStore.value.lastDiscoveryAt
        )
        assertEquals(listOf("NOT_SUPPORTED"), discoveryRuns())
    }

    @Test
    fun `discovery switched off does not even open a run row`() {
        val fixture = fixture(config = config(discoveryEnabled = false))

        assertEquals(DiscoveryOutcome.Disabled, fixture.run())
        assertEquals(
            "A log full of \"disabled\" rows would bury the scans that ran",
            emptyList<String?>(),
            discoveryRuns()
        )
        assertEquals(0, fixture.credentials.borrowCount)
        assertEquals(0, fixture.client.sessionsOpened)
    }

    @Test
    fun `no mailbox connected is not configured, and nothing is contacted`() {
        val fixture = fixture(
            config = config(address = ""),
            credentials = FakeEmailCredentialStore()
        )

        assertEquals(DiscoveryOutcome.NotConfigured, fixture.run())
        assertEquals(0, fixture.client.sessionsOpened)
        assertEquals(emptyList<String?>(), discoveryRuns())
    }

    // -------------------------------------------------------------------------------------
    // Failure
    // -------------------------------------------------------------------------------------

    @Test
    fun `an auth failure is a failed scan and not a banner`() {
        val client = gmailMailbox()
        client.connectionTest = ConnectionTest.AuthFailed
        val fixture = fixture(client = client)

        assertEquals(DiscoveryOutcome.Failed("AUTH_FAILED"), fixture.run())
        assertTrue(
            "A Gmail-only extension failing must not put a banner on Home for a mail channel " +
                "that is working (spec 11 §11.7)",
            fixture.configStore.stateWrites.isEmpty()
        )
        assertEquals(EmailAccountState.NOT_CONFIGURED, fixture.configStore.value.state)
        assertEquals(listOf("ERROR:AUTH_FAILED"), discoveryRuns())
        assertNull(fixture.configStore.value.lastDiscoveryAt)
    }

    @Test
    fun `a network failure names the cause's class and nothing else`() {
        val client = gmailMailbox()
        client.connectionTest = ConnectionTest.NetworkError("SocketTimeoutException")
        val fixture = fixture(client = client)

        val outcome = fixture.run() as DiscoveryOutcome.Failed

        // The fake wraps the detail in an IOException, exactly as the real client wraps a
        // provider failure — so what reaches the log is a class name, never a server message
        // that could quote the login (spec 04 §4.1).
        assertEquals("IOException", outcome.detail)
        assertTrue(fixture.configStore.stateWrites.isEmpty())
    }

    // -------------------------------------------------------------------------------------
    // The run row
    // -------------------------------------------------------------------------------------

    @Test
    fun `the run row reads the shared counters as discovery's own`() {
        fixture().run(SyncTrigger.MANUAL)

        val run = runBlocking { db.syncRunDao().getLastSuccessful(SyncChannel.DISCOVERY) }
        assertNotNull(run)
        assertEquals(SyncTrigger.MANUAL, run!!.trigger)
        assertEquals("sightings peeked", 3, run.fetched)
        assertEquals("senders already known", 1, run.skipped)
        assertEquals("proposals recorded", 1, run.parsedTxn)
        // Discovery creates no transaction, no due and no receipt, and is not watermarked.
        assertEquals(0, run.duesUpserted)
        assertEquals(0, run.receipts)
        assertEquals(0, run.linkedTwin)
        assertNull("Writing a UID here would look like a watermark to every reader", run.lastUid)
        assertNotNull(run.finishedAt)
    }
}
