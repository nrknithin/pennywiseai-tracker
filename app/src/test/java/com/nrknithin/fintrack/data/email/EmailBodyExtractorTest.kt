package com.nrknithin.fintrack.data.email

import com.nrknithin.fintrack.parser.core.TransactionType
import com.nrknithin.fintrack.parser.core.email.EmailMessage
import com.nrknithin.fintrack.parser.core.email.OneCardEmailParser
import com.nrknithin.fintrack.parser.core.email.due.DueKind
import com.nrknithin.fintrack.parser.core.email.due.GoDaddyRenewalDueParser
import com.nrknithin.fintrack.parser.core.text.BodyNormaliser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Spec 11 §11.5, tested where it is testable: [EmailBodyExtractor.htmlToText] and
 * [EmailBodyExtractor.fromParts] are pure functions, so every case here runs on a string
 * with no IMAP server, no credential and no `javax.mail` session.
 *
 * The HTML fixtures are the shapes spec 10 §10.8 item 6 calls "HTML only" — OneCard,
 * Amazon Pay, GoDaddy, the SBI table — written the way a mail client emits them, i.e.
 * pretty-printed with a newline between `</td>` and `<td>`, because that newline is the
 * thing most likely to break the cell join the parsers depend on.
 *
 * **No real data.** Every card suffix is `1234`, every amount, domain, merchant, reference
 * and address is invented, and the sender addresses are `example.com` placeholders: these
 * tests are about the body, and each parser's own suite in `:parser-core` covers which
 * sender it answers to (spec 06 §6.2).
 */
class EmailBodyExtractorTest {

    private val extractor = EmailBodyExtractor()

    // ------------------------------------------------------------------ htmlToText

    @Test
    fun `head style and script never reach the text`() {
        val html = """
            <html>
              <head><title>Statement</title><style>p { color: red; }</style></head>
              <body><p>Total due 250.00</p><script>track();</script></body>
            </html>
        """.trimIndent()

        assertEquals("Total due 250.00", extractor.htmlToText(html))
    }

    @Test
    fun `br p and div become line breaks`() {
        assertEquals(
            "Line one\nLine two\nLine three",
            extractor.htmlToText("<div>Line one<br>Line two</div><p>Line three</p>")
        )
    }

    @Test
    fun `entities are decoded and invisible characters are dropped`() {
        val zeroWidthSpace = Char(0x200B)
        val html = "<p>Amount:&nbsp;&#8377;1,234.00$zeroWidthSpace&nbsp;&amp;&nbsp;fees</p>"

        assertEquals("Amount: ₹1,234.00 & fees", extractor.htmlToText(html))
    }

    /**
     * The guarantee `SBIEmailParser` is written against: one row per line, cells joined
     * with `" | "` *between* them, so a line edge is also a cell boundary.
     */
    @Test
    fun `each table row is one line with its cells joined by pipes`() {
        val html = """
            <table>
              <tr><td>Amount (INR)</td><td>800.00</td></tr>
              <tr><td>Last 4 Digit of Account</td><td>X1234</td></tr>
              <tr><td>Date &amp; Time</td><td>11-09-2026 04:20</td></tr>
            </table>
        """.trimIndent()

        assertEquals(
            "Amount (INR) | 800.00\n" +
                "Last 4 Digit of Account | X1234\n" +
                "Date & Time | 11-09-2026 04:20",
            extractor.htmlToText(html)
        )
    }

    /**
     * A newline between `</td>` and `<td>` is whitespace in HTML, not a line break. If it
     * were honoured the cell separator would start a line of its own and every label/value
     * regex in spec 12 would miss.
     */
    @Test
    fun `a newline between cells does not split the row`() {
        val html = """
            <table>
              <tr>
                <td>Amount:</td>
                <td>INR 1,199.00</td>
              </tr>
            </table>
        """.trimIndent()

        assertEquals("Amount: | INR 1,199.00", extractor.htmlToText(html))
    }

