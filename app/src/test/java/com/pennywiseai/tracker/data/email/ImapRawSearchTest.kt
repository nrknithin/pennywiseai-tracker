package com.pennywiseai.tracker.data.email

import com.sun.mail.iap.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.util.Date
import java.util.Properties
import javax.mail.Session
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

/**
 * The three pure pieces of the Gmail raw search: reading a `UID SEARCH` reply
 * ([parseSearchResponse]), writing the query into the command line ([quoteImap]), and turning
 * an envelope into a [SenderSighting] ([sightingOf]).
 *
 * Why these are worth a suite of their own: all three sit between the app and a protocol
 * stream, where being wrong is silent. A mis-parsed response invents UIDs or loses them and
 * the run still "succeeds"; an unescaped quote lets the rest of a saved query be read as
 * further SEARCH keys against a session already logged in as the owner; and a sighting that
 * dropped the display phrase would leave the discovery screen able to label a sender only by
 * its domain, which is the whole thing this feature is for.
 *
 * Every case runs against real `com.sun.mail` types — the actual `Response` parser and the
 * actual `MimeMessage` header parser — because the rules being tested are rules about what
 * those types do, and a mock of them would only test the mock.
 */
class ImapRawSearchTest {

    private val session: Session = Session.getInstance(Properties())

    /** One line of an IMAP reply, parsed by JavaMail exactly as it would be off the socket. */
    private fun line(text: String): Response = Response(text)

    // -- reading the reply ---------------------------------------------------------------

    @Test
    fun `the UIDs of an untagged SEARCH line are read in order`() {
        val uids = parseSearchResponse(
            arrayOf(
                line("* SEARCH 12 34 56"),
                line("A1 OK UID SEARCH completed")
            )
        )

        assertEquals(listOf(12L, 34L, 56L), uids)
    }

    @Test
    fun `a search that matched nothing is an empty list, and so is no reply at all`() {
        assertEquals(
            emptyList<Long>(),
            parseSearchResponse(arrayOf(line("* SEARCH"), line("A1 OK UID SEARCH completed")))
        )
        // A caller that never got an array at all has no candidates either. Note what neither
        // of these means: `searchRaw` turns "not asked" into null before this function is ever
        // reached, so an empty list here can only ever mean "asked, and nothing matched".
        assertEquals(emptyList<Long>(), parseSearchResponse(null))
    }

    @Test
    fun `a server that refuses the query yields no candidates rather than throwing`() {
        val uids = parseSearchResponse(
            arrayOf(line("A1 NO [CANNOT] Unsupported search criterion"))
        )

        // Gmail answers NO to an X-GM-RAW it dislikes. Discovery is optional, so a saved query
        // the server will not run costs one skipped scan — never a failed sync, and never a
        // banner on Home for a mail channel that is working.
        assertEquals(emptyList<Long>(), uids)
    }

    @Test
    fun `a BAD reply discards anything the same command had already emitted`() {
        val uids = parseSearchResponse(
            arrayOf(
                line("* SEARCH 12 34"),
                line("A1 BAD Invalid search criteria")
            )
        )

        // The command did not complete, so its partial output is not a result. Keeping the 12
        // and 34 would report a clean scan of a query the server rejected.
        assertEquals(emptyList<Long>(), uids)
    }

    @Test
    fun `a CONDSTORE MODSEQ suffix is not read as a UID`() {
        val uids = parseSearchResponse(
            arrayOf(
                line("* SEARCH 12 34 (MODSEQ 917162500)"),
                line("A1 OK UID SEARCH completed")
            )
        )

        // RFC 7162 appends this to `* SEARCH` on a CONDSTORE server. 917162500 is a
        // mod-sequence, not a UID; taking it for one would put a nonexistent message into the
        // peek that follows.
        assertEquals(listOf(12L, 34L), uids)
    }

    @Test
    fun `untagged lines that are not SEARCH results are ignored`() {
        val uids = parseSearchResponse(
            arrayOf(
                line("* 23 EXISTS"),
                line("* 0 RECENT"),
                line("* OK [UIDNEXT 4392] Predicted next UID"),
                line("* SEARCH 7"),
                line("A1 OK UID SEARCH completed")
            )
        )

        // Any command can carry untagged status updates. Only `SEARCH` is claimed — `* 23
        // EXISTS` would otherwise contribute a 23 that is a message count, not a UID.
        assertEquals(listOf(7L), uids)
    }

    @Test
    fun `several SEARCH lines accumulate`() {
        val uids = parseSearchResponse(
            arrayOf(
                line("* SEARCH 1 2"),
                line("* SEARCH 3"),
                line("A1 OK UID SEARCH completed")
            )
        )

        // A server is free to split a long result. Taking only the first line would silently
        // halve the corpus the discovery measurement rests on.
        assertEquals(listOf(1L, 2L, 3L), uids)
    }

