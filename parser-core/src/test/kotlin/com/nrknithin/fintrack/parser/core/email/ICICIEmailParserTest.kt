package com.nrknithin.fintrack.parser.core.email

import com.nrknithin.fintrack.parser.core.TransactionType
import com.nrknithin.fintrack.parser.core.test.EmailParserTestUtils
import com.nrknithin.fintrack.parser.core.test.ExpectedTransaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * ICICI credit-card email templates I1 and I2 (spec 12 §12.3).
 *
 * Amounts, merchants and card numbers are invented. The dates and the shape of each amount
 * are what the parsing logic turns on, so those are kept as the spec's samples state them.
 */
class ICICIEmailParserTest {

    private val parser = ICICIEmailParser()

    @TestFactory
    fun `icici email parser reads card purchases and payments`(): List<DynamicTest> {
        val cases = listOf(
            EmailParserTestUtils.EmailCase(
                name = "I1: card purchase carries both the available and the total credit limit",
                from = SENDER,
                subject = "Transaction alert for your ICICI Bank Credit Card",
                body = purchaseBody(),
                expected = ExpectedTransaction(
                    amount = BigDecimal("482.60"),
                    currency = "INR",
                    type = TransactionType.CREDIT,
                    merchant = "ACME RETAIL",
                    accountLast4 = "4821",
                    balance = BigDecimal("158340.25"),
                    creditLimit = BigDecimal("350000.00"),
                    isFromCard = true
                )
            ),
            EmailParserTestUtils.EmailCase(
                name = "I1: a purchase that states no limits is still read",
                from = SENDER,
                subject = "Transaction alert for your ICICI Bank Credit Card",
                body = "Dear Customer,\n" +
                    "Your ICICI Bank Credit Card XX4821 has been used for a transaction of " +
                    "INR 482.60 on Sep 04, 2026 at 10:13:10. Info: ACME RETAIL.",
                expected = ExpectedTransaction(
                    amount = BigDecimal("482.60"),
                    currency = "INR",
                    type = TransactionType.CREDIT,
                    merchant = "ACME RETAIL",
                    accountLast4 = "4821",
                    isFromCard = true
                )
            ),
            EmailParserTestUtils.EmailCase(
                name = "I1: an amount written without decimals",
                from = SENDER,
                subject = "Transaction alert for your ICICI Bank Credit Card",
                body = "Your ICICI Bank Credit Card XX4821 has been used for a transaction of " +
                    "INR 214 on Sep 15, 2026 at 12:04:06. Info: DEMO MART. " +
                    "The Available Credit Limit on your card is INR 1,58,126.25 and " +
                    "Total Credit Limit is INR 3,50,000.00.",
                expected = ExpectedTransaction(
                    amount = BigDecimal("214.00"),
                    currency = "INR",
                    type = TransactionType.CREDIT,
                    merchant = "DEMO MART",
                    accountLast4 = "4821",
                    balance = BigDecimal("158126.25"),
                    creditLimit = BigDecimal("350000.00"),
                    isFromCard = true
                )
            ),
            EmailParserTestUtils.EmailCase(
                name = "I1: the month spelled out in full",
                from = SENDER,
                subject = "Transaction alert for your ICICI Bank Credit Card",
                body = "Your ICICI Bank Credit Card XX4821 has been used for a transaction of " +
                    "INR 482.60 on September 04, 2026 at 10:13:10. Info: ACME RETAIL.",
                expected = ExpectedTransaction(
                    amount = BigDecimal("482.60"),
                    currency = "INR",
                    type = TransactionType.CREDIT,
                    merchant = "ACME RETAIL",
                    accountLast4 = "4821",
                    isFromCard = true
                )
            ),
            EmailParserTestUtils.EmailCase(
                name = "I2: payment received is a transfer into the card, not income",
                from = SENDER,
                subject = "Payment received on your ICICI Bank Credit Card.",
                body = paymentBody(),
                expected = ExpectedTransaction(
                    amount = BigDecimal("4318.67"),
                    currency = "INR",
                    type = TransactionType.TRANSFER,
                    merchant = "Credit Card Payment",
                    accountLast4 = "4821",
                    toAccount = "4821",
                    isFromCard = true
                )
            ),
            EmailParserTestUtils.EmailCase(
                name = "I2: the masked PAN yields its trailing four digits",
                from = SENDER,
                subject = "Payment received on your ICICI Bank Credit Card.",
                body = "We have received payment of INR 12,000.00 on your ICICI Bank Credit Card " +
                    "account 5241XXXXXXXX4821 on 03 Aug 2026.",
                expected = ExpectedTransaction(
                    amount = BigDecimal("12000.00"),
                    currency = "INR",
                    type = TransactionType.TRANSFER,
                    accountLast4 = "4821",
                    toAccount = "4821",
                    isFromCard = true
                )
            ),
            EmailParserTestUtils.EmailCase(
                name = "Rejects the monthly statement, which is a due and not money moved",
                from = SENDER,
                subject = "Amazon Pay ICICI Bank Credit Card Statement for the period Aug 01, 2026 to Aug 31, 2026",
                body = statementBody(),
                shouldParse = false
            ),
            EmailParserTestUtils.EmailCase(
                name = "Rejects a card-status notice that names the card but no transaction",
                from = SENDER,
                subject = "Update on your ICICI Bank Credit Card",
                body = "Your ICICI Bank Credit Card XX4821 has been blocked as requested. " +
                    "The Available Credit Limit on your card is INR 1,58,340.25 and " +
                    "Total Credit Limit is INR 3,50,000.00.",
                shouldParse = false
            )
        )

        val handleCases = listOf(
            SENDER to true,
            "\"ICICI Bank\" <credit_cards@icici.bank.in>" to true,
            // Marketing sub-domains and other banks are somebody else's mail.
            "services@custcomm.icici.bank.in" to false,
            "alerts@hdfcbank.bank.in" to false
        )

        return EmailParserTestUtils.runTransactionSuite(parser, cases, handleCases)
    }

