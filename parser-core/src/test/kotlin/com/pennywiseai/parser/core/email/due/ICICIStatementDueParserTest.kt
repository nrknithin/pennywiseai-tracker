package com.pennywiseai.parser.core.email.due

import com.pennywiseai.parser.core.test.EmailParserTestUtils
import com.pennywiseai.parser.core.test.EmailParserTestUtils.DueCase
import com.pennywiseai.parser.core.test.EmailParserTestUtils.ExpectedDue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Spec 12 §12.3 I3 — the ICICI credit-card statement due.
 *
 * Fixtures keep the dates the corpus states (spec 10 §10.2: the bill whose total the
 * later "Payment received" mail settles) and replace every amount and card number with an
 * invented one, per repo constraint 4 and spec constraint C3.
 *
 * Bodies are written in the two shapes `EmailBodyExtractor` produces (spec 11 §11.5): the
 * HTML statement table flattened with ` | ` between cells, and a plain-text variant with
 * one label per line.
 */
class ICICIStatementDueParserTest {

    private val parser = ICICIStatementDueParser()

    @TestFactory
    fun `icici statement parser reads the bill it is sent`(): List<DynamicTest> {
        val cases = listOf(
            DueCase(
                name = "Statement table: total, minimum and due date",
                from = SENDER,
                subject = SUBJECT,
                body = HTML_BODY,
                expected = listOf(
                    ExpectedDue(
                        issuer = "ICICI Bank",
                        kind = DueKind.CARD_STATEMENT,
                        amount = BigDecimal("4318.63"),
                        currency = "INR",
                        dueDate = LocalDate.of(2026, 9, 22),
                        minimumDue = BigDecimal("215.00"),
                        accountLast4 = "7788",
                        autoPay = false,
                        source = "ICICI"
                    )
                )
            ),
            DueCase(
                name = "Plain-text variant: colon labels, Rs. prefix, dd-MMM-yyyy due date",
                from = SENDER,
                subject = SUBJECT,
                body = PLAIN_BODY,
                expected = listOf(
                    ExpectedDue(
                        amount = BigDecimal("4318.63"),
                        dueDate = LocalDate.of(2026, 9, 22),
                        minimumDue = BigDecimal("215.00"),
                        accountLast4 = "7788",
                        source = "ICICI"
                    )
                )
            ),
            DueCase(
                name = "Due date written as dd/MM/yyyy",
                from = SENDER,
                subject = SUBJECT,
                body = HTML_BODY.replace("Payment due by | Sep 22, 2026", "Payment due by | 22/09/2026"),
                expected = listOf(
                    ExpectedDue(
                        amount = BigDecimal("4318.63"),
                        dueDate = LocalDate.of(2026, 9, 22)
                    )
                )
            ),
            DueCase(
                name = "Card number stated in the subject rather than the body",
                from = SENDER,
                subject = "Amazon Pay ICICI Bank Credit Card XX7788 Statement for the period " +
                    "Aug 05, 2026 to Sep 04, 2026",
                body = HTML_BODY.replace("Card Number | 5241 XXXX XXXX 7788 | ", ""),
                expected = listOf(
                    ExpectedDue(
                        amount = BigDecimal("4318.63"),
                        accountLast4 = "7788",
                        dueDate = LocalDate.of(2026, 9, 22)
                    )
                )
            ),
            DueCase(
                name = "Amount label carries a unit parenthetical",
                from = SENDER,
                subject = SUBJECT,
                body = HTML_BODY
                    .replace("Total Amount Due | INR", "Total Amount Due (in INR) |")
                    .replace("Minimum Amount Due | INR", "Minimum Amount Due (in INR) |"),
                expected = listOf(
                    ExpectedDue(
                        amount = BigDecimal("4318.63"),
                        minimumDue = BigDecimal("215.00"),
                        dueDate = LocalDate.of(2026, 9, 22)
                    )
                )
            ),
            DueCase(
                name = "Statement without a minimum still yields the bill",
                from = SENDER,
                subject = SUBJECT,
                body = HTML_BODY.replace("Minimum Amount Due | INR 215.00 | ", ""),
                expected = listOf(
                    ExpectedDue(
                        amount = BigDecimal("4318.63"),
                        dueDate = LocalDate.of(2026, 9, 22)
                    )
                )
            )
        )

        return EmailParserTestUtils.runDueSuite(parser, cases)
    }

