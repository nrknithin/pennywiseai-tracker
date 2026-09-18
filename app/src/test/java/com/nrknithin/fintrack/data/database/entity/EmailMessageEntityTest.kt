package com.nrknithin.fintrack.data.database.entity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The attachment columns spec 03 §3.2 adds to `email_messages`, and R-11-12's rule that the
 * names are recorded.
 *
 * The join is worth pinning down because the column is one TEXT field standing in for a list:
 * every rule about blanks, trimming and a filename that itself contains the separator has to
 * be in one place, or the Email Log and M5's PDF rules will disagree about what an attachment
 * is called.
 */
class EmailMessageEntityTest {

    @Test
    fun `a row logged before anything is known claims no attachments`() {
        val row = EmailMessageEntity()

        // Constraint C4 / upstream constraint 3: every field defaults, because this table is
        // backup-serialised. And "no attachments" is stored as NULL, not as an empty string.
        assertFalse(row.hasAttachments)
        assertNull(row.attachmentNames)
    }

    @Test
    fun `names are joined for storage and split back`() {
        val names = listOf("statement-sep-2026.pdf", "summary.csv")

        val stored = EmailMessageEntity.encodeAttachmentNames(names)

        assertEquals("statement-sep-2026.pdf;summary.csv", stored)
        assertEquals(names, EmailMessageEntity.decodeAttachmentNames(stored))
    }

    @Test
    fun `no attachments is null rather than an empty string`() {
        assertNull(EmailMessageEntity.encodeAttachmentNames(emptyList()))
        // A MIME part can carry a Content-Type with no name at all; that is not an attachment
        // name, and storing "" for it would read back as one nameless attachment.
        assertNull(EmailMessageEntity.encodeAttachmentNames(listOf("", "   ")))
    }

    @Test
    fun `a name containing the separator cannot read back as two attachments`() {
        val stored = EmailMessageEntity.encodeAttachmentNames(listOf("bill;october.pdf", " receipt.pdf "))

        assertEquals("bill october.pdf;receipt.pdf", stored)
        assertEquals(listOf("bill october.pdf", "receipt.pdf"), EmailMessageEntity.decodeAttachmentNames(stored))
    }

    @Test
    fun `an absent or empty column decodes to no attachments`() {
        assertEquals(emptyList<String>(), EmailMessageEntity.decodeAttachmentNames(null))
        assertEquals(emptyList<String>(), EmailMessageEntity.decodeAttachmentNames(""))
        assertEquals(emptyList<String>(), EmailMessageEntity.decodeAttachmentNames(";;"))
    }

    @Test
    fun `a row that carried a PDF keeps both the flag and the name`() {
        val names = listOf("credit-card-statement.pdf")
        val row = EmailMessageEntity(
            mailbox = "[Gmail]/All Mail",
            uid = 4_201L,
            hasAttachments = true,
            attachmentNames = EmailMessageEntity.encodeAttachmentNames(names)
        )

        assertEquals(names, EmailMessageEntity.decodeAttachmentNames(row.attachmentNames))
    }
}
