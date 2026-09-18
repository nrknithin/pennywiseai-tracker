package com.nrknithin.fintrack.parser.core.email

import com.nrknithin.fintrack.parser.core.TransactionType
import com.nrknithin.fintrack.parser.core.test.EmailParserTestUtils
import com.nrknithin.fintrack.parser.core.test.EmailParserTestUtils.EmailCase
import com.nrknithin.fintrack.parser.core.test.ExpectedTransaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZonedDateTime

/**
 * Axis Bank email templates A1-A4 and A6 (spec 12 §12.2).
 *
 * Amounts are invented; only their shapes are the ones the spec's samples state, because
 * those are what the parsing logic turns on (`INR 214` with no decimals, `16-09-26`
 * two-digit years, `2,53,471`-style grouping). Every account number, card number, UPI
 * reference, merchant and person name is invented too: fixtures carry no real account data.
 *
 * Bodies come in both shapes spec 11 §11.5 produces — `text/plain` with one label per
 * line, and an HTML-derived line whose table cells are joined with ` | `.
 */
class AxisEmailParserTest {

    // ========================================
    // Fixtures
    // ========================================

    private val parser = AxisEmailParser(setOf(ACCOUNT_HOLDER))

    /** The app before the user has confirmed any account holder name. */
    private val parserWithoutHolderNames = AxisEmailParser()

    @TestFactory
    fun `A1 reads a savings debit from the label lines`(): List<DynamicTest> {
        val cases = listOf(
            EmailCase(
                name = "UPI P2M debit becomes an expense with merchant and 12-digit reference",
                from = ALERTS,
                subject = "INR 47.25 was debited from your A/c no. XX881234.",
                body = """
                    Dear Customer,

                    INR 47.25 was debited from your A/c no. XX881234.

                    Transaction Details:
                    Account Number: XX881234
                    Amount Debited: INR 47.25
                    Date & Time: 16-09-26, 15:59:12 IST
                    Transaction Info: UPI/P2M/400123456789/DEMO MART
                    Available Balance: INR 10,000.00

                    Regards,
                    Axis Bank
                """.trimIndent(),
                expected = ExpectedTransaction(
                    amount = BigDecimal("47.25"),
                    currency = "INR",
                    type = TransactionType.EXPENSE,
                    merchant = "DEMO MART",
                    reference = "400123456789",
                    accountLast4 = "1234",
                    balance = BigDecimal("10000.00"),
                    isFromCard = false
                )
            ),
            EmailCase(
                name = "Same debit as an HTML table, cells joined with pipes",
                from = ALERTS,
                subject = "INR 47.25 was debited from your A/c no. XX881234.",
                body = "Dear Customer, INR 47.25 was debited from your A/c no. XX881234. " +
                    "Transaction Details: | Account Number: | XX881234 | Amount Debited: | INR 47.25 | " +
                    "Date & Time: | 16-09-26, 15:59:12 IST | " +
                    "Transaction Info: | UPI/P2M/400123456789/DEMO MART | " +
                    "Available Balance: | INR 10,000.00",
                expected = ExpectedTransaction(
                    amount = BigDecimal("47.25"),
                    currency = "INR",
                    type = TransactionType.EXPENSE,
                    merchant = "DEMO MART",
                    reference = "400123456789",
                    accountLast4 = "1234",
                    balance = BigDecimal("10000.00")
                )
            ),
            EmailCase(
                name = "Value on the line below its label is still read",
                from = ALERTS,
                subject = "INR 47.25 was debited from your A/c no. XX881234.",
                body = """
                    Account Number:
                    XX881234
                    Amount Debited:
                    INR 47.25
                    Date & Time:
                    16-09-26, 15:59:12 IST
                    Transaction Info:
                    UPI/P2M/400123456789/DEMO MART
                """.trimIndent(),
                expected = ExpectedTransaction(
                    amount = BigDecimal("47.25"),
                    currency = "INR",
                    type = TransactionType.EXPENSE,
                    merchant = "DEMO MART",
                    accountLast4 = "1234"
                )
            ),
            EmailCase(
                name = "Indian grouping in the amount survives Money parsing",
                from = ALERTS,
                subject = "INR 2,53,471.86 was debited from your A/c no. XX881234.",
                body = """
                    Account Number: XX881234
                    Amount Debited: INR 2,53,471.86
                    Date & Time: 16-09-26, 15:59:12 IST
                    Transaction Info: UPI/P2M/400123456789/DEMO MART
                """.trimIndent(),
                expected = ExpectedTransaction(
                    amount = BigDecimal("253471.86"),
                    currency = "INR",
                    type = TransactionType.EXPENSE
                )
            ),
            EmailCase(
                name = "Withdrawal that arrives in the label layout is still merchant ATM",
                from = ALERTS,
                subject = "INR 2,000.00 was debited from your A/c no. XX881234.",
                body = """
                    Account Number: XX881234
                    Amount Debited: INR 2,000.00
                    Date & Time: 12-09-26, 18:45:10 IST
                    Transaction Info: ATM-WDL/S1AB4321/
                    Available Balance: INR 8,000.00
                """.trimIndent(),
                expected = ExpectedTransaction(
                    amount = BigDecimal("2000.00"),
                    currency = "INR",
                    type = TransactionType.EXPENSE,
                    merchant = "ATM",
                    accountLast4 = "1234",
                    balance = BigDecimal("8000.00")
                )
            ),
            EmailCase(
                name = "Non-UPI transaction info is kept verbatim as the merchant",
                from = ALERTS,
                subject = "INR 640.00 was debited from your A/c no. XX881234.",
                body = """
                    Account Number: XX881234
                    Amount Debited: INR 640.00
                    Date & Time: 16-09-26, 11:02:00 IST
                    Transaction Info: NEFT/AXISP00477112/UTILITY BOARD
                """.trimIndent(),
                expected = ExpectedTransaction(
                    amount = BigDecimal("640.00"),
                    currency = "INR",
                    type = TransactionType.EXPENSE,
                    merchant = "NEFT/AXISP00477112/UTILITY BOARD"
                )
            )
        )

        return EmailParserTestUtils.runTransactionSuite(parser, cases)
    }

