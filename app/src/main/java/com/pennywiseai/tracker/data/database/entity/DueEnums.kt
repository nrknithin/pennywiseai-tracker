package com.pennywiseai.tracker.data.database.entity

import kotlinx.serialization.Serializable

/**
 * The due vocabulary as stored (spec 03 §3.5).
 *
 * These mirror the parser-side enums in `com.pennywiseai.parser.core.email.due` rather than
 * reusing them. Two reasons: backup-serialised enums must be `@Serializable` (upstream
 * `CLAUDE.md` constraint 3) and `parser-core` carries no serialization plugin, and the stored
 * vocabulary is a schema contract that should not shift because a parser gained a case.
 * `DueMappers` converts between them, and a test pins the two lists together.
 *
 * Every enum here declares a fallback for values a newer backup might carry, per spec 03 §3.5:
 * "reading a value the app doesn't know maps to the enum's declared fallback and logs once".
 */
@Serializable
enum class DueKind {
    CARD_STATEMENT,
    DOMAIN_RENEWAL,
    INVOICE,
    LOAN_EMI,
    CARD_EMI,
    SUBSCRIPTION,
    COMMITMENT,
    INSURANCE,
    RECHARGE,
    RENT,
    TAX,
    SALARY,
    INCOME_OTHER,

    /**
     * A postpaid utility or telecom bill. Not in spec 03 §3.5's list, which spec 12 T1
     * (Airtel) nonetheless emits; added because such a bill carries a separate higher
     * amount payable after the due date and no other kind expresses that.
     */
    UTILITY_BILL,

    /**
     * A one-off payable from a lender's preclosure quote (spec 12 H12). Also absent from
     * §3.5. Kept distinct from [LOAN_EMI] because it ends a loan rather than advancing its
     * schedule, and because its amount must come from the lender's mail rather than from
     * the app's own outstanding figure.
     */
    LOAN_PRECLOSURE,

    OTHER;

    companion object {
        val FALLBACK = OTHER

        fun fromStored(value: String?): DueKind =
            entries.firstOrNull { it.name == value } ?: FALLBACK
    }
}

@Serializable
enum class DueStatus {
    /** Money is still owed. The only status the Pending tab shows. */
    OPEN,

    PAID_ON_TIME,
    PAID_LATE,

    /** The user asserted it was paid; no transaction matched it. */
    MARKED_PAID,

    /** Nothing actually due — a zero-amount statement, or the user dismissed it. */
    DISMISSED,

    /** A commitment cycle the user deliberately skipped, e.g. an EMI holiday (M2). */
    SKIPPED;

    companion object {
        val FALLBACK = OPEN

        fun fromStored(value: String?): DueStatus =
            entries.firstOrNull { it.name == value } ?: FALLBACK
    }

    /** True for the statuses that mean the money question is closed. */
    val isSettled: Boolean
        get() = this == PAID_ON_TIME || this == PAID_LATE || this == MARKED_PAID
}

@Serializable
enum class DueSource {
    EMAIL,
    SMS,
    MANUAL,

    /** Generated from a commitment's schedule rather than from any message (M2). */
    COMMITMENT,

    /** Established by a document, e.g. a policy PDF that proves a premium was paid (M2). */
    DOCUMENT;

    companion object {
        val FALLBACK = MANUAL

        fun fromStored(value: String?): DueSource =
            entries.firstOrNull { it.name == value } ?: FALLBACK
    }
}
