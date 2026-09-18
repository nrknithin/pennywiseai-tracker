package com.nrknithin.fintrack.parser.core.email.due

import com.nrknithin.fintrack.parser.core.test.EmailParserTestUtils
import com.nrknithin.fintrack.parser.core.test.EmailParserTestUtils.DueCase
import com.nrknithin.fintrack.parser.core.test.EmailParserTestUtils.ExpectedDue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Spec 12 §12.2 A5 — the Axis credit-card statement due, whose summary figures carry no
 * labels of their own (spec 10 §10.2: positional `<total> Dr <min> Dr <dd/MM/yyyy>`).
 *
 * The due date is the one the corpus states for this card (a reminder mail repeats this
 * bill, due on 02 Oct 2026); the amounts are invented — no sample even states a minimum
 * — as are the card digits, per repo constraint 4 and spec constraint C3.
 *
 * Bodies follow spec 11 §11.5: HTML tables flattened with ` | ` between cells.
 */
class AxisStatementDueParserTest {

    private val parser = AxisStatementDueParser()

    @TestFactory
    fun `axis statement parser reads the positional summary row`(): List<DynamicTest> {
        val cases = listOf(
            DueCase(
                name = "Header row then value row - the shape the corpus describes",
                from = SENDER,
                subject = SUBJECT,
                body = HTML_BODY,
                expected = listOf(
                    ExpectedDue(
                        issuer = "Axis Bank",
                        kind = DueKind.CARD_STATEMENT,
                        amount = BigDecimal("21483.65"),
                        currency = "INR",
                        dueDate = LocalDate.of(2026, 10, 2),
                        minimumDue = BigDecimal("883.00"),
                        accountLast4 = "55",
                        autoPay = false,
                        source = "AXIS"
                    )
                )
            ),
            DueCase(
                name = "Label and value cells interleaved",
                from = SENDER,
                subject = SUBJECT,
                body = "Dear Cardholder | Total Payment Due | 21,483.65 Dr | " +
                    "Minimum Payment Due | 883.00 Dr | Payment Due Date | 02/10/2026 | " +
                    "Statement attached.",
                expected = listOf(
                    ExpectedDue(
                        amount = BigDecimal("21483.65"),
                        minimumDue = BigDecimal("883.00"),
                        dueDate = LocalDate.of(2026, 10, 2),
                        source = "AXIS"
                    )
                )
            ),
            DueCase(
                name = "Debit marker written as Dr. with wider spacing",
                from = SENDER,
                subject = SUBJECT,
                body = "Statement summary  21,483.65  Dr.   883.00  Dr.   02/10/2026",
                expected = listOf(
                    ExpectedDue(
                        amount = BigDecimal("21483.65"),
                        minimumDue = BigDecimal("883.00"),
                        dueDate = LocalDate.of(2026, 10, 2)
                    )
                )
            ),
            DueCase(
                name = "Due date written with dashes",
                from = SENDER,
                subject = SUBJECT,
                body = HTML_BODY.replace("02/10/2026", "02-10-2026"),
                expected = listOf(
                    ExpectedDue(
                        amount = BigDecimal("21483.65"),
                        dueDate = LocalDate.of(2026, 10, 2)
                    )
                )
            ),
            DueCase(
                name = "Four-digit card mask in the subject",
                from = SENDER,
                subject = "Airtel Axis Bank Rupay Credit Card ending XX4455 - Sep 2026",
                body = HTML_BODY,
                expected = listOf(
                    ExpectedDue(
                        amount = BigDecimal("21483.65"),
                        accountLast4 = "4455",
                        dueDate = LocalDate.of(2026, 10, 2)
                    )
                )
            ),
            DueCase(
                name = "Minimum stated as nil is kept as stated",
                from = SENDER,
                subject = SUBJECT,
                body = HTML_BODY.replace("883.00 Dr", "0.00 Dr"),
                expected = listOf(
                    ExpectedDue(
                        amount = BigDecimal("21483.65"),
                        minimumDue = BigDecimal("0.00"),
                        dueDate = LocalDate.of(2026, 10, 2)
                    )
                )
            )
        )

        return EmailParserTestUtils.runDueSuite(parser, cases)
    }

