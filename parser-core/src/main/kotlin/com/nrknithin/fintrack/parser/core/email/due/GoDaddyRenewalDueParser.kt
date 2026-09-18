package com.nrknithin.fintrack.parser.core.email.due

import com.nrknithin.fintrack.parser.core.email.EmailMessage
import com.nrknithin.fintrack.parser.core.email.EmailSenderRegistry
import com.nrknithin.fintrack.parser.core.text.DateParse
import com.nrknithin.fintrack.parser.core.text.Money

/**
 * GoDaddy domain-expiry notice — spec 12 §12.7 D1 (corpus: `renewals@godaddy.com`,
 * subject "Your domain expires soon.", sent 30/15/… days ahead of the cancel date).
 *
 * This is the one parser in the channel that routinely returns **several** dues from a
 * single mail. The notice is an HTML table with one row per domain; `EmailBodyExtractor`
 * (spec 11 §11.5) turns each `<tr>` into its own line and joins the cells with ` | `, so
 * a row reaches the parser as
 *
 *     example-domain.com | Cancels 28-09-2026 | ₹1,275.00 | 1 year
 *
 * and each row becomes its own due with its own date, price and term. Rows are read
 * independently: a row whose date will not parse is dropped without costing the others.
 *
 * The domain name is the [ParsedDue.reference] — it is the only identifier GoDaddy gives,
 * and it is what makes two notices about the same domain (30 days out, then 15) merge
 * into one due instead of stacking up (spec 20 §20.4).
 */
class GoDaddyRenewalDueParser : DueParser {

    override val id: String = "GODADDY_RENEWAL"

    override fun canHandle(from: String): Boolean {
        val address = EmailSenderRegistry.normaliseAddress(from) ?: return false
        return address.endsWith("@godaddy.com") || address.endsWith(".godaddy.com")
    }

    override fun parse(message: EmailMessage): List<ParsedDue> {
        if (!isExpiryNotice(message.subject)) return emptyList()

        val dues = LinkedHashMap<String, ParsedDue>()
        for (line in message.body.lineSequence()) {
            val due = parseRow(line) ?: continue
            // One notice can name the same domain twice — a summary block above the table
            // and then the table row itself. The first reading wins; a second due for the
            // same domain and date would be a duplicate of it, not a second obligation.
            val key = "${due.reference}|${due.dueDate}"
            if (!dues.containsKey(key)) dues[key] = due
        }
        return dues.values.toList()
    }

    /**
     * True for the renewal notice, false for the cart reminder.
     *
     * Spec 12 §12.7 D1 rejects the cart reminder *by subject*. The marketing address it
     * comes from (`renewals@e.godaddy.com`) is also in the ignore list, so in the live
     * pipeline it never reaches a parser — but the cart mail's own subject can say
     * "expires soon" too (it is the cart that expires), so the cart wording is checked
     * first and wins. Relying on the sender alone would leave that hole open.
     */
    private fun isExpiryNotice(subject: String): Boolean =
        !CART_SUBJECT.containsMatchIn(subject) && EXPIRY_SUBJECT.containsMatchIn(subject)

    /** One table row → one due, or null when the row is not a domain row. */
    private fun parseRow(line: String): ParsedDue? {
        val cancels = CANCELS.find(line) ?: return null
        val dueDate = DateParse.parseDate(cancels.groupValues[1]) ?: return null

        // The domain is the row's first cell, so it always sits left of "Cancels".
        val domain = DOMAIN.find(line.substring(0, cancels.range.first)) ?: return null

        // Price and term are later cells, but layouts disagree on their order, so both are
        // looked for anywhere right of the domain rather than at a fixed offset.
        val rest = line.substring(domain.range.last + 1)
        // A price of zero is a failed capture or a promotional row, never money owed;
        // the domain still expires, so the row stands with no amount rather than with a
        // wrong one (spec 01, constraint C7).
        val price = Money.parse(PRICE.find(rest)?.value)?.takeIf { it.amount.signum() > 0 }

        return ParsedDue(
            issuer = ISSUER,
            kind = DueKind.DOMAIN_RENEWAL,
            amount = price?.amount,
            dueDate = dueDate,
            currency = price?.currency ?: DEFAULT_CURRENCY,
            reference = domain.value.lowercase(),
            periodLabel = TERM.find(rest)?.value?.trim(),
            source = SOURCE
        )
    }

    private companion object {
        const val ISSUER = "GoDaddy"
        const val SOURCE = "GODADDY"
        const val DEFAULT_CURRENCY = "INR"

        /** `expires`, `expiring`, `expired` — the notice's own wording. */
        val EXPIRY_SUBJECT = Regex("""\bexpir(?:e|es|ed|ing)\b""", RegexOption.IGNORE_CASE)

        /** Cart-reminder wording, checked before the expiry wording (see [isExpiryNotice]). */
        val CART_SUBJECT = Regex(
            """\b(?:cart|basket|checkout)\b|complete\s+your\s+(?:purchase|order)""",
            RegexOption.IGNORE_CASE
        )

        /**
         * The row's date cell. GoDaddy writes `Cancels 28-09-2026`; the separator and an
         * `on` are tolerated because HTML-to-text leaves cell pipes and stray colons
         * behind. Month-name spellings are accepted too, but the `Cancels` anchor itself
         * is required: a looser anchor such as `expires` would turn a footer line like
         * "godaddy.com … offer expires 30-09-2026" into a due for the wrong domain.
         */
        val CANCELS = Regex(
            """Cancels\b[\s:|]*(?:on\s+)?(""" +
                """\d{1,2}[-/]\d{1,2}[-/]\d{2,4}""" +
                """|\d{1,2}\s+[A-Za-z]{3,9},?\s+\d{4}""" +
                """|[A-Za-z]{3,9}\s+\d{1,2},?\s+\d{4})""",
            RegexOption.IGNORE_CASE
        )

        /** A domain name: labels, then a TLD of at least two letters. */
        val DOMAIN = Regex(
            """\b[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\.[a-z0-9-]{1,63})*\.[a-z]{2,24}\b""",
            RegexOption.IGNORE_CASE
        )

        /**
         * The price cell. A currency token is required: without one, the `2` of "2 years"
         * would read as a price, and GoDaddy bills the same account in ₹ and in $. The
         * word boundary on the letter tokens matters — `rs` sits inside "years".
         */
        val PRICE = Regex(
            """(?:₹|\bRs\.?|\bINR\b|US\$|\$|\bUSD\b)\s*[\d,]+(?:\.\d{1,2})?""",
            RegexOption.IGNORE_CASE
        )

        /** The term cell — "1 year", "2 years", "12 months". Kept as the period label. */
        val TERM = Regex("""\b\d{1,2}\s*(?:years?|yrs?|months?|mos?)\b""", RegexOption.IGNORE_CASE)
    }
}
