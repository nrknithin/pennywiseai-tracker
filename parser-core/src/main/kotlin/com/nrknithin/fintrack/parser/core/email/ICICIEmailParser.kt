package com.nrknithin.fintrack.parser.core.email

import com.nrknithin.fintrack.parser.core.ParsedTransaction
import com.nrknithin.fintrack.parser.core.TransactionType
import com.nrknithin.fintrack.parser.core.text.DateParse
import com.nrknithin.fintrack.parser.core.text.Money
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * ICICI Bank credit-card mail — `credit_cards@icici.bank.in` (spec 12 §12.3).
 *
 * Two templates carry money:
 *  - **I1 · Purchase** — one sentence naming the card, the amount, the date and time, the
 *    merchant after `Info:`, and *both* limits: the available credit limit and the total
 *    credit limit. The pair is what lets the app show card utilisation without waiting for
 *    a statement (spec 10, note 9).
 *  - **I2 · Payment received** — money moving *into* the card, so TRANSFER rather than
 *    INCOME. The card is written as a spaced masked PAN (`5241 XXXX XXXX 4821`); only its
 *    trailing four digits identify the account.
 *
 * The monthly statement (I3) is a *due*, not a transaction: it is left to the due parser and
 * reported here as a non-transaction so nothing downstream reads its "total amount due" as
 * money that moved (spec 11 §11.4, steps b and d).
 */
class ICICIEmailParser : EmailTransactionParser {

    override val id: String = "ICICI_EMAIL"

    override fun canHandle(from: String): Boolean {
        val address = EmailSenderRegistry.normaliseAddress(from) ?: return false
        // The exact card-alerts address, plus any local part the bank rotates to on the
        // same domain. Sub-domains (`custcomm.icici.bank.in`) are marketing and excluded.
        return address == SENDER_ADDRESS || address.substringAfter("@", "") == SENDER_DOMAIN
    }

    /**
     * Deliberately not gated on [canHandle]: a user can map a new ICICI address onto this
     * parser from the Email Log (spec 11 R-11-10), and both templates below name the bank
     * and the card, so they identify themselves.
     */
    override fun parse(message: EmailMessage): ParsedTransaction? =
        parsePurchase(message) ?: parsePaymentReceived(message)

    override fun isNonTransactionEmail(message: EmailMessage): Boolean =
        STATEMENT_SUBJECT.containsMatchIn(message.subject) &&
            !PURCHASE.containsMatchIn(message.body) &&
            !PAYMENT_RECEIVED.containsMatchIn(message.body)

    // ========================================
    // I1 · Purchase
    // ========================================

    private fun parsePurchase(message: EmailMessage): ParsedTransaction? {
        val match = PURCHASE.find(message.body) ?: return null
        val money = Money.parse(match.groupValues[2]) ?: return null
        val cardLast4 = lastFour(match.groupValues[1]) ?: return null
        val occurredAt = DateParse.parse(match.groupValues[3], match.groupValues[4])

        // Both limits are optional. They describe the card rather than the transaction, so
        // a variant that omits them still yields the purchase instead of being dropped.
        val availableCreditLimit = amountAfter(AVAILABLE_LIMIT, message.body)
        val totalCreditLimit = amountAfter(TOTAL_LIMIT, message.body)

        return ParsedTransaction(
            amount = money.amount,
            type = TransactionType.CREDIT,
            merchant = merchantFromInfo(message.body),
            reference = null,
            accountLast4 = cardLast4,
            // On a credit card the stated "available credit limit" is the balance-after:
            // what is left to spend. The outstanding figure the account screen shows is
            // `creditLimit - balance`, computed where the two are stored (spec 12 §12.2, A4).
            balance = availableCreditLimit,
            creditLimit = totalCreditLimit,
            smsBody = message.body,
            sender = message.from,
            timestamp = occurredAt?.let { toEpochMillis(it) } ?: message.receivedAt,
            bankName = BANK_NAME,
            isFromCard = true,
            currency = money.currency
        )
    }

    // ========================================
    // I2 · Payment received on the card
    // ========================================

