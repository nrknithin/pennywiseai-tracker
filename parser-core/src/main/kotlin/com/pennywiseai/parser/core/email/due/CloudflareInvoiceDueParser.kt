package com.pennywiseai.parser.core.email.due

import com.pennywiseai.parser.core.email.EmailMessage
import com.pennywiseai.parser.core.email.EmailSenderRegistry
import com.pennywiseai.parser.core.text.DateParse
import com.pennywiseai.parser.core.text.Money

/**
 * Cloudflare "Your invoice is available" — spec 12 §12.7 CF1 (corpus:
 * `noreply@notify.cloudflare.com`, monthly, HTML body).
 *
 * The line the mail is read for is
 *
 *     Invoice IN-1234567: $0.00 due October 1, 2026
 *
 * and **most months that figure really is $0.00**: a free-plan account is invoiced every
 * month whether or not it owes anything. A zero invoice is therefore not a due at all —
 * it is the normal case — so only a figure greater than zero produces one. Showing "$0.00
 * due 1 Oct" in Pending would be a standing piece of noise the user has to dismiss every
 * month, and spec 20 R-20-09 takes the same line for card statements with nothing due.
 *
 * Every invoice line in the mail is read, so an account billed on several invoices in one
 * notice yields one due per invoice that actually has an amount.
 */
class CloudflareInvoiceDueParser : DueParser {

    override val id: String = "CLOUDFLARE_INVOICE"

    override fun canHandle(from: String): Boolean {
        val address = EmailSenderRegistry.normaliseAddress(from) ?: return false
        return address.endsWith("@cloudflare.com") || address.endsWith(".cloudflare.com")
    }

    override fun parse(message: EmailMessage): List<ParsedDue> {
        val dues = LinkedHashMap<String, ParsedDue>()

        for (match in INVOICE.findAll(message.body)) {
            val (reference, rawAmount, rawDate) = match.destructured

            // CF1's whole rule: no amount, or nothing owed, means no due.
            val money = Money.parse(rawAmount)?.takeIf { it.amount.signum() > 0 } ?: continue
            val dueDate = DateParse.parseDate(rawDate) ?: continue

            // A notice that repeats the same invoice in a summary block states one bill,
            // not two.
            val invoiceNumber = reference.uppercase()
            if (dues.containsKey(invoiceNumber)) continue

            dues[invoiceNumber] = ParsedDue(
                issuer = ISSUER,
                kind = DueKind.INVOICE,
                amount = money.amount,
                dueDate = dueDate,
                currency = money.currency,
                reference = invoiceNumber,
                source = SOURCE
            )
        }

        return dues.values.toList()
    }

    private companion object {
        const val ISSUER = "Cloudflare"
        const val SOURCE = "CLOUDFLARE"

        /**
         * `Invoice IN-1234567: $8.50 due October 1, 2026` (spec 12 CF1). The invoice-number
         * prefix, the dollar figure and the "due <date>" tail all have to be present, which
         * is what keeps this parser off every other mail Cloudflare sends.
         */
        val INVOICE = Regex(
            """Invoice\s+(IN-\d+)\s*[:\-]?\s*""" +
                """(\$\s*[\d,]+(?:\.\d{1,2})?|[\d,]+(?:\.\d{1,2})?\s*USD)\s*(?:USD)?\s*""" +
                """(?:is\s+)?due\s+([A-Za-z]{3,9}\s+\d{1,2},?\s*\d{4})""",
            RegexOption.IGNORE_CASE
        )
    }
}
