package com.pennywiseai.parser.core.email.due

import com.pennywiseai.parser.core.test.EmailParserTestUtils
import com.pennywiseai.parser.core.test.EmailParserTestUtils.DueCase
import com.pennywiseai.parser.core.test.EmailParserTestUtils.ExpectedDue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Spec 12 §12.7 CF1 — Cloudflare "Your invoice is available".
 *
 * Bodies are shaped the way `EmailBodyExtractor` leaves Cloudflare's HTML (spec 11 §11.5).
 * Invoice numbers and amounts are invented; the `$0.00` case is the one the corpus
 * actually shows (spec 10 §10.4), and it is the reason the rule exists.
 */
class CloudflareInvoiceDueParserTest {

    private val parser = CloudflareInvoiceDueParser()

    @TestFactory
    fun `cloudflare invoice becomes a due only when something is owed`(): List<DynamicTest> {
        val cases = listOf(
            DueCase(
                name = "Invoice with an amount becomes a due",
                from = SENDER,
                subject = SUBJECT,
                body = """
                    Cloudflare

                    Your invoice is available.

                    Invoice IN-1234567: $8.50 due October 1, 2026

                    View this invoice in the Cloudflare dashboard.
                """.trimIndent(),
                expected = listOf(
                    ExpectedDue(
                        issuer = "Cloudflare",
                        kind = DueKind.INVOICE,
                        amount = BigDecimal("8.50"),
                        currency = "USD",
                        dueDate = LocalDate.of(2026, 10, 1),
                        reference = "IN-1234567",
                        autoPay = false,
                        source = "CLOUDFLARE"
                    )
                )
            ),
            DueCase(
                name = "Zero invoice yields no due at all",
                from = SENDER,
                subject = SUBJECT,
                body = """
                    Cloudflare

                    Your invoice is available.

                    Invoice IN-1234568: $0.00 due October 1, 2026
                """.trimIndent(),
                expected = emptyList()
            ),
            DueCase(
                name = "Zero and non-zero invoices in one mail yield only the non-zero one",
                from = SENDER,
                subject = SUBJECT,
                body = """
                    Cloudflare

                    Your invoice is available.

                    Invoice IN-1234568: $0.00 due October 1, 2026
                    Invoice IN-1234569: $12.00 due October 1, 2026
                """.trimIndent(),
                expected = listOf(
                    ExpectedDue(
                        amount = BigDecimal("12.00"),
                        currency = "USD",
                        dueDate = LocalDate.of(2026, 10, 1),
                        reference = "IN-1234569"
                    )
                )
            ),
            DueCase(
                name = "The is-due wording and a comma-grouped amount read the same",
                from = SENDER,
                subject = SUBJECT,
                body = """
                    Invoice IN-1234570: $1,200.00 is due November 1, 2026
                """.trimIndent(),
                expected = listOf(
                    ExpectedDue(
                        amount = BigDecimal("1200.00"),
                        currency = "USD",
                        dueDate = LocalDate.of(2026, 11, 1),
                        reference = "IN-1234570"
                    )
                )
            ),
            DueCase(
                name = "The same invoice repeated in the mail is one due",
                from = SENDER,
                subject = SUBJECT,
                body = """
                    Invoice IN-1234567: $8.50 due October 1, 2026
                    Summary | Invoice IN-1234567: $8.50 due October 1, 2026
                """.trimIndent(),
                expected = listOf(
                    ExpectedDue(
                        amount = BigDecimal("8.50"),
                        dueDate = LocalDate.of(2026, 10, 1),
                        reference = "IN-1234567"
                    )
                )
            ),
            DueCase(
                name = "Invoice whose due date cannot be read is dropped, never guessed",
                from = SENDER,
                subject = SUBJECT,
                body = """
                    Invoice IN-1234571: $8.50 due Octobre 1, 2026
                """.trimIndent(),
                expected = emptyList()
            ),
            DueCase(
                name = "Account notice carrying no invoice line is not a due",
                from = SENDER,
                subject = "Your Cloudflare account was updated",
                body = """
                    Cloudflare

                    A change was made to your account settings.
                """.trimIndent(),
                expected = emptyList()
            )
        )

        val handleCases = listOf(
            SENDER to true,
            "\"Cloudflare\" <noreply@notify.cloudflare.com>" to true,
            "billing@cloudflare.com" to true,
            "noreply@pgsharp.com" to false,
            "renewals@godaddy.com" to false
        )

        return EmailParserTestUtils.runDueSuite(parser, cases, handleCases)
    }

    private companion object {
        const val SENDER = "noreply@notify.cloudflare.com"
        const val SUBJECT = "Your invoice is available"
    }
}
