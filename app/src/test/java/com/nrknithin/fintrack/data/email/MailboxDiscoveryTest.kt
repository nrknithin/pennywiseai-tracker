package com.nrknithin.fintrack.data.email

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The sender-discovery search, driven against the fake on both kinds of server.
 *
 * The feature exists because of a measurement: 687 messages in the owner's Gmail Purchases
 * category over a year, 467 of them from senders the registry already knows, 220 unaccounted
 * for. `X-GM-RAW category:purchases` is how the app asks Gmail the same question, and
 * everything below is about the two ways that can go wrong.
 *
 * **Gmail is not IMAP.** `X-GM-RAW` is a Gmail extension; on any other server the command
 * does not exist. So the capability is checked, the search degrades to the registry's own
 * `OR(FROM …)`, and — the part that needs a test rather than a comment — the degraded path is
 * *silent*: a plain IMAP server is a supported configuration, not a failure.
 *
 * **A widened search must not become a widened download.** Discovery asks about senders
 * nobody has approved, so it may read their envelopes and nothing else (R-11-03, and
 * constraint C2 for the flag a body fetch would set). The fake keeps [FakeMailboxClient.peekedUids]
 * apart from [FakeMailboxClient.fetchedUids] precisely so that bound can be asserted rather
 * than assumed.
 */
class MailboxDiscoveryTest {

    private fun <T> session(
        client: FakeMailboxClient,
        block: suspend (MailboxSession) -> T
    ): T = runBlocking { client.withSession(testConfig(), TEST_PASSWORD, block) }

    /**
     * A folder holding two known-bank messages, two from one unknown merchant, and one from a
     * sender neither search should ever return.
     *
     * The registry terms match UIDs 1 and 3; Gmail's Purchases classification is modelled as
     * matching 2 and 4. That the two sets are disjoint is what makes the union visible.
     */
    private fun mailbox(
        supportsGmailRawSearch: Boolean,
        merchantAltReceivedAt: Instant = TEST_NOW.minusSeconds(60L * 4)
    ) = FakeMailboxClient(
        messages = listOf(
            mailboxMessage(uid = 1, from = BANK_SENDER),
            mailboxMessage(uid = 2, from = MERCHANT_SENDER),
            mailboxMessage(uid = 3, from = BANK_SENDER),
            mailboxMessage(uid = 4, from = MERCHANT_SENDER_ALT, receivedAt = merchantAltReceivedAt),
            mailboxMessage(uid = 5, from = UNRELATED_SENDER)
        ),
        supportsGmailRawSearch = supportsGmailRawSearch,
        rawSearchHits = mapOf(TEST_DISCOVERY_QUERY to setOf(2L, 4L)),
        displayPhrases = mapOf(2L to "Shop", 4L to "Shop Orders")
    )

    // -- the capability -------------------------------------------------------------------

    @Test
    fun `a plain IMAP server says it cannot be asked, and answers null rather than empty`() {
        val client = mailbox(supportsGmailRawSearch = false)

        val uids = session(client) { session ->
            assertTrue(
                "a server with no X-GM-EXT-1 must not claim the capability",
                !session.supportsGmailRawSearch
            )
            session.searchRaw(TEST_DISCOVERY_QUERY, receivedSince = null, limit = 100)
        }

        // Null, not emptyList(). Spec 01 constraint C7 applied to a capability: an empty list
        // would say "asked, nothing matched", and the screen would report a clean scan of a
        // mailbox that was never scanned.
        assertNull(uids)
    }

    @Test
    fun `a Gmail-like server answers the query, ascending`() {
        val client = mailbox(supportsGmailRawSearch = true)

        val uids = session(client) { session ->
            assertTrue(session.supportsGmailRawSearch)
            session.searchRaw(TEST_DISCOVERY_QUERY, receivedSince = null, limit = 100)
        }

        assertEquals(listOf(2L, 4L), uids)
    }

    @Test
    fun `a Gmail-like server that matches nothing answers an empty list, not null`() {
        val client = mailbox(supportsGmailRawSearch = true)

        val uids = session(client) {
            it.searchRaw("category:social", receivedSince = null, limit = 100)
        }

        // The other half of the C7 distinction: this server *was* asked. "No new senders" and
        // "discovery needs Gmail" are different sentences on screen 47, and collapsing the two
        // answers into one would make them indistinguishable.
        assertNotNull(uids)
        assertEquals(emptyList<Long>(), uids)
    }