    /**
     * Wrapper markup inside a cell is layout, not paragraphs: email HTML nests tables and
     * divs several deep and the row still has to arrive as one line.
     */
    @Test
    fun `wrapper markup inside a cell does not break the row`() {
        val html = """
            <table>
              <tr>
                <td><div><span>Merchant:</span></div></td>
                <td><div>EXAMPLE STORE</div></td>
              </tr>
            </table>
        """.trimIndent()

        assertEquals("Merchant: | EXAMPLE STORE", extractor.htmlToText(html))
    }

    @Test
    fun `an html body with no text at all yields nothing`() {
        assertEquals("", extractor.htmlToText("<div><img src=\"cid:logo\"><br></div>"))
        assertEquals("", extractor.htmlToText("   "))
    }

    /** Spec 04 §4.1: a mail bomb must be bounded before jsoup is handed the document. */
    @Test
    fun `an oversized html body is bounded rather than parsed whole`() {
        val html = "<p>Total 99.00</p>" + "y".repeat(EmailBodyExtractor.MAX_HTML_BYTES)

        val text = extractor.htmlToText(html)

        assertTrue("expected the leading paragraph to survive", text.startsWith("Total 99.00"))
        assertTrue("expected the text to be bounded", text.length <= EmailBodyExtractor.MAX_HTML_BYTES)
    }

    // ------------------------------------------------- the HTML-only senders of spec 10

    /**
     * OneCard (spec 12 §12.4) ships no `text/plain` part at all, so this path is the only
     * way its spends are ever seen. The assertion runs the real parser: if the cell join or
     * the line breaks change, this fails rather than the app silently losing card spends.
     */
    @Test
    fun `a OneCard spend survives the html to text conversion`() {
        val html = """
            <html><body>
              <table>
                <tr>
                  <td><p>Your Federal Bank One Credit Card ending in 1234 was used to make a payment.</p></td>
                </tr>
                <tr>
                  <td>Amount:</td>
                  <td>INR&nbsp;1,199.00</td>
                </tr>
                <tr>
                  <td>Merchant:</td>
                  <td>EXAMPLE STORE</td>
                </tr>
                <tr>
                  <td>Date:</td>
                  <td>12/09/2026</td>
                </tr>
                <tr>
                  <td>Time:</td>
                  <td>19:05:44</td>
                </tr>
              </table>
            </body></html>
        """.trimIndent()

        val body = extractor.fromParts(plain = null, html = html)
        assertEquals(ExtractedBody.MIME_HTML, body.sourceMimeType)

        val parsed = OneCardEmailParser().parse(
            EmailMessage(
                from = PLACEHOLDER_SENDER,
                subject = "Payment update on your Federal Bank One credit card",
                body = body.text,
                receivedAt = RECEIVED_AT
            )
        )

        assertNotNull("the extracted body must still parse as a OneCard spend", parsed)
        val spend = parsed!!
        assertEquals(0, BigDecimal("1199.00").compareTo(spend.amount))
        assertEquals("INR", spend.currency)
        assertEquals(TransactionType.CREDIT, spend.type)
        assertEquals("EXAMPLE STORE", spend.merchant)
        assertEquals("1234", spend.accountLast4)
        assertTrue(spend.isFromCard)
    }

