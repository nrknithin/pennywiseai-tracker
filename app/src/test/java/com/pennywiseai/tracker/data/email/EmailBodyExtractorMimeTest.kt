package com.pennywiseai.tracker.data.email

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Properties
import javax.activation.DataHandler
import javax.mail.Part
import javax.mail.Session
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart
import javax.mail.util.ByteArrayDataSource

/**
 * The MIME half of spec 11 §11.5: [EmailBodyExtractor.extract] walking a real message.
 *
 * Kept apart from `EmailBodyExtractorTest` on purpose. Everything here builds a
 * `javax.mail` message **in memory** — no session to a server, no credential, no network —
 * but it does need `com.sun.mail:android-mail` and `android-activation` on the unit-test
 * classpath. The rest of the extractor's behaviour is covered by the pure-function suite,
 * so if that Android port ever refuses to run under a plain JVM this file can go without
 * taking the coverage of §11.5 with it.
 *
 * `saveChanges()` is called on every fixture because that is what writes the Content-Type
 * headers down the tree; a message built without it claims to be `text/plain` whatever it
 * actually contains, which is a JavaMail quirk and not something the extractor should
 * paper over.
 */
class EmailBodyExtractorMimeTest {

    private val extractor = EmailBodyExtractor()
    private val session: Session = Session.getInstance(Properties())

    @Test
    fun `a plain text mail is taken as it is`() {
        val message = MimeMessage(session)
        message.setText("Rs. 500.00 has been debited from your account 1234 on 12 Sep, 2026.")
        message.saveChanges()

        val body = extractor.extract(message)

        assertEquals(ExtractedBody.MIME_PLAIN, body.sourceMimeType)
        assertEquals("Rs. 500.00 has been debited from your account 1234 on 12 Sep, 2026.", body.text)
        assertFalse(body.hasAttachments)
        assertTrue(body.bodyHash.isNotEmpty())
    }

    @Test
    fun `multipart alternative prefers the html when the text part is a stub`() {
        val alternative = MimeMultipart("alternative")
        alternative.addBodyPart(textPart("View this email in a browser", ExtractedBody.MIME_PLAIN))
        alternative.addBodyPart(
            textPart("<p>Your credit card bill payment is due on 02 Oct 2026.</p>", ExtractedBody.MIME_HTML)
        )

        val message = MimeMessage(session)
        message.setContent(alternative)
        message.saveChanges()

        val body = extractor.extract(message)

        assertEquals(ExtractedBody.MIME_HTML, body.sourceMimeType)
        assertEquals("Your credit card bill payment is due on 02 Oct 2026.", body.text)
        assertFalse(body.hasAttachments)
    }

    /**
     * A statement mail: `multipart/mixed` holding a `multipart/alternative` body and a PDF.
     * Spec 11 R-11-12 wants the attachment's **name** and nothing more in M1, so the walk
     * records it from the headers and never touches its bytes.
     */
    @Test
    fun `a statement mail names its attachment and still reads the html body`() {
        val alternative = MimeMultipart("alternative")
        alternative.addBodyPart(textPart("Statement", ExtractedBody.MIME_PLAIN))
        alternative.addBodyPart(
            textPart(
                "<table><tr><td>Total Amount Due</td><td>1,234.00</td></tr>" +
                    "<tr><td>Payment due by</td><td>02 Oct 2026</td></tr></table>",
                ExtractedBody.MIME_HTML
            )
        )
        val bodyHolder = MimeBodyPart()
        bodyHolder.setContent(alternative)

        val mixed = MimeMultipart("mixed")
        mixed.addBodyPart(bodyHolder)
        mixed.addBodyPart(pdfAttachment("statement.pdf"))

        val message = MimeMessage(session)
        message.setContent(mixed)
        message.saveChanges()

        val body = extractor.extract(message)

        assertEquals(ExtractedBody.MIME_HTML, body.sourceMimeType)
        assertEquals(
            "Total Amount Due | 1,234.00\nPayment due by | 02 Oct 2026",
            body.text
        )
        assertTrue(body.hasAttachments)
        assertEquals(listOf("statement.pdf"), body.attachmentNames)
    }

    /**
     * An attached `.txt` is a file, not the body — otherwise a forwarded note could replace
     * the bank's own wording. Only the disposition distinguishes the two.
     */
    @Test
    fun `a text attachment is named but never becomes the body`() {
        val mixed = MimeMultipart("mixed")
        mixed.addBodyPart(
            textPart(
                "Rs. 500.00 has been debited from your account 1234 on 12 Sep, 2026.",
                ExtractedBody.MIME_PLAIN
            )
        )
        val attached = textPart("some other text entirely, quite a lot of it in fact", ExtractedBody.MIME_PLAIN)
        attached.fileName = "note.txt"
        attached.disposition = Part.ATTACHMENT
        mixed.addBodyPart(attached)

        val message = MimeMessage(session)
        message.setContent(mixed)
        message.saveChanges()

        val body = extractor.extract(message)

        assertEquals(ExtractedBody.MIME_PLAIN, body.sourceMimeType)
        assertEquals("Rs. 500.00 has been debited from your account 1234 on 12 Sep, 2026.", body.text)
        assertEquals(listOf("note.txt"), body.attachmentNames)
    }

    private fun textPart(content: String, mimeType: String): MimeBodyPart {
        val part = MimeBodyPart()
        part.setContent(content, "$mimeType; charset=utf-8")
        return part
    }

    /** Bytes rather than a String, so JavaMail needs no content handler for `application/pdf`. */
    private fun pdfAttachment(name: String): MimeBodyPart {
        val part = MimeBodyPart()
        part.dataHandler = DataHandler(
            ByteArrayDataSource("not a real document".toByteArray(), "application/pdf")
        )
        part.fileName = name
        part.disposition = Part.ATTACHMENT
        return part
    }
}