    // -- the command line -----------------------------------------------------------------

    @Test
    fun `a query carrying a newline is refused rather than written into the command line`() {
        val client = mailbox(supportsGmailRawSearch = true)

        val injected = session(client) {
            it.searchRaw("category:purchases\r\nA2 STORE 1 +FLAGS (\\Deleted)", null, 100)
        }

        // The query is interpolated into an IMAP command line, so a newline in it is a second
        // command smuggled onto the wire under this session's login — and the one written here
        // is the exact thing R-11-02 forbids the app from ever doing to the owner's mailbox.
        // Refused, not escaped: no legitimate Gmail query contains a newline.
        assertNull(injected)
        assertNull(session(client) { it.searchRaw("only\nnewline", null, 100) })
    }

    @Test
    fun `a blank query is refused, because X-GM-RAW with nothing in it matches everything`() {
        val client = mailbox(supportsGmailRawSearch = true)

        assertNull(session(client) { it.searchRaw("", null, 100) })
        assertNull(session(client) { it.searchRaw("   ", null, 100) })
    }

    @Test
    fun `a non-positive limit is refused, the way an empty term list is`() {
        val client = mailbox(supportsGmailRawSearch = true)

        assertNull(session(client) { it.searchRaw(TEST_DISCOVERY_QUERY, null, limit = 0) })
    }

    // -- the window -----------------------------------------------------------------------

    @Test
    fun `the raw search is bounded by the received-since date`() {
        val old = TEST_NOW.minusSeconds(400L * 24 * 3600)
        val client = mailbox(supportsGmailRawSearch = true, merchantAltReceivedAt = old)

        val uids = session(client) {
            it.searchRaw(TEST_DISCOVERY_QUERY, receivedSince = TEST_NOW.minusSeconds(30L * 24 * 3600), limit = 100)
        }

        // Unlike searchUids there is no watermark branch that can switch this bound off:
        // discovery re-scans the same window every run, so the date is the only thing keeping
        // a one-year lookback from becoming an all-time one.
        assertEquals(listOf(2L), uids)
    }

    @Test
    fun `the raw search is capped at the limit, oldest first`() {
        val client = mailbox(supportsGmailRawSearch = true)

        val uids = session(client) { it.searchRaw(TEST_DISCOVERY_QUERY, null, limit = 1) }

        // The same MailboxUidWindow arithmetic the registry search uses, so one scan can never
        // be unbounded however many messages the query matches.
        assertEquals(listOf(2L), uids)
    }

    // -- the union, and the fallback ------------------------------------------------------

    @Test
    fun `the candidate set is the union of the raw search and the registry search`() {
        val client = mailbox(supportsGmailRawSearch = true)

        val uids = session(client) {
            it.discoveryCandidateUids(TEST_SEARCH_TERMS, TEST_DISCOVERY_QUERY, receivedSince = null, limit = 100)
        }

        // 1 and 3 are the registry's; 2 and 4 are Gmail's. Both halves matter: without the raw
        // search the unknown merchant is invisible, and without the registry search a known
        // sender Gmail files outside the query drops out of the scan's denominator.
        assertEquals(listOf(1L, 2L, 3L, 4L), uids)
        // UID 5 is in neither. A discovery pass is still a filtered pass, not a mailbox dump.
        assertTrue(5L !in uids)
    }

    @Test
    fun `a plain server falls back to the registry search alone, silently`() {
        val client = mailbox(supportsGmailRawSearch = false)

        val uids = session(client) {
            it.discoveryCandidateUids(TEST_SEARCH_TERMS, TEST_DISCOVERY_QUERY, receivedSince = null, limit = 100)
        }

        // `rawSearchHits` is populated on this fake too, so the empty half of the union is the
        // capability being absent and not the data. No throw, no exception, no half-answer:
        // a non-Gmail server is a supported configuration.
        assertEquals(listOf(1L, 3L), uids)
    }

