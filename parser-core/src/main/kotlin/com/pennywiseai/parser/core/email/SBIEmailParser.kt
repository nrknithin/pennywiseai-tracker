package com.pennywiseai.parser.core.email

import com.pennywiseai.parser.core.ParsedTransaction
import com.pennywiseai.parser.core.TransactionType
import com.pennywiseai.parser.core.text.DateParse
import com.pennywiseai.parser.core.text.Money
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * State Bank of India ATM alerts — `donotreply.sbiatm@alerts.sbi.co.in` (spec 12 §12.5).
 *
 * The one template this sender uses is an HTML table of label/value rows, which the body
 * extractor renders as cell pairs (spec 11 §11.5). Every fact is read by its label through
 * [cell], so a row order change or an extra row the bank adds costs nothing.
 *
 * SBI sends account debits and credits by **SMS only** (spec 10 §10.1); this address carries
 * ATM activity alone, which is why the merchant is an ATM label and the account last-4 comes
 * from the account, not a card.
 */
class SBIEmailParser : EmailTransactionParser {

    override val id: String = "SBI_EMAIL"

    override fun canHandle(from: String): Boolean {
        val address = EmailSenderRegistry.normaliseAddress(from) ?: return false
        return address == SENDER_ADDRESS || address.substringAfter("@", "") == SENDER_DOMAIN
    }

    override fun parse(message: EmailMessage): ParsedTransaction? {
        val body = message.body

        // Two labels only this table uses: without at least one of them the message is
        // some other SBI mail and none of the cells below can be trusted.
        val accountCell = cell(body, LABEL_ACCOUNT)
        val transactionNumber = cell(body, LABEL_TRANSACTION_NUMBER)
        if (accountCell == null && transactionNumber == null) return null

        // The amount is the one mandatory figure. No amount, no transaction — the app
        // never shows a guessed number (spec 01, C7).
        val money = Money.parse(cell(body, LABEL_AMOUNT)) ?: return null

        val occurredAt = parseCellDateTime(cell(body, LABEL_DATE_TIME))

        return ParsedTransaction(
            amount = money.amount,
            type = transactionType(cell(body, LABEL_TRANSACTION_TYPE)),
            merchant = merchantLabel(cell(body, LABEL_CHANNEL), cell(body, LABEL_CITY)),
            reference = transactionNumber,
            accountLast4 = lastFour(accountCell),
            balance = Money.amount(cell(body, LABEL_AVAILABLE_BALANCE)),
            creditLimit = null,
            smsBody = body,
            sender = message.from,
            timestamp = occurredAt?.let { toEpochMillis(it) } ?: message.receivedAt,
            bankName = BANK_NAME,
            // Cash out of an ATM debits the savings account; the last-4 above is the
            // account's, not a card's, so this is not a card transaction.
            isFromCard = false,
            currency = money.currency
        )
    }

    /**
     * The value of one `| label | value |` cell pair, or null when the table has no such
     * row (spec 12 §12.5).
     *
     * Two tolerances the literal spec regex lacks. The extractor joins cells with ` | `
     * *between* them, so the first cell on a line has no pipe in front of it and the last
     * has none after it — a line edge counts as a boundary. And the bank writes the unit
     * inside the label (`Amount (INR)`, `Available Balance (INR)`), so a trailing currency
     * parenthetical is part of the label rather than a different label.
     */
    private fun cell(body: String, label: String): String? {
        val pattern = CELL_PATTERNS[label] ?: cellPattern(label)
        return pattern.find(body)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * Cash withdrawals are the only kind this sender has been seen to send, but the
     * direction is read from the table rather than assumed: recording a deposit as an
     * expense would be a wrong number, and the type cell is what says which it is.
     */
    private fun transactionType(rawType: String?): TransactionType =
        if (rawType != null && CREDIT_WORDS.containsMatchIn(rawType)) {
            TransactionType.INCOME
        } else {
            TransactionType.EXPENSE
        }

    /**
     * `ATM <city>` for the ATM channel (spec 12 §12.5). The channel cell supplies the first
     * half instead of a hard-coded "ATM" so that a row naming another channel is labelled
     * with that channel rather than mislabelled as an ATM.
     */
    private fun merchantLabel(rawChannel: String?, rawCity: String?): String {
        val channel = rawChannel?.takeIf { it.isNotBlank() } ?: DEFAULT_CHANNEL
        return if (rawCity.isNullOrBlank()) channel else "$channel $rawCity"
    }

    /**
     * Splits `11-09-2026 04:20` into its two halves before parsing, because the combined
     * date-and-time shapes `DateParse` knows all carry seconds; handing it the halves keeps
     * the minute instead of silently falling back to midnight.
     */
    private fun parseCellDateTime(raw: String?): LocalDateTime? {
        if (raw == null) return null
        val time = TRAILING_TIME.find(raw) ?: return DateParse.parse(raw)
        val datePart = raw.substring(0, time.range.first).trim()
        return DateParse.parse(datePart, time.groupValues[1]) ?: DateParse.parse(raw)
    }

    private companion object {
        private const val BANK_NAME = "State Bank of India"
        private const val SENDER_ADDRESS = "donotreply.sbiatm@alerts.sbi.co.in"
        private const val SENDER_DOMAIN = "alerts.sbi.co.in"

        /** Used only when the table omits the channel row; every observed row says ATM. */
        private const val DEFAULT_CHANNEL = "ATM"

        private const val LABEL_AMOUNT = "Amount"
        private const val LABEL_ACCOUNT = "Last 4 Digit of Account"
        private const val LABEL_TRANSACTION_TYPE = "Transaction Type"
        private const val LABEL_CHANNEL = "Channel"
        private const val LABEL_AVAILABLE_BALANCE = "Available Balance"
        private const val LABEL_DATE_TIME = "Date & Time"
        private const val LABEL_TRANSACTION_NUMBER = "Transaction Number"
        private const val LABEL_CITY = "City"

        private val ZONE: ZoneId = ZoneId.of("Asia/Kolkata")

        private val CREDIT_WORDS = Regex(
            """\b(?:CREDIT|CREDITED|DEPOSIT|DEPOSITED|REFUND|REVERSAL)\b""",
            RegexOption.IGNORE_CASE
        )

        private val TRAILING_TIME = Regex("""(\d{1,2}:\d{2}(?::\d{2})?(?:\s*[AaPp]\.?[Mm]\.?)?)\s*$""")

        /** One compiled pattern per label this parser asks for — never compiled in [parse]. */
        private val CELL_PATTERNS: Map<String, Regex> = listOf(
            LABEL_AMOUNT,
            LABEL_ACCOUNT,
            LABEL_TRANSACTION_TYPE,
            LABEL_CHANNEL,
            LABEL_AVAILABLE_BALANCE,
            LABEL_DATE_TIME,
            LABEL_TRANSACTION_NUMBER,
            LABEL_CITY
        ).associateWith { cellPattern(it) }

        private fun cellPattern(label: String): Regex = Regex(
            """(?:^|\|)\s*${Regex.escape(label)}\s*(?:\(\s*(?:INR|Rs\.?|₹)\s*\))?\s*\|\s*([^|\n]+?)\s*(?=\||$)""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
        )

        /** Trailing four digits of a captured run — `X4408` -> `4408`. */
        private fun lastFour(raw: String?): String? {
            val digits = raw?.filter { it.isDigit() }.orEmpty()
            return if (digits.length >= 4) digits.takeLast(4) else null
        }

        private fun toEpochMillis(dateTime: LocalDateTime): Long =
            dateTime.atZone(ZONE).toInstant().toEpochMilli()
    }
}
