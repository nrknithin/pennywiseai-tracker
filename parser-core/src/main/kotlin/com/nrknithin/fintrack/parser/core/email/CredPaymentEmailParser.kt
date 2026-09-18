package com.nrknithin.fintrack.parser.core.email

import com.nrknithin.fintrack.parser.core.ParsedTransaction
import com.nrknithin.fintrack.parser.core.TransactionType
import com.nrknithin.fintrack.parser.core.text.DateParse
import com.nrknithin.fintrack.parser.core.text.Money
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * CRED "payment successful" mails (spec 12 §12.6, template C2).
 *
 * Body shape, in CRED's lower-case prose: `<issuer> •• <4> payment details amount paid
 * ₹51,204.00 payment date Sep 04, 2026 … UTR No.: <utr>`.
 *
 * Unlike C1 this is money that actually moved, so it is a transaction rather than a due:
 * a TRANSFER **into** the card, which is what a card-bill payment is — the balance leaves
 * a bank account and lands on the card (spec 14 §14.1). The paying account is not named
 * anywhere in CRED's mail, so [ParsedTransaction.fromAccount] stays null; the destination
 * is identified by the issuer and the card's last four.
 *
 * The mail also quotes the statement's own bill amount ("your latest statement … bill
 * amount"). The amount regex is anchored on `amount paid` so that figure can never be
 * read as the payment.
 *
 * It does not settle the due by itself (spec 20 §20.4 step 7) — the app's dedup and
 * settlement layers do that, using the UTR as [ParsedTransaction.reference] and the date
 * CRED states rather than the delivery time (spec 14 §14.2).
 */
class CredPaymentEmailParser : EmailTransactionParser {

    override val id: String = "CRED_PAYMENT"

    override fun canHandle(from: String): Boolean =
        EmailSenderRegistry.normaliseAddress(from) == SENDER

    override fun parse(message: EmailMessage): ParsedTransaction? =
        runCatching { readPayment(message) }.getOrNull()

    /**
     * True for CRED's "new bill" notice, which is a due (template C1) and must not reach
     * the generic parser — the bill amount there would become a phantom card charge.
     */
    override fun isNonTransactionEmail(message: EmailMessage): Boolean {
        val text = flatten(message.searchText)
        return PAYMENT.find(text) == null && NEW_BILL.containsMatchIn(text)
    }

    private fun readPayment(message: EmailMessage): ParsedTransaction? {
        val text = flatten(message.searchText)
        val match = PAYMENT.find(text) ?: return null

        // Mandatory: an issuer we can name, an amount paid, and the payment date. A
        // payment with any of those missing is not reported at all (spec 01, C7).
        val issuer = ISSUERS[match.groupValues[1].lowercase()] ?: return null
        val paid = Money.parse(match.groupValues[3]) ?: return null
        val paidOn = DateParse.parse(match.groupValues[4]) ?: return null

        val last4 = match.groupValues[2].takeLast(4)
        // The UTR is optional in the pattern: a payment mail that states amount, card and
        // date is still a real payment, and an absent reference is simply unknown.
        val utr = match.groupValues[5].ifBlank { null }

        return ParsedTransaction(
            amount = paid.amount,
            type = TransactionType.TRANSFER,
            merchant = merchantFor(issuer),
            reference = utr,
            accountLast4 = last4,
            balance = null,
            creditLimit = null,
            smsBody = message.body,
            sender = message.from,
            // CRED states a date but no time, so the payment lands at midnight IST
            // (spec 12 §12.11); the bank's own debit alert is matched to it by day, not
            // by minute (spec 14 §14.3 L3).
            timestamp = toEpochMillis(paidOn),
            bankName = BANK_NAMES[issuer] ?: issuer,
            isFromCard = true,
            currency = paid.currency,
            fromAccount = null,
            toAccount = "${merchantFor(issuer)} $last4"
        )
    }

    /**
     * What the settlement scorer matches on (spec 20 §20.5, "issuer" +30): the issuer name
     * followed by the instrument. Issuers that already name the card — "SBI Card",
     * "OneCard" — are left alone rather than doubled into "SBI Card Credit Card".
     */
    private fun merchantFor(issuer: String): String =
        if (issuer.endsWith("Card", ignoreCase = true)) issuer else "$issuer Credit Card"

    private fun toEpochMillis(dateTime: LocalDateTime): Long =
        dateTime.atZone(ZONE).toInstant().toEpochMilli()

    /**
     * Collapses the body to single-spaced text: HTML-derived bodies join table cells with
     * ` | ` and CRED's text bodies break the payment block across lines (spec 11 §11.5).
     */
    private fun flatten(text: String): String =
        WHITESPACE.replace(text.replace('|', ' '), " ").trim()

    private companion object {
        const val SENDER = "protect@cred.club"

        val ZONE: ZoneId = ZoneId.of("Asia/Kolkata")

        const val BULLET = "\u2022" // •
        const val MIDDOT = "\u00B7" // · — some HTML-to-text conversions render the bullet as this
        const val RUPEE = "\u20B9" // ₹

        const val MONEY = """(?:Rs\.?\s*INR|Rs\.?|INR|$RUPEE)?\s*[\d,]+(?:\.\d{1,2})?"""
        const val MONTH_DATE = """[A-Za-z]{3,9}\s+\d{1,2},\s*\d{4}"""
        const val CARD = """\s*[$BULLET$MIDDOT]+\s*(?:[Xx*]{2,}[\s-]*)?(\d{4})\b"""

        /**
         * Spec 12 §12.6 C2, with two deliberate departures from the printed regex.
         *
         * 1. **IGNORE_CASE.** The printed C2 regex has no such flag, yet matches title-case
         *    issuer tokens against a body that is lower-case throughout — as written it can
         *    never fire. C1's regex, same body, does carry the flag.
         * 2. **The UTR is optional and the issuer list is the full six.** C2 prints four
         *    issuers where C1 prints six, and requires the UTR; either omission silently
         *    drops a real payment, which no field of it would have made up.
         */
        val PAYMENT = Regex(
            """(HDFC Bank|Axis Bank|ICICI Bank|SBI Card|OneCard|American Express)""" +
                CARD +
                """\s*payment details""" +
                """\s*amount paid\s*($MONEY)""" +
                """\s*payment date\s*($MONTH_DATE)""" +
                // The reference must contain a digit, so a sentence such as "UTR details
                // will follow" cannot hand a word back as the UTR.
                """(?:[\s\S]{0,300}?UTR\s*(?:No\.?|Number)?\s*:?\s*([A-Za-z0-9/-]*\d[A-Za-z0-9/-]*))?""",
            RegexOption.IGNORE_CASE
        )

        /** The C1 "new bill" notice, recognisable from either the subject or the body. */
        val NEW_BILL = Regex(
            """new bill for your|bill summary\s*total amount due""",
            RegexOption.IGNORE_CASE
        )

        /**
         * Issuer token as CRED writes it, lower-cased, to the app's name for it. The
         * transaction's `bankName` then uses the registry's canonical spelling, so this
         * payment can twin the issuer's own "payment received" mail (spec 14 §14.3 L2).
         */
        val ISSUERS = mapOf(
            "hdfc bank" to "HDFC Bank",
            "axis bank" to "Axis Bank",
            "icici bank" to "ICICI Bank",
            "sbi card" to "SBI Card",
            "onecard" to "OneCard",
            "american express" to "American Express"
        )

        /** Issuer to the bank name the sender registry uses, where the two differ. */
        val BANK_NAMES = mapOf(
            "SBI Card" to "State Bank of India"
        )

        val WHITESPACE = Regex("""\s+""")
    }
}