    @TestFactory
    fun `A2 reads a savings credit and tells income from a self-transfer`(): List<DynamicTest> {
        val cases = listOf(
            EmailCase(
                name = "P2A credit from another person is income",
                from = ALERTS,
                subject = "INR 7,845.60 was credited to your A/c no. XX881234.",
                body = savingsCreditBody("UPI/P2A/400987654321/PAYROLL SERVICES/HDFC/UPI"),
                expected = ExpectedTransaction(
                    amount = BigDecimal("7845.60"),
                    currency = "INR",
                    type = TransactionType.INCOME,
                    merchant = "PAYROLL SERVICES",
                    reference = "400987654321",
                    accountLast4 = "1234",
                    balance = BigDecimal("17845.60")
                )
            ),
            EmailCase(
                name = "P2A credit from the account holder is a transfer, not income",
                from = ALERTS,
                subject = "INR 7,845.60 was credited to your A/c no. XX881234.",
                body = savingsCreditBody("UPI/P2A/400987654321/ACCOUNT HOLDER/HDFC/UPI"),
                expected = ExpectedTransaction(
                    amount = BigDecimal("7845.60"),
                    currency = "INR",
                    type = TransactionType.TRANSFER,
                    reference = "400987654321",
                    accountLast4 = "1234",
                    fromAccount = "HDFC",
                    toAccount = "1234"
                )
            ),
            EmailCase(
                name = "Holder name truncated by the bank still reads as a transfer",
                from = ALERTS,
                subject = "INR 7,845.60 was credited to your A/c no. XX881234.",
                body = savingsCreditBody("UPI/P2A/400987654321/ACCOUNT H/HDFC/UPI"),
                expected = ExpectedTransaction(
                    amount = BigDecimal("7845.60"),
                    currency = "INR",
                    type = TransactionType.TRANSFER,
                    fromAccount = "HDFC",
                    toAccount = "1234"
                )
            ),
            EmailCase(
                name = "P2A credit with no bank segment transfers with an unknown source",
                from = ALERTS,
                subject = "INR 7,845.60 was credited to your A/c no. XX881234.",
                body = savingsCreditBody("UPI/P2A/400987654321/ACCOUNT HOLDER"),
                expected = ExpectedTransaction(
                    amount = BigDecimal("7845.60"),
                    currency = "INR",
                    type = TransactionType.TRANSFER,
                    toAccount = "1234"
                )
            ),
            EmailCase(
                name = "P2M credit is income even when the payer name resembles the holder",
                from = ALERTS,
                subject = "INR 7,845.60 was credited to your A/c no. XX881234.",
                body = savingsCreditBody("UPI/P2M/400987654321/ACCOUNT HOLDER"),
                expected = ExpectedTransaction(
                    amount = BigDecimal("7845.60"),
                    currency = "INR",
                    type = TransactionType.INCOME,
                    merchant = "ACCOUNT HOLDER"
                )
            )
        )

        return EmailParserTestUtils.runTransactionSuite(parser, cases)
    }

