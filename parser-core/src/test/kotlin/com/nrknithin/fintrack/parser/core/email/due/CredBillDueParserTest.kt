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
 * Template C1 (spec 12 §12.6): CRED Protect "new bill" notices.
 *
 * Fixtures keep the lower-case prose CRED actually sends and the amounts the spec samples
 * state; card numbers are invented four-digit runs (spec 01, constraint C3).
 */
class CredBillDueParserTest {

    private val parser = CredBillDueParser()

    @TestFactory
    fun `cred new bill notices become card statement dues`(): List<DynamicTest> {
        val cases = listOf(
            DueCase(
                name = "Axis bill: total, minimum, due date and card",
                from = SENDER,
                subject = "Important : New bill for your Axis Bank card XXXX-1234",
                body = AXIS_BILL,
                expected = listOf(
                    ExpectedDue(
                        issuer = "Axis Bank",
                        kind = DueKind.CARD_STATEMENT,
                        amount = BigDecimal("12480.55"),
                        currency = "INR",
                        dueDate = LocalDate.of(2026, 10, 2),
                        minimumDue = BigDecimal("883.00"),
                        accountLast4 = "1234",
                        feesDetected = BigDecimal("347.65"),
                        source = "CRED"
                    )
                )
            ),
            DueCase(
                name = "Extra charges never become the bill amount",
                from = SENDER,
                subject = "Important : New bill for your HDFC Bank card XXXX-9012",
                body = HDFC_BILL_FEES_FIRST,
                expected = listOf(
                    ExpectedDue(
                        issuer = "HDFC Bank",
                        // Odd grouping the sender emits verbatim; Money repairs it
                        // (spec 12 §12.11) and the 347.65 finding stays a finding.
                        amount = BigDecimal("253471.86"),
                        minimumDue = BigDecimal("7130.00"),
                        accountLast4 = "9012",
                        feesDetected = BigDecimal("347.65"),
                        source = "CRED"
                    )
                )
            ),
            DueCase(
                name = "HTML-derived body with cell separators and full month names",
                from = SENDER,
                subject = "Important : New bill for your ICICI Bank card XXXX-5678",
                body = ICICI_BILL_HTML,
                expected = listOf(
                    ExpectedDue(
                        issuer = "ICICI Bank",
                        kind = DueKind.CARD_STATEMENT,
                        amount = BigDecimal("3918.60"),
                        dueDate = LocalDate.of(2026, 9, 22),
                        minimumDue = BigDecimal("562.00"),
                        accountLast4 = "5678"
                    )
                )
            ),
            DueCase(
                name = "Single bullet and a masked card run",
                from = SENDER,
                subject = "Important : New bill for your SBI Card card XXXX-2468",
                body = SBI_BILL_MASKED,
                expected = listOf(
                    ExpectedDue(
                        issuer = "SBI Card",
                        amount = BigDecimal("9148.00"),
                        dueDate = LocalDate.of(2026, 10, 5),
                        accountLast4 = "2468"
                    )
                )
            ),
            DueCase(
                name = "Rejects the payment-success mail from the same sender",
                from = SENDER,
                subject = "your credit card bill payment was successful",
                body = PAYMENT_SUCCESS,
                expected = emptyList()
            ),
            DueCase(
                name = "Rejects a bill block with no due date",
                from = SENDER,
                subject = "Important : New bill for your Axis Bank card XXXX-1234",
                body = AXIS_BILL_NO_DUE_DATE,
                expected = emptyList()
            ),
            DueCase(
                name = "Rejects an issuer outside the known set",
                from = SENDER,
                subject = "Important : New bill for your Kotak Bank card XXXX-1357",
                body = UNKNOWN_ISSUER_BILL,
                expected = emptyList()
            ),
            DueCase(
                name = "Rejects a CRED mail with no bill summary",
                from = SENDER,
                subject = "your credit score has been updated",
                body = "your credit score is now 812. check what changed on the cred app.",
                expected = emptyList()
            )
        )

        val handleCases = listOf(
            SENDER to true,
            "CRED Protect <protect@cred.club>" to true,
            "talk@cred.club" to false,
            "alerts@hdfcbank.bank.in" to false
        )

        return EmailParserTestUtils.runDueSuite(parser, cases, handleCases)
    }

