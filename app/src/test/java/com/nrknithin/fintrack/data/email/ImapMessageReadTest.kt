package com.nrknithin.fintrack.data.email

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.util.Date
import java.util.Properties
import javax.mail.AuthenticationFailedException
import javax.mail.FolderClosedException
import javax.mail.MessageRemovedException
import javax.mail.MessagingException
import javax.mail.Session
import javax.mail.StoreClosedException
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

/**
 * The line between "this message failed" and "the connection failed" ([readOneMessage]), and
 * the envelope mapping behind it ([headerOf]).
 *
 * Why this is worth its own suite: the watermark advances per committed batch (spec 11
 * R-11-07), so the two failures have opposite correct outcomes. A header that throws must
 * cost one message — contained, the batch completes, the watermark moves past it — because a
 * failure thrown out of the batch would leave the watermark below that UID and every later
 * run would fetch the same message and die on it again, forever. A *connection* that dies
 * must do the opposite: end the batch, so the watermark never advances over mail that was
 * never read.
 *
 * Both halves are exercised against real `javax.mail` exception types rather than a mock,
 * because the whole rule is about which of those types means which thing.
 */
class ImapMessageReadTest {

    private val session: Session = Session.getInstance(Properties())

    /** What [readOneMessage] filed, so a test can assert on the recorded reason. */
    private val recorded = mutableListOf<Throwable>()

    private fun <T> read(body: () -> T): T? = readOneMessage(onContained = { recorded += it }, body = body)

    // -- containment --------------------------------------------------------------------

    @Test
    fun `a read that succeeds is passed through untouched`() {
        assertEquals(42L, read { 42L })
        assertTrue(recorded.isEmpty())
    }

    @Test
    fun `a header that will not parse costs that message and is recorded`() {
        val failure = MessagingException("Illegal address in From")

        assertNull(read<Long> { throw failure })

        assertEquals(listOf<Throwable>(failure), recorded)
    }

    @Test
    fun `a part that will not decode costs that message and is recorded`() {
        val failure = IOException("unsupported charset")

        assertNull(read<Long> { throw failure })

        assertEquals(listOf<Throwable>(failure), recorded)
    }

    @Test
    fun `a message deleted between the search and the fetch is skipped but not an error`() {
        assertNull(read<Long> { throw MessageRemovedException() })

        // Nothing went wrong, so nothing is filed: an "ERROR:" row in the Email Log for mail
        // the user themselves deleted would be an invented failure (spec 01 constraint C7).
        assertTrue(recorded.isEmpty())
    }

    @Test
    fun `a dead folder ends the batch instead of being blamed on the message`() {
        val thrown = assertThrows(MailboxException.Network::class.java) {
            read<Long> { throw FolderClosedException(null) }
        }

        // Reported as network so the worker retries (spec 11 §11.3, last line) rather than
        // marking every remaining UID in the batch as a bad message and moving past them.
        assertTrue(thrown.cause is FolderClosedException)
        assertTrue(recorded.isEmpty())
    }

    @Test
    fun `a dead store ends the batch too`() {
        val thrown = assertThrows(MailboxException.Network::class.java) {
            read<Long> { throw StoreClosedException(null) }
        }

        assertTrue(thrown.cause is StoreClosedException)
        assertTrue(recorded.isEmpty())
    }

    @Test
    fun `a mid-session auth failure is reported as auth, without the provider's message`() {
        val thrown = assertThrows(MailboxException.Auth::class.java) {
            read<Long> { throw AuthenticationFailedException("LOGIN failed for someone@example.com") }
        }

        // Spec 04 §4.1: the server's reply can echo the login, so it never travels.
        assertEquals("auth failed", thrown.message)
        assertNull(thrown.cause)
        assertTrue(recorded.isEmpty())
    }

    @Test
    fun `a MailboxException from further in is passed through as it is`() {
        val original = MailboxException.Network(IOException("dns"))

        val thrown = assertThrows(MailboxException.Network::class.java) {
            read<Long> { throw original }
        }

        assertTrue(thrown === original)
    }

    // -- the envelope -------------------------------------------------------------------

    @Test
    fun `the from address is reduced to a bare lower-case address`() {
        val message = MimeMessage(session)
        message.setFrom(InternetAddress("ALERTS@Example.com", "SOMEONE"))
        message.subject = "Your bill is ready"
        message.saveChanges()

        val header = headerOf(uid = 7L, message = message)

        assertEquals(7L, header.uid)
        assertEquals("alerts@example.com", header.from)
        assertEquals("Your bill is ready", header.subject)
    }

    @Test
    fun `a message with no From and no Subject still produces a header`() {
        // R-11-05 logs every fetched message, so a header-less one has to map to something.
        val header = headerOf(uid = 8L, message = MimeMessage(session))

        assertEquals("", header.from)
        assertEquals("", header.subject)
        assertNull(header.messageId)
    }

    @Test
    fun `the sent date stands in when there is no INTERNALDATE`() {
        val sent = Instant.parse("2026-09-17T09:00:00Z")
        val message = MimeMessage(session)
        message.setFrom(InternetAddress("someone@example.com"))
        message.sentDate = Date.from(sent)
        message.saveChanges()

        // An in-memory message has no INTERNALDATE; only a server sets one.
        assertEquals(sent, headerOf(uid = 9L, message = message).receivedAt)
    }

    @Test
    fun `a message with no date at all falls back to now rather than being dropped`() {
        val before = Instant.now()

        val receivedAt = headerOf(uid = 10L, message = MimeMessage(session)).receivedAt

        // Documented in FORK-CHANGES as the one place a value is filled in: received_at is
        // not nullable and R-11-05 requires the row. RFC 3501 makes this unreachable against
        // a real server.
        assertTrue(!receivedAt.isBefore(before))
        assertTrue(!receivedAt.isAfter(Instant.now()))
    }
}
