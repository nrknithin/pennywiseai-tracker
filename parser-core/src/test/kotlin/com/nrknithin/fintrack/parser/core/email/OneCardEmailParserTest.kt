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
 * OneCard spend alerts (spec 12 §12.4).
 *
 * This sender ships no plain-text part, so every fixture body is written the way the HTML
 * extractor hands it over: table cells joined with ` | `, whitespace collapsed (spec 11
 * §11.5). The second fixture is the same mail rendered as one run of text, which is what a
 * single-column layout produces — both shapes have been seen from this sender's templates.
 * Card digits, amounts and merchants are invented; the dates are the spec's.
 */
class OneCardEmailParserTest {

    private val parser = OneCardEmailParser()

    @TestFactory
    fun `onecard email parser reads card spends`(): List<DynamicTest> {
        val cases = listOf(
            EmailParserTestUtils.EmailCase(
                name = "Card spend from a table-shaped body",
                from = SENDER,
                subject = "Payment update on your Federal Bank One credit card",
                body = tableBody(),
                expected = ExpectedTransaction(
                    amount = BigDecimal("1868.40"),
                    currency = "INR",
                    type = TransactionType.CREDIT,
                    merchant = "SAMPLE SERVICES",
                    accountLast4 = "3316",
                    isFromCard = true
                )
            ),
            EmailParserTestUtils.EmailCase(
                name = "Card spend from a single run of text stops the merchant at the Date label",
                from = SENDER,
                subject = "Payment update on your Federal Bank One credit card",
                body = "Your Federal Bank One Credit Card ending in 3316 was used to make a payment. " +
                    "Amount: INR 1,199.00 Merchant: EXAMPLE STORE Date: 12/09/2026 Time: 19:05:44",
                expected = ExpectedTransaction(
                    amount = BigDecimal("1199.00"),
                    currency = "INR",
                    type = TransactionType.CREDIT,
                    merchant = "EXAMPLE STORE",
                    accountLast4 = "3316",
                    isFromCard = true
                )
            ),
            EmailParserTestUtils.EmailCase(
                name = "A spend without a time cell is still read",
                from = SENDER,
                subject = "Payment update on your Federal Bank One credit card",
                body = "Your Federal Bank One Credit Card ending in 3316 was used to make a payment. | " +
                    "Amount: | INR 349.00 | Merchant: | SAMPLE SERVICES | Date: | 12/09/2026",
                expected = ExpectedTransaction(
                    amount = BigDecimal("349.00"),
                    currency = "INR",
                    type = TransactionType.CREDIT,
                    merchant = "SAMPLE SERVICES",
                    accountLast4 = "3316",
                    isFromCard = true
                )
            ),
            EmailParserTestUtils.EmailCase(
                name = "Rejects the monthly statement, which carries a due and no spend",
                from = STATEMENT_SENDER,
                subject = "Your OneCard statement for August 2026",
                body = statementBody(),
                shouldParse = false
            ),
            EmailParserTestUtils.EmailCase(
                name = "Rejects a spend alert whose amount never arrived",
                from = SENDER,
                subject = "Payment update on your Federal Bank One credit card",
                body = "Your Federal Bank One Credit Card ending in 3316 was used to make a payment. | " +
                    "Merchant: | SAMPLE SERVICES | Date: | 15/09/2026 | Time: | 01:41:30",
                shouldParse = false
            )
        )

        val handleCases = listOf(
            SENDER to true,
            // Same domain, different local part: the statement address reaches this parser
            // and is answered with null rather than a guess.
            STATEMENT_SENDER to true,
            "alerts@axis.bank.in" to false,
            "no-reply@getonecard.app.example.com" to false
        )

        return EmailParserTestUtils.runTransactionSuite(parser, cases, handleCases)
    }

    @TestFactory
    fun `onecard email parser times spends by the date and time in the body`(): List<DynamicTest> = listOf(
        dynamicTest("The stated date and time win over the received time") {
            val parsed = parser.parse(
                EmailParserTestUtils.message(
                    from = SENDER,
                    subject = "Payment update on your Federal Bank One credit card",
                    body = tableBody()
                )
            )
            assertNotNull(parsed)
            assertEquals(millisAt(2026, 9, 15, 1, 41, 30), parsed!!.timestamp)
        },
        dynamicTest("A missing time falls back to midnight on the stated date, never to now") {
            val parsed = parser.parse(
                EmailParserTestUtils.message(
                    from = SENDER,
                    subject = "Payment update on your Federal Bank One credit card",
                    body = "Your Federal Bank One Credit Card ending in 3316 was used to make a payment. | " +
                        "Amount: | INR 349.00 | Merchant: | SAMPLE SERVICES | Date: | 12/09/2026"
                )
            )
            assertNotNull(parsed)
            assertEquals(millisAt(2026, 9, 12), parsed!!.timestamp)
        },
        dynamicTest("A body with no date at all falls back to the received time") {
            val parsed = parser.parse(
                EmailParserTestUtils.message(
                    from = SENDER,
                    subject = "Payment update on your Federal Bank One credit card",
                    body = "Your Federal Bank One Credit Card ending in 3316 was used to make a payment. | " +
                        "Amount: | INR 349.00 | Merchant: | SAMPLE SERVICES"
                )
            )
            assertNotNull(parsed)
            assertEquals(EmailParserTestUtils.FIXED_RECEIVED_AT, parsed!!.timestamp)
        }
    )

    @TestFactory
    fun `onecard email parser separates statements from spends`(): List<DynamicTest> = listOf(
        dynamicTest("The statement mail is reported as a non-transaction") {
            val flagged = parser.isNonTransactionEmail(
                EmailParserTestUtils.message(
                    from = STATEMENT_SENDER,
                    subject = "Your OneCard statement for August 2026",
                    body = statementBody()
                )
            )
            assertTrue(flagged)
        },
        dynamicTest("A spend alert is never reported as a non-transaction") {
            val flagged = parser.isNonTransactionEmail(
                EmailParserTestUtils.message(
                    from = SENDER,
                    subject = "Payment update on your Federal Bank One credit card",
                    body = tableBody()
                )
            )
            assertFalse(flagged)
        }
    )

    @TestFactory
    fun `onecard email parser names the product, not the issuing bank`(): List<DynamicTest> = listOf(
        dynamicTest("Bank name is OneCard even though the card is issued on Federal Bank") {
            val parsed = parser.parse(
                EmailParserTestUtils.message(
                    from = SENDER,
                    subject = "Payment update on your Federal Bank One credit card",
                    body = tableBody()
                )
            )
            assertNotNull(parsed)
            assertEquals("OneCard", parsed!!.bankName)
        }
    )

    private fun tableBody() =
        "Payment update on your Federal Bank One credit card | Hi ACCOUNT HOLDER, | " +
            "Your Federal Bank One Credit Card ending in 3316 was used to make a payment. | " +
            "Amount: | INR 1,868.40 | Merchant: | SAMPLE SERVICES | Date: | 15/09/2026 | Time: | 01:41:30 | " +
            "Not you? Report it in the OneCard app."

    private fun statementBody() =
        "Your OneCard statement is ready. | Total Amount Due | INR 12,345.00 | " +
            "Minimum Amount Due | INR 620.00 | Due Date | 05/09/2026"

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
        private const val SENDER = "no-reply@getonecard.app"
        private const val STATEMENT_SENDER = "statement@getonecard.app"
    }
}
