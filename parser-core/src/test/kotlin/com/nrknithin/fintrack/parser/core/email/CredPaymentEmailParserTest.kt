package com.nrknithin.fintrack.parser.core.email

import com.nrknithin.fintrack.parser.core.ParsedTransaction
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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

/**
 * Template C2 (spec 12 §12.6): CRED "payment successful" mails.
 *
 * Fixtures keep the lower-case prose CRED actually sends and the amounts the spec samples
 * state; card numbers and the UTR are invented runs (spec 01, constraint C3).
 */
class CredPaymentEmailParserTest {

    private val parser = CredPaymentEmailParser()

    @TestFactory
    fun `cred payment mails become transfers into the card`(): List<DynamicTest> {
        val cases = listOf(
            EmailCase(
                name = "Axis card bill paid: amount, card and UTR",
                from = SENDER,
                subject = "your credit card bill payment was successful",
                body = AXIS_PAYMENT,
                expected = ExpectedTransaction(
                    // The mail also quotes the statement bill amount; the payment is the
                    // figure under "amount paid", never that one.
                    amount = BigDecimal("51204.00"),
                    currency = "INR",
                    type = TransactionType.TRANSFER,
                    merchant = "Axis Bank Credit Card",
                    reference = "525312345678",
                    accountLast4 = "1234",
                    isFromCard = true,
                    toAccount = "Axis Bank Credit Card 1234"
                )
            ),
            EmailCase(
                name = "HTML-derived body with cell separators and no UTR",
                from = SENDER,
                subject = "your credit card bill payment was successful",
                body = ICICI_PAYMENT_HTML,
                expected = ExpectedTransaction(
                    amount = BigDecimal("3918.60"),
                    currency = "INR",
                    type = TransactionType.TRANSFER,
                    merchant = "ICICI Bank Credit Card",
                    accountLast4 = "5678",
                    isFromCard = true,
                    toAccount = "ICICI Bank Credit Card 5678"
                )
            ),
            EmailCase(
                name = "OneCard payment with a masked card run",
                from = SENDER,
                subject = "your credit card bill payment was successful",
                body = ONECARD_PAYMENT,
                expected = ExpectedTransaction(
                    amount = BigDecimal("1806.45"),
                    currency = "INR",
                    type = TransactionType.TRANSFER,
                    // "OneCard" already names the card, so it is not doubled into
                    // "OneCard Credit Card".
                    merchant = "OneCard",
                    reference = "526309876543",
                    accountLast4 = "8765",
                    isFromCard = true,
                    toAccount = "OneCard 8765"
                )
            ),
            EmailCase(
                name = "Rejects the new-bill notice from the same sender",
                from = SENDER,
                subject = "Important : New bill for your Axis Bank card XXXX-1234",
                body = NEW_BILL,
                shouldParse = false
            ),
            EmailCase(
                name = "Rejects a payment block with no amount",
                from = SENDER,
                subject = "your credit card bill payment was successful",
                body = AXIS_PAYMENT_NO_AMOUNT,
                shouldParse = false
            ),
            EmailCase(
                name = "Rejects an issuer outside the known set",
                from = SENDER,
                subject = "your credit card bill payment was successful",
                body = UNKNOWN_ISSUER_PAYMENT,
                shouldParse = false
            ),
            EmailCase(
                name = "Rejects a CRED mail that describes no payment",
                from = SENDER,
                subject = "your credit score has been updated",
                body = "your credit score is now 812. check what changed on the cred app.",
                shouldParse = false
            )
        )

        val handleCases = listOf(
            SENDER to true,
            "CRED Protect <protect@cred.club>" to true,
            "talk@cred.club" to false,
            "no-reply@amazonpay.in" to false
        )

        return EmailParserTestUtils.runTransactionSuite(parser, cases, handleCases)
    }

    @Test
    fun `the payment date in the body wins over the received date`() {
        val parsed = parseOne(AXIS_PAYMENT)
        val expected = LocalDate.of(2026, 9, 4)
            .atStartOfDay(ZoneId.of("Asia/Kolkata"))
            .toInstant()
            .toEpochMilli()

        assertEquals(expected, parsed.timestamp, "timestamp must come from the stated payment date")
        assertTrue(
            parsed.timestamp != EmailParserTestUtils.FIXED_RECEIVED_AT,
            "timestamp must not fall back to the delivery time"
        )
    }

