package com.pennywiseai.parser.core.email.due

import com.pennywiseai.parser.core.test.EmailParserTestUtils
import com.pennywiseai.parser.core.test.EmailParserTestUtils.DueCase
import com.pennywiseai.parser.core.test.EmailParserTestUtils.ExpectedDue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * Template H12 (spec 12 §12.1) — the HDFC loan-preclosure confirmation.
 *
 * The figures, the loan account, the card digits and the addressee are all invented; only
 * the shapes are the spec's (`2,53471.86` keeps the broken Indian grouping this template
 * has been seen to emit).
 *
 * Two behaviours are load-bearing here and each has its own case: the amount comes from
 * the mail even when the mail also quotes an outstanding figure, and a mail that states
 * no amount produces no due at all rather than a zero one.
 */
class HDFCPreclosureDueParserTest {

    private val parser = HDFCPreclosureDueParser()

    private val sender = "information@hdfcbank.bank.in"
    private val subject = "Preclosure request related to your HDFC Bank Credit Card."

    /** Epoch millis for a wall-clock time in the zone the bank writes in. */
    private fun receivedOn(date: LocalDate, hour: Int, minute: Int): Long =
        ZonedDateTime.of(date.atTime(hour, minute), EmailParserTestUtils.FIXED_ZONE)
            .toInstant()
            .toEpochMilli()

    @TestFactory
    fun `preclosure confirmations become a one-off due payable the next day`(): List<DynamicTest> {
        val cases = listOf(
            DueCase(
                name = "Confirmation states the preclosed amount and the loan account",
                from = sender,
                subject = subject,
                body = """
                    Dear ACCOUNT HOLDER,

                    With reference to your request, we have preclosed your loan A/C 9911002233 booked on your HDFC Bank Credit Card ending 4321.

                    Preclosed amount : Rs. 2,53,471.86

                    Kindly make the payment on the next day to avoid further interest.

                    Warm regards,
                    HDFC Bank
                """.trimIndent(),
                expected = listOf(
                    ExpectedDue(
                        issuer = "HDFC Bank",
                        kind = DueKind.LOAN_PRECLOSURE,
                        amount = BigDecimal("253471.86"),
                        currency = "INR",
                        // Received 17 Sep 2026 → "the next day".
                        dueDate = LocalDate.of(2026, 9, 18),
                        reference = "9911002233",
                        autoPay = false,
                        source = "HDFC"
                    )
                )
            ),
            DueCase(
                name = "The stated figure wins over an outstanding quoted in the same mail",
                from = sender,
                subject = subject,
                body = "we have preclosed your loan A/C no. 9911002233. Total outstanding as per " +
                    "our records was Rs. 2,55,000.00. Preclosure amount of Rs. 2,53,471.86 is " +
                    "payable. Kindly make the payment on the next day.",
                expected = listOf(
                    ExpectedDue(
                        kind = DueKind.LOAN_PRECLOSURE,
                        amount = BigDecimal("253471.86"),
                        reference = "9911002233",
                        source = "HDFC"
                    )
                )
            ),
            DueCase(
                name = "Broken Indian grouping is repaired, not re-spaced",
                from = sender,
                subject = subject,
                body = "we have preclosed your loan A/C 9911002233. Preclosed amount : Rs. 2,53471.86 " +
                    "Kindly make the payment on the next day.",
                expected = listOf(
                    ExpectedDue(
                        amount = BigDecimal("253471.86"),
                        dueDate = LocalDate.of(2026, 9, 18),
                        reference = "9911002233"
                    )
                )
            ),
            DueCase(
                name = "A mail received late at night is due the next IST day",
                from = sender,
                subject = subject,
                body = "we have preclosed your loan A/C 9911002233. Preclosed amount : Rs. 2,53,471.86 " +
                    "Kindly make the payment on the next day.",
                expected = listOf(
                    ExpectedDue(
                        amount = BigDecimal("253471.86"),
                        dueDate = LocalDate.of(2026, 9, 17)
                    )
                ),
                receivedAt = receivedOn(LocalDate.of(2026, 9, 16), 23, 50)
            ),
            DueCase(
                name = "Amount phrased as preclosed for a sum",
                from = sender,
                subject = subject,
                body = "we have preclosed your loan A/C 9911002233 for Rs. 41,234.00. Kindly make " +
                    "the payment on the next day.",
                expected = listOf(
                    ExpectedDue(
                        amount = BigDecimal("41234.00"),
                        reference = "9911002233",
                        kind = DueKind.LOAN_PRECLOSURE
                    )
                )
            ),
            DueCase(
                name = "Amount phrased as payable towards preclosure",
                from = sender,
                subject = subject,
                body = "Kindly pay Rs. 41,234.00 towards preclosure of your loan A/C 9911002233 " +
                    "on the next day.",
                expected = listOf(
                    ExpectedDue(
                        amount = BigDecimal("41234.00"),
                        reference = "9911002233"
                    )
                )
            ),
            DueCase(
                name = "HTML-derived body with pipe-joined cells",
                from = sender,
                subject = subject,
                body = "Loan A/C | 9911002233 | Preclosed amount | Rs. 2,53,471.86 | " +
                    "Kindly make the payment on the next day.",
                expected = listOf(
                    ExpectedDue(
                        amount = BigDecimal("253471.86"),
                        reference = "9911002233",
                        source = "HDFC"
                    )
                )
            ),
            DueCase(
                name = "Masked loan account is kept as the reference",
                from = sender,
                subject = subject,
                body = "we have preclosed your loan A/C no. XXXXXX2233. Preclosed amount : " +
                    "INR 41,234.00. Kindly make the payment on the next day.",
                expected = listOf(
                    ExpectedDue(
                        amount = BigDecimal("41234.00"),
                        reference = "XXXXXX2233"
                    )
                )
            )
        )

        return EmailParserTestUtils.runDueSuite(parser, cases)
    }

