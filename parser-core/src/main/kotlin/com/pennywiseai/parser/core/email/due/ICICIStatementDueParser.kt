package com.pennywiseai.parser.core.email.due

import com.pennywiseai.parser.core.email.EmailMessage
import com.pennywiseai.parser.core.email.EmailSenderRegistry
import com.pennywiseai.parser.core.text.DateParse
import com.pennywiseai.parser.core.text.Money

/**
 * ICICI Bank credit-card statement → one `CARD_STATEMENT` due (spec 12 §12.3 I3,
 * corpus row `credit_cards@icici.bank.in` — statement, spec 10 §10.2).
 *
 * The mail is HTML with a PDF attached; after `EmailBodyExtractor` (spec 11 §11.5) the
 * summary table arrives as label/value pairs joined with ` | `, so every figure here is
 * **label-anchored**: `Total Amount Due`, `Minimum Amount Due` and `Payment due by`.
 * The card product and the billing period come from the subject, which reads
 * `<card name> Credit Card Statement for the period <from> to <to>`.
 *
 * What makes a due: a positive total *and* a due date. Both are stated on every sample
 * in the corpus, so failing to read either means the template moved and the right answer
 * is no due rather than a half-read bill (spec 01, constraint C7). The minimum is
 * optional — a statement that omits it still tells the user what to pay and when.
 *
 * A nil bill (`Total Amount Due` 0.00) produces no due: there is nothing to pay, and a
 * zero-amount due would sit in Pending Dues asking to be settled by a payment that will
 * never come.
 */
class ICICIStatementDueParser : DueParser {

    override val id: String = "ICICI_CARD_STATEMENT"

    override fun canHandle(from: String): Boolean =
        EmailSenderRegistry.normaliseAddress(from) == SENDER

    override fun parse(message: EmailMessage): List<ParsedDue> =
        runCatching { readStatement(message) }.getOrDefault(emptyList())

    private fun readStatement(message: EmailMessage): List<ParsedDue> {
        if (!canHandle(message.from)) return emptyList()
        // The same address also sends transaction alerts and payment receipts (I1, I2);
        // only the statement carries a due.
        if (!STATEMENT_SUBJECT.containsMatchIn(message.subject)) return emptyList()

        val body = message.body

        val total = Money.parse(TOTAL_DUE.find(body)?.groupValues?.get(1)) ?: return emptyList()
        if (total.amount.signum() <= 0) return emptyList()

        val dueDate = DateParse.parseDate(PAYMENT_DUE_BY.find(body)?.groupValues?.get(1))
            ?: return emptyList()

        val minimum = Money.amount(MINIMUM_DUE.find(body)?.groupValues?.get(1))
        val cardName = cardName(message.subject)

        return listOf(
            ParsedDue(
                issuer = ISSUER,
                kind = DueKind.CARD_STATEMENT,
                amount = total.amount,
                dueDate = dueDate,
                currency = total.currency,
                minimumDue = minimum,
                accountLast4 = last4(message.subject) ?: last4(body),
                periodLabel = periodLabel(message.subject),
                source = SOURCE,
                description = cardName
            )
        )
    }

    /**
     * The four digits the issuer showed, from `XX1234`, `4567 XXXX XXXX 1234` or
     * `ending in 1234`. The masked run can be shorter than four digits, so the trailing
     * digits are taken as stated and never padded (spec 12, last-4 convention).
     */
    private fun last4(text: String): String? =
        MASKED_CARD.find(text)?.groupValues?.get(1)?.takeLast(4)

    /** `… Statement for the period Aug 05, 2026 to Sep 04, 2026` → the part after "period". */
    private fun periodLabel(subject: String): String? =
        PERIOD.find(subject)?.groupValues?.get(1)
            ?.trim()
            ?.trimEnd('.', ',', '-', ' ')
            ?.ifEmpty { null }

    /** `Amazon Pay ICICI Bank Credit Card`, kept for the due-detail screen only. */
    private fun cardName(subject: String): String? =
        CARD_NAME.find(subject)?.groupValues?.get(1)
            ?.trim()
            ?.removePrefix("Your ")
            ?.removePrefix("your ")
            ?.trim()
            ?.ifEmpty { null }

    private companion object {
        const val SENDER = "credit_cards@icici.bank.in"
        const val ISSUER = "ICICI Bank"
        const val SOURCE = "ICICI"

        /**
         * An amount with the currency token the mail used, handed to [Money] whole so the
         * currency is read rather than assumed (spec 12 §12.11 `MONEY`).
         */
        const val MONEY = """((?:Rs\.?|INR|\u20B9)?\s*[\d,]+(?:\.\d{1,2})?)"""

        /** The shapes ICICI writes dates in: `Sep 22, 2026`, `22-Sep-2026`, `22/09/2026`. */
        const val DATE =
            """(?:[A-Za-z]{3,9}\s+\d{1,2},?\s*\d{4}|\d{1,2}[-/][A-Za-z]{3,9}[-/]\d{2,4}|\d{1,2}[-/]\d{1,2}[-/]\d{2,4})"""

        /**
         * Between a label and its value: a colon in the text/plain variant, ` | ` once the
         * HTML table has been flattened, and sometimes a parenthetical unit (`(in INR)`).
         */
        const val GAP = """(?:\s*\([^)]{0,20}\))?[\s:|]*"""

        val STATEMENT_SUBJECT = Regex("""\bstatement\b""", RegexOption.IGNORE_CASE)

        val TOTAL_DUE = Regex("""Total\s+Amount\s+Due$GAP$MONEY""", RegexOption.IGNORE_CASE)
        val MINIMUM_DUE = Regex("""Minimum\s+Amount\s+Due$GAP$MONEY""", RegexOption.IGNORE_CASE)
        val PAYMENT_DUE_BY = Regex("""Payment\s+due\s+by$GAP($DATE)""", RegexOption.IGNORE_CASE)

        val MASKED_CARD =
            Regex("""(?:ending(?:\s+(?:in|with))?\s*(?:X+\s*)?|X{2,}\s*)(\d{2,4})\b""", RegexOption.IGNORE_CASE)

        // No end anchor: a subject is one line, so `.+` already runs to its end.
        val PERIOD = Regex("""for\s+the\s+period\s*(?:from\s+)?(.+)""", RegexOption.IGNORE_CASE)
        val CARD_NAME = Regex("""([A-Za-z0-9 .&'-]{3,60}?Credit\s+Card)\s+Statement""", RegexOption.IGNORE_CASE)
    }
}