    @TestFactory
    fun `A1 A2 self-transfers need the holder names the app was given`(): List<DynamicTest> {
        val cases = listOf(
            EmailCase(
                name = "Without configured holder names the credit stays income",
                from = ALERTS,
                subject = "INR 7,845.60 was credited to your A/c no. XX881234.",
                body = savingsCreditBody("UPI/P2A/400987654321/ACCOUNT HOLDER/HDFC/UPI"),
                expected = ExpectedTransaction(
                    amount = BigDecimal("7845.60"),
                    currency = "INR",
                    type = TransactionType.INCOME,
                    merchant = "ACCOUNT HOLDER"
                )
            )
        )

        return EmailParserTestUtils.runTransactionSuite(parserWithoutHolderNames, cases)
    }

    @TestFactory
    fun `A1 reads a P2A debit to the holder's own account as an outgoing transfer`(): List<DynamicTest> {
        val cases = listOf(
            EmailCase(
                name = "Debit to the holder's other bank is a transfer out",
                from = ALERTS,
                subject = "INR 5,000.00 was debited from your A/c no. XX881234.",
                body = """
                    Account Number: XX881234
                    Amount Debited: INR 5,000.00
                    Date & Time: 16-09-26, 09:15:30 IST
                    Transaction Info: UPI/P2A/400555666777/ACCOUNT HOLDER/HDFC/UPI
                    Available Balance: INR 5,000.00
                """.trimIndent(),
                expected = ExpectedTransaction(
                    amount = BigDecimal("5000.00"),
                    currency = "INR",
                    type = TransactionType.TRANSFER,
                    reference = "400555666777",
                    accountLast4 = "1234",
                    fromAccount = "1234",
                    toAccount = "HDFC"
                )
            ),
            EmailCase(
                name = "Debit to a namesake stranger stays an expense",
                from = ALERTS,
                subject = "INR 5,000.00 was debited from your A/c no. XX881234.",
                body = """
                    Account Number: XX881234
                    Amount Debited: INR 5,000.00
                    Date & Time: 16-09-26, 09:15:30 IST
                    Transaction Info: UPI/P2A/400555666777/OTHER PERSON/HDFC/UPI
                """.trimIndent(),
                expected = ExpectedTransaction(
                    amount = BigDecimal("5000.00"),
                    currency = "INR",
                    type = TransactionType.EXPENSE,
                    merchant = "OTHER PERSON"
                )
            )
        )

        return EmailParserTestUtils.runTransactionSuite(parser, cases)
    }

    @TestFactory
    fun `A3 reads an ATM withdrawal and the balance it leaves`(): List<DynamicTest> {
        val cases = listOf(
            EmailCase(
                name = "ATM withdrawal sentence yields merchant ATM, the ATM id and the balance",
                from = ALERTS,
                subject = "Notification from Axis Bank",
                body = "Dear Customer, INR 2,000.00 has been debited from your A/c no. XX881234 " +
                    "on 12-09-2026 18:45:10 at ATM-WDL/S1AB4321/. Available balance: INR 8,000.00",
                expected = ExpectedTransaction(
                    amount = BigDecimal("2000.00"),
                    currency = "INR",
                    type = TransactionType.EXPENSE,
                    merchant = "ATM",
                    reference = "S1AB4321",
                    accountLast4 = "1234",
                    balance = BigDecimal("8000.00"),
                    isFromCard = false
                )
            ),
            EmailCase(
                name = "Long account run reports only its trailing four digits",
                from = ALERTS,
                subject = "Notification from Axis Bank",
                body = "INR 2,000.00 was debited from your A/c no. XX887712 on 12-09-2026 18:45:10 IST " +
                    "at ATM-WDL/S1AB4321/. Available balance: INR 8,000.00",
                expected = ExpectedTransaction(
                    amount = BigDecimal("2000.00"),
                    currency = "INR",
                    type = TransactionType.EXPENSE,
                    merchant = "ATM",
                    accountLast4 = "7712"
                )
            )
        )

        return EmailParserTestUtils.runTransactionSuite(parser, cases)
    }

