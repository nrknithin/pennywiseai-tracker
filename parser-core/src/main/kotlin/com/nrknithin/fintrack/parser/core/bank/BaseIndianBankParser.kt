package com.nrknithin.fintrack.parser.core.bank

import com.nrknithin.fintrack.parser.core.CompiledPatterns
import com.nrknithin.fintrack.parser.core.MandateInfo
import com.nrknithin.fintrack.parser.core.TransactionType
import kotlinx.datetime.LocalDateTime
import java.math.BigDecimal

/** The first amount within a short reach after "Bal" / "Balance" / "Avl. Bal". */
private val BALANCE_ENQUIRY_AMOUNT = Regex(
    """\bbal(?:ance)?\b.{0,60}?(?:INR|Rs\.?|₹)\s*([0-9][0-9,]*(?:\.\d{1,2})?)""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
)

/**
 * Words a balance-enquiry reply never carries but transaction, card-decline, dues and
 * wallet alerts do ("declined due to insufficient balance … Avl Limit INR …").
 */
private val NOT_A_BALANCE_ENQUIRY_REPLY = Regex(
    """\b(?:debit(?:ed)?|credit(?:ed)?|withdrawn|deposited|spent|transferr?ed|payment|paid|refund(?:ed)?|declined|insufficient|limit|due|emi|mandate|maintain|ensure|sent|received|deducted|toll|wallet|reversal)\b""",
    RegexOption.IGNORE_CASE
)

/**
 * Base abstract class for Indian bank parsers.
 * Handles common patterns across Indian banks (INR currency, UPI, etc.).
 */
abstract class BaseIndianBankParser : BankParser() {

    override fun getCurrency() = "INR"

    /**
     * Checks if the message is for an investment transaction.
     * Contains keywords specific to Indian investment platforms and terms.
     */
    override fun isInvestmentTransaction(lowerMessage: String): Boolean {
        // Credits to a bank account are income, not investment (ACH dividends, vendor payments, etc.)
        // Only use "credited" and "deposited" — "received" is ambiguous
        // (e.g. "X has received Rs Y from your A/c" means outgoing money)
        if (lowerMessage.contains("credited") ||
            lowerMessage.contains("deposited")) {
            return false
        }

        return super.isInvestmentTransaction(lowerMessage)
    }

    // ==========================================
    // Unified Mandate / Subscription Logic
    // ==========================================

    /**
     * Checks if this is an E-Mandate notification (not a transaction).
     */
    open fun isEMandateNotification(message: String): Boolean {
        val lowerMessage = message.lowercase()
        return lowerMessage.contains("e-mandate") ||
                lowerMessage.contains("upi-mandate") ||
                (lowerMessage.contains("mandate") && lowerMessage.contains("successfully created"))
    }

    /**
     * Checks if this is a future debit notification (subscription alert, not a current transaction).
     */
    open fun isFutureDebitNotification(message: String): Boolean {
        val lowerMessage = message.lowercase()
        return lowerMessage.contains("will be debited") ||
                lowerMessage.contains("mandate set for") ||
                (lowerMessage.contains("upcoming") && lowerMessage.contains("mandate"))
    }

    /**
     * Parses combined Mandate / E-Mandate / UPI-Mandate subscription information.
     * Returns a general MandateInfo implementation.
     */
    open fun parseMandateSubscription(message: String): MandateInfo? {
        if (!isEMandateNotification(message) && !isFutureDebitNotification(message)) {
            return null
        }

        // 1. Extract amount
        // Patterns: "Rs.1050.00", "INR 59.00", "Rs 123.45"
        val amount = CompiledPatterns.Amount.INR_PATTERN.find(message)?.let { match ->
            val amountStr = match.groupValues[1].replace(",", "")
            try {
                BigDecimal(amountStr)
            } catch (e: NumberFormatException) {
                null
            }
        } ?: CompiledPatterns.Amount.RS_PATTERN.find(message)?.let { match ->
            val amountStr = match.groupValues[1].replace(",", "")
            try {
                BigDecimal(amountStr)
            } catch (e: NumberFormatException) {
                null
            }
        } ?: return null

        // 2. Extract merchant
        // Patterns: "towards Google Play", "for Netflix", "Info: Spotify"
        var merchant = "Unknown Subscription"
        val merchantPatterns = listOf(
            Regex("""towards\s+([^.\n]+?)(?:\s+from|\s+A/c|\s+UMRN|\s+ID:|\s+Alert:|\s*\.|$)""", RegexOption.IGNORE_CASE),
            Regex("""for\s+([^.\n]+?)(?:\s+mandate|\s+will\s+be|\s+ID:|\s+Act:|\s*\.|$)""", RegexOption.IGNORE_CASE),
            Regex("""Info:\s*([^.\n]+?)(?:\s*$)""", RegexOption.IGNORE_CASE)
        )

        for (pattern in merchantPatterns) {
            pattern.find(message)?.let { match ->
                val m = cleanMerchantName(match.groupValues[1].trim())
                if (isValidMerchantName(m)) merchant = m
            }
        }

        // 3. Extract date (for future debits)
        // Patterns: "on 29-May-25", "set for 29-May-25"
        // Matches DD-MMM-YY, dd/MM/yyyy formats common in Indian banks
        val datePattern = Regex("""(?:on|for)\s+(${CompiledPatterns.Date.DD_MMM_YY.pattern}|${CompiledPatterns.Date.DD_MM_YYYY.pattern})""", RegexOption.IGNORE_CASE)
        val dateStr = datePattern.find(message)?.groupValues?.get(1)?.let { rawDate ->
            // Normalize slashes to dashes if needed or keep as is, consumer will parse
            rawDate
        }

        // 4. Extract UMN (Unique Mandate Number) if present
        val umnPattern = Regex("""UMN[:\s]+([^.\s]+)""", RegexOption.IGNORE_CASE)
        val umn = umnPattern.find(message)?.groupValues?.get(1)

        // 5. Extract the debiting account's last 4 digits, if the SMS states it
        // (e.g. "from A/c XX1234"). Reuses the bank's own account helper.
        val accountLast4 = extractAccountLast4(message)

        return object : MandateInfo {
            override val amount = amount
            override val nextDeductionDate = dateStr
            override val merchant = merchant
            override val umn = umn
            override val dateFormat = "dd-MMM-yy" // Default fallback
            override val accountLast4 = accountLast4
        }
    }

    // ==========================================
    // Unified Balance Update Logic
    // ==========================================

    /**
     * Checks if this is a balance update notification (not a transaction).
     */
    open fun isBalanceUpdateNotification(message: String): Boolean {
        val lowerMessage = message.lowercase()

        // Check for balance update patterns
        // Must contain "Available Balance" or similar keywords
        // And typically "as on" or "is Rs." without transaction words like "debited", "spent"
        val hasBalanceKeyword = lowerMessage.contains("available bal") ||
                lowerMessage.contains("avl bal") ||
                lowerMessage.contains("account balance") ||
                lowerMessage.contains("a/c balance") ||
                lowerMessage.contains("updated balance")

        val hasTxnKeyword = lowerMessage.contains("debited") ||
                lowerMessage.contains("credited") ||
                lowerMessage.contains("withdrawn") ||
                lowerMessage.contains("deposited") ||
                lowerMessage.contains("spent") ||
                lowerMessage.contains("transferred") ||
                lowerMessage.contains("payment of")

        return hasBalanceKeyword && !hasTxnKeyword
    }

    data class BaseBalanceUpdateInfo(
        val bankName: String,
        val accountLast4: String?,
        val balance: BigDecimal,
        val asOfDate: LocalDateTime? = null
    )

    /**
     * Parses generic balance update notification.
     */
    open fun parseBalanceUpdate(message: String): BaseBalanceUpdateInfo? {
        if (!isBalanceUpdateNotification(message)) {
            return null
        }

        // Extract account last 4 digits
        val accountLast4 = extractAccountLast4(message)

        // Extract balance amount
        // Patterns: "is Rs. 12,345", "Avl Bal Rs 12345"
        val balance = extractBalance(message) ?: return null

        return BaseBalanceUpdateInfo(
            bankName = getBankName(),
            accountLast4 = accountLast4,
            balance = balance
        )
    }

    /**
     * A reply to a balance enquiry — a missed call or an SMS "BAL" to the bank: an
     * account, the word balance and an amount, and none of the words a transaction,
     * card or dues alert carries. The amount is the first one after "Bal"/"Balance":
     * "Bal for your Axis Bank A/c no. XX1234 - INR 999.99",
     * "Avl bal in A/c XX1234 as on 30-SEP-26 is INR 12,345.67",
     * "Your A/c ending 1234 has balance of Rs. 12,345.67".
     */
    protected fun parseBalanceEnquiryReply(message: String): BaseBalanceUpdateInfo? {
        if (NOT_A_BALANCE_ENQUIRY_REPLY.containsMatchIn(message)) return null
        val balance = BALANCE_ENQUIRY_AMOUNT.find(message)?.groupValues?.get(1)
            ?.replace(",", "")?.toBigDecimalOrNull() ?: return null
        val accountLast4 = extractAccountLast4(message) ?: return null
        return BaseBalanceUpdateInfo(
            bankName = getBankName(),
            accountLast4 = accountLast4,
            balance = balance
        )
    }

    // ==========================================
    // Common Helper Methods
    // ==========================================

    /**
     * Helper function to convert month abbreviation to number.
     */
    protected fun getMonthNumber(monthAbbr: String): Int {
        return when (monthAbbr.uppercase()) {
            "JAN" -> 1
            "FEB" -> 2
            "MAR" -> 3
            "APR" -> 4
            "MAY" -> 5
            "JUN" -> 6
            "JUL" -> 7
            "AUG" -> 8
            "SEP" -> 9
            "OCT" -> 10
            "NOV" -> 11
            "DEC" -> 12
            else -> 1
        }
    }
}
