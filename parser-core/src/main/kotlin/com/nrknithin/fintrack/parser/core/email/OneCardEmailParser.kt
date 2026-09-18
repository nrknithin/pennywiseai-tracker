package com.nrknithin.fintrack.parser.core.email

import com.nrknithin.fintrack.parser.core.ParsedTransaction
import com.nrknithin.fintrack.parser.core.TransactionType
import com.nrknithin.fintrack.parser.core.text.DateParse
import com.nrknithin.fintrack.parser.core.text.Money
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * OneCard card-spend mail — `no-reply@getonecard.app` (spec 12 §12.4).
 *
 * **HTML only.** This sender ships no `text/plain` part, so the body always arrives from
 * jsoup: table cells joined with ` | ` and whitespace collapsed (spec 11 §11.5). The
 * label/value pairs therefore show up as `Amount: | INR 1,868.40` in some renderings and as
 * a plain run of text in others, which is why every field pattern below treats pipes and
 * spaces as the same separator. Spec 12 §12.4 states the template as one regex written for
 * tidy plain text; matched against real extractor output that regex never fires.
 *
 * The card is issued on Federal Bank — which is why the sender registry gives it the FEDBNK
 * SMS alias — but the product the user recognises is OneCard, so that is the bank name.
 */
class OneCardEmailParser : EmailTransactionParser {

    override val id: String = "ONECARD_EMAIL"

    override fun canHandle(from: String): Boolean {
        val address = EmailSenderRegistry.normaliseAddress(from) ?: return false
        return address == SENDER_ADDRESS || address.substringAfter("@", "") == SENDER_DOMAIN
    }

    override fun parse(message: EmailMessage): ParsedTransaction? {
        val body = message.body
        // The trigger sentence is what makes this a spend alert rather than a statement,
        // a reminder or a marketing mail.
        val card = CARD_USED.find(body) ?: return null
        val cardLast4 = lastFour(card.groupValues[1]) ?: return null
        val money = Money.parse(AMOUNT.find(body)?.groupValues?.get(1)) ?: return null

        val occurredAt = DateParse.parse(
            DATE.find(body)?.groupValues?.get(1),
            TIME.find(body)?.groupValues?.get(1)
        )

        return ParsedTransaction(
            amount = money.amount,
            type = TransactionType.CREDIT,
            merchant = MERCHANT.find(body)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() },
            // The template carries no transaction reference; dedup falls back to
            // amount + card + time (spec 14 §14.3, L2).
            reference = null,
            accountLast4 = cardLast4,
            balance = null,
            creditLimit = null,
            smsBody = body,
            sender = message.from,
            timestamp = occurredAt?.let { toEpochMillis(it) } ?: message.receivedAt,
            bankName = BANK_NAME,
            isFromCard = true,
            currency = money.currency
        )
    }

    /**
     * Statement and EMI-foreclosure mail from the same domain describes an expectation or
     * an event, never money moving, so it is recorded as NON_TXN rather than handed to the
     * generic parser (spec 12 §12.4; spec 11 §11.4, step d).
     */
    override fun isNonTransactionEmail(message: EmailMessage): Boolean =
        !CARD_USED.containsMatchIn(message.body) &&
            NON_TXN_SUBJECT.containsMatchIn(message.subject)

    private companion object {
        private const val BANK_NAME = "OneCard"
        private const val SENDER_ADDRESS = "no-reply@getonecard.app"
        private const val SENDER_DOMAIN = "getonecard.app"

        private val ZONE: ZoneId = ZoneId.of("Asia/Kolkata")

        /** Pipes are cell joins from the HTML table and read exactly like spaces here. */
        private const val SEPARATOR = """[\s|]*"""

        /** Ends a label: an optional colon, then any run of spaces and cell joins. */
        private const val LABEL_END = """\s*:?""" + SEPARATOR

        /**
         * The trigger sentence. Both gaps allow cell joins because the extractor can put
         * the card number in a cell of its own.
         */
        private val CARD_USED = Regex(
            """ending in${SEPARATOR}(?:XX|\*+)?(\d{4})${SEPARATOR}was used to make a payment""",
            RegexOption.IGNORE_CASE
        )

        /** The whole money run is captured so `Money.parse` reads the currency token too. */
        private val AMOUNT = Regex(
            """Amount$LABEL_END((?:INR|Rs\.?|₹|\$|USD)?\s*[\d,]+(?:\.\d{1,2})?)""",
            RegexOption.IGNORE_CASE
        )

        /**
         * The merchant ends at the next cell boundary, the next line, or the `Date:` label
         * when the extractor produced one long run of text.
         */
        private val MERCHANT = Regex(
            """Merchant$LABEL_END([^|\n]+?)\s*(?=[|\n]|Date\s*:|$)""",
            RegexOption.IGNORE_CASE
        )

        private val DATE = Regex(
            """Date$LABEL_END(\d{1,2}/\d{1,2}/\d{2,4})""",
            RegexOption.IGNORE_CASE
        )

        private val TIME = Regex(
            """Time$LABEL_END(\d{1,2}:\d{2}(?::\d{2})?(?:\s*[AaPp]\.?[Mm]\.?)?)""",
            RegexOption.IGNORE_CASE
        )

        private val NON_TXN_SUBJECT = Regex(
            """statement|foreclos|e-?mandate""",
            RegexOption.IGNORE_CASE
        )

        private fun lastFour(raw: String?): String? {
            val digits = raw?.filter { it.isDigit() }.orEmpty()
            return if (digits.length >= 4) digits.takeLast(4) else null
        }

        private fun toEpochMillis(dateTime: LocalDateTime): Long =
            dateTime.atZone(ZONE).toInstant().toEpochMilli()
    }
}
