package com.nrknithin.fintrack.parser.core.email.due

import com.nrknithin.fintrack.parser.core.email.EmailMessage
import com.nrknithin.fintrack.parser.core.email.EmailSenderRegistry
import com.nrknithin.fintrack.parser.core.text.DateParse
import com.nrknithin.fintrack.parser.core.text.Money

/**
 * HDFC Bank's "upcoming E-mandate (Auto payment)" notice (spec 12 §12.1, template H9).
 *
 * The mail lands about three days before a standing instruction charges a card, and for
 * several merchants it is the *only* announcement the user gets — some subscriptions never
 * email a bill of their own, and some are visible only through this template (spec 10 §10.4).
 *
 * It becomes a due with [ParsedDue.autoPay] set: the money leaves without the user doing
 * anything, so the app shows "will be auto-debited on <date> from <card>" and only
 * escalates when the debit fails to arrive (spec 01, rule 5). The SI Hub ID is carried as
 * [ParsedDue.reference] so the H8 "e-mandate paid" mail settles exactly this due rather
 * than a same-amount neighbour (spec 21 §21.2, rule 1).
 *
 * Kind is [DueKind.SUBSCRIPTION]: spec 12 writes `SUBSCRIPTION_CHARGE`, which is not a
 * value of the enum — see the reconciliation note on [DueKind].
 */
class HDFCMandateDueParser : DueParser {

    override val id: String = "HDFC_UPCOMING_MANDATE"

    /**
     * Any HDFC Bank address, not just `alerts@`, because the bank rotates local parts
     * (spec 11 §11.6) and the body trigger below is what really decides. The marketing
     * subdomain is excluded outright: `IgnoreRules` already drops it, and a parser that
     * can be reached on its own should not depend on that.
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
        if (!text.contains(TRIGGER, ignoreCase = true)) return emptyList()

        val match = UPCOMING_MANDATE.find(text) ?: return emptyList()

        val money = Money.parse(match.groupValues[1]) ?: return emptyList()
        // A mandate for nothing is a failed capture, not a free month (spec 01, C7).
        if (money.amount.signum() <= 0) return emptyList()

        val merchant = cleanMerchant(match.groupValues[2])
        if (merchant.isEmpty()) return emptyList()

        val dueDate = DateParse.parseDate(match.groupValues[5]) ?: return emptyList()

        // Spec 12 conventions: the last four digits of whatever run the card group caught.
        val last4 = match.groupValues[4].takeLast(4)
        val cardType = match.groupValues[3].lowercase()
            .replaceFirstChar { it.uppercase() }
        val siHubId = match.groupValues[6].trim().ifEmpty { null }

        return listOf(
            ParsedDue(
                issuer = merchant,
                kind = DueKind.SUBSCRIPTION,
                amount = money.amount,
                dueDate = dueDate,
                currency = money.currency,
                accountLast4 = last4,
                reference = siHubId,
                autoPay = true,
                autoPayInstrument = "HDFC \u2026$last4",
                source = SOURCE,
                description = "Auto-pay on the HDFC $cardType Card ending $last4"
            )
        )
    }

    /** Drops the punctuation HTML-to-text conversion leaves clinging to a merchant name. */
    private fun cleanMerchant(raw: String): String =
        raw.trim().trim('.', ',', '-', '|', ' ')

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

        /** Cheap pre-filter; the template regex is the real test. */
        const val TRIGGER = "upcoming"

        /**
         * Spec 12 H9, with three widenings, none of which invents a value:
         *  - the `INR` prefix is optional, because the amount sits between `of` and `for`
         *    where nothing else can be mistaken for it;
         *  - the card run may be prefixed `XX` and may be longer than four digits (H10
         *    writes `xx1234`), and the last four are taken from whatever is captured;
         *  - the trailing `SI Hub ID` is optional. A notice without one still states an
         *    amount, a card and a date, and is worth more as a due with no reference than
         *    as nothing at all; settlement then falls back to amount + card + date,
         *    which is how the H7 twin settles anyway.
         */
        val UPCOMING_MANDATE = Regex(
            """upcoming\s+E-?\s?mandate\s*\(\s*Auto\s*payment\s*\)\s*of\s+""" +
                """((?:Rs\.?\s*INR|INR|Rs\.?|₹)?\s*[\d,]+(?:\.\d{1,2})?)\s+for\s+(.+?)\.\s*""" +
                """Amount\s+will\s+be\s+debited\s+from\s+your\s+HDFC\s+Bank\s+(Credit|Debit)\s+""" +
                """Card\s+ending\s+(?:XX\s*)?(\d{4,})\s+on\s+(\d{1,2}/\d{1,2}/\d{2,4})""" +
                """(?:\s*\.?\s*SI\s*Hub\s*ID\s*:?\s*([A-Za-z0-9._-]+))?""",
            RegexOption.IGNORE_CASE
        )

        val WHITESPACE = Regex("""\s+""")
    }
}