    @TestFactory
    fun `A4 reads a credit-card purchase and the card's total limit`(): List<DynamicTest> {
        val cases = listOf(
            EmailCase(
                name = "Card purchase with an amount that has no decimals at all",
                from = ALERTS,
                subject = "INR 214 spent on credit card no. XX5678",
                body = cardPurchaseBody(),
                expected = ExpectedTransaction(
                    amount = BigDecimal("214.00"),
                    currency = "INR",
                    type = TransactionType.CREDIT,
                    merchant = "ACME RETAIL",
                    accountLast4 = "5678",
                    creditLimit = BigDecimal("52000.00"),
                    isFromCard = true
                )
            ),
            EmailCase(
                name = "Same card purchase as an HTML table, cells joined with pipes",
                from = ALERTS,
                subject = "INR 214 spent on credit card no. XX5678",
                body = "Dear Customer, | Transaction Amount: | INR 214 | Merchant Name: | ACME RETAIL | " +
                    "Credit Card No. | XX5678 | Date & Time: | 15-09-2026, 12:04:06 IST | " +
                    "Available Limit*: | INR 31,275.64 | Total Credit Limit*: | INR 52,000",
                expected = ExpectedTransaction(
                    amount = BigDecimal("214.00"),
                    currency = "INR",
                    type = TransactionType.CREDIT,
                    merchant = "ACME RETAIL",
                    accountLast4 = "5678",
                    creditLimit = BigDecimal("52000.00"),
                    isFromCard = true
                )
            ),
            EmailCase(
                name = "Card purchase with a grouped, decimal amount",
                from = ALERTS,
                subject = "INR 1,234.50 spent on credit card no. XX5678",
                body = cardPurchaseBody(amount = "INR 1,234.50", merchant = "EXAMPLE STORE"),
                expected = ExpectedTransaction(
                    amount = BigDecimal("1234.50"),
                    currency = "INR",
                    type = TransactionType.CREDIT,
                    merchant = "EXAMPLE STORE",
                    accountLast4 = "5678",
                    creditLimit = BigDecimal("52000.00"),
                    isFromCard = true
                )
            )
        )

        return EmailParserTestUtils.runTransactionSuite(parser, cases)
    }

    @TestFactory
    fun `mail that states no transaction is rejected`(): List<DynamicTest> {
        val cases = listOf(
            EmailCase(
                name = "A6 net banking password change",
                from = ALERTS,
                subject = "Your Net Banking password has been changed",
                body = "Dear Customer, your Net Banking password has been changed on 16-09-26 at 15:59:12 IST. " +
                    "If this was not you, call us immediately.",
                shouldParse = false
            ),
            EmailCase(
                name = "A6 cooling period notice",
                from = ALERTS,
                subject = "Cooling period on your newly added payee",
                body = "Dear Customer, a cooling period applies to the payee you added. " +
                    "You can transfer funds to this payee after 24 hours.",
                shouldParse = false
            ),
            EmailCase(
                name = "A6 credit limit reduction",
                from = ALERTS,
                subject = "Notification from Axis Bank",
                body = "Dear Customer, the credit limit on your credit card no. XX5678 has been reduced. " +
                    "Total Credit Limit*: INR 52,000",
                shouldParse = false
            ),
            EmailCase(
                name = "Statement mail states no amount",
                from = "statements@axis.bank.in",
                subject = "Axis Bank savings account e-statement",
                body = "Dear Customer, please find attached your account statement for August 2026. " +
                    "The attachment is password protected.",
                shouldParse = false
            ),
            EmailCase(
                name = "Label present but its amount is missing - never borrow the next line's digits",
                from = ALERTS,
                subject = "INR was debited from your A/c no. XX881234.",
                body = """
                    Account Number: XX881234
                    Amount Debited: INR
                    Date & Time: 16-09-26, 15:59:12 IST
                    Transaction Info: UPI/P2M/400123456789/DEMO MART
                    Available Balance: INR 10,000.00
                """.trimIndent(),
                shouldParse = false
            ),
            EmailCase(
                name = "Card mail whose transaction amount is missing",
                from = ALERTS,
                subject = "Transaction on credit card no. XX5678",
                body = """
                    Merchant Name: ACME RETAIL
                    Credit Card No. XX5678
                    Date & Time: 15-09-2026, 12:04:06 IST
                    Total Credit Limit*: INR 52,000
                """.trimIndent(),
                shouldParse = false
            )
        )

        val handleCases = listOf(
            ALERTS to true,
            "\"Axis Bank\" <alerts@axis.bank.in>" to true,
            "cc.statements@axis.bank.in" to true,
            "alerts@hdfcbank.bank.in" to false,
            "credit_cards@icici.bank.in" to false,
            "alerts@axis.bank.in.example.com" to false,
            "not-an-address" to false
        )

        return EmailParserTestUtils.runTransactionSuite(parser, cases, handleCases)
    }