    @Test
    fun `bill generated date is carried as the period label`() {
        val due = parseOne(AXIS_BILL)
        assertEquals("generated sep 07, 2026", due.periodLabel, "periodLabel")
    }

    @Test
    fun `a bill with no extra-charges line leaves feesDetected unset`() {
        val due = parseOne(ICICI_BILL_HTML)
        assertNull(due.feesDetected, "feesDetected must stay null when CRED found nothing")
    }

    @Test
    fun `the due is attributed to the issuer and not to the aggregator`() {
        val due = parseOne(AXIS_BILL)
        assertEquals("Axis Bank", due.issuer, "issuer")
        assertEquals("CRED", due.source, "source")
    }

    private fun parseOne(body: String): ParsedDue {
        val dues = parser.parse(
            EmailParserTestUtils.message(
                from = SENDER,
                subject = "Important : New bill for your card",
                body = body
            )
        )
        assertEquals(1, dues.size, "Expected exactly one due, got $dues")
        return dues.first()
    }

    private companion object {
        const val SENDER = "protect@cred.club"

        /** The bullet CRED puts between the issuer and the card, and the rupee sign. */
        const val BULLET = "•"
        const val RUPEE = "₹"

        /** text/plain, kept as CRED writes it: lower-case prose, one field per line. */
        val AXIS_BILL = """
            hi there, your new bill is here.
            axis bank $BULLET$BULLET 1234
            bill summary
            total amount due
            ${RUPEE}12,480.55
            minimum due
            ${RUPEE}883.00
            due date
            october 2, 2026
            bill generated on
            sep 07, 2026
            CRED Protect helped you identify ${RUPEE}347.65 in extra charges
            pay on cred and win rewards
        """.trimIndent()

        /** The corpus wording for the finding, placed before the bill block. */
        val HDFC_BILL_FEES_FIRST = """
            extra charges detected ${RUPEE}347.65 on your last statement
            hdfc bank $BULLET$BULLET 9012
            bill summary
            total amount due ${RUPEE}2,53471.86
            minimum due ${RUPEE}7,130.00
            due date oct 1, 2026
            bill generated on sep 11, 2026
        """.trimIndent()

        /** HTML-derived: table cells joined with " | ", whitespace collapsed (spec 11 §11.5). */
        val ICICI_BILL_HTML =
            "icici bank $BULLET$BULLET 5678 | bill summary | total amount due | $RUPEE 3,918.60 | " +
                "minimum due | $RUPEE 562.00 | due date | September 22, 2026 | " +
                "bill generated on | September 02, 2026 | pay now"

        val SBI_BILL_MASKED = """
            sbi card $BULLET xxxx-2468
            bill summary
            total amount due ${RUPEE}9,148.00
            minimum due ${RUPEE}457.00
            due date oct 5, 2026
            bill generated on sep 15, 2026
        """.trimIndent()

        val AXIS_BILL_NO_DUE_DATE = """
            axis bank $BULLET$BULLET 1234
            bill summary
            total amount due ${RUPEE}12,480.55
            minimum due ${RUPEE}883.00
            bill generated on sep 07, 2026
        """.trimIndent()

        val UNKNOWN_ISSUER_BILL = """
            kotak bank $BULLET$BULLET 1357
            bill summary
            total amount due ${RUPEE}4,120.00
            minimum due ${RUPEE}206.00
            due date oct 2, 2026
            bill generated on sep 07, 2026
        """.trimIndent()

        val PAYMENT_SUCCESS = """
            your credit card bill payment was successful
            axis bank $BULLET$BULLET 1234
            payment details
            amount paid ${RUPEE}51,204.00
            payment date sep 04, 2026
            UTR No.: 525312345678
        """.trimIndent()
    }
}