    @TestFactory
    fun `a mail without a clear amount yields no due at all`(): List<DynamicTest> {
        val cases = listOf(
            DueCase(
                name = "Preclosure confirmed but no figure stated",
                from = sender,
                subject = subject,
                body = "we have preclosed your loan A/C 9911002233. Our representative will share " +
                    "the preclosure figure with you shortly."
            ),
            DueCase(
                name = "Two different preclosure figures are ambiguous, so neither is used",
                from = sender,
                subject = subject,
                body = "we have preclosed your loan A/C 9911002233. Preclosed amount : Rs. 41,234.00 " +
                    "Preclosure amount : Rs. 47,890.00 Kindly make the payment on the next day."
            ),
            DueCase(
                name = "A bare number with no currency token is not read as money",
                from = sender,
                subject = subject,
                // Deliberate: the body around a preclosure line is full of digits (the loan
                // account, dates), so the amount is only trusted when the bank marked it as
                // money. Missing the due is recoverable; showing the wrong figure is not.
                body = "we have preclosed your loan A/C 9911002233. Preclosed amount : 2,53,471.86 " +
                    "Kindly make the payment on the next day."
            ),
            DueCase(
                name = "An ordinary HDFC transaction alert is not a preclosure",
                from = "alerts@hdfcbank.bank.in",
                subject = "You have done a UPI txn. Check details!",
                body = "Rs.21345.60 is debited from your account ending 4321 towards VPA " +
                    "someone@okbank (ACCOUNT HOLDER) on 16-09-26. UPI transaction reference " +
                    "no.: 612345678901."
            ),
            DueCase(
                name = "The same wording from a lender the parser does not serve",
                from = "statement@getonecard.app",
                subject = "Your OneCard EMI has been foreclosed",
                body = "we have preclosed your loan A/C 9911002233. Preclosed amount : Rs. 41,234.00 " +
                    "Kindly make the payment on the next day."
            )
        )

        return EmailParserTestUtils.runDueSuite(parser, cases)
    }

    @TestFactory
    fun `handles HDFC bank mail only`(): List<DynamicTest> = EmailParserTestUtils.runDueSuite(
        parser,
        cases = emptyList(),
        handleCases = listOf(
            "information@hdfcbank.bank.in" to true,
            """ "HDFC Bank" <information@hdfcbank.bank.in>""".trim() to true,
            // Spec 12 names information@, but preclosure wording from any HDFC address is
            // the same mail; the body trigger is what discriminates.
            "alerts@hdfcbank.bank.in" to true,
            "information@mailers.hdfcbank.bank.in" to false,
            "statement@getonecard.app" to false,
            "alerts@axis.bank.in" to false,
            "" to false
        )
    )
}