    @TestFactory
    fun `axis statement parser returns no due rather than guessing`(): List<DynamicTest> {
        val cases = listOf(
            DueCase(
                name = "Credit balance - Cr instead of Dr, and nothing to pay",
                from = SENDER,
                subject = SUBJECT,
                body = HTML_BODY.replace(" Dr ", " Cr ")
            ),
            DueCase(
                name = "Only one amount carries a debit marker - the run is incomplete",
                from = SENDER,
                subject = SUBJECT,
                body = "Total Payment Due | Payment Due Date | 21,483.65 Dr | 02/10/2026"
            ),
            DueCase(
                name = "No due date after the two amounts",
                from = SENDER,
                subject = SUBJECT,
                body = "Total Payment Due | Minimum Payment Due | 21,483.65 Dr | 883.00 Dr"
            ),
            DueCase(
                name = "Minimum larger than the total - positions are not what they seem",
                from = SENDER,
                subject = SUBJECT,
                body = HTML_BODY.replace("21,483.65 Dr | 883.00 Dr", "883.00 Dr | 21,483.65 Dr")
            ),
            DueCase(
                name = "Nil total - nothing to pay, so no due",
                from = SENDER,
                subject = SUBJECT,
                body = HTML_BODY.replace("21,483.65 Dr | 883.00 Dr", "0.00 Dr | 0.00 Dr")
            ),
            DueCase(
                name = "Cover note with the figures only in the attached PDF",
                from = SENDER,
                subject = SUBJECT,
                body = "Dear Cardholder | Your credit card statement for September 2026 is " +
                    "attached. | The PDF is password protected. | " +
                    "Statement Period | 14/08/2026 - 13/09/2026"
            ),
            DueCase(
                name = "The same summary from the transaction-alert address is not this mail",
                from = "alerts@axis.bank.in",
                subject = SUBJECT,
                body = HTML_BODY
            )
        )

        return EmailParserTestUtils.runDueSuite(
            parser,
            cases,
            handleCases = listOf(
                SENDER to true,
                "\"Axis Bank Credit Cards\" <cc.statements@axis.bank.in>" to true,
                "CC.STATEMENTS@AXIS.BANK.IN" to true,
                "alerts@axis.bank.in" to false,
                "statements@axis.bank.in" to false,
                "credit_cards@icici.bank.in" to false
            )
        )
    }

    @Test
    fun `period label prefers the statement period stated in the body`() {
        val due = parser.parse(EmailParserTestUtils.message(SENDER, SUBJECT, HTML_BODY)).single()

        assertEquals("14/08/2026 - 13/09/2026", due.periodLabel, "periodLabel")
        assertEquals("Airtel Axis Bank Rupay Credit Card", due.description, "description")
    }

    @Test
    fun `period label falls back to the month in the subject`() {
        val body = HTML_BODY.replace("Statement Period | 14/08/2026 - 13/09/2026 | ", "")

        val due = parser.parse(EmailParserTestUtils.message(SENDER, SUBJECT, body)).single()

        assertEquals("Sep 2026", due.periodLabel, "periodLabel")
    }

    @Test
    fun `a subject without a card mask leaves the card unknown`() {
        val due = parser.parse(
            EmailParserTestUtils.message(SENDER, "Your credit card statement - Sep 2026", HTML_BODY)
        ).single()

        assertNull(due.accountLast4, "accountLast4")
    }

    private companion object {
        const val SENDER = "cc.statements@axis.bank.in"

        /** Spec 12 §12.2 A5: `<card name> ending XX<2-4> - <Month YYYY>`. */
        const val SUBJECT = "Airtel Axis Bank Rupay Credit Card ending XX55 - Sep 2026"

        /**
         * The summary table as spec 11 §11.5 flattens it: the labels sit in the header row
         * and the three figures follow as one positional run.
         */
        const val HTML_BODY =
            "Dear Cardholder | Statement Period | 14/08/2026 - 13/09/2026 | " +
                "Total Payment Due | Minimum Payment Due | Payment Due Date | " +
                "21,483.65 Dr | 883.00 Dr | 02/10/2026 | " +
                "Your statement is attached as a password-protected PDF."
    }
}