    /**
     * GoDaddy (spec 12 §12.7 D1) is the one mail that is several dues at once, and the only
     * thing separating them is the row. One line per `<tr>` is therefore load-bearing.
     */
    @Test
    fun `a GoDaddy renewal notice yields one due per table row`() {
        val html = """
            <html><body>
              <table>
                <tr><th>Domain</th><th>Status</th><th>Price</th><th>Term</th></tr>
                <tr>
                  <td>example-domain.com</td>
                  <td>Cancels 28-09-2026</td>
                  <td>&#8377;1,499.00</td>
                  <td>1 year</td>
                </tr>
                <tr>
                  <td>second-example.net</td>
                  <td>Cancels 03-10-2026</td>
                  <td>&#8377;899.00</td>
                  <td>2 years</td>
                </tr>
              </table>
            </body></html>
        """.trimIndent()

        val body = extractor.fromParts(plain = null, html = html)

        val dues = GoDaddyRenewalDueParser().parse(
            EmailMessage(
                from = PLACEHOLDER_SENDER,
                subject = "Your domain expires soon.",
                body = body.text,
                receivedAt = RECEIVED_AT
            )
        )

        assertEquals(2, dues.size)
        assertEquals("example-domain.com", dues[0].reference)
        assertEquals(DueKind.DOMAIN_RENEWAL, dues[0].kind)
        assertEquals(LocalDate.of(2026, 9, 28), dues[0].dueDate)
        assertEquals(0, BigDecimal("1499.00").compareTo(requireNotNull(dues[0].amount)))
        assertEquals("second-example.net", dues[1].reference)
        assertEquals(LocalDate.of(2026, 10, 3), dues[1].dueDate)
    }

    /**
     * Amazon Pay's reminder (spec 12 §12.6 C3) is HTML with a one-line text part, so this
     * is the shape its M2 parser will have to match: the label, the issuer and the amount
     * are three cells, which means pipes sit where that spec's regex writes `\s*`.
     */
    @Test
    fun `an Amazon Pay reminder keeps its label issuer and amount on one line`() {
        val html = """
            <html><body>
              <table>
                <tr><td>Your credit card bill payment is due on 02 Oct 2026</td></tr>
                <tr><td>Due for Amount</td><td>Axis Bank</td><td>&#8377; 1,234.00</td></tr>
                <tr><td>Credit Card Number ending</td><td>1234</td></tr>
              </table>
            </body></html>
        """.trimIndent()

        assertEquals(
            "Your credit card bill payment is due on 02 Oct 2026\n" +
                "Due for Amount | Axis Bank | ₹ 1,234.00\n" +
                "Credit Card Number ending | 1234",
            extractor.htmlToText(html)
        )
    }

    // ------------------------------------------------------------------ part choice

    @Test
    fun `a usable plain part wins over the html part`() {
        val plain = "Rs. 500.00 has been debited from your account 1234 on 12 Sep, 2026."

        val body = extractor.fromParts(plain = plain, html = "<p>marketing rendering</p>")

        assertEquals(ExtractedBody.MIME_PLAIN, body.sourceMimeType)
        assertEquals(plain, body.text)
    }

    /** Amazon Pay and Cashfree both ship a one-line text part ahead of the real content. */
    @Test
    fun `a one line plain fragment loses to the html part`() {
        val body = extractor.fromParts(
            plain = "Payment Reminder",
            html = "<p>Your credit card bill payment is due on 02 Oct 2026.</p>"
        )

        assertEquals(ExtractedBody.MIME_HTML, body.sourceMimeType)
        assertEquals("Your credit card bill payment is due on 02 Oct 2026.", body.text)
    }

    @Test
    fun `a view-in-browser stub loses to the html part even when it is long enough`() {
        val stub = "View this email in a browser\nhttps://example.com/view/12345678"
        assertTrue("the fixture must clear the length bar", stub.length >= EmailBodyExtractor.MIN_PLAIN_LENGTH)

        val body = extractor.fromParts(plain = stub, html = "<p>Total due 1,234.00 on 02 Oct 2026.</p>")

        assertEquals(ExtractedBody.MIME_HTML, body.sourceMimeType)
        assertEquals("Total due 1,234.00 on 02 Oct 2026.", body.text)
    }

    /**
     * A phrase that merely appears at the top of a real body is not a stub: what is left
     * after removing it is a whole mail.
     */
    @Test
    fun `a real body that mentions viewing in a browser is still used`() {
        val plain = "View this email in a browser\n" +
            "Rs. 500.00 has been debited from your account 1234 on 12 Sep, 2026."

        val body = extractor.fromParts(plain = plain, html = "<p>ignored</p>")

        assertEquals(ExtractedBody.MIME_PLAIN, body.sourceMimeType)
        assertTrue(body.text.contains("has been debited"))
    }

