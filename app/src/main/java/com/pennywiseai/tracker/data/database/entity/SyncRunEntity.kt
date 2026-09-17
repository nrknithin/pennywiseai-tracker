package com.pennywiseai.tracker.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import java.time.LocalDateTime

/**
 * One row per worker execution (spec 03 §3.3, spec 02 §2.5).
 *
 * This is what the Email Log screen reads, and what makes a sync explainable after the fact:
 * how many messages were fetched, how many became transactions, how many were recognised as
 * twins of something already known, and what went wrong. Spec 11 AC-11-01 is stated directly
 * in terms of these counters.
 *
 * Every field has a default because this table reaches the backup (spec 01 constraint C4) —
 * though note spec 07's M6 gate excludes `sync_runs` from the restore-equality check, since a
 * restored device has its own run history.
 */
@Entity(
    tableName = "sync_runs",
    indices = [
        Index(value = ["started_at"]),
        Index(value = ["channel", "started_at"])
    ]
)
@Serializable
data class SyncRunEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "channel")
    val channel: SyncChannel = SyncChannel.EMAIL,

    @ColumnInfo(name = "started_at")
    @Contextual
    val startedAt: LocalDateTime = LocalDateTime.now(),

    /** Null while the run is in flight; a row that never gains one was killed mid-sync. */
    @ColumnInfo(name = "finished_at")
    @Contextual
    val finishedAt: LocalDateTime? = null,

    @ColumnInfo(name = "trigger")
    val trigger: SyncTrigger = SyncTrigger.PERIODIC,

    /** Messages downloaded. For a cloud snapshot run (M8), chunks uploaded. */
    @ColumnInfo(name = "fetched")
    val fetched: Int = 0,

    @ColumnInfo(name = "parsed_txn")
    val parsedTxn: Int = 0,

    /** Messages recognised as describing a transaction already known (spec 14). */
    @ColumnInfo(name = "linked_twin")
    val linkedTwin: Int = 0,

    @ColumnInfo(name = "dues_upserted")
    val duesUpserted: Int = 0,

    @ColumnInfo(name = "dues_settled")
    val duesSettled: Int = 0,

    @ColumnInfo(name = "receipts")
    val receipts: Int = 0,

    @ColumnInfo(name = "skipped")
    val skipped: Int = 0,

    @ColumnInfo(name = "errors")
    val errors: Int = 0,

    /** The UID watermark this run advanced to; unused for non-email channels. */
    @ColumnInfo(name = "last_uid")
    val lastUid: Long? = null,

    @ColumnInfo(name = "error_message")
    val errorMessage: String? = null,

    @ColumnInfo(name = "duration_ms")
    val durationMs: Long? = null
)