    @TestFactory
    fun `icici email parser times transactions by the date inside the body`(): List<DynamicTest> = listOf(
        dynamicTest("I1 uses the stated date and time, not the received time") {
            val parsed = parser.parse(
                EmailParserTestUtils.message(
                    from = SENDER,
                    subject = "Transaction alert for your ICICI Bank Credit Card",
                    body = purchaseBody()
                )
            )
            assertNotNull(parsed)
            assertEquals(millisAt(2026, 9, 4, 10, 13, 10), parsed!!.timestamp)
        },
        dynamicTest("I2 uses the stated date at midnight, the only time it carries") {
            val parsed = parser.parse(
                EmailParserTestUtils.message(
                    from = SENDER,
                    subject = "Payment received on your ICICI Bank Credit Card.",
                    body = paymentBody()
                )
            )
            assertNotNull(parsed)
            assertEquals(millisAt(2026, 8, 3), parsed!!.timestamp)
        }
    )

    @TestFactory
    fun `icici email parser separates statements from transactions`(): List<DynamicTest> = listOf(
        dynamicTest("The statement mail is reported as a non-transaction") {
            val flagged = parser.isNonTransactionEmail(
                EmailParserTestUtils.message(
                    from = SENDER,
                    subject = "Amazon Pay ICICI Bank Credit Card Statement for the period Aug 01, 2026 to Aug 31, 2026",
                    body = statementBody()
                )
            )
            assertTrue(flagged, "The statement carries a due, never a transaction")
        },
        dynamicTest("A purchase mail is never reported as a non-transaction") {
            val flagged = parser.isNonTransactionEmail(
                EmailParserTestUtils.message(
                    from = SENDER,
                    subject = "Transaction alert for your ICICI Bank Credit Card",
                    body = purchaseBody()
                )
            )
            assertFalse(flagged)
        }
    )

    private fun purchaseBody() =
        "Dear Customer,\n" +
            "Your ICICI Bank Credit Card XX4821 has been used for a transaction of INR 482.60 " +
            "on Sep 04, 2026 at 10:13:10. Info: ACME RETAIL. " +
            "The Available Credit Limit on your card is INR 1,58,340.25 and " +
            "Total Credit Limit is INR 3,50,000.00."

    private fun paymentBody() =
        "Dear Customer,\n" +
            "We have received payment of INR 4318.67 on your ICICI Bank Credit Card account " +
            "5241 XXXX XXXX 4821 on 03-Aug-2026. Thank you for banking with us."

    private fun statementBody() =
        "Dear Customer,\n" +
            "Your credit card statement is attached. Total Amount Due INR 12,345.00 " +
            "Minimum Amount Due INR 620.00 Payment due by 20-Sep-2026."

    private fun millisAt(
        year: Int,
        month: Int,
        day: Int,
        hour: Int = 0,
        minute: Int = 0,
        second: Int = 0
    ): Long = LocalDateTime.of(year, month, day, hour, minute, second)
        .atZone(ZoneId.of("Asia/Kolkata"))
        .toInstant()
        .toEpochMilli()

    private companion object {
        private const val SENDER = "credit_cards@icici.bank.in"
    }
}
