package com.pennywiseai.parser.core.email.due

import com.pennywiseai.parser.core.email.EmailMessage
import com.pennywiseai.parser.core.email.EmailSenderRegistry
import com.pennywiseai.parser.core.text.DateParse
import com.pennywiseai.parser.core.text.Money

/**
 * "We received your payment" notices about a credit-card bill — spec 20 §20.4 step 7, read
 * from the two templates the corpus carries: CRED's payment-success mail (spec 12 §12.6 C2)
 * and ICICI's payment-received mail (spec 12 §12.3 I2).
 *
 * **Why a payment is read as a due reading at all.** These mails say nothing new about what
 * is owed; what they say is that somebody paid it. That matters because the paying account is
 * very often one Fin cannot see — a different bank, a UPI app, somebody else's card — so the
 * bill would otherwise sit in Pending for ever with no hint that it was cleared. Recording the
 * notice as a `PAYMENT_CONFIRMED` source on the due is what lets screen 34 say "CRED says this
 * was paid" and what M7's `UNMATCHED_PAYMENT_NOTICE` alert (spec 26) is computed from. The
 * notice never settles anything by itself; that stays §20.5's job, driven by a saved
 * transaction, and both templates are read by a transaction parser as well
 * (`CredPaymentEmailParser`, `ICICIEmailParser` I2), so a payment Fin *can* see still settles
 * exactly as it did before this parser existed.
 *
 * **Every field is deliberate, and two of them are not what their names suggest:**
 *  - [ParsedDue.amount] is the amount **paid**, which is not the amount billed whenever the
 *    user paid the minimum. `DueMergeEngine` never lets a confirmation move a due's figure, so
 *    it reaches `due_sources.amount_seen` and stops there.
 *  - [ParsedDue.dueDate] is the **payment** date. Neither template states a due date, and the
 *    field is non-null by design; the date the sender *does* state is the one every consumer
 *    needs — it is what the merge matches on and what spec 26's three-day window counts from.
 *    A confirmation is never authoritative, so it can never write this onto `dues.due_date`.
 *
 * [ParsedDue.reference] is left null even though CRED states a UTR: `dues.reference` identifies
 * the *bill* — an invoice number, a mandate id — and filling it with a payment's UTR would both
 * misname the due and block the real identifier from ever landing there. The UTR is kept where
 * it belongs, on the transaction this same mail produces.
 */
class CardPaymentConfirmationDueParser : DueParser {

    override val id: String = "CARD_PAYMENT_CONFIRMED"

    override fun canHandle(from: String): Boolean {
        val address = EmailSenderRegistry.normaliseAddress(from) ?: return false
        return address == CRED_SENDER || address == ICICI_SENDER
    }

    override fun parse(message: EmailMessage): List<ParsedDue> =
        runCatching { read(message) }.getOrDefault(emptyList())

    private fun read(message: EmailMessage): List<ParsedDue> {
        val address = EmailSenderRegistry.normaliseAddress(message.from) ?: return emptyList()
        return when (address) {
            CRED_SENDER -> readCred(message)
            ICICI_SENDER -> readIcici(message)
            else -> emptyList()
        }
    }

    // ========================================
    // C2 · CRED payment success
    // ========================================

    /**
     * `<issuer> •• <4> payment details amount paid ₹… payment date <Mon d, yyyy>`.
     *
     * The block is anchored on `payment details` / `amount paid`, which C1's "new bill" mail
     * from the same address does not contain, so a bill can never be read as a payment.
     */
    private fun readCred(message: EmailMessage): List<ParsedDue> {
        val match = CRED_PAYMENT.find(flatten(message.searchText)) ?: return emptyList()

        // Mandatory: an issuer the app can name, a figure and a date. Any of them missing and
        // the mail yields nothing — a confirmation attributed to the wrong card would mark the
        // wrong bill as paid (spec 01, constraint C7).
        val issuer = CRED_ISSUERS[match.groupValues[1].lowercase()] ?: return emptyList()
        val paid = Money.parse(match.groupValues[3]) ?: return emptyList()
        val paidOn = DateParse.parseDate(match.groupValues[4]) ?: return emptyList()

        return listOf(
            ParsedDue(
                issuer = issuer,
                kind = DueKind.CARD_STATEMENT,
                amount = paid.amount,
                dueDate = paidOn,
                currency = paid.currency,
                accountLast4 = match.groupValues[2].takeLast(4),
                paymentConfirmed = true,
                // CRED is an aggregator: the due belongs to the issuer it names, and only the
                // source token records who reported the payment (spec 20 §20.4 step 5).
                source = CRED_SOURCE,
                description = CRED_DESCRIPTION
            )
        )
    }

    // ========================================
    // I2 · ICICI payment received
    // ========================================

