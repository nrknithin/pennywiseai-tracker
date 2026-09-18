package com.nrknithin.fintrack.parser.core.email

import com.nrknithin.fintrack.parser.core.TransactionType
import com.nrknithin.fintrack.parser.core.test.EmailParserTestUtils
import com.nrknithin.fintrack.parser.core.test.EmailParserTestUtils.EmailCase
import com.nrknithin.fintrack.parser.core.test.ExpectedTransaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Spec 12 §12.7 P2 — PGSharp payment confirmation.
 *
 * The 21 Sep period comes from the corpus (spec 10 §10.4); the amount, the invoice
 * numbers and the card's last four digits are invented, as constraint C3 requires.
 */
class PGSharpPaymentEmailParserTest {

    private val parser = PGSharpPaymentEmailParser()

    @TestFactory
    fun `pgsharp payment confirmation becomes a dollar transaction`(): List<DynamicTest> {
        val cases = listOf(
            EmailCase(
                name = "Receipt for the monthly invoice",
                from = SENDER,
                subject = "Invoice Payment Confirmation",
                body = RECEIPT_BODY,
                expected = ExpectedTransaction(
                    amount = BigDecimal("7.25"),
                    currency = "USD",
                    type = TransactionType.CREDIT,
                    merchant = "PGSharp",
                    reference = "1042",
                    accountLast4 = "4242",
                    isFromCard = true
                )
            ),
            EmailCase(
                name = "Service-activated wording, prefixed invoice number, spelled-out card",
                from = SENDER,
                subject = "Service activated - PGSharp Standard Edition",
                body = """
                    PGSharp

                    Payment date: 21/09/2026
                    Payment of $7.25 USD received for Invoice IN-2044.

                    Charged to Mastercard XX4242.
                """.trimIndent(),
                expected = ExpectedTransaction(
                    amount = BigDecimal("7.25"),
                    currency = "USD",
                    type = TransactionType.CREDIT,
                    merchant = "PGSharp",
                    reference = "IN-2044",
                    accountLast4 = "4242",
                    isFromCard = true
                )
            ),
            EmailCase(
                name = "Receipt that names no card still reports the payment",
                from = SENDER,
                subject = "Invoice Payment Confirmation",
                body = """
                    PGSharp

                    Thanks for your payment.
                    This is a payment receipt for Invoice 1043.

                    Total paid: $7.25 USD
                """.trimIndent(),
                expected = ExpectedTransaction(
                    amount = BigDecimal("7.25"),
                    currency = "USD",
                    type = TransactionType.CREDIT,
                    merchant = "PGSharp",
                    reference = "1043"
                )
            ),
            EmailCase(
                name = "Customer Invoice is an expectation, not a payment",
                from = SENDER,
                subject = "Customer Invoice",
                body = INVOICE_BODY,
                shouldParse = false
            ),
            EmailCase(
                name = "Receipt with no amount is not turned into the usual price",
                from = SENDER,
                subject = "Invoice Payment Confirmation",
                body = """
                    PGSharp

                    Thanks for your payment. Invoice 1042 is settled and your service
                    continues without interruption.
                """.trimIndent(),
                shouldParse = false
            ),
            EmailCase(
                name = "Marketing mail quoting the price is not a payment",
                from = SENDER,
                subject = "Upgrade to Standard Edition",
                body = """
                    PGSharp

                    Standard Edition is $7.25 USD a month. Upgrade from your account page.
                """.trimIndent(),
                shouldParse = false
            )
        )

        val handleCases = listOf(
            SENDER to true,
            "\"PGSharp\" <noreply@pgsharp.com>" to true,
            "billing@mail.pgsharp.com" to true,
            "renewals@godaddy.com" to false,
            "alerts@hdfcbank.bank.in" to false
        )

        return EmailParserTestUtils.runTransactionSuite(parser, cases, handleCases)
    }

    @Test
    fun `payment date in the body wins over the received date`() {
        val parsed = parser.parse(
            EmailParserTestUtils.message(
                from = SENDER,
                subject = "Invoice Payment Confirmation",
                body = """
                    PGSharp

                    This is a payment receipt for Invoice 1042.
                    Payment date: 21/09/2026

                    Total paid: $7.25 USD
                """.trimIndent()
            )
        )

        assertNotNull(parsed)
        val expected = ZonedDateTime.of(
            LocalDate.of(2026, 9, 21).atStartOfDay(),
            ZoneId.of("Asia/Kolkata")
        ).toInstant().toEpochMilli()
        assertEquals(expected, parsed!!.timestamp)
    }

    @Test
    fun `service period in brackets is never read as the payment date`() {
        val parsed = parser.parse(
            EmailParserTestUtils.message(
                from = SENDER,
                subject = "Invoice Payment Confirmation",
                body = RECEIPT_BODY
            )
        )

        assertNotNull(parsed)
        assertEquals(EmailParserTestUtils.FIXED_RECEIVED_AT, parsed!!.timestamp)
    }

    @Test
    fun `the invoice mail is reported as a non-transaction email`() {
        val invoice = EmailParserTestUtils.message(
            from = SENDER,
            subject = "Customer Invoice",
            body = INVOICE_BODY
        )
        val receipt = EmailParserTestUtils.message(
            from = SENDER,
            subject = "Invoice Payment Confirmation",
            body = RECEIPT_BODY
        )

        assertTrue(parser.isNonTransactionEmail(invoice))
        assertFalse(parser.isNonTransactionEmail(receipt))
    }

    private companion object {
        const val SENDER = "noreply@pgsharp.com"

        val RECEIPT_BODY = """
            PGSharp

            Thanks for your payment.

            This is a payment receipt for Invoice 1042.

            PGSharp Standard Edition (21/09/2026 - 21/10/2026) | $7.25 USD

            Total paid: $7.25 USD
            Charged to Visa-4242
        """.trimIndent()

        val INVOICE_BODY = """
            PGSharp

            Customer Invoice

            Invoice #: 1042
            Due date: 21/09/2026

            Amount due: $7.25 USD

            This invoice will be charged automatically to Visa-4242 on file.
        """.trimIndent()
    }
}
