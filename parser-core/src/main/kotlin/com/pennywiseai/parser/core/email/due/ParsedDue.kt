package com.pennywiseai.parser.core.email.due

import java.math.BigDecimal
import java.time.LocalDate

/**
 * How urgently the *sender* framed the due, taken from their wording rather than from
 * the date arithmetic (spec 12 C3). Amazon Pay sends "due tomorrow" / "due today" /
 * "was due on", and a reminder that says OVERDUE is worth surfacing even when the app's
 * own clock has not reached the date yet.
 */
enum class DueEscalation {
    DUE_SOON,
    DUE_TODAY,
    OVERDUE
}

/**
 * A due as recovered from one email, before the app merges it with anything it already
 * knows (spec 12; merge rules in spec 20 §20.4).
 *
 * This is deliberately a *parser-side* type: no Room annotations, no ids, no status. A
 * due's lifecycle — OPEN, settled, late — belongs to the app, and a parser has no way to
 * know whether the bill it just read was already paid.
 *
 * Fields the sender did not state stay null. Nothing is defaulted to a plausible figure:
 * an insurance reminder with no premium yields `amount = null`, and the UI shows "—"
 * rather than a guess (spec 01, constraint C7).
 */
data class ParsedDue(
    /** Who wants the money, as the user would name them: "Axis Bank", "GoDaddy", "Airtel". */
    val issuer: String,

    val kind: DueKind,

    /** Null when the sender did not state an amount (a Jio expiry notice, for example). */
    val amount: BigDecimal?,

    val dueDate: LocalDate,

    val currency: String = "INR",

    /** Card statements only: the minimum the issuer will accept. */
    val minimumDue: BigDecimal? = null,

    /** Last four digits of the card or account being billed, used to merge across senders. */
    val accountLast4: String? = null,

    /**
     * The sender's own identifier for this bill — invoice number, mandate id, SI Hub id,
     * loan account. Two records carrying the same reference are the same due.
     */
    val reference: String? = null,

    /** Human-readable billing period, e.g. "generated Sep 17, 2026" or "1 Aug - 31 Aug". */
    val periodLabel: String? = null,

    /** True for e-mandates and standing instructions: the money leaves without the user acting. */
    val autoPay: Boolean = false,

    /** What will be debited, when [autoPay] — e.g. "HDFC …1234". */
    val autoPayInstrument: String? = null,

    /** Higher amount payable after [dueDate], where the biller states one (Airtel). */
    val lateAmount: BigDecimal? = null,

    /** Extra charges the sender claims to have found, shown as a chip on the due (CRED). */
    val feesDetected: BigDecimal? = null,

    /** Urgency as worded by the sender, where they worded one. */
    val escalation: DueEscalation? = null,

    /**
     * Which sender this reading came from — "CRED", "AMAZON_PAY", "BILLDESK", "HDFC".
     * Recorded on the due so the detail screen can show that three senders agreed, and so
     * a merge can prefer the issuer's own figure over an aggregator's.
     */
    val source: String,

    /** Free text worth keeping for the detail screen; never parsed again. */
    val description: String? = null
) {
    /**
     * The key two readings must share to be the same due (spec 20 §20.4): same kind, same
     * issuer, same card. The date is compared separately with a tolerance, because CRED
     * and the issuer sometimes state due dates a day or two apart.
     */
    val mergeKey: String
        get() = listOf(
            kind.name,
            issuer.lowercase().trim(),
            accountLast4.orEmpty()
        ).joinToString("|")
}
