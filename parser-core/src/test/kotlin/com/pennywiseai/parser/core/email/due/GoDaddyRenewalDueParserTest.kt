package com.pennywiseai.parser.core.email.due

import com.pennywiseai.parser.core.test.EmailParserTestUtils
import com.pennywiseai.parser.core.test.EmailParserTestUtils.DueCase
import com.pennywiseai.parser.core.test.EmailParserTestUtils.ExpectedDue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Spec 12 §12.7 D1 — GoDaddy "Your domain expires soon.".
 *
 * Bodies are shaped the way `EmailBodyExtractor` leaves an HTML table (spec 11 §11.5):
 * one line per `<tr>`, cells joined with ` | `, whitespace collapsed inside a line.
 * Domains and prices are invented; the dates are the ones spec 01 §1.4
 * states for this notice, because those carry the parsing logic.
 */
class GoDaddyRenewalDueParserTest {

    private val parser = GoDaddyRenewalDueParser()

    @TestFactory
    fun `godaddy renewal notice yields one due per domain row`(): List<DynamicTest> {
        val cases = listOf(
            DueCase(
                name = "Two domain rows become two dues with their own date and price",
                from = SENDER,
                subject = SUBJECT,
                body = """
                    GoDaddy | Your domain expires soon.
                    Renew now to keep your domains working.
                    Domain | Expires | Price | Term
                    example-domain-one.com | Cancels 28-09-2026 | ₹1,275.00 | 1 year
                    example-domain-two.in | Cancels 29-09-2026 | ₹2,150.00 | 2 years
                    Renew all | ₹3,425.00
                    Manage your domains at godaddy.com/help
                """.trimIndent(),
                expected = listOf(
                    ExpectedDue(
                        issuer = "GoDaddy",
                        kind = DueKind.DOMAIN_RENEWAL,
                        amount = BigDecimal("1275.00"),
                        currency = "INR",
                        dueDate = LocalDate.of(2026, 9, 28),
                        reference = "example-domain-one.com",
                        autoPay = false,
                        source = "GODADDY"
                    ),
                    ExpectedDue(
                        issuer = "GoDaddy",
                        kind = DueKind.DOMAIN_RENEWAL,
                        amount = BigDecimal("2150.00"),
                        currency = "INR",
                        dueDate = LocalDate.of(2026, 9, 29),
                        reference = "example-domain-two.in",
                        autoPay = false,
                        source = "GODADDY"
                    )
                )
            ),
            DueCase(
                name = "Price cell in dollars keeps its own currency",
                from = SENDER,
                subject = SUBJECT,
                body = """
                    Domain | Expires | Price | Term
                    example-domain-three.org | Cancels 30-09-2026 | $19.99 | 1 year
                """.trimIndent(),
                expected = listOf(
                    ExpectedDue(
                        amount = BigDecimal("19.99"),
                        currency = "USD",
                        dueDate = LocalDate.of(2026, 9, 30),
                        reference = "example-domain-three.org"
                    )
                )
            ),
            DueCase(
                name = "Row with no price still yields the due, with no amount",
                from = SENDER,
                subject = SUBJECT,
                body = """
                    Domain | Expires | Term
                    example-domain-four.net | Cancels 01-10-2026 | 1 year
                """.trimIndent(),
                expected = listOf(
                    ExpectedDue(
                        kind = DueKind.DOMAIN_RENEWAL,
                        dueDate = LocalDate.of(2026, 10, 1),
                        reference = "example-domain-four.net",
                        expectNullAmount = true
                    )
                )
            ),
            DueCase(
                name = "Row whose cancel date cannot be read is dropped, never guessed",
                from = SENDER,
                subject = SUBJECT,
                body = """
                    Domain | Expires | Price | Term
                    example-domain-five.com | Cancels 31-31-2026 | ₹999.00 | 1 year
                """.trimIndent(),
                expected = emptyList()
            ),
            DueCase(
                name = "Unreadable row does not cost the readable rows around it",
                from = SENDER,
                subject = SUBJECT,
                body = """
                    Domain | Expires | Price | Term
                    example-domain-five.com | Cancels 31-31-2026 | ₹999.00 | 1 year
                    example-domain-one.com | Cancels 28-09-2026 | ₹1,275.00 | 1 year
                """.trimIndent(),
                expected = listOf(
                    ExpectedDue(
                        amount = BigDecimal("1275.00"),
                        dueDate = LocalDate.of(2026, 9, 28),
                        reference = "example-domain-one.com"
                    )
                )
            ),
            DueCase(
                name = "Same domain listed twice in one notice is one due",
                from = SENDER,
                subject = SUBJECT,
                body = """
                    example-domain-one.com | Cancels 28-09-2026 | ₹1,275.00 | 1 year
                    Domain | Expires | Price | Term
                    example-domain-one.com | Cancels 28-09-2026 | ₹1,275.00 | 1 year
                """.trimIndent(),
                expected = listOf(
                    ExpectedDue(
                        amount = BigDecimal("1275.00"),
                        dueDate = LocalDate.of(2026, 9, 28),
                        reference = "example-domain-one.com"
                    )
                )
            ),
            DueCase(
                name = "Cart reminder from the marketing address is rejected",
                from = "renewals@e.godaddy.com",
                subject = "Your cart expires soon - complete your purchase",
                body = """
                    Items in your cart | Price
                    example-domain-six.com | ₹1,275.00 | 1 year
                    Cancels 28-09-2026 if you do not check out.
                """.trimIndent(),
                expected = emptyList()
            ),
            DueCase(
                name = "Cart wording is rejected even from the renewals address",
                from = SENDER,
                subject = "Your cart expires soon",
                body = """
                    example-domain-one.com | Cancels 28-09-2026 | ₹1,275.00 | 1 year
                """.trimIndent(),
                expected = emptyList()
            ),
            DueCase(
                name = "Mail that announces no expiry is not a renewal notice",
                from = SENDER,
                subject = "Your GoDaddy receipt",
                body = """
                    example-domain-one.com | Cancels 28-09-2026 | ₹1,275.00 | 1 year
                """.trimIndent(),
                expected = emptyList()
            )
        )

        val handleCases = listOf(
            SENDER to true,
            "\"GoDaddy Renewals\" <renewals@godaddy.com>" to true,
            "renewals@e.godaddy.com" to true,
            "noreply@pgsharp.com" to false,
            "alerts@hdfcbank.bank.in" to false
        )

        return EmailParserTestUtils.runDueSuite(parser, cases, handleCases)
    }

    @Test
    fun `term cell is kept as the period label of its own row`() {
        val dues = parser.parse(
            EmailParserTestUtils.message(
                from = SENDER,
                subject = SUBJECT,
                body = """
                    example-domain-one.com | Cancels 28-09-2026 | ₹1,275.00 | 1 year
                    example-domain-two.in | Cancels 29-09-2026 | ₹2,150.00 | 2 years
                """.trimIndent()
            )
        )

        assertEquals(listOf("1 year", "2 years"), dues.map { it.periodLabel })
    }

    private companion object {
        const val SENDER = "renewals@godaddy.com"
        const val SUBJECT = "Your domain expires soon."
    }
}
