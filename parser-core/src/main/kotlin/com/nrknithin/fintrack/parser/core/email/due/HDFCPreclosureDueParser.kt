package com.nrknithin.fintrack.parser.core.email.due

import com.nrknithin.fintrack.parser.core.email.EmailMessage
import com.nrknithin.fintrack.parser.core.email.EmailSenderRegistry
import com.nrknithin.fintrack.parser.core.text.Money
import com.nrknithin.fintrack.parser.core.text.ParsedMoney
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * HDFC Bank's loan-preclosure confirmation (spec 12 §12.1, template H12).
 *
 * The lender writes "we have preclosed your loan A/C …" with the settlement figure and
 * "Kindly make the payment on the next day", so the mail is a one-off payable due
 * tomorrow — [DueKind.LOAN_PRECLOSURE] rather than [DueKind.LOAN_EMI], because it ends
 * the loan instead of advancing its schedule.
 *
 * **The figure must come from the mail.** This is the case spec 12 records as having gone
 * wrong in real life: the app's own amortised outstanding and the lender's preclosure
 * quote are different numbers, and the one that settles the loan is the lender's. So the
 * amount is only ever read from a preclosure-anchored, currency-prefixed capture; when
 * the mail states no such figure this parser yields **no due at all** rather than a zero
 * or an estimate (spec 01, constraint C7). A missing due is recoverable — a wrong figure
 * shown as the amount to pay is not.
 */
class HDFCPreclosureDueParser : DueParser {

    override val id: String = "HDFC_PRECLOSURE"

    /**
     * Spec 12 names `information@hdfcbank.bank.in`, but the whole bank domain is accepted:
     * HDFC rotates local parts (spec 11 §11.6) and the preclosure wording below is
     * unmistakable, so nothing else can match. The marketing subdomain stays out.
     */
    override fun canHandle(from: String): Boolean {
        val address = EmailSenderRegistry.normaliseAddress(from) ?: return false
        val domain = address.substringAfter('@', "")
        if (domain.isEmpty() || domain.endsWith(MARKETING_DOMAIN)) return false
        return domain == BANK_DOMAIN || domain.endsWith(".$BANK_DOMAIN")
    }

    override fun parse(message: EmailMessage): List<ParsedDue> {
        if (!canHandle(message.from)) return emptyList()

        val text = flatten(message.searchText)
        if (!PRECLOSURE_WORD.containsMatchIn(text)) return emptyList()

        val money = statedAmount(text) ?: return emptyList()
        if (money.amount.signum() <= 0) return emptyList()

        // "Kindly make the payment on the next day" — the mail carries no due date of its
        // own, so the received date (in IST, the zone the bank writes in) plus one day is
        // the date the bank actually asked for.
        val dueDate = runCatching { receivedDate(message.receivedAt).plusDays(1) }.getOrNull()
            ?: return emptyList()

        return listOf(
            ParsedDue(
                issuer = ISSUER,
                kind = DueKind.LOAN_PRECLOSURE,
                amount = money.amount,
                dueDate = dueDate,
                currency = money.currency,
                reference = loanAccount(text),
                source = SOURCE,
                description = "Preclosure amount as stated by HDFC Bank; payable the next day"
            )
        )
    }

    /**
     * The preclosed amount, or null when the mail does not state one unambiguously.
     *
     * Patterns are tried in order of how tightly they bind the number to the word
     * "preclosure", and the first pattern that matches at all decides — a later, looser
     * pattern never gets to overrule a tighter one. Every pattern requires a currency
     * token immediately before the digits, which is what keeps a loan account number or a
     * date out of the amount. If the winning pattern matches several *different* figures
     * the mail is ambiguous and the answer is null: picking one of them would be a guess.
     */
    private fun statedAmount(text: String): ParsedMoney? {
        for (pattern in AMOUNT_PATTERNS) {
            val found = pattern.findAll(text)
                .mapNotNull { Money.parse(it.groupValues[1]) }
                .toList()
            if (found.isEmpty()) continue

            val distinct = found.distinctBy { it.amount to it.currency }
            return if (distinct.size == 1) distinct.first() else null
        }
        return null
    }

    /**
     * The loan account the mail names, kept as the due's reference so the H4 netbanking
     * payment can be matched back to it. Null when the mail names none, or names only a
     * fully masked one — spec 12's mapping wants the account, but an absent account is no
     * reason to drop a due whose amount and date are both stated.
     */
    private fun loanAccount(text: String): String? {
        val raw = LOAN_ACCOUNT.find(text)?.groupValues?.get(1)?.trim()?.trimEnd('.', ',')
        if (raw.isNullOrEmpty()) return null
        return if (raw.count { it.isDigit() } >= MIN_ACCOUNT_DIGITS) raw else null
    }

    private fun receivedDate(epochMillis: Long): LocalDate =
        Instant.ofEpochMilli(epochMillis).atZone(IST).toLocalDate()

    /**
     * One line, single spaces. `text/plain` arrives as the bank sent it while an
     * HTML-derived body has its table cells joined with ` | ` (spec 11 §11.5); flattening
     * both to the same shape lets one regex read either.
     */
    private fun flatten(raw: String): String =
        WHITESPACE.replace(raw.replace('|', ' '), " ").trim()

    private companion object {
        const val BANK_DOMAIN = "hdfcbank.bank.in"
        const val MARKETING_DOMAIN = "mailers.hdfcbank.bank.in"
        const val SOURCE = "HDFC"
        const val ISSUER = "HDFC Bank"

        /** Four digits is the least that identifies an account; fewer is noise. */
        const val MIN_ACCOUNT_DIGITS = 4

        val IST: ZoneId = ZoneId.of("Asia/Kolkata")

        /** A currency token is mandatory here — see [statedAmount]. */
        const val MONEY = """((?:Rs\.?\s*INR|INR|Rs\.?|₹)\s*[\d,]+(?:\.\d{1,2})?)"""

        val PRECLOSURE_WORD = Regex("""preclos(?:e|ed|es|ure|ing)\b""", RegexOption.IGNORE_CASE)

        /** Tightest first; see [statedAmount] for why the order matters. */
        val AMOUNT_PATTERNS = listOf(
            // "Preclosed amount : Rs. 2,53,471.86" / "Preclosure loan value of INR …"
            Regex(
                """preclos\w*\s+(?:loan\s+)?(?:amount|value)[\s\S]{0,40}?$MONEY""",
                RegexOption.IGNORE_CASE
            ),
            // "Rs. 2,53,471.86 towards preclosure of the above loan"
            Regex(
                """$MONEY[\s\S]{0,60}?\btowards\s+(?:the\s+)?preclos\w*""",
                RegexOption.IGNORE_CASE
            ),
            // "we have preclosed your loan A/C 4411002233 for Rs. 2,53,471.86"
            Regex(
                """preclos\w*[\s\S]{0,120}?\bfor\s+$MONEY""",
                RegexOption.IGNORE_CASE
            )
        )

        val LOAN_ACCOUNT = Regex(
            """loan\s+(?:a\s*/?\s*c|account)\.?\s*(?:no\.?|number)?\s*[:\-]?\s*([0-9Xx*]{4,})""",
            RegexOption.IGNORE_CASE
        )

        val WHITESPACE = Regex("""\s+""")
    }
}
