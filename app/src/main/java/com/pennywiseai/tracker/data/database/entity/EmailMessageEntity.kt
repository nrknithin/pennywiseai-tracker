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
    val createdAt: LocalDateTime = LocalDateTime.now()
)