    @Test
    fun `the card is the destination and the paying account stays unknown`() {
        val parsed = parseOne(AXIS_PAYMENT)

        assertEquals("Axis Bank", parsed.bankName, "bankName")
        assertEquals("Axis Bank Credit Card 1234", parsed.toAccount, "toAccount")
        assertNull(parsed.fromAccount, "CRED never names the account the money came from")
        assertNull(parsed.balance, "no balance is stated")
        assertNull(parsed.creditLimit, "no credit limit is stated")
    }

    @Test
    fun `a payment with no UTR still parses with no reference`() {
        val parsed = parseOne(ICICI_PAYMENT_HTML)
        assertNull(parsed.reference, "reference stays unknown rather than invented")
    }

    @Test
    fun `an issuer already named Card is not doubled into Credit Card`() {
        val parsed = parseOne(SBI_PAYMENT)

        assertEquals("SBI Card", parsed.merchant, "merchant")
        assertEquals("State Bank of India", parsed.bankName, "bankName uses the registry spelling")
    }

    @Test
    fun `the new-bill notice is reported as a non-transaction email`() {
        val billMail = EmailParserTestUtils.message(
            from = SENDER,
            subject = "Important : New bill for your Axis Bank card XXXX-1234",
            body = NEW_BILL
        )
        val paymentMail = EmailParserTestUtils.message(
            from = SENDER,
            subject = "your credit card bill payment was successful",
            body = AXIS_PAYMENT
        )

        assertTrue(parser.isNonTransactionEmail(billMail), "the bill notice is a due, not a transaction")
        assertFalse(parser.isNonTransactionEmail(paymentMail), "the payment mail is a transaction")
    }

    private fun parseOne(body: String): ParsedTransaction {
        val parsed = parser.parse(
            EmailParserTestUtils.message(
                from = SENDER,
                subject = "your credit card bill payment was successful",
                body = body
            )
        )
        assertNotNull(parsed, "Expected a transaction from: $body")
        return parsed!!
    }

    private companion object {
        const val SENDER = "protect@cred.club"

        /** The bullet CRED puts between the issuer and the card, and the rupee sign. */
        const val BULLET = "•"
        const val RUPEE = "₹"

        /** text/plain, kept as CRED writes it: lower-case prose, one field per line. */
        val AXIS_PAYMENT = """
            your credit card bill payment was successful
            axis bank $BULLET$BULLET 1234
            payment details
            amount paid
            ${RUPEE}51,204.00
            payment date
            sep 04, 2026
            your latest statement had a bill amount of ${RUPEE}12,480.55
            UTR No.: 525312345678
        """.trimIndent()

        /** HTML-derived: table cells joined with " | ", whitespace collapsed (spec 11 §11.5). */
        val ICICI_PAYMENT_HTML =
            "icici bank $BULLET$BULLET 5678 | payment details | amount paid | $RUPEE 3,918.60 | " +
                "payment date | September 04, 2026 | your payment was settled in 3 seconds"

        val ONECARD_PAYMENT = """
            onecard $BULLET xxxx-8765
            payment details
            amount paid ${RUPEE}1,806.45
            payment date sep 15, 2026
            UTR No.: 526309876543
        """.trimIndent()

        val SBI_PAYMENT = """
            sbi card $BULLET$BULLET 2468
            payment details
            amount paid ${RUPEE}9,148.00
            payment date sep 20, 2026
            UTR No.: 526412345678
        """.trimIndent()

        val AXIS_PAYMENT_NO_AMOUNT = """
            your credit card bill payment was successful
            axis bank $BULLET$BULLET 1234
            payment details
            payment date sep 04, 2026
            UTR No.: 525312345678
        """.trimIndent()

        val UNKNOWN_ISSUER_PAYMENT = """
            kotak bank $BULLET$BULLET 1357
            payment details
            amount paid ${RUPEE}4,120.00
            payment date sep 04, 2026
            UTR No.: 525312345678
        """.trimIndent()

        val NEW_BILL = """
            axis bank $BULLET$BULLET 1234
            bill summary
            total amount due ${RUPEE}12,480.55
            minimum due ${RUPEE}883.00
            due date october 2, 2026
            bill generated on sep 07, 2026
        """.trimIndent()
    }
}