    @TestFactory
    fun `A6 marks lifecycle mail as a non-transaction without swallowing real ones`(): List<DynamicTest> = listOf(
        nonTransactionTest(
            name = "Password change is a non-transaction",
            subject = "Your Net Banking password has been changed",
            body = "Dear Customer, your Net Banking password has been changed.",
            expected = true
        ),
        nonTransactionTest(
            name = "Cooling period notice is a non-transaction",
            subject = "Notification from Axis Bank",
            body = "A cooling period of 24 hours applies to the payee you added.",
            expected = true
        ),
        nonTransactionTest(
            name = "Limit reduction notice is a non-transaction",
            subject = "Notification from Axis Bank",
            body = "The credit limit on your card has been reduced with effect from 16-09-26.",
            expected = true
        ),
        nonTransactionTest(
            name = "Card purchase is never a non-transaction even though it quotes limits",
            subject = "INR 214 spent on credit card no. XX5678",
            body = cardPurchaseBody(),
            expected = false
        ),
        nonTransactionTest(
            name = "Savings credit is never a non-transaction",
            subject = "INR 7,845.60 was credited to your A/c no. XX881234.",
            body = savingsCreditBody("UPI/P2M/400123456789/DEMO MART"),
            expected = false
        )
    )

    @TestFactory
    fun `the date inside the body wins over the received time`(): List<DynamicTest> = listOf(
        dynamicTest("A1 timestamp comes from the Date & Time line") {
            val parsed = parser.parse(
                EmailParserTestUtils.message(
                    from = ALERTS,
                    subject = "INR 47.25 was debited from your A/c no. XX881234.",
                    body = """
                        Account Number: XX881234
                        Amount Debited: INR 47.25
                        Date & Time: 16-09-26, 15:59:12 IST
                        Transaction Info: UPI/P2M/400123456789/DEMO MART
                    """.trimIndent()
                )
            )
            assertNotNull(parsed)
            assertEquals(istMillis(2026, 9, 16, 15, 59, 12), parsed!!.timestamp)
        },
        dynamicTest("A4 timestamp reads the four-digit year the card template uses") {
            val parsed = parser.parse(
                EmailParserTestUtils.message(
                    from = ALERTS,
                    subject = "INR 214 spent on credit card no. XX5678",
                    body = cardPurchaseBody()
                )
            )
            assertNotNull(parsed)
            assertEquals(istMillis(2026, 9, 15, 12, 4, 6), parsed!!.timestamp)
        },
        dynamicTest("A3 timestamp comes from the withdrawal sentence") {
            val parsed = parser.parse(
                EmailParserTestUtils.message(
                    from = ALERTS,
                    subject = "Notification from Axis Bank",
                    body = "INR 2,000.00 has been debited from your A/c no. XX881234 on 12-09-2026 18:45:10 " +
                        "at ATM-WDL/S1AB4321/. Available balance: INR 8,000.00"
                )
            )
            assertNotNull(parsed)
            assertEquals(istMillis(2026, 9, 12, 18, 45, 10), parsed!!.timestamp)
        },
        dynamicTest("A mail with no stated date falls back to when it was received") {
            val parsed = parser.parse(
                EmailParserTestUtils.message(
                    from = ALERTS,
                    subject = "INR 47.25 was debited from your A/c no. XX881234.",
                    body = """
                        Account Number: XX881234
                        Amount Debited: INR 47.25
                        Transaction Info: UPI/P2M/400123456789/DEMO MART
                    """.trimIndent()
                )
            )
            assertNotNull(parsed)
            assertEquals(EmailParserTestUtils.FIXED_RECEIVED_AT, parsed!!.timestamp)
        }
    )