    // -- writing the query ----------------------------------------------------------------

    @Test
    fun `a plain query is wrapped in quotes`() {
        assertEquals("\"category:purchases\"", quoteImap(TEST_DISCOVERY_QUERY))
    }

    @Test
    fun `a double quote in the query is escaped rather than allowed to close the string`() {
        // Gmail's own syntax for a phrase search, so this is an ordinary query and not an
        // attack — but an unescaped quote here would end the quoted string early and let the
        // remainder of a saved query be read as further SEARCH keys.
        assertEquals("\"subject:\\\"order placed\\\"\"", quoteImap("subject:\"order placed\""))
    }

    @Test
    fun `a backslash is escaped too`() {
        // Escaped first, or a trailing backslash would escape the closing quote instead.
        assertEquals("\"a\\\\b\"", quoteImap("a\\b"))
    }

    // -- the envelope ---------------------------------------------------------------------

    @Test
    fun `the display phrase survives, where the header mapping throws it away`() {
        val message = MimeMessage(session)
        message.setFrom(InternetAddress(MERCHANT_SENDER, "Shop"))
        message.subject = "Your order has shipped"
        message.saveChanges()

        val sighting = sightingOf(uid = 11L, message = message)

        assertEquals(11L, sighting.uid)
        assertEquals(MERCHANT_SENDER, sighting.address)
        assertEquals("Shop", sighting.displayPhrase)

        // The reason SenderSighting exists at all. headerOf reduces From to the bare address,
        // so nothing downstream of it can recover the name the sender really sent — and a
        // sighting carries no subject, so a widened search cannot carry the owner's mail.
        assertEquals(MERCHANT_SENDER, headerOf(uid = 11L, message = message).from)
    }

    @Test
    fun `an RFC 2047 encoded phrase arrives decoded`() {
        val raw = "From: =?UTF-8?Q?Sh=C3=B6p?= <" + MERCHANT_SENDER + ">\r\n" +
            "Subject: test\r\n\r\nbody\r\n"

        val sighting = sightingOf(uid = 12L, message = MimeMessage(session, raw.byteInputStream()))

        // JavaMail decodes; nothing here re-implements it. A raw `=?UTF-8?Q?…?=` shown to the
        // owner as a proposed sender name would be worse than showing the bare domain.
        assertEquals("Shöp", sighting.displayPhrase)
        assertEquals(MERCHANT_SENDER, sighting.address)
    }

    @Test
    fun `a From with no phrase gives a null name, never one derived from the address`() {
        val message = MimeMessage(session)
        message.setFrom(InternetAddress(MERCHANT_SENDER))
        message.saveChanges()

        // Null, not "Shop" reconstructed from `shop.example.net`: a proper noun that was never
        // sent cannot be recovered, and inventing one is spec 01 constraint C7. The UI labels
        // such a row by its domain instead.
        assertNull(sightingOf(uid = 13L, message = message).displayPhrase)
    }

    @Test
    fun `a phrase that is only the address again is passed through, not judged here`() {
        val raw = "From: \"" + MERCHANT_SENDER_ALT + "\" <" + MERCHANT_SENDER_ALT + ">\r\n\r\nbody\r\n"

        val sighting = sightingOf(uid = 14L, message = MimeMessage(session, raw.byteInputStream()))

        // Deciding that this is not a *name* is SenderDisplayName's job in :parser-core, where
        // CLAUDE.md constraint 5 puts text handling. This layer reports what the header said.
        assertEquals(MERCHANT_SENDER_ALT, sighting.displayPhrase)
    }

    @Test
    fun `a message with no From at all still produces a sighting`() {
        val sighting = sightingOf(uid = 15L, message = MimeMessage(session))

        // The same choice headerOf makes: a header-less message is still evidence that
        // something was in the folder, and dropping it would be the larger loss.
        assertEquals("", sighting.address)
        assertNull(sighting.displayPhrase)
    }

    @Test
    fun `the sighting's date comes from the message, not from the clock`() {
        val sent: Instant = Instant.parse("2026-09-01T06:30:00Z")
        val message = MimeMessage(session)
        message.setFrom(InternetAddress(MERCHANT_SENDER))
        message.sentDate = Date.from(sent)
        message.saveChanges()

        // An in-memory message has no INTERNALDATE; only a server sets one, so sentDate stands
        // in. firstSeen/lastSeen on a discovered sender are built from this and nothing else.
        assertEquals(sent, sightingOf(uid = 16L, message = message).receivedAt)
    }
}
