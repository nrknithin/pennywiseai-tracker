package com.nrknithin.fintrack.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import java.time.LocalDateTime

/**
 * Every message that described a transaction (spec 03 §3.3, spec 14).
 *
 * One real event produces one `TransactionEntity` no matter how many messages announce it —
 * a bank SMS, the bank's email, a gateway receipt, a CRED confirmation (spec 01 rule 2). The
 * extra messages land here instead of becoming duplicate transactions, which is what lets the
 * transaction detail screen show "seen in 3 messages" and lets a wrong merge be undone.
 *
 * [parserId] is load-bearing beyond display. Spec 14 §14.3's guard says email never twins
 * another email, but HDFC genuinely sends two different templates for the same card charge
 * (H7 "charge without OTP" and H8 "e-mandate paid") and they must collapse. Recording which
 * template produced each source lets the guard be stated precisely — same channel *and* same
 * template is a duplicate; same channel, different template is the twin pair the corpus needs.
 */
@Entity(
    tableName = "transaction_sources",
    indices = [
        Index(value = ["transaction_id"]),
        // One source row per message. Re-processing the same email must update, not duplicate.
        Index(value = ["channel", "source_ref"], unique = true)
    ]
)
@Serializable
data class TransactionSourceEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "transaction_id")
    val transactionId: Long = 0,

    @ColumnInfo(name = "channel")
    val channel: SourceChannel = SourceChannel.EMAIL,

    /** The SMS `_id`, the `email_messages.id`, or the notification key. */
    @ColumnInfo(name = "source_ref")
    val sourceRef: String = "",

    @ColumnInfo(name = "sender")
    val sender: String? = null,

    @ColumnInfo(name = "received_at")
    @Contextual
    val receivedAt: LocalDateTime = LocalDateTime.now(),

    @ColumnInfo(name = "body_hash")
    val bodyHash: String = "",

    /** True for the source whose body is held in `transactions.sms_body`. */
    @ColumnInfo(name = "is_primary")
    val isPrimary: Boolean = false,

    /** Which dedup layer joined this source to the transaction; null for the first source. */
    @ColumnInfo(name = "match_rule")
    val matchRule: MatchRule? = null,

    /** Template id such as `H1`, `A3`, `SBI-ATM`, or the parser class's simple name. */
    @ColumnInfo(name = "parser_id")
    val parserId: String? = null
)
