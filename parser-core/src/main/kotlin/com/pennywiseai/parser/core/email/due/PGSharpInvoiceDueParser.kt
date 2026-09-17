package com.pennywiseai.parser.core.email.due

import com.pennywiseai.parser.core.email.EmailMessage
import com.pennywiseai.parser.core.email.EmailSenderRegistry
import com.pennywiseai.parser.core.text.DateParse
import com.pennywiseai.parser.core.text.Money
import com.pennywiseai.parser.core.text.ParsedMoney

/**
 * PGSharp "Customer Invoice" — spec 12 §12.7 P1 (corpus: `noreply@pgsharp.com`, monthly).
 *
 * A dollar invoice: amount, due date, invoice number, and the card PGSharp will charge
 * on its own. The amount stays in **USD** — spec 01 §1.5 shows foreign dues in their own
 * currency and converts only for totals, and repo constraint #2 forbids mixing currencies
 * anywhere upstream of that.
 *
 * The named card is recorded as [ParsedDue.autoPayInstrument] only, in the brand-and-last-4
 * shape spec 21 AC-21-03 stores (`"Visa-…"`). It is deliberately not turned into an
 * `accountLast4`: the app has no idea whether that card is one it tracks, and guessing
 * would merge this invoice into an unrelated card's due (spec 20 §20.4 keys on
 * `accountLast4`). Resolving the instrument to an account is the app's job, and only once
 * the charge actually arrives.
 */
class PGSharpInvoiceDueParser : DueParser {

    override val id: String = "PGSHARP_INVOICE"

    override fun canHandle(from: String): Boolean {
        val address = EmailSenderRegistry.normaliseAddress(from) ?: return false
        return address.endsWith("@pgsharp.com") || address.endsWith(".pgsharp.com")
    }

    override fun parse(message: EmailMessage): List<ParsedDue> {
        if (!INVOICE_SUBJECT.containsMatchIn(message.subject)) return emptyList()

        val text = message.searchText
        // The payment confirmation carries the same invoice number and amount; it is a
        // transaction (P2), and reading it here would re-open a due that was just paid.
        if (SETTLED_WORDING.containsMatchIn(text)) return emptyList()

        val dueDate = DUE_DATE.find(text)
            ?.groupValues?.get(1)
            ?.let { DateParse.parseDate(it) }
            ?: return emptyList()

        // An invoice with no amount, or one that totals zero, is not money owed. Both are
        // dropped rather than reported as a due with an invented or a zero figure.
        val money = firstAmount(text)?.takeIf { it.amount.signum() > 0 } ?: return emptyList()

        val instrument = autoPayInstrument(text)

        return listOf(
            ParsedDue(
                issuer = ISSUER,
                kind = DueKind.INVOICE,
                amount = money.amount,
                dueDate = dueDate,
                currency = money.currency,
                reference = invoiceNumber(text),
                autoPay = instrument != null || AUTO_PAY_WORDING.containsMatchIn(text),
                autoPayInstrument = instrument,
                source = SOURCE
            )
        )
    }

    /**
     * The amount, by label precedence rather than by position: a mail that states both a
     * line price and an "Amount due" must yield the latter, and `Regex.find` alone would
     * return whichever came first in the text.
     */
    private fun firstAmount(text: String): ParsedMoney? {
        for (pattern in AMOUNT_PATTERNS) {
            val money = pattern.find(text)?.groupValues?.get(1)?.let { Money.parse(it) }
            if (money != null) return money
        }
        return null
    }

    /** The invoice number, which P2 later uses to settle this due. Null when unstated. */
    private fun invoiceNumber(text: String): String? =
        INVOICE_NUMBER.findAll(text)
            .map { it.groupValues[1] }
            .firstOrNull { candidate -> candidate.any { it.isDigit() } }