    private fun parsePaymentReceived(message: EmailMessage): ParsedTransaction? {
        val match = PAYMENT_RECEIVED.find(message.body) ?: return null
        val money = Money.parse(match.groupValues[1]) ?: return null
        val cardLast4 = lastFour(match.groupValues[2]) ?: return null
        val occurredOn = DateParse.parseDate(match.groupValues[3])

        return ParsedTransaction(
            amount = money.amount,
            type = TransactionType.TRANSFER,
            merchant = CARD_PAYMENT_MERCHANT,
            reference = null,
            accountLast4 = cardLast4,
            balance = null,
            creditLimit = null,
            smsBody = message.body,
            sender = message.from,
            timestamp = occurredOn?.atStartOfDay()?.let { toEpochMillis(it) } ?: message.receivedAt,
            bankName = BANK_NAME,
            isFromCard = true,
            currency = money.currency,
            // The mail says what was paid, never which account paid it. The dedup rules
            // match the paying debit by amount (spec 14 §14.3, L5); naming a source here
            // would be inventing a fact.
            fromAccount = null,
            toAccount = cardLast4
        )
    }

    private fun merchantFromInfo(body: String): String? =
        INFO.find(body)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    private fun amountAfter(pattern: Regex, body: String): BigDecimal? =
        pattern.find(body)?.let { Money.amount(it.groupValues[1]) }

    private companion object {
        private const val BANK_NAME = "ICICI Bank"
        private const val SENDER_ADDRESS = "credit_cards@icici.bank.in"
        private const val SENDER_DOMAIN = "icici.bank.in"

        /** Mirrors HDFC's H4 label so both card-bill payments read the same in the list. */
        private const val CARD_PAYMENT_MERCHANT = "Credit Card Payment"

        private val ZONE: ZoneId = ZoneId.of("Asia/Kolkata")

        /**
         * I1 core: card, amount, date (`Sep 04, 2026`) and time. Spec 12 §12.3 folds the
         * merchant and both limits into this one regex; they are matched separately below
         * so a purchase mail that omits any of them is still read rather than dropped.
         */
        private val PURCHASE = Regex(
            """ICICI Bank Credit Card\s+(?:XX|\*+)?(\d{4})\s+has been used for a transaction of""" +
                """\s+(?:INR|Rs\.?|₹)\s*([\d,]+(?:\.\d{1,2})?)\s+on\s+([A-Za-z]{3,9}\s+\d{1,2},?\s*\d{4})""" +
                """\s+at\s+(\d{1,2}:\d{2}(?::\d{2})?)""",
            RegexOption.IGNORE_CASE
        )

        /** The merchant runs to the end of the sentence; a pipe or newline also ends it. */
        private val INFO = Regex("""Info\s*:\s*([^.\n|]+)""", RegexOption.IGNORE_CASE)

        private val AVAILABLE_LIMIT = Regex(
            """Available Credit Limit[^0-9]*([\d,]+(?:\.\d{1,2})?)""",
            RegexOption.IGNORE_CASE
        )

        private val TOTAL_LIMIT = Regex(
            """Total Credit Limit\s*(?:is)?\s*(?:INR|Rs\.?|₹)?\s*([\d,]+(?:\.\d{1,2})?)""",
            RegexOption.IGNORE_CASE
        )

        /**
         * I2: the account is a spaced masked PAN, so the capture is a run of digits, `X`
         * and separators, closed by the ` on <date>` that follows it.
         */
        private val PAYMENT_RECEIVED = Regex(
            """received payment of\s+(?:INR|Rs\.?|₹)\s*([\d,]+(?:\.\d{1,2})?)""" +
                """\s+on your ICICI Bank Credit Card(?:\s+account)?\s+([0-9Xx*][0-9Xx*\s-]{2,24}?)""" +
                """\s+on\s+(\d{1,2}[-/\s][A-Za-z]{3,9}[-/\s]\d{2,4}|\d{1,2}[-/]\d{1,2}[-/]\d{2,4})""",
            RegexOption.IGNORE_CASE
        )

        /** I3 — the monthly statement, which the due parser owns. */
        private val STATEMENT_SUBJECT = Regex("""Credit Card Statement""", RegexOption.IGNORE_CASE)

        /** Trailing four digits of a captured run — `5241 XXXX XXXX 4821` -> `4821`. */
        private fun lastFour(raw: String?): String? {
            val digits = raw?.filter { it.isDigit() }.orEmpty()
            return if (digits.length >= 4) digits.takeLast(4) else null
        }

        private fun toEpochMillis(dateTime: LocalDateTime): Long =
            dateTime.atZone(ZONE).toInstant().toEpochMilli()
    }
}