    /**
     * Spec 11 §11.5 says "else text/html" and is silent about a mail with only a short text
     * part. Dropping it would lose the only body there is, so it is kept.
     */
    @Test
    fun `a short plain part is kept when there is no html part`() {
        val body = extractor.fromParts(plain = "Recharge successful.", html = null)

        assertEquals(ExtractedBody.MIME_PLAIN, body.sourceMimeType)
        assertEquals("Recharge successful.", body.text)
        assertTrue(body.bodyHash.isNotEmpty())
    }

    @Test
    fun `a message with no usable body records the attachment and no hash`() {
        val body = extractor.fromParts(
            plain = null,
            html = "<div><img src=\"cid:logo\"></div>",
            attachmentNames = listOf("statement.pdf")
        )

        assertEquals(ExtractedBody.MIME_NONE, body.sourceMimeType)
        assertEquals("", body.text)
        // Never md5(""): L0 dedup merges on equal body hashes, and one shared hash for every
        // bodyless mail would merge unrelated messages (spec 14 §14.3).
        assertEquals("", body.bodyHash)
        assertTrue(body.hasAttachments)
        assertEquals(listOf("statement.pdf"), body.attachmentNames)
        assertFalse(body.truncated)
    }

    // ------------------------------------------------------------------ hash and cap

    @Test
    fun `two deliveries of one mail hash the same despite tracking links and case`() {
        val first = extractor.fromParts(
            plain = null,
            html = "<p>Bill of INR 250.00 is due on 02 Oct 2026.</p>" +
                "<p>https://example.com/track/aaa111</p>"
        )
        val second = extractor.fromParts(
            plain = null,
            html = "<P>BILL OF INR   250.00 IS DUE ON 02 OCT 2026.</P>" +
                "<p>https://example.com/track/bbb222</p>"
        )

        assertEquals(first.bodyHash, second.bodyHash)
        assertTrue(first.bodyHash.isNotEmpty())
    }

    @Test
    fun `a different amount is a different body`() {
        val first = extractor.fromParts(plain = null, html = "<p>Bill of INR 250.00 is due.</p>")
        val second = extractor.fromParts(plain = null, html = "<p>Bill of INR 251.00 is due.</p>")

        assertNotEquals(first.bodyHash, second.bodyHash)
    }

    /**
     * The hash is of the whole body, the stored text is of the part that fit: a bill that
     * grows past the cap still has to recognise its own re-delivery (spec 11 R-11-08).
     */
    @Test
    fun `an over-long body is truncated but hashed whole`() {
        val long = "A".repeat(3000)

        val body = extractor.fromParts(plain = long, html = null, maxBodyKb = 1)

        assertTrue(body.truncated)
        assertEquals(1024, body.text.length)
        assertEquals(BodyNormaliser.bodyHash(long), body.bodyHash)
    }

    /** Truncation cuts on a character boundary, never in the middle of a rupee sign. */
    @Test
    fun `truncation never splits a multi-byte character`() {
        val rupees = "₹".repeat(400)

        val body = extractor.fromParts(plain = rupees, html = null, maxBodyKb = 1)

        assertTrue(body.truncated)
        assertTrue(body.text.toByteArray(Charsets.UTF_8).size <= 1024)
        assertFalse("no replacement characters", body.text.contains(Char(0xFFFD)))
        assertEquals("₹".repeat(341), body.text)
    }

    @Test
    fun `a body inside the cap is not marked truncated`() {
        val body = extractor.fromParts(plain = "Rs. 500.00 debited from account 1234.", html = null)

        assertFalse(body.truncated)
    }

    private companion object {
        /**
         * The parsers under test read the body, not the sender, so the fixtures use a
         * placeholder address rather than a real institutional one (spec 04 §4.5).
         */
        const val PLACEHOLDER_SENDER = "no-reply@example.com"

        /** 12 Sep 2026 00:00 UTC, as epoch millis. Any fixed instant does. */
        const val RECEIVED_AT = 1_789_171_200_000L
    }
}