    /** `"Visa-4242"` — brand and last four, normalised to the shape spec 21 stores. */
    private fun autoPayInstrument(text: String): String? {
        val match = CARD.find(text) ?: return null
        val brand = WHITESPACE.replace(match.groupValues[1].trim(), " ")
        return "$brand-${match.groupValues[2]}"
    }

    private companion object {
        const val ISSUER = "PGSharp"
        const val SOURCE = "PGSHARP"

        /** Date shapes the invoice uses: `21/09/2026`, `21 Sep 2026`, `September 21, 2026`. */
        const val DATE_TOKEN =
            """(\d{1,2}[-/]\d{1,2}[-/]\d{2,4}""" +
                """|\d{1,2}\s+[A-Za-z]{3,9},?\s+\d{4}""" +
                """|[A-Za-z]{3,9}\s+\d{1,2},?\s+\d{4})"""

        /** A dollar figure, written either `$7.25` or `7.25 USD`. */
        const val USD_AMOUNT = """(\$\s*[\d,]+(?:\.\d{1,2})?|[\d,]+(?:\.\d{1,2})?\s*USD)"""

        val INVOICE_SUBJECT = Regex("""\binvoice\b""", RegexOption.IGNORE_CASE)

        /** Wording that marks the mail as a receipt for this invoice, not the invoice. */
        val SETTLED_WORDING = Regex(
            """payment\s+(?:receipt|confirmation|successful|received)""" +
                """|thank(?:s| you)\s+for\s+your\s+payment""" +
                """|service\s+activated|paid\s+in\s+full""",
            RegexOption.IGNORE_CASE
        )

        /**
         * `Due date: 21/09/2026`, `Payment due by September 21, 2026`. "Amount due: $7.25"
         * cannot match — the group after the label has to be a date — and scanning simply
         * continues to the real due date.
         */
        val DUE_DATE = Regex("""\bdue\s*(?:date|on|by)?\s*[:\-]?\s*$DATE_TOKEN""", RegexOption.IGNORE_CASE)

        /** Tried in order; the first that matches wins (see [firstAmount]). */
        val AMOUNT_PATTERNS = listOf(
            Regex(
                """(?:amount\s+due|balance\s+due|total\s+due|amount\s+payable)\s*[:\-]?\s*$USD_AMOUNT""",
                RegexOption.IGNORE_CASE
            ),
            Regex("""(?:total|amount)\s*[:\-]?\s*$USD_AMOUNT""", RegexOption.IGNORE_CASE),
            Regex(USD_AMOUNT, RegexOption.IGNORE_CASE)
        )

        /**
         * `Invoice #: 1042`, `Invoice no. 1042`, `Invoice IN-1042`, bare `Invoice 1042`.
         * The trailing look-ahead keeps a date out of the capture, so "Invoice date
         * 14/09/2026" yields nothing rather than an invoice numbered "14".
         */
        val INVOICE_NUMBER = Regex(
            """\bInvoice\s*(?:number|no\.?|#)?\s*[:#]?\s*([A-Za-z]{0,6}-?\d{2,12})(?![\d/\-])""",
            RegexOption.IGNORE_CASE
        )

        /** Brand plus last four: `Visa-4242`, `Visa ending in 4242`, `Mastercard XX4242`. */
        val CARD = Regex(
            """\b(Visa|Master\s?Card|Amex|American\s+Express|Discover|RuPay|Maestro""" +
                """|Diners(?:\s+Club)?|UnionPay|JCB)\s*""" +
                """(?:[-–—]|ending(?:\s+in)?|\*+|x+)?\s*(\d{4})\b""",
            RegexOption.IGNORE_CASE
        )

        /** Auto-pay wording, for the months when the mail names no card at all. */
        val AUTO_PAY_WORDING = Regex(
            """auto(?:matically|-)?\s*(?:pay|paid|charg\w+|debit\w*|renew\w*)|card\s+on\s+file""",
            RegexOption.IGNORE_CASE
        )

        val WHITESPACE = Regex("""\s+""")
    }
}