    @TestFactory
    fun `fields the mail does not state are left unknown`(): List<DynamicTest> = listOf(
        dynamicTest("A4 without limit lines reports no credit limit rather than zero") {
            val parsed = parser.parse(
                EmailParserTestUtils.message(
                    from = ALERTS,
                    subject = "INR 214 spent on credit card no. XX5678",
                    body = """
                        Transaction Amount: INR 214
                        Merchant Name: ACME RETAIL
                        Credit Card No. XX5678
                        Date & Time: 15-09-2026, 12:04:06 IST
                    """.trimIndent()
                )
            )
            assertNotNull(parsed)
            assertNull(parsed!!.creditLimit, "No limit was stated")
            assertEquals(BigDecimal("214.00"), parsed.amount)
        },
        dynamicTest("A4 states limits, not a balance, so no balance is reported") {
            val parsed = parser.parse(
                EmailParserTestUtils.message(
                    from = ALERTS,
                    subject = "INR 214 spent on credit card no. XX5678",
                    body = cardPurchaseBody()
                )
            )
            assertNotNull(parsed)
            assertNull(parsed!!.balance, "The available limit is not a balance")
        },
        dynamicTest("A1 without an account line reports no account rather than a guess") {
            val parsed = parser.parse(
                EmailParserTestUtils.message(
                    from = ALERTS,
                    subject = "INR 47.25 was debited from your A/c.",
                    body = """
                        Amount Debited: INR 47.25
                        Date & Time: 16-09-26, 15:59:12 IST
                        Transaction Info: UPI/P2M/400123456789/DEMO MART
                    """.trimIndent()
                )
            )
            assertNotNull(parsed)
            assertNull(parsed!!.accountLast4, "No account number was stated")
        },
        dynamicTest("A1 without an available balance reports no balance") {
            val parsed = parser.parse(
                EmailParserTestUtils.message(
                    from = ALERTS,
                    subject = "INR 47.25 was debited from your A/c no. XX881234.",
                    body = """
                        Account Number: XX881234
                        Amount Debited: INR 47.25
                        Date & Time: 16-09-26, 15:59:12 IST
                        Transaction Info: UPI/P2M/400123456789/DEMO MART
                    """.trimIndent()
                )
            )
            assertNotNull(parsed)
            assertNull(parsed!!.balance, "No balance was stated")
        },
        dynamicTest("The parser reports Axis Bank and echoes the body it read") {
            val body = cardPurchaseBody()
            val parsed = parser.parse(
                EmailParserTestUtils.message(
                    from = ALERTS,
                    subject = "INR 214 spent on credit card no. XX5678",
                    body = body
                )
            )
            assertNotNull(parsed)
            assertEquals("Axis Bank", parsed!!.bankName)
            assertEquals(body, parsed.smsBody)
            assertEquals(ALERTS, parsed.sender)
        }
    )

    // ========================================
    // Helpers
    // ========================================

    private fun nonTransactionTest(
        name: String,
        subject: String,
        body: String,
        expected: Boolean
    ): DynamicTest = dynamicTest(name) {
        val message = EmailParserTestUtils.message(from = ALERTS, subject = subject, body = body)
        if (expected) {
            assertTrue(parser.isNonTransactionEmail(message), "Expected a non-transaction verdict")
            assertNull(parser.parse(message), "A non-transaction mail must not produce a transaction")
        } else {
            assertFalse(parser.isNonTransactionEmail(message), "Expected the mail to reach the parser")
        }
    }

    private fun savingsCreditBody(transactionInfo: String): String = """
        Dear Customer,

        Transaction Details:
        Account Number: XX881234
        Amount Credited: INR 7,845.60
        Date & Time: 16-09-26, 16:02:41 IST
        Transaction Info: $transactionInfo
        Available Balance: INR 17,845.60
    """.trimIndent()

    private fun cardPurchaseBody(
        amount: String = "INR 214",
        merchant: String = "ACME RETAIL"
    ): String = """
        Dear Customer,

        Transaction Details:
        Transaction Amount: $amount
        Merchant Name: $merchant
        Credit Card No. XX5678
        Date & Time: 15-09-2026, 12:04:06 IST
        Available Limit*: INR 31,275.64
        Total Credit Limit*: INR 52,000
    """.trimIndent()

    private fun istMillis(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        second: Int
    ): Long = ZonedDateTime.of(
        LocalDateTime.of(year, month, day, hour, minute, second),
        EmailParserTestUtils.FIXED_ZONE
    ).toInstant().toEpochMilli()

    private companion object {
        const val ALERTS = "alerts@axis.bank.in"

        /** Stand-in for the mailbox owner's name; no real name appears in these fixtures. */
        const val ACCOUNT_HOLDER = "ACCOUNT HOLDER"
    }
}