    /**
     * `We have received payment of INR … on your ICICI Bank Credit Card account
     * <1234 XXXX XXXX 5678> on <dd-Mon-yyyy>.`
     *
     * The same address also sends purchase alerts (I1) and the statement (I3); neither carries
     * this sentence, so no subject test is needed to tell them apart.
     */
    private fun readIcici(message: EmailMessage): List<ParsedDue> {
        val match = ICICI_PAYMENT.find(message.body) ?: return emptyList()

        val paid = Money.parse(match.groupValues[1]) ?: return emptyList()
        val paidOn = DateParse.parseDate(match.groupValues[3]) ?: return emptyList()
        val last4 = lastFour(match.groupValues[2]) ?: return emptyList()

        return listOf(
            ParsedDue(
                issuer = ICICI_ISSUER,
                kind = DueKind.CARD_STATEMENT,
                amount = paid.amount,
                dueDate = paidOn,
                currency = paid.currency,
                accountLast4 = last4,
                paymentConfirmed = true,
                source = ICICI_SOURCE,
                description = ICICI_DESCRIPTION
            )
        )
    }

    /** Trailing four digits of a masked run — `1234 XXXX XXXX 5678` -> `5678`. */
    private fun lastFour(raw: String?): String? =
        raw?.filter { it.isDigit() }?.takeIf { it.length >= 4 }?.takeLast(4)

    /**
     * Collapses the body to single-spaced text: HTML-derived bodies join table cells with
     * ` | ` and CRED's text bodies break the payment block across lines (spec 11 §11.5).
     */
    private fun flatten(text: String): String =
        WHITESPACE.replace(text.replace('|', ' '), " ").trim()

    private companion object {
        const val CRED_SENDER = "protect@cred.club"
        const val ICICI_SENDER = "credit_cards@icici.bank.in"

        const val CRED_SOURCE = "CRED"
        const val ICICI_SOURCE = "ICICI"

        const val ICICI_ISSUER = "ICICI Bank"

        /** Shown on the due detail screen; never parsed again. */
        const val CRED_DESCRIPTION = "Payment confirmed by CRED"
        const val ICICI_DESCRIPTION = "Payment received, per ICICI Bank"

        const val BULLET = "•" // •
        const val MIDDOT = "·" // · — some HTML-to-text conversions render the bullet as this
        const val RUPEE = "₹" // ₹

        /** Spec 12 §12.11's shared MONEY shape, captured whole so [Money] reads the currency. */
        const val MONEY = """(?:Rs\.?\s*INR|Rs\.?|INR|$RUPEE)?\s*[\d,]+(?:\.\d{1,2})?"""

        const val MONTH_DATE = """[A-Za-z]{3,9}\s+\d{1,2},\s*\d{4}"""

        /** The card line: `axis bank •• 1234`, tolerating a mask like `xxxx-1234`. */
        const val CARD = """\s*[$BULLET$MIDDOT]+\s*(?:[Xx*]{2,}[\s-]*)?(\d{4})\b"""

        /**
         * Spec 12 §12.6 C2, minus its UTR clause.
         *
         * `CredPaymentEmailParser` reads the UTR because a transaction wants a reference; a
         * *due* reading does not, and requiring it here would silently drop a real payment
         * confirmation from a mail whose reference line was worded differently. Case-insensitive
         * for the same reason C1 is: CRED's body is lower-case prose and the issuer tokens are
         * written in title case.
         */
        val CRED_PAYMENT = Regex(
            """(HDFC Bank|Axis Bank|ICICI Bank|SBI Card|OneCard|American Express)""" +
                CARD +
                """\s*payment details""" +
                """\s*amount paid\s*($MONEY)""" +
                """\s*payment date\s*($MONTH_DATE)""",
            RegexOption.IGNORE_CASE
        )

        /**
         * Spec 12 §12.3 I2. The account is a spaced masked PAN, so the capture is a run of
         * digits, `X` and separators, closed by the ` on <date>` that follows it — the same
         * shape `ICICIEmailParser` reads the transaction from.
         */
        val ICICI_PAYMENT = Regex(
            """received payment of\s+((?:INR|Rs\.?|$RUPEE)?\s*[\d,]+(?:\.\d{1,2})?)""" +
                """\s+on your ICICI Bank Credit Card(?:\s+account)?\s+([0-9Xx*][0-9Xx*\s-]{2,24}?)""" +
                """\s+on\s+(\d{1,2}[-/\s][A-Za-z]{3,9}[-/\s]\d{2,4}|\d{1,2}[-/]\d{1,2}[-/]\d{2,4})""",
            RegexOption.IGNORE_CASE
        )

        /** Issuer token as CRED writes it, lower-cased, to the name the app uses. */
        val CRED_ISSUERS = mapOf(
            "hdfc bank" to "HDFC Bank",
            "axis bank" to "Axis Bank",
            "icici bank" to "ICICI Bank",
            "sbi card" to "SBI Card",
            "onecard" to "OneCard",
            "american express" to "American Express"
        )

        val WHITESPACE = Regex("""\s+""")
    }
}
