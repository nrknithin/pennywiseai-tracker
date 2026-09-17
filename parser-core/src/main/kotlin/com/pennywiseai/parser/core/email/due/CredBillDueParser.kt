package com.pennywiseai.parser.core.email.due

import com.pennywiseai.parser.core.email.EmailMessage
import com.pennywiseai.parser.core.email.EmailSenderRegistry
import com.pennywiseai.parser.core.text.DateParse
import com.pennywiseai.parser.core.text.Money
import java.math.BigDecimal

/**
 * CRED Protect "new bill" notices (spec 12 §12.6, template C1).
 *
 * Subject: `Important : New bill for your <Issuer> card XXXX-<4>`, body a lower-case
 * summary block: `<issuer> •• <4> bill summary total amount due ₹… minimum due ₹…
 * due date <Month d, yyyy> bill generated on <Mon d, yyyy>`.
 *
 * CRED is an aggregator, not the biller: the due is attributed to **the card issuer it
 * names**, never to CRED, and only [ParsedDue.source] records where the reading came
 * from (spec 20 §20.4 step 5). That is what lets the issuer's own statement and this
 * notice merge into one due instead of two.
 *
 * The one trap in this template is the CRED Protect line — `CRED Protect helped you
 * identify ₹347.65 in extra charges`. That figure is a finding *about* the bill, not the
 * bill: it goes to [ParsedDue.feesDetected] and never to the amount. Both readings are
 * taken by their own anchored regex so neither can pick up the other's number.
 */
class CredBillDueParser : DueParser {

    override val id: String = "CRED_NEW_BILL"

    override fun canHandle(from: String): Boolean =
        EmailSenderRegistry.normaliseAddress(from) == SENDER

    override fun parse(message: EmailMessage): List<ParsedDue> =
        runCatching { readBill(message) }.getOrDefault(emptyList<ParsedDue>())

    private fun readBill(message: EmailMessage): List<ParsedDue> {
        val text = flatten(message.searchText)
        val match = BILL.find(text) ?: return emptyList()

        // Mandatory: an issuer we can name, a total, and a date. Anything missing and the
        // mail yields no due at all — a card bill with a guessed date is worse than none
        // (spec 01, constraint C7).
        val issuer = ISSUERS[match.groupValues[1].lowercase()] ?: return emptyList()
        val total = Money.parse(match.groupValues[3]) ?: return emptyList()
        val dueDate = DateParse.parseDate(match.groupValues[5]) ?: return emptyList()

        val generatedOn = match.groupValues[6].trim()

        return listOf(
            ParsedDue(
                issuer = issuer,
                kind = DueKind.CARD_STATEMENT,
                amount = total.amount,
                dueDate = dueDate,
                currency = total.currency,
                // Optional: a minimum CRED stated but we could not read stays null rather
                // than blocking the whole bill.
                minimumDue = Money.amount(match.groupValues[4]),
                accountLast4 = match.groupValues[2].takeLast(4),
                periodLabel = "generated $generatedOn",
                feesDetected = readFees(text),
                source = SOURCE
            )
        )
    }

    /** The CRED Protect finding, when the mail carries one. Never the bill amount. */
    private fun readFees(text: String): BigDecimal? {
        for (pattern in FEES) {
            val found = pattern.find(text) ?: continue
            Money.amount(found.groupValues[1])?.let { return it }
        }
        return null
    }

    /**
     * Collapses the body to single-spaced text.
     *
     * HTML-derived bodies arrive with table cells joined by ` | ` (spec 11 §11.5), and
     * CRED's text bodies break the summary block across lines. Both shapes reduce to the
     * one the template regex is written against, so the regex stays readable.
     */
    private fun flatten(text: String): String =
        WHITESPACE.replace(text.replace('|', ' '), " ").trim()

    private companion object {
        /** C1 and C2 both come from CRED Protect; `talk@cred.club` is a different template. */
        const val SENDER = "protect@cred.club"

        /** Recorded on the due so the detail screen can show which senders agreed. */
        const val SOURCE = "CRED"

        const val BULLET = "\u2022" // •
        const val MIDDOT = "\u00B7" // · — some HTML-to-text conversions render the bullet as this
        const val RUPEE = "\u20B9" // ₹

        /** Spec 12 §12.11's shared MONEY shape, without its own capture group. */
        const val MONEY = """(?:Rs\.?\s*INR|Rs\.?|INR|$RUPEE)?\s*[\d,]+(?:\.\d{1,2})?"""

        /**
         * `September 22, 2026` or `Sep 22, 2026`. Spec 12 C1 allows the full month name on
         * the due date but only the three-letter form on `bill generated on`; both fields
         * accept both spellings here, because CRED writing "September 02, 2026" in the
         * second line would otherwise lose the whole bill.
         */
        const val MONTH_DATE = """[A-Za-z]{3,9}\s+\d{1,2},\s*\d{4}"""

        /** The card line: `axis bank •• 1234`, tolerating a mask like `xxxx-1234`. */
        const val CARD = """\s*[$BULLET$MIDDOT]+\s*(?:[Xx*]{2,}[\s-]*)?(\d{4})\b"""

        /**
         * Spec 12 §12.6 C1. Case-insensitive because CRED's body is lower-case prose while
         * the issuer tokens are written in title case.
         */
        val BILL = Regex(
            """(HDFC Bank|Axis Bank|ICICI Bank|SBI Card|OneCard|American Express)""" +
                CARD +
                """\s*bill summary""" +
                """\s*total amount due\s*($MONEY)""" +
                """\s*minimum due\s*($MONEY)""" +
                """\s*due date\s*($MONTH_DATE)""" +
                """\s*bill generated on\s*($MONTH_DATE)""",
            RegexOption.IGNORE_CASE
        )

        /**
         * The extra-charges figure, in the two wordings the corpus shows: spec 12 §12.6
         * quotes "helped you identify ₹347.65 in extra charges", spec 10 §10.3 quotes
         * "extra charges detected ₹347.65". Both anchor on the words, so neither can drift
         * onto the bill amount.
         */
        val FEES = listOf(
            Regex(
                """(?:identify|identified|found|detected)\s*($MONEY)\s*in\s+extra\s+charges""",
                RegexOption.IGNORE_CASE
            ),
            Regex(
                """extra\s+charges\s*(?:detected|found)?\s*:?\s*($MONEY)""",
                RegexOption.IGNORE_CASE
            )
        )

        /** Issuer token as CRED writes it, lower-cased, to the name the app uses. */
        val ISSUERS = mapOf(
            "hdfc bank" to "HDFC Bank",
            "axis bank" to "Axis Bank",
            "icici bank" to "ICICI Bank",
            // The SBI card business is a separate issuer from the bank ("State Bank of
            // India"), and spec 20 §20.4's issuerKey list has no SBI entry, so CRED's own
            // name for it stands.
            "sbi card" to "SBI Card",
            "onecard" to "OneCard",
            "american express" to "American Express"
        )

        val WHITESPACE = Regex("""\s+""")
    }
}
