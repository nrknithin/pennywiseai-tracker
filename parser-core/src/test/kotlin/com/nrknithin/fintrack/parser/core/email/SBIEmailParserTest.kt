package com.nrknithin.fintrack.parser.core.email

import com.nrknithin.fintrack.parser.core.TransactionType
import com.nrknithin.fintrack.parser.core.test.EmailParserTestUtils
import com.nrknithin.fintrack.parser.core.test.ExpectedTransaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * SBI ATM alerts (spec 12 §12.5).
 *
 * The bodies are the HTML table as the extractor renders it: one row per line with the two
 * cells joined by ` | ` (spec 11 §11.5). The second fixture writes the same table as the
 * fully pipe-delimited single line spec 12 §12.5 quotes, so both boundaries are covered.
 * The date is the spec's sample; the amount, balance, account digits, transaction number
 * and city are invented.
 */
class SBIEmailParserTest {

    private val parser = SBIEmailParser()

    @TestFactory
    fun `sbi email parser reads atm alerts`(): List<DynamicTest> {
        val cases = listOf(
            EmailParserTestUtils.EmailCase(
                name = "Cash withdrawal: amount, account, balance, reference and ATM city",
                from = SENDER,
                subject = "Transaction Alert from State Bank of India",
                body = withdrawalBody(),
                expected = ExpectedTransaction(
                    amount = BigDecimal("800.00"),
                    currency = "INR",
                    type = TransactionType.EXPENSE,
                    merchant = "ATM CHENNAI",
                    reference = "802417",
                    accountLast4 = "4408",
                    balance = BigDecimal("6450.00"),
                    isFromCard = false
                )
            ),
            EmailParserTestUtils.EmailCase(
                name = "The same table written as one fully pipe-delimited line",
                from = SENDER,
                subject = "Transaction Alert from State Bank of India",
                body = "| Amount | 800.00 | Last 4 Digit of Account | X4408 | " +
                    "Transaction Type | CASH WITHDRAWAL | Channel | ATM | " +
                    "Available Balance | 6,450.00 | Date & Time | 11 Sep 2026 04:20:31 | " +
                    "Transaction Number | 802417 | City | NEW DELHI |",
                expected = ExpectedTransaction(
                    amount = BigDecimal("800.00"),
                    currency = "INR",
                    type = TransactionType.EXPENSE,
                    merchant = "ATM NEW DELHI",
                    reference = "802417",
                    accountLast4 = "4408",
                    balance = BigDecimal("6450.00"),
                    isFromCard = false
                )
            ),
            EmailParserTestUtils.EmailCase(
                name = "A deposit at the same machine is income, not another withdrawal",
                from = SENDER,
                subject = "Transaction Alert from State Bank of India",
                body = "Amount (INR) | 2,000.00\n" +
                    "Last 4 Digit of Account | X4408\n" +
                    "Transaction Type | CASH DEPOSIT\n" +
                    "Channel | ATM\n" +
                    "Available Balance (INR) | 8,450.00\n" +
                    "Date & Time | 11-09-2026 11:05\n" +
                    "Transaction Number | 802418\n" +
                    "City | CHENNAI",
                expected = ExpectedTransaction(
                    amount = BigDecimal("2000.00"),
                    currency = "INR",
                    type = TransactionType.INCOME,
                    merchant = "ATM CHENNAI",
                    reference = "802418",
                    accountLast4 = "4408",
                    balance = BigDecimal("8450.00")
                )
            ),
            EmailParserTestUtils.EmailCase(
                name = "A table with no city row still names the channel",
                from = SENDER,
                subject = "Transaction Alert from State Bank of India",
                body = "Amount (INR) | 800.00\n" +
                    "Last 4 Digit of Account | X4408\n" +
                    "Transaction Type | CASH WITHDRAWAL\n" +
                    "Channel | ATM\n" +
                    "Transaction Number | 802417",
                expected = ExpectedTransaction(
                    amount = BigDecimal("800.00"),
                    currency = "INR",
                    type = TransactionType.EXPENSE,
                    merchant = "ATM",
                    reference = "802417",
                    accountLast4 = "4408"
                )
            ),
            EmailParserTestUtils.EmailCase(
                name = "Rejects a balance alert that states no amount rather than inventing one",
                from = SENDER,
                subject = "Account Alert from State Bank of India",
                body = "Last 4 Digit of Account | X4408\n" +
                    "Available Balance (INR) | 6,450.00\n" +
                    "Date & Time | 11-09-2026 04:20",
                shouldParse = false
            ),
            EmailParserTestUtils.EmailCase(
                name = "Rejects a service notice that carries no table at all",
                from = SENDER,
                subject = "Service request update",
                body = "Dear Customer, your request for a new debit card PIN has been registered. " +
                    "Please collect the acknowledgement at your home branch.",
                shouldParse = false
            )
        )

        val handleCases = listOf(
            SENDER to true,
            "\"SBI ATM Alerts\" <donotreply.sbiatm@alerts.sbi.co.in>" to true,
            // Marketing mail arrives from a different sub-domain and is not this parser's.
            "sbi@communications.sbi.co.in" to false,
            "credit_cards@icici.bank.in" to false
        )

        return EmailParserTestUtils.runTransactionSuite(parser, cases, handleCases)
    }

    @TestFactory
    fun `sbi email parser times withdrawals by the date and time in the table`(): List<DynamicTest> = listOf(
        dynamicTest("A date and time without seconds keeps its minute") {
            val parsed = parser.parse(
                EmailParserTestUtils.message(
                    from = SENDER,
                    subject = "Transaction Alert from State Bank of India",
                    body = withdrawalBody()
                )
            )
            assertNotNull(parsed)
            assertEquals(millisAt(2026, 9, 11, 4, 20), parsed!!.timestamp)
        },
        dynamicTest("A table with no date row falls back to the received time") {
            val parsed = parser.parse(
                EmailParserTestUtils.message(
                    from = SENDER,
                    subject = "Transaction Alert from State Bank of India",
                    body = "Amount (INR) | 800.00\n" +
                        "Last 4 Digit of Account | X4408\n" +
                        "Transaction Type | CASH WITHDRAWAL\n" +
                        "Channel | ATM\n" +
                        "Transaction Number | 802417\n" +
                        "City | CHENNAI"
                )
            )
            assertNotNull(parsed)
            assertEquals(EmailParserTestUtils.FIXED_RECEIVED_AT, parsed!!.timestamp)
        }
    )

    private fun withdrawalBody() =
        "Dear Customer, the details of your transaction are given below.\n" +
            "Amount (INR) | 800.00\n" +
            "Last 4 Digit of Account | X4408\n" +
            "Transaction Type | CASH WITHDRAWAL\n" +
            "Channel | ATM\n" +
            "Available Balance (INR) | 6,450.00\n" +
            "Date & Time | 11-09-2026 04:20\n" +
            "Transaction Number | 802417\n" +
            "City | CHENNAI\n" +
            "Thank you for banking with State Bank of India."

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
        private const val SENDER = "donotreply.sbiatm@alerts.sbi.co.in"
    }
}
