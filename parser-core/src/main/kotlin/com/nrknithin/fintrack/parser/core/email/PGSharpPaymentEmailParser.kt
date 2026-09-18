package com.nrknithin.fintrack.parser.core.email

import com.nrknithin.fintrack.parser.core.ParsedTransaction
import com.nrknithin.fintrack.parser.core.TransactionType
import com.nrknithin.fintrack.parser.core.text.DateParse
import com.nrknithin.fintrack.parser.core.text.Money
import com.nrknithin.fintrack.parser.core.text.ParsedMoney
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * PGSharp payment confirmation — `noreply@pgsharp.com` (spec 12 §12.7 P2).
 *
 *     This is a payment receipt for Invoice 1042.
 *     PGSharp Standard Edition (21/09/2026 - 21/10/2026) | $7.25 USD
 *
 * The money has moved, so this is a transaction — the twin of the P1 invoice, which is
 * only an expectation. The invoice number is carried through as
 * [ParsedTransaction.reference] so the app can settle that invoice by its number rather
 * than by amount alone (spec 12 P2).
 *
 * **The amount stays in dollars.** [ParsedTransaction.currency] is a plain string and is
 * set to the currency the mail's own token names; nothing here converts to rupees. The
 * repo's hard constraint #2 — amounts in different currencies are never summed — can only
 * be honoured upstream if the parser reports the real currency.
 *
 * Type is CREDIT with `isFromCard = true` in every case: PGSharp charges the card it holds
 * on file, and spec 12 P2 says the transaction lands on that card when the app tracks it
 * and on a foreign-card placeholder account when it does not. Choosing between those two
 * is the app's job; this parser only reports the last four digits when the receipt states
 * them, and null when it does not.
 */
class PGSharpPaymentEmailParser : EmailTransactionParser {

    override val id: String = "PGSHARP_EMAIL"

    override fun canHandle(from: String): Boolean {
        val address = EmailSenderRegistry.normaliseAddress(from) ?: return false
        return address == SENDER_ADDRESS || address.endsWith("@$SENDER_DOMAIN") ||
            address.endsWith(".$SENDER_DOMAIN")
    }

    override fun parse(message: EmailMessage): ParsedTransaction? {
        val text = message.searchText
        // Only a receipt is a transaction. The "Customer Invoice" mail describes the same
        // money *before* it moves and belongs to PGSharpInvoiceDueParser (P1); reading it
        // here would double-count the charge (spec 01, rule 1).
        if (!PAYMENT_WORDING.containsMatchIn(text)) return null

        // A receipt with no amount is a notification, not a payment. Nothing is assumed
        // from the subscription's usual price (spec 01, constraint C7).
        val money = firstAmount(text)?.takeIf { it.amount.signum() > 0 } ?: return null

        return ParsedTransaction(
            amount = money.amount,
            type = TransactionType.CREDIT,
            merchant = MERCHANT,
            reference = invoiceNumber(text),
            // Spec 12 conventions: the trailing four digits of the captured run, and only
            // when the receipt names the card — never inferred from a card the app knows.
            accountLast4 = CARD.find(text)?.groupValues?.get(2),
            balance = null,
            creditLimit = null,
            smsBody = message.body,
            sender = message.from,
            timestamp = paymentTimestamp(text) ?: message.receivedAt,
            bankName = ISSUER,
            isFromCard = true,
            currency = money.currency
        )
    }

    /**
     * True for the unpaid "Customer Invoice" mail, so the pipeline records NON_TXN and
     * stops before the generic parser reads the invoice total as a payment (spec 11 §11.4,
     * step d). The due parsers have already run by then, so the invoice itself is not lost.
     */
    override fun isNonTransactionEmail(message: EmailMessage): Boolean =
        INVOICE_SUBJECT.containsMatchIn(message.subject) &&
            !PAYMENT_WORDING.containsMatchIn(message.searchText)

    /**
     * The amount, by label precedence rather than by position: the receipt lists the line
     * price before the total, and a plain `find` would return whichever came first.
     */
    private fun firstAmount(text: String): ParsedMoney? {
        for (pattern in AMOUNT_PATTERNS) {
            val money = pattern.find(text)?.groupValues?.get(1)?.let { Money.parse(it) }
            if (money != null) return money
        }
        return null
    }