    @TestFactory
    fun `icici statement parser refuses to guess`(): List<DynamicTest> {
        val cases = listOf(
            DueCase(
                name = "No payment due date stated - no due rather than a guessed date",
                from = SENDER,
                subject = SUBJECT,
                body = HTML_BODY.replace("Payment due by | Sep 22, 2026 | ", "")
            ),
            DueCase(
                name = "No total amount due stated - no due rather than the minimum",
                from = SENDER,
                subject = SUBJECT,
                body = HTML_BODY.replace("Total Amount Due | INR 4,318.63 | ", "")
            ),
            DueCase(
                name = "Nil statement - nothing to pay, so no due",
                from = SENDER,
                subject = SUBJECT,
                body = HTML_BODY.replace("INR 4,318.63", "INR 0.00")
            ),
            DueCase(
                name = "Transaction alert from the same sender is not a statement",
                from = SENDER,
                subject = "Transaction alert for your ICICI Bank Credit Card",
                body = "Dear Customer, Your ICICI Bank Credit Card XX7788 has been used for a " +
                    "transaction of INR 812.00 on Sep 04, 2026 at 10:13:10. Info: MERCHANT NAME. " +
                    "The Available Credit Limit on your card is INR 2,31,764.55 and Total Credit " +
                    "Limit is INR 3,50,000.00."
            ),
            DueCase(
                name = "Payment receipt from the same sender is not a statement",
                from = SENDER,
                subject = "Payment received on your ICICI Bank Credit Card.",
                body = "We have received payment of INR 4,318.63 on your ICICI Bank Credit Card " +
                    "account 5241 XXXX XXXX 7788 on 03-Aug-2026."
            ),
            DueCase(
                name = "The same statement body from another bank is not this parser's mail",
                from = "cc.statements@axis.bank.in",
                subject = SUBJECT,
                body = HTML_BODY
            )
        )

        return EmailParserTestUtils.runDueSuite(
            parser,
            cases,
            handleCases = listOf(
                SENDER to true,
                "\"ICICI Bank Credit Cards\" <credit_cards@icici.bank.in>" to true,
                "CREDIT_CARDS@ICICI.BANK.IN" to true,
                "services@custcomm.icici.bank.in" to false,
                "cc.statements@axis.bank.in" to false,
                "alerts@hdfcbank.bank.in" to false
            )
        )
    }

    @Test
    fun `period label and card product name both come from the subject`() {
        val due = parser.parse(EmailParserTestUtils.message(SENDER, SUBJECT, HTML_BODY)).single()

        assertEquals("Aug 05, 2026 to Sep 04, 2026", due.periodLabel, "periodLabel")
        assertEquals("Amazon Pay ICICI Bank Credit Card", due.description, "description")
    }

    @Test
    fun `an unstated minimum stays null instead of defaulting`() {
        val body = HTML_BODY.replace("Minimum Amount Due | INR 215.00 | ", "")

        val due = parser.parse(EmailParserTestUtils.message(SENDER, SUBJECT, body)).single()

        assertNull(due.minimumDue, "minimumDue")
    }

    @Test
    fun `an unstated card number stays null instead of being borrowed from elsewhere`() {
        val body = HTML_BODY.replace("Card Number | 5241 XXXX XXXX 7788 | ", "")

        val due = parser.parse(EmailParserTestUtils.message(SENDER, SUBJECT, body)).single()

        assertNull(due.accountLast4, "accountLast4")
    }

    private companion object {
        const val SENDER = "credit_cards@icici.bank.in"

        const val SUBJECT =
            "Amazon Pay ICICI Bank Credit Card Statement for the period Aug 05, 2026 to Sep 04, 2026"

        /** The HTML summary table as spec 11 §11.5 flattens it: cells joined with ` | `. */
        const val HTML_BODY =
            "Dear Customer | Here is your Amazon Pay ICICI Bank Credit Card statement. | " +
                "Card Number | 5241 XXXX XXXX 7788 | Statement Date | Sep 04, 2026 | " +
                "Total Amount Due | INR 4,318.63 | Minimum Amount Due | INR 215.00 | " +
                "Payment due by | Sep 22, 2026 | " +
                "Your statement is attached as a password-protected PDF."

        val PLAIN_BODY = listOf(
            "Dear Customer,",
            "",
            "Amazon Pay ICICI Bank Credit Card statement",
            "Card Number: XX7788",
            "Statement Date: 04-Sep-2026",
            "Total Amount Due: Rs. 4,318.63",
            "Minimum Amount Due: Rs. 215.00",
            "Payment due by: 22-Sep-2026"
        ).joinToString("\n")
    }
}
