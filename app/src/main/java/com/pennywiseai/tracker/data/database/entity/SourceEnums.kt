package com.pennywiseai.tracker.data.database.entity

import kotlinx.serialization.Serializable

/** Where a record came from (spec 03 §3.5 `SourceChannel`). */
@Serializable
enum class SourceChannel {
    SMS,
    EMAIL,
    NOTIFICATION,
    IMPORT,
    MANUAL;

    companion object {
        val FALLBACK = MANUAL

        fun fromStored(value: String?): SourceChannel =
            entries.firstOrNull { it.name == value } ?: FALLBACK
    }
}

/**
 * Which dedup layer decided that two messages describe one event (spec 14 §14.3).
 *
 * Stored on every source row so the transaction detail screen can answer "why were these
 * merged?", and so a wrong merge can be traced to the rule that made it rather than guessed at.
 */
@Serializable
enum class MatchRule {
    /** L0 — identical normalised body. An exact re-delivery. */
    HASH,

    /** L1 — same 12-digit UPI reference, amount and direction, within 24 h. */
    UPI_REF,

    /** L2 — same amount, bank, account and type within the alert window. */
    AMOUNT_WINDOW,

    /** L3 — same amount, bank, type and calendar day, with similar merchants. */
    SAME_DAY,

    /** L4 — a gateway or merchant receipt matched to a bank charge. */
    GATEWAY_ORDER,

    /** L5 — a debit in one own account and the credit in another. */
    TRANSFER_LINK,

    /** The user merged these by hand. */
    MANUAL,

    /** The user split these apart; recorded with a never-merge pair so no rule re-joins them. */
    MANUAL_SPLIT;

    companion object {
        val FALLBACK = MANUAL

        fun fromStored(value: String?): MatchRule =
            entries.firstOrNull { it.name == value } ?: FALLBACK
    }
}

/** What a notice said about a due, so escalation can be shown without re-reading the mail. */
@Serializable
enum class NoticeKind {
    STATEMENT,
    REMINDER,
    DUE_TODAY,
    OVERDUE,
    AUTOPAY_UPCOMING,
    PAYMENT_CONFIRMED;

    companion object {
        val FALLBACK = STATEMENT

        fun fromStored(value: String?): NoticeKind =
            entries.firstOrNull { it.name == value } ?: FALLBACK
    }
}

/** How two readings of the same due were joined (spec 20 §20.4). */
@Serializable
enum class MergeRule {
    /** Matched on issuer, kind and account — the issuer's own notice. */
    MERGE_KEY,

    /** Matched through an aggregator's view of the same bill (CRED, Amazon Pay, BillDesk). */
    AGGREGATOR_KEY,

    MANUAL;

    companion object {
        val FALLBACK = MANUAL

        fun fromStored(value: String?): MergeRule =
            entries.firstOrNull { it.name == value } ?: FALLBACK
    }
}

/** Which worker a `sync_runs` row describes (spec 03 §3.3). */
@Serializable
enum class SyncChannel {
    EMAIL,
    SMS,
    BACKUP,
    DUE_GEN,
    ALERTS,

    /** Snapshot runs (M8). A log category, not a source of records. */
    CLOUD;

    companion object {
        val FALLBACK = EMAIL

        fun fromStored(value: String?): SyncChannel =
            entries.firstOrNull { it.name == value } ?: FALLBACK
    }
}

/** What started a worker run. */
@Serializable
enum class SyncTrigger {
    PERIODIC,
    MANUAL,
    BOOT,
    FOREGROUND,

    /** Started by an FCM tick (M8). The tick adds punctuality, never behaviour. */
    PUSH;

    companion object {
        val FALLBACK = PERIODIC

        fun fromStored(value: String?): SyncTrigger =
            entries.firstOrNull { it.name == value } ?: FALLBACK
    }
}
