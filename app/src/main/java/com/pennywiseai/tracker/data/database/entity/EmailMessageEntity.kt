package com.pennywiseai.tracker.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import java.time.LocalDateTime

/**
 * Every email the sync fetched, logged **before** it is parsed (spec 03 §3.2, spec 11 R-11-05).
 *
 * Writing the row first is what makes the channel safe to interrupt: a crash mid-parse loses
 * no mail, the Email Log can show exactly what was seen and why it was skipped, and
 * "Re-process unmatched" (spec 11 R-11-10) can replay messages after the user adds a sender
 * override or the app gains a parser.
 *
 * Every field has a default because this table reaches the backup (spec 01 constraint C4,
 * upstream `CLAUDE.md` constraint 3).
 */
@Entity(
    tableName = "email_messages",
    indices = [
        // One row per message per mailbox. This is the uniqueness the resumable sync leans
        // on: a re-fetched UID is recognised rather than duplicated (spec 11 R-11-08).
        Index(value = ["mailbox", "uid"], unique = true),
        Index(value = ["received_at"]),
        Index(value = ["processed"]),
        Index(value = ["message_id"])
    ]
)
@Serializable
data class EmailMessageEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    /** The IMAP folder this came from; in practice `[Gmail]/All Mail` (spec 11 R-11-02). */
    @ColumnInfo(name = "mailbox")
    val mailbox: String = "INBOX",

    /** IMAP UID, unique within [mailbox] for a given UIDVALIDITY. */
    @ColumnInfo(name = "uid")
    val uid: Long = 0,

    /** RFC 5322 Message-ID. Survives a UIDVALIDITY reset, so it is the second dedup key. */
    @ColumnInfo(name = "message_id")
    val messageId: String? = null,

    /** Normalised lower-case sender address, without the display name. */
    @ColumnInfo(name = "from_address")
    val fromAddress: String = "",

    @ColumnInfo(name = "subject")
    val subject: String? = null,

    /** Extracted text: `text/plain` when usable, else jsoup output for `text/html`. */
    @ColumnInfo(name = "body")
    val body: String = "",

    /** md5 of the normalised body (spec 11 §11.5 step 4), used for twin detection. */
    @ColumnInfo(name = "body_hash")
    val bodyHash: String = "",

    /** IMAP INTERNALDATE — when the server received it, not any date inside the body. */
    @ColumnInfo(name = "received_at")
    @Contextual
    val receivedAt: LocalDateTime = LocalDateTime.now(),

    @ColumnInfo(name = "processed")
    val processed: Boolean = false,

    /** Set when this email produced, or was linked to, a transaction. */
    @ColumnInfo(name = "transaction_id")
    val transactionId: Long? = null,

    /** How many dues this email produced or merged into. */
    @ColumnInfo(name = "due_count")
    val dueCount: Int = 0,

    /**
     * Why nothing was created, when nothing was:
     * `IGNORE:<rule>` · `NO_PARSER` · `UNKNOWN_SENDER` · `NON_TXN` · `DUPLICATE:<txnId>` ·
     * `ERROR:<class>` · `RECEIPT_ONLY`.
     */
    @ColumnInfo(name = "skip_reason")
    val skipReason: String? = null,

    @ColumnInfo(name = "created_at")
    @Contextual
    val createdAt: LocalDateTime = LocalDateTime.now(),

    /**
     * Whether the MIME tree carried an attachment part (spec 11 §11.5 step 3).
     *
     * Recorded even in M1, where nothing reads it: the flag is free at extraction time and
     * cannot be recovered afterwards, because M1 keeps only the extracted text and the body
     * is trimmed after `retainBodiesDays`. M5's PDF statement rules would otherwise have to
     * re-download the mailbox to learn which mails had a PDF at all.
     */
    @ColumnInfo(name = "has_attachments")
    val hasAttachments: Boolean = false,

    /**
     * Attachment filenames joined by `;` (spec 03 §3.2, R-11-12), or null when there were
     * none — never an empty string, so "no attachments" and "names unknown" cannot be told
     * apart by accident.
     *
     * Use [encodeAttachmentNames] / [decodeAttachmentNames] rather than joining by hand.
     */
    @ColumnInfo(name = "attachment_names")
    val attachmentNames: String? = null
) {
    companion object {

        /**
         * The separator spec 03 §3.2 names for `attachment_names`.
         *
         * A single character, not a comma: commas are common in real filenames while `;` is
         * rejected by Windows and rare everywhere else. [encodeAttachmentNames] still
         * substitutes it out of a name, because "rare" is not "never".
         */
        const val ATTACHMENT_NAME_SEPARATOR = ";"

        /**
         * `;`-joins [names] for storage, or null when there is nothing to store.
         *
         * Blank names are dropped (a MIME part may carry a `Content-Type` with no `name`)
         * and a separator inside a name becomes a space, so a filename can never read back
         * as two attachments.
         */
        fun encodeAttachmentNames(names: List<String>): String? = names
            .asSequence()
            .map { it.trim().replace(ATTACHMENT_NAME_SEPARATOR, " ") }
            .filter { it.isNotEmpty() }
            .joinToString(ATTACHMENT_NAME_SEPARATOR)
            .takeIf { it.isNotEmpty() }

        /** The inverse of [encodeAttachmentNames]; null or empty yields an empty list. */
        fun decodeAttachmentNames(raw: String?): List<String> {
            if (raw.isNullOrEmpty()) return emptyList()
            return raw.split(ATTACHMENT_NAME_SEPARATOR)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }
    }
}
