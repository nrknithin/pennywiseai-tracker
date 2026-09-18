package com.nrknithin.fintrack.parser.core.email.due

import com.nrknithin.fintrack.parser.core.test.EmailParserTestUtils
import com.nrknithin.fintrack.parser.core.test.EmailParserTestUtils.DueCase
import com.nrknithin.fintrack.parser.core.test.EmailParserTestUtils.ExpectedDue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Spec 12 §12.7 P1 — PGSharp "Customer Invoice".
 *
 * The 21st comes from the corpus (spec 10 §10.4, spec 20 §20.8); the amount, the invoice
 * numbers and the card's last four digits are invented, as constraint C3 requires.
 */
class PGSharpInvoiceDueParserTest {

    private val parser = PGSharpInvoiceDueParser()

    @TestFactory
    fun `pgsharp customer invoice becomes a dollar due`(): List<DynamicTest> {
        val cases = listOf(
            DueCase(
                name = "Monthly invoice with an auto-pay card on file",
                from = SENDER,
                subject = SUBJECT,
                body = """
                    PGSharp

                    Customer Invoice

                    Invoice #: 1042
                    Issued: 14/09/2026
                    Due date: 21/09/2026

                    Description | Amount
                    PGSharp Standard Edition (21/09/2026 - 21/10/2026) | $7.25 USD

                    Amount due: $7.25 USD

                    This invoice will be charged automatically to Visa-4242 on file.
                """.trimIndent(),
                expected = listOf(
                    ExpectedDue(
                        issuer = "PGSharp",
                        kind = DueKind.INVOICE,
                        amount = BigDecimal("7.25"),
                        currency = "USD",
                        dueDate = LocalDate.of(2026, 9, 21),
                        reference = "1042",
                        autoPay = true,
                        autoPayInstrument = "Visa-4242",
                        source = "PGSHARP"
                    )
                )
            ),
            DueCase(
                name = "Month-name due date, prefixed invoice number, spelled-out card",
                from = SENDER,
                subject = SUBJECT,
                body = """
                    PGSharp

                    Invoice no. IN-2044
                    Payment due by September 21, 2026
                    Balance due: $7.25 USD

                    Your Visa ending in 4242 will be charged automatically on the due date.
                """.trimIndent(),
                expected = listOf(
                    ExpectedDue(
                        amount = BigDecimal("7.25"),
                        currency = "USD",
                        dueDate = LocalDate.of(2026, 9, 21),
                        reference = "IN-2044",
                        autoPay = true,
                        autoPayInstrument = "Visa-4242"
                    )
                )
            ),
            DueCase(
                name = "Invoice that names no card is still a due, just not an auto-pay one",
                from = SENDER,
                subject = SUBJECT,
                body = """
                    PGSharp

                    Invoice #: 1043
                    Due date: 21/09/2026
                    Amount due: $7.25 USD

                    Pay this invoice from your account page.
                """.trimIndent(),
                expected = listOf(
                    ExpectedDue(
                        amount = BigDecimal("7.25"),
                        currency = "USD",
                        dueDate = LocalDate.of(2026, 9, 21),
                        reference = "1043",
                        autoPay = false
                    )
                )
            ),
            DueCase(
                name = "Invoice with no due date yields nothing rather than a guessed date",
                from = SENDER,
                subject = SUBJECT,
                body = """
                    PGSharp

                    Invoice #: 1044
                    Issued: 14/09/2026
                    Amount due: $7.25 USD
                """.trimIndent(),
                expected = emptyList()
            ),
            DueCase(
                name = "Invoice with no amount yields nothing",
                from = SENDER,
                subject = SUBJECT,
                body = """
                    PGSharp

                    Invoice #: 1045
                    Due date: 21/09/2026

                    Your invoice is ready in your account.
                """.trimIndent(),
                expected = emptyList()
            ),
            DueCase(
                name = "Invoice totalling zero is not money owed",
                from = SENDER,
                subject = SUBJECT,
                body = """
                    PGSharp

                    Invoice #: 1046
                    Due date: 21/09/2026
                    Amount due: $0.00 USD
                """.trimIndent(),
                expected = emptyList()
            ),
            DueCase(
                name = "Payment confirmation for the same invoice is not a due",
                from = SENDER,
                subject = "Invoice Payment Confirmation",
                body = """
                    PGSharp

                    This is a payment receipt for Invoice 1042.
                    Due date: 21/09/2026

                    PGSharp Standard Edition (21/09/2026 - 21/10/2026) | $7.25 USD
                    Total paid: $7.25 USD
                """.trimIndent(),
                expected = emptyList()
            ),
            DueCase(
                name = "Mail that is not an invoice at all is ignored",
                from = SENDER,
                subject = "Welcome to PGSharp",
                body = """
                    PGSharp

                    Your account is ready. Standard Edition costs $7.25 USD a month,
                    charged on the 21st.
                """.trimIndent(),
                expected = emptyList()
            )
        )

        val handleCases = listOf(
            SENDER to true,
            "\"PGSharp\" <noreply@pgsharp.com>" to true,
            "billing@mail.pgsharp.com" to true,
            "renewals@godaddy.com" to false,
            "noreply@notify.cloudflare.com" to false
        )

        return EmailParserTestUtils.runDueSuite(parser, cases, handleCases)
    }

    private companion object {
        const val SENDER = "noreply@pgsharp.com"
        const val SUBJECT = "Customer Invoice"
    }
}
