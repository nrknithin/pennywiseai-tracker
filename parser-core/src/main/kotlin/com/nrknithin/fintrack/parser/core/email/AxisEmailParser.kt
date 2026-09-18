package com.nrknithin.fintrack.parser.core.email

import com.nrknithin.fintrack.parser.core.ParsedTransaction
import com.nrknithin.fintrack.parser.core.TransactionType
import com.nrknithin.fintrack.parser.core.text.DateParse
import com.nrknithin.fintrack.parser.core.text.Money
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Axis Bank alert mail — templates A1-A4 and A6 of spec 12 §12.2.
 *
 * Axis states one fact per line behind a label ending in `:` instead of writing prose,
 * so every field is read by its own label-anchored regex rather than by one
 * template-wide pattern. A line that moves, or that Axis stops sending, then costs that
 * one field instead of the whole transaction. The same labels survive the HTML-to-text
 * conversion of spec 11 §11.5, which can leave ` | ` between a label and its value or
 * push the value onto the next line, so every label pattern tolerates both.
 *
 * | Template | Recognised by | Result |
 * |---|---|---|
 * | A1 | `Amount Debited:` | EXPENSE from the savings account |
 * | A2 | `Amount Credited:` | INCOME, or TRANSFER when the counterparty is the holder |
 * | A3 | `ATM-WDL/` in the debit sentence | EXPENSE, merchant `ATM`, balance after |
 * | A4 | `Transaction Amount:` on a credit-card mail | CREDIT, with the card's total limit |
 * | A6 | password / cooling-period / limit-reduction wording | [isNonTransactionEmail] |
 *
 * The A4 mail also states the card's **available** limit. [ParsedTransaction] has no
 * field for it, and `balance` means "balance after" — a savings figure — so the
 * available limit is deliberately not mapped here; the card's outstanding
 * (`total - available`, spec 12 §12.2 and 25 R-25-03) has to be derived where the
 * balance snapshot is written, from the stored body.
 *
 * @param accountHolderNames names the mailbox owner's own accounts are held in. A UPI
 *   `P2A` counterparty matching one of these is the user moving money between their own
 *   banks — a TRANSFER, never income (spec 14 §14.5). Empty by default: the app passes
 *   `settings.accounts.own`, and with nothing configured the parser reports what the
 *   mail says rather than guessing at an identity.
 */
