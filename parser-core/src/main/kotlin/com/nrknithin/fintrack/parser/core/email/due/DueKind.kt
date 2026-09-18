package com.nrknithin.fintrack.parser.core.email.due

/**
 * What a due *is*, from the parser's point of view (spec 03 §3.5).
 *
 * The app-side `DueEntity.kind` stores these names verbatim, so a value added here must
 * also be accepted there — the column is TEXT, so adding a name needs no migration.
 *
 * Reconciliation note: spec 03 §3.5 and spec 12 name three kinds differently. Spec 12
 * writes `SUBSCRIPTION_CHARGE` (H9), `UTILITY_BILL` (T1) and `LOAN_PRECLOSURE` (H12),
 * none of which appear in spec 03's list. Spec 03 is the data-model authority, so
 * `SUBSCRIPTION_CHARGE` maps onto [SUBSCRIPTION]; [UTILITY_BILL] and [LOAN_PRECLOSURE]
 * are added because no existing value carries their meaning and both drive distinct UI
 * (a late-fee amount, and a pay-by-tomorrow one-off).
 */
enum class DueKind {
    /** A credit-card bill: statement total, minimum due, due date. */
    CARD_STATEMENT,

    /** A domain renewal — GoDaddy lists several per mail, one due each. */
    DOMAIN_RENEWAL,

    /** A SaaS or service invoice with an explicit invoice number (PGSharp, Cloudflare). */
    INVOICE,

    /** An instalment-loan EMI falling due. */
    LOAN_EMI,

    /** A card-issued EMI instalment, billed with the card statement. */
    CARD_EMI,

    /** A recurring subscription charge, including e-mandate auto-pay notices. */
    SUBSCRIPTION,

    /** A user-defined promise to pay, with no external bill behind it. */
    COMMITMENT,

    /** An insurance premium falling due. */
    INSURANCE,

    /** A prepaid recharge that must happen before a plan expires. */
    RECHARGE,

    /** Rent. */
    RENT,

    /** A tax payment. */
    TAX,

    /** Money expected *in* on a schedule — salary. */
    SALARY,

    /** Money expected in from something other than salary. */
    INCOME_OTHER,

    /**
     * A postpaid utility or telecom bill, which unlike a card statement carries a
     * separate higher amount payable after the due date (spec 12 T1, Airtel).
     */
    UTILITY_BILL,

    /**
     * A one-off payable created when a lender confirms a preclosure quote, due almost
     * immediately (spec 12 H12). Distinct from [LOAN_EMI]: it ends the loan rather than
     * advancing its schedule, and its amount comes from the lender's mail, never from
     * the app's own outstanding figure.
     */
    LOAN_PRECLOSURE,

    OTHER
}