    /** The invoice this receipt pays — the key the matching due is settled by. */
    private fun invoiceNumber(text: String): String? =
        INVOICE_NUMBER.findAll(text)
            .map { it.groupValues[1] }
            .firstOrNull { candidate -> candidate.any { it.isDigit() } }

    /**
     * The payment date stated in the body, which wins over the received date (spec 14
     * §14.2). Only a labelled date counts: the service period in brackets,
     * `(21/09/2026 - 21/10/2026)`, is a subscription window rather than the day money
     * moved, and reading it as one would date the transaction by luck.
     */
    private fun paymentTimestamp(text: String): Long? =
        PAYMENT_DATE.find(text)
            ?.groupValues?.get(1)
            ?.let { DateParse.parse(it) }
            ?.let { toEpochMillis(it) }

    private companion object {
        private const val ISSUER = "PGSharp"
        private const val MERCHANT = "PGSharp"
        private const val SENDER_ADDRESS = "noreply@pgsharp.com"
        private const val SENDER_DOMAIN = "pgsharp.com"

        private val ZONE: ZoneId = ZoneId.of("Asia/Kolkata")

        /** A dollar figure, written either `$7.25` or `7.25 USD`. */
        private const val USD_AMOUNT = """(\$\s*[\d,]+(?:\.\d{1,2})?|[\d,]+(?:\.\d{1,2})?\s*USD)"""

        /** Date shapes seen on the receipt: `21/09/2026`, `21 Sep 2026`, `September 21, 2026`. */
        private const val DATE_TOKEN =
            """(\d{1,2}[-/]\d{1,2}[-/]\d{2,4}""" +
                """|\d{1,2}\s+[A-Za-z]{3,9},?\s+\d{4}""" +
                """|[A-Za-z]{3,9}\s+\d{1,2},?\s+\d{4})"""

        /** What makes the mail a receipt: the subject or the first line always says so. */
        private val PAYMENT_WORDING = Regex(
            """payment\s+(?:receipt|confirmation|successful|received)""" +
                """|thank(?:s| you)\s+for\s+your\s+payment""" +
                """|receipt\s+for\s+invoice|service\s+activated|paid\s+in\s+full""",
            RegexOption.IGNORE_CASE
        )

        private val INVOICE_SUBJECT = Regex("""\binvoice\b""", RegexOption.IGNORE_CASE)

        /** Tried in order; the first that matches wins (see [firstAmount]). */
        private val AMOUNT_PATTERNS = listOf(
            Regex(
                """(?:total\s+paid|amount\s+paid|amount\s+charged|payment\s+of|paid)""" +
                    """\s*[:\-]?\s*$USD_AMOUNT""",
                RegexOption.IGNORE_CASE
            ),
            Regex("""(?:total|amount)\s*[:\-]?\s*$USD_AMOUNT""", RegexOption.IGNORE_CASE),
            Regex(USD_AMOUNT, RegexOption.IGNORE_CASE)
        )

        /**
         * `Invoice 1042`, `Invoice #: 1042`, `Invoice IN-1042`. The trailing look-ahead
         * keeps a date out of the capture, so "Invoice date 14/09/2026" yields nothing
         * rather than an invoice numbered "14".
         */
        private val INVOICE_NUMBER = Regex(
            """\bInvoice\s*(?:number|no\.?|#)?\s*[:#]?\s*([A-Za-z]{0,6}-?\d{2,12})(?![\d/\-])""",
            RegexOption.IGNORE_CASE
        )

        /** Brand plus last four: `Visa-4242`, `Visa ending in 4242`, `Mastercard XX4242`. */
        private val CARD = Regex(
            """\b(Visa|Master\s?Card|Amex|American\s+Express|Discover|RuPay|Maestro""" +
                """|Diners(?:\s+Club)?|UnionPay|JCB)\s*""" +
                """(?:[-–—]|ending(?:\s+in)?|\*+|x+)?\s*(\d{4})\b""",
            RegexOption.IGNORE_CASE
        )

        /** Only a labelled payment date — never the service period (see [paymentTimestamp]). */
        private val PAYMENT_DATE = Regex(
            """(?:payment\s+date|paid\s+on|date\s+paid|payment\s+received\s+on|charged\s+on)""" +
                """\s*[:\-]?\s*$DATE_TOKEN""",
            RegexOption.IGNORE_CASE
        )

        private fun toEpochMillis(dateTime: LocalDateTime): Long =
            dateTime.atZone(ZONE).toInstant().toEpochMilli()
    }
}