    @Test
    fun `a UID both searches return appears once`() {
        val client = mailbox(supportsGmailRawSearch = true).apply {
            // Gmail files a known bank's mail under Purchases too — the overlap is the normal
            // case, not the edge one.
            rawSearchHits = mapOf(TEST_DISCOVERY_QUERY to setOf(1L, 2L))
        }

        val uids = session(client) {
            it.discoveryCandidateUids(TEST_SEARCH_TERMS, TEST_DISCOVERY_QUERY, receivedSince = null, limit = 100)
        }

        // De-duplicated by the same window arithmetic both halves already applied; a repeated
        // UID would be peeked twice and counted twice in the report.
        assertEquals(listOf(1L, 2L, 3L), uids)
    }

    @Test
    fun `the union is capped after it is formed, not before`() {
        val client = mailbox(supportsGmailRawSearch = true)

        val uids = session(client) {
            it.discoveryCandidateUids(TEST_SEARCH_TERMS, TEST_DISCOVERY_QUERY, receivedSince = null, limit = 2)
        }

        // Two capped lists concatenated are not a capped list. Ascending before the cap, so the
        // budget buys the oldest candidates and the rest stay contiguous above them.
        assertEquals(listOf(1L, 2L), uids)
    }

    @Test
    fun `no budget means no candidates and no round trip`() {
        val client = mailbox(supportsGmailRawSearch = true)

        val uids = session(client) {
            it.discoveryCandidateUids(TEST_SEARCH_TERMS, TEST_DISCOVERY_QUERY, receivedSince = null, limit = 0)
        }

        assertEquals(emptyList<Long>(), uids)
    }

    // -- the peek -------------------------------------------------------------------------

    @Test
    fun `peeking a sender reads its envelope and never its body`() {
        val client = mailbox(supportsGmailRawSearch = true)

        val sightings = session(client) { it.peekSenders(listOf(2L, 4L)) }

        assertEquals(listOf(2L, 4L), sightings.map { it.uid })
        assertEquals(listOf(2L, 4L), client.peekedUids)
        // The bound on the whole feature. UIDs 2 and 4 are from a sender the registry has never
        // heard of; R-11-03 exists so their mail is never downloaded, and constraint C2 so that
        // nothing the app does can mark it read in the owner's real mailbox. A peek that had
        // quietly become a fetch would show up here and nowhere else.
        assertEquals(emptyList<Long>(), client.fetchedUids)
    }

    @Test
    fun `the peek keeps the display phrase and the server's own date`() {
        val client = mailbox(supportsGmailRawSearch = true)

        val sighting = session(client) { it.peekSenders(listOf(2L)) }.single()

        assertEquals(MERCHANT_SENDER, sighting.address)
        // The phrase is the whole point: `shop.example.net` cannot be turned back into "Shop",
        // so a name that is not carried here can never be recovered downstream.
        assertEquals("Shop", sighting.displayPhrase)
        // From the message, never from the clock — a report has to be reproducible from its
        // input, and firstSeen/lastSeen are built from exactly this.
        assertEquals(TEST_NOW.minusSeconds(60L * 2), sighting.receivedAt)
    }

    @Test
    fun `a sender that named itself nothing yields a null phrase, not a guess`() {
        val client = mailbox(supportsGmailRawSearch = true).apply { displayPhrases = emptyMap() }

        val sighting = session(client) { it.peekSenders(listOf(2L)) }.single()

        // Constraint C7 at the boundary: no phrase means no name, and the row will be labelled
        // by its domain rather than by a title-cased local part that nobody sent.
        assertNull(sighting.displayPhrase)
    }

    @Test
    fun `a UID that vanished between the search and the peek is absent, not a hole`() {
        val client = mailbox(supportsGmailRawSearch = true)

        val sightings = session(client) { it.peekSenders(listOf(2L, 99L, 4L)) }

        // The owner deleted the mail in between. Absent rather than null, exactly as `fetch`
        // answers, so callers key off the sighting's own uid and not off the index.
        assertEquals(listOf(2L, 4L), sightings.map { it.uid })
    }

    @Test
    fun `a From header that will not parse costs that sighting and is recorded`() {
        val client = mailbox(supportsGmailRawSearch = true).apply { unreadableUids = setOf(2L) }

        val unreadable = session(client) { session ->
            val sightings = session.peekSenders(listOf(2L, 4L))
            // Contained per message, as everywhere else in this layer: one malformed header
            // must cost one sender, never the scan.
            assertEquals(listOf(4L), sightings.map { it.uid })
            session.unreadable
        }

        assertEquals(listOf(UnreadableMessage(2L, "MessagingException")), unreadable)
    }
}
