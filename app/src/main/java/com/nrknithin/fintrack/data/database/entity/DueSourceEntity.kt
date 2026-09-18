package com.nrknithin.fintrack.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Every notice that described a due (spec 03 §3.3, spec 20 §20.4).
 *
 * One card bill is announced by up to four senders — the issuer's statement, CRED, Amazon Pay
 * and BillDesk — each with its own wording and sometimes its own idea of the amount or the
 * date. They merge into one `dues` row, and each reading is kept here.
 *
 * Keeping the *seen* values ([amountSeen], [minDueSeen], [dueDateSeen]) rather than only the
 * merged result is what lets the app show a disagreement instead of silently preferring one
 * sender. That matters: spec 12 H12 records a real case where the app's own figure and the
 * lender's differed and the user paid the wrong one.
 */
@Entity(
    tableName = "due_sources",
    indices = [
        Index(value = ["due_id"]),
        Index(value = ["email_message_id"])
    ]
)
@Serializable
data class DueSourceEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    /** Row id of the `dues` row this notice was merged into — not `dues.due_id`. */
    @ColumnInfo(name = "due_id")
    val dueId: Long = 0,

    @ColumnInfo(name = "channel")
    val channel: SourceChannel = SourceChannel.EMAIL,

    @ColumnInfo(name = "email_message_id")
    val emailMessageId: Long? = null,

    @ColumnInfo(name = "sender")
    val sender: String? = null,

    @ColumnInfo(name = "notice_kind")
    val noticeKind: NoticeKind = NoticeKind.STATEMENT,

    /** The amount *this* sender stated, which may differ from the merged due's amount. */
    @ColumnInfo(name = "amount_seen")
    @Contextual
    val amountSeen: BigDecimal? = null,

    @ColumnInfo(name = "min_due_seen")
    @Contextual
    val minDueSeen: BigDecimal? = null,

    @ColumnInfo(name = "due_date_seen")
    @Contextual
    val dueDateSeen: LocalDate? = null,

    @ColumnInfo(name = "received_at")
    @Contextual
    val receivedAt: LocalDateTime = LocalDateTime.now(),

    @ColumnInfo(name = "merge_rule")
    val mergeRule: MergeRule = MergeRule.MERGE_KEY
)