class AxisEmailParser(
    private val accountHolderNames: Set<String> = emptySet()
) : EmailTransactionParser {

    override val id: String = "AXIS_EMAIL"

    override fun canHandle(from: String): Boolean {
        val address = EmailSenderRegistry.normaliseAddress(from) ?: return false
        val domain = address.substringAfter('@', "")
        return domain == AXIS_DOMAIN || domain.endsWith(".$AXIS_DOMAIN")
    }

    override fun parse(message: EmailMessage): ParsedTransaction? {
        if (isNonTransactionEmail(message)) return null
        val body = message.body
        // Order matters. A4 and A3 carry markers the savings templates never do, and
        // A1/A2's labels are the fallback for the rest of what this address sends.
        return parseCardPurchase(message, body)
            ?: parseAtmWithdrawal(message, body)
            ?: parseSavingsMovement(message, body)
    }

    override fun isNonTransactionEmail(message: EmailMessage): Boolean {
        val text = message.searchText.lowercase()
        // A transaction label outranks the A6 wording: a limit-reduction notice and a
        // card purchase both talk about limits, and dropping a real purchase would be
        // silent data loss (spec 01, constraint C7).
        if (TRANSACTION_MARKER.containsMatchIn(text)) return false
        return IGNORED_PHRASES.any { text.contains(it) } ||
            IGNORED_PATTERNS.any { it.containsMatchIn(text) }
    }

    // ========================================
    // A1 / A2 · savings debit and credit
    // ========================================

    private fun parseSavingsMovement(message: EmailMessage, body: String): ParsedTransaction? {
        val amountMatch = AMOUNT_LABEL.find(body) ?: return null
        val money = Money.parse(amountMatch.groupValues[2]) ?: return null
        val debited = amountMatch.groupValues[1].equals("Debited", ignoreCase = true)

        val accountLast4 = lastFour(ACCOUNT_LABEL.find(body)?.groupValues?.get(1))
        val info = cleanValue(INFO_LABEL.find(body)?.groupValues?.get(1))
        val upi = info?.let { UPI_INFO.find(it) }

        var type = if (debited) TransactionType.EXPENSE else TransactionType.INCOME
        var merchant = info
        var reference: String? = null
        var fromAccount: String? = null
        var toAccount: String? = null

        if (upi != null) {
            // UPI/P2M/<ref12>/<merchant>, or UPI/P2A/<ref12>/<counterparty>/<bank>/UPI.
            val subFormat = upi.groupValues[1].uppercase()
            reference = upi.groupValues[2]
            val counterparty = cleanValue(upi.groupValues[3])
            val counterpartyBank = cleanValue(upi.groupValues[4])
            merchant = counterparty

            if (subFormat != "P2M" && isAccountHolder(counterparty)) {
                type = TransactionType.TRANSFER
                if (debited) {
                    fromAccount = accountLast4
                    toAccount = counterpartyBank
                } else {
                    fromAccount = counterpartyBank
                    toAccount = accountLast4
                }
            }
        } else if (info != null && info.startsWith(ATM_INFO_PREFIX, ignoreCase = true)) {
            // The same withdrawal sometimes arrives in the label layout rather than as
            // the A3 sentence; the merchant is the machine either way.
            merchant = ATM_MERCHANT
        }

        return ParsedTransaction(
            amount = money.amount,
            type = type,
            merchant = merchant,
            reference = reference,
            accountLast4 = accountLast4,
            balance = Money.amount(BALANCE_LABEL.find(body)?.groupValues?.get(1)),
            smsBody = message.body,
            sender = message.from,
            timestamp = labelTimestamp(message, body),
            bankName = BANK_NAME,
            isFromCard = false,
            currency = money.currency,
            fromAccount = fromAccount,
            toAccount = toAccount
        )
    }

    // ========================================
    // A3 · ATM withdrawal
    // ========================================

    private fun parseAtmWithdrawal(message: EmailMessage, body: String): ParsedTransaction? {
        val match = ATM_WITHDRAWAL.find(body) ?: return null
        val money = Money.parse(match.groupValues[1]) ?: return null

        return ParsedTransaction(
            amount = money.amount,
            type = TransactionType.EXPENSE,
            merchant = ATM_MERCHANT,
            // The ATM id is the only identifier this template carries, and it is what
            // ties the mail to the SMS twin for the same withdrawal.
            reference = cleanValue(match.groupValues[5]),
            accountLast4 = lastFour(match.groupValues[2]),
            balance = Money.amount(BALANCE_LABEL.find(body)?.groupValues?.get(1)),
            smsBody = message.body,
            sender = message.from,
            timestamp = bodyTimestamp(message, match.groupValues[3], match.groupValues[4]),
            bankName = BANK_NAME,
            isFromCard = false,
            currency = money.currency
        )
    }

    // ========================================
    // A4 · Credit-card purchase
    // ========================================

    private fun parseCardPurchase(message: EmailMessage, body: String): ParsedTransaction? {
        if (!CARD_TEMPLATE_MARKER.containsMatchIn(message.searchText)) return null
        val money = Money.parse(CARD_AMOUNT.find(body)?.groupValues?.get(1)) ?: return null

        return ParsedTransaction(
            amount = money.amount,
            type = TransactionType.CREDIT,
            merchant = cleanValue(CARD_MERCHANT.find(body)?.groupValues?.get(1)),
            reference = null,
            accountLast4 = lastFour(CARD_NUMBER.find(body)?.groupValues?.get(1)),
            // A card mail states limits, never an account balance.
            balance = null,
            creditLimit = Money.amount(TOTAL_CREDIT_LIMIT.find(body)?.groupValues?.get(1)),
            smsBody = message.body,
            sender = message.from,
            timestamp = labelTimestamp(message, body),
            bankName = BANK_NAME,
            isFromCard = true,
            currency = money.currency
        )
    }

    // ========================================
    // Helpers
    // ========================================

    /**
     * True when `candidate` names the mailbox owner.
     *
     * Axis truncates the counterparty in the UPI info field (`ACCOUNT H` for
     * `ACCOUNT HOLDER`), so a prefix match in either direction is the only way a real
     * self-transfer is recognised; [MIN_NAME_MATCH] keeps an initial or two from
     * matching a stranger who happens to share them.
     */
    private fun isAccountHolder(candidate: String?): Boolean {
        if (candidate == null || accountHolderNames.isEmpty()) return false
        val name = normaliseName(candidate) ?: return false
        return accountHolderNames.any { holder ->
            val known = normaliseName(holder)
            when {
                known == null -> false
                known == name -> true
                name.length < MIN_NAME_MATCH -> false
                else -> known.startsWith(name) || name.startsWith(known)
            }
        }
    }

    /** The `Date & Time:` line when the mail has one, else the received time (spec 14 §14.2). */
    private fun labelTimestamp(message: EmailMessage, body: String): Long {
        val match = DATE_TIME_LABEL.find(body) ?: return message.receivedAt
        return bodyTimestamp(message, match.groupValues[1], match.groupValues[2])
    }

    private fun bodyTimestamp(message: EmailMessage, rawDate: String, rawTime: String): Long {
        val dateTime = DateParse.parse(rawDate, rawTime) ?: return message.receivedAt
        return toEpochMillis(dateTime)
    }

    private fun toEpochMillis(dateTime: LocalDateTime): Long =
        dateTime.atZone(ZONE).toInstant().toEpochMilli()

    private companion object {

        const val BANK_NAME = "Axis Bank"
        const val AXIS_DOMAIN = "axis.bank.in"
        const val ATM_MERCHANT = "ATM"
        const val ATM_INFO_PREFIX = "ATM-WDL"

        /** Axis timestamps its alerts in IST, and says so on the line (`... IST`). */
        val ZONE: ZoneId = ZoneId.of("Asia/Kolkata")

        /** Shortest counterparty name allowed to match a holder name by prefix. */
        const val MIN_NAME_MATCH = 4

        /**
         * The spec's MONEY convention, captured **with** its currency token so
         * `Money.parse` reports the currency the mail actually named.
         *
         * The gap before the digits is spaces and tabs only, never a newline: a label
         * whose value went missing would otherwise reach across the line break and read
         * the day out of the next line's date as an amount.
         */
        const val MONEY = """((?:INR|Rs\.?|₹)?[ \t]*[\d,]+(?:\.\d{1,2})?)"""

        /** Between a label's colon and its value: a cell separator, a line break, or neither. */
        const val AFTER_LABEL = """\s*:\s*\|?\s*"""

        /** A value that ends at the next HTML cell boundary or at the end of the line. */
        const val LINE_VALUE = """([^|\n]+)"""

        // --- A1 / A2 ---------------------------------------------------------
        val AMOUNT_LABEL = Regex(
            """Amount\s+(Debited|Credited)$AFTER_LABEL$MONEY""",
            RegexOption.IGNORE_CASE
        )
        val ACCOUNT_LABEL = Regex(
            """Account\s+(?:Number|No\.?)\s*:?\s*\|?\s*[X*]*\s*(\d{4,})""",
            RegexOption.IGNORE_CASE
        )
        val INFO_LABEL = Regex("""Transaction\s+Info$AFTER_LABEL$LINE_VALUE""", RegexOption.IGNORE_CASE)
        val BALANCE_LABEL = Regex("""Available\s+Balance\s*:?\s*\|?\s*$MONEY""", RegexOption.IGNORE_CASE)

        /** `Date & Time: 16-09-26, 15:59:12 IST` — and `15-09-2026` on the card template. */
        val DATE_TIME_LABEL = Regex(
            """Date\s*(?:&(?:amp;)?|and)\s*Time$AFTER_LABEL(\d{1,2}-\d{1,2}-\d{2,4})\s*,?\s*\|?\s*(\d{1,2}:\d{2}(?::\d{2})?)""",
            RegexOption.IGNORE_CASE
        )

        /**
         * The UPI sub-format inside `Transaction Info`. `P2M` names a merchant; `P2A`
         * and `P2P` name a person and the bank their account sits at.
         */
        val UPI_INFO = Regex(
            """^UPI/(P2M|P2A|P2P)/(\d{12})/([^/]+)(?:/([^/]+))?""",
            RegexOption.IGNORE_CASE
        )

        // --- A3 ---------------------------------------------------------------
        val ATM_WITHDRAWAL = Regex(
            """$MONEY\s+(?:has been|was|is)\s+debited\s+from\s+your\s+A/c(?:\s*no\.?)?\s*[X*]*\s*(\d{4,})""" +
                """\s+on\s+(\d{1,2}-\d{1,2}-\d{2,4})\s+(\d{1,2}:\d{2}(?::\d{2})?)\s*(?:IST)?""" +
                """[\s\S]{0,40}?ATM-WDL/([^/\s|]+)""",
            RegexOption.IGNORE_CASE
        )

        // --- A4 ---------------------------------------------------------------
        /** What tells a card mail from a savings mail before any amount is read. */
        val CARD_TEMPLATE_MARKER = Regex(
            """spent\s+on\s+credit\s+card|Credit\s+Card\s+(?:No|Number)|Total\s+Credit\s+Limit""",
            RegexOption.IGNORE_CASE
        )
        val CARD_AMOUNT = Regex("""Transaction\s+Amount$AFTER_LABEL$MONEY""", RegexOption.IGNORE_CASE)
        val CARD_MERCHANT = Regex("""Merchant\s+Name$AFTER_LABEL$LINE_VALUE""", RegexOption.IGNORE_CASE)
        val CARD_NUMBER = Regex(
            """Credit\s+Card\s+(?:No\.?|Number)\s*:?\s*\|?\s*[X*]*\s*(\d{4,})""",
            RegexOption.IGNORE_CASE
        )
        val TOTAL_CREDIT_LIMIT = Regex(
            """Total\s+Credit\s+Limit\s*\*?\s*:?\s*\|?\s*$MONEY""",
            RegexOption.IGNORE_CASE
        )

        // --- A6 ---------------------------------------------------------------
        /** Any of these means the mail states money that moved, whatever else it says. */
        val TRANSACTION_MARKER = Regex(
            """amount\s+(?:debited|credited)\s*:|transaction\s+amount\s*:|atm-wdl/""",
            RegexOption.IGNORE_CASE
        )

        /** The A6 ignore set: lifecycle and security notices from the alerts address. */
        val IGNORED_PHRASES = listOf(
            "net banking password",
            "netbanking password",
            "password has been changed",
            "password has been reset",
            "password change request",
            "cooling period"
        )

        /**
         * A6 wording that puts words between the subject and the verb — Axis names the
         * card in the middle of the sentence ("the credit limit on your card no. XX…
         * has been reduced"), which no fixed phrase can catch.
         */
        val IGNORED_PATTERNS = listOf(
            Regex("""limit[^\n]{0,60}?(?:has been|was|is being)\s+reduced""", RegexOption.IGNORE_CASE),
            Regex("""reduction in (?:your|the)[^\n]{0,30}?limit""", RegexOption.IGNORE_CASE)
        )

        val WHITESPACE = Regex("""\s+""")

        /** Trailing four digits of a captured run — `XX881234` reads as `1234` (spec 12). */
        fun lastFour(raw: String?): String? {
            val digits = raw?.filter { it.isDigit() } ?: return null
            return if (digits.length >= 4) digits.takeLast(4) else null
        }

        /** Collapses what HTML-to-text leaves behind and drops sentence punctuation. */
        fun cleanValue(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            val collapsed = WHITESPACE.replace(raw.replace('|', ' '), " ").trim()
            return collapsed.trimEnd('.', ',', '-', ' ').ifEmpty { null }
        }

        fun normaliseName(raw: String): String? {
            val kept = raw.uppercase().map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("")
            return WHITESPACE.replace(kept, " ").trim().ifEmpty { null }
        }
    }
}
