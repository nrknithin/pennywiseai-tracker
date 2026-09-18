package com.nrknithin.fintrack.parser.core.email.due

import com.nrknithin.fintrack.parser.core.test.EmailParserTestUtils
import com.nrknithin.fintrack.parser.core.test.EmailParserTestUtils.DueCase
import com.nrknithin.fintrack.parser.core.test.EmailParserTestUtils.ExpectedDue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Templates C2 (spec 12 §12.6) and I2 (spec 12 §12.3): the two "we received your payment"
 * mails, read as payment-confirmation due readings per spec 20 §20.4 step 7.
 *
 * The properties worth pinning are not the amounts but the *shape* of what the parser claims:
 * every reading is flagged [ParsedDue.paymentConfirmed], carries no escalation, and never
 * fires on the bill mails that share each address.
 *
 * All data is invented. Per spec 06 §6.2 the corpus's shapes are kept and its values are not:
 * card suffixes are `1234`/`5678`/`4321`, references are `123456789012`, amounts are made up.
 */
class CardPaymentConfirmationDueParserTest {

    private val parser = CardPaymentConfirmationDueParser()

    @TestFactory
    fun `payment notices become payment-confirmation readings`(): List<DynamicTest> {
        val cases = listOf(
            DueCase(
                name = "C2: CRED payment success names the issuer, the card and the amount paid",
                from = CRED_SENDER,
                subject = "your credit card bill payment was successful",
                body = CRED_AXIS_PAYMENT,
                expected = listOf(
                    ExpectedDue(
                        issuer = "Axis Bank",
                        kind = DueKind.CARD_STATEMENT,
                        amount = BigDecimal("51204.00"),
                        currency = "INR",
                        dueDate = LocalDate.of(2026, 9, 4),
                        accountLast4 = "1234",
                        source = "CRED"
                    )
                )
            ),
            DueCase(
                name = "C2: an HTML-derived body with cell separators still reads",
                from = CRED_SENDER,
                subject = "your credit card bill payment was successful",
                body = CRED_ICICI_PAYMENT_HTML,
                expected = listOf(
                    ExpectedDue(
                        issuer = "ICICI Bank",
                        amount = BigDecimal("3918.60"),
                        dueDate = LocalDate.of(2026, 9, 22),
                        accountLast4 = "5678",
                        source = "CRED"
                    )
                )
            ),
            DueCase(
                name = "C2: a masked card run and a single bullet",
                from = CRED_SENDER,
                subject = "your credit card bill payment was successful",
                body = CRED_SBI_PAYMENT_MASKED,
                expected = listOf(
                    ExpectedDue(
                        issuer = "SBI Card",
                        amount = BigDecimal("9148.00"),
                        dueDate = LocalDate.of(2026, 10, 5),
                        accountLast4 = "4321",
                        source = "CRED"
                    )
                )
            ),
            DueCase(
                name = "C2: the new-bill mail from the same address is not a payment",
                from = CRED_SENDER,
                subject = "Important : New bill for your Axis Bank card XXXX-1234",
                body = CRED_NEW_BILL,
                expected = emptyList()
            ),
            DueCase(
                name = "C2: an issuer outside the known set is not attributed to anyone",
                from = CRED_SENDER,
                subject = "your credit card bill payment was successful",
                body = CRED_UNKNOWN_ISSUER_PAYMENT,
                expected = emptyList()
            ),
            DueCase(
                name = "C2: a payment block with no date yields nothing",
                from = CRED_SENDER,
                subject = "your credit card bill payment was successful",
                body = CRED_PAYMENT_NO_DATE,
                expected = emptyList()
            ),
            DueCase(
                name = "I2: ICICI payment received names the card from the masked PAN",
                from = ICICI_SENDER,
                subject = "Payment received on your ICICI Bank Credit Card.",
                body = ICICI_PAYMENT,
                expected = listOf(
                    ExpectedDue(
                        issuer = "ICICI Bank",
                        kind = DueKind.CARD_STATEMENT,
                        amount = BigDecimal("4480.00"),
                        currency = "INR",
                        dueDate = LocalDate.of(2026, 9, 4),
                        accountLast4 = "5678",
                        source = "ICICI"
                    )
                )
            ),
            DueCase(
                name = "I2: the purchase alert from the same address is not a payment",
                from = ICICI_SENDER,
                subject = "Transaction alert for your ICICI Bank Credit Card",
                body = ICICI_PURCHASE,
                expected = emptyList()
            ),
            DueCase(
                name = "I2: the statement from the same address is not a payment",
                from = ICICI_SENDER,
                subject = "ICICI Bank Credit Card Statement for the period Aug 05, 2026 to Sep 04, 2026",
                body = ICICI_STATEMENT,
                expected = emptyList()
            )
        )

        val handleCases = listOf(
            CRED_SENDER to true,
            "CRED Protect <protect@cred.club>" to true,
            ICICI_SENDER to true,
            "ICICI Bank <credit_cards@icici.bank.in>" to true,
            "talk@cred.club" to false,
            "alerts@hdfcbank.bank.in" to false
        )

        return EmailParserTestUtils.runDueSuite(parser, cases, handleCases)
    }

    // -----------------------------------------------------------------------------------------
    // The flag itself — §20.4 step 7's whole point
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a CRED payment success is flagged as a payment confirmation`() {
        assertTrue(parseOne(CRED_SENDER, CRED_AXIS_PAYMENT).paymentConfirmed)
    }

    @Test
    fun `an ICICI payment received is flagged as a payment confirmation`() {
        assertTrue(parseOne(ICICI_SENDER, ICICI_PAYMENT).paymentConfirmed)
    }

    @Test
    fun `a payment confirmation claims no urgency`() {
        // Escalation is urgency and a paid bill has none; the flag is a separate axis
        // (spec 20 §20.4 step 7).
        assertNull(parseOne(CRED_SENDER, CRED_AXIS_PAYMENT).escalation, "escalation")
        assertNull(parseOne(ICICI_SENDER, ICICI_PAYMENT).escalation, "escalation")
    }

    @Test
    fun `the bill parsers keep producing bills, unflagged`() {
        // The two readings of one CRED address must not drift into each other: C1 is still a
        // bill, and a bill is never a confirmation.
        val bill = CredBillDueParser().parse(
            EmailParserTestUtils.message(
                from = CRED_SENDER,
                subject = "Important : New bill for your Axis Bank card XXXX-1234",
                body = CRED_NEW_BILL
            )
        ).single()
        assertFalse(bill.paymentConfirmed, "a new-bill notice is not a payment confirmation")
    }

    @Test
    fun `the payment UTR is not adopted as the due's reference`() {
        // `dues.reference` identifies the bill; a payment's UTR there would misname the due
        // and block the real identifier from ever filling the column.
        assertNull(parseOne(CRED_SENDER, CRED_AXIS_PAYMENT).reference, "reference")
    }

    @Test
    fun `the date carried is the date the payment was made`() {
        // Neither template states a due date, and `ParsedDue.dueDate` is non-null by design.
        assertEquals(LocalDate.of(2026, 9, 4), parseOne(ICICI_SENDER, ICICI_PAYMENT).dueDate)
    }

    @Test
    fun `the factory offers both the bill parser and the confirmation parser for CRED`() {
        val ids = DueParserFactory.parsersFor(CRED_SENDER).map { it.id }
        assertTrue(ids.contains("CRED_NEW_BILL"), "expected the bill parser, got $ids")
        assertTrue(ids.contains("CARD_PAYMENT_CONFIRMED"), "expected the payment parser, got $ids")
    }

    @Test
    fun `the factory offers both the statement parser and the confirmation parser for ICICI`() {
        val ids = DueParserFactory.parsersFor(ICICI_SENDER).map { it.id }
        assertTrue(ids.contains("ICICI_CARD_STATEMENT"), "expected the statement parser, got $ids")
        assertTrue(ids.contains("CARD_PAYMENT_CONFIRMED"), "expected the payment parser, got $ids")
    }

    // -----------------------------------------------------------------------------------------
    // The issuer guard — "an issuer the app can name, or nothing"
    // -----------------------------------------------------------------------------------------
    //
    // CRED is an aggregator, so the issuer token in the mail is the only thing that says which
    // bill was paid. `readCred` resolves it through a fixed map and bails when the lookup misses.
    // That elvis is unreachable today only because the template regex admits exactly the same six
    // tokens the map keys. The three factories below pin the *observable* halves of that
    // contract — every token the alternation admits resolves to a name, any other token yields
    // nothing, and case does not matter — which is what a caller can rely on.
    //
    // What they deliberately do not do is detect drift between the two sides, and an earlier
    // version of this comment claimed they did. They cannot: all three iterate hard-coded
    // literal lists, because `CRED_ISSUERS` is private and the regex is not introspectable from
    // here. Add `|Kotak Bank` to the alternation without a map key and the negative factory's
    // "kotak bank yields nothing" case still passes, because an unresolvable issuer and an
    // unmatched regex are the same silence from outside. Pinning drift would mean exposing the
    // map to the test, which is a larger change than the risk warrants — the failure mode it
    // guards is a payment attributed to nobody, and that is already the asserted behaviour.

    @TestFactory
    fun `every issuer the CRED template admits resolves to a name the app uses`(): List<DynamicTest> =
        listOf(
            "hdfc bank" to "HDFC Bank",
            "axis bank" to "Axis Bank",
            "icici bank" to "ICICI Bank",
            "sbi card" to "SBI Card",
            "onecard" to "OneCard",
            "american express" to "American Express"
        ).map { (token, issuer) ->
            DynamicTest.dynamicTest("CRED's \"$token\" is read as $issuer") {
                assertEquals(issuer, parseOne(CRED_SENDER, credPayment(token)).issuer)
            }
        }

    @TestFactory
    fun `a CRED payment naming an issuer the app cannot name yields nothing`(): List<DynamicTest> =
        // Each of these is a real-shaped C2 mail differing from a parsed one in the issuer token
        // alone: same card line, same figure, same date. Silence is the required answer — a
        // confirmation the app cannot attribute must never mark *some other* bill paid (C7).
        listOf(
            "kotak bank",
            "yes bank",
            "federal bank",
            "idfc first bank",
            "bank"
        ).map { token ->
            DynamicTest.dynamicTest("CRED's \"$token\" is not attributed to anyone") {
                val dues = parser.parse(
                    EmailParserTestUtils.message(
                        from = CRED_SENDER,
                        subject = "your credit card bill payment was successful",
                        body = credPayment(token)
                    )
                )
                assertTrue(dues.isEmpty(), "expected no reading for \"$token\", got $dues")
            }
        }

    @TestFactory
    fun `the issuer token is resolved however CRED cases it`(): List<DynamicTest> =
        // CRED's text bodies are lower-case prose and its HTML tables title-case the same token;
        // the lookup lower-cases before it hits the map so both land on one issuer.
        listOf("axis bank", "Axis Bank", "AXIS BANK", "Axis BANK").map { token ->
            DynamicTest.dynamicTest("\"$token\" is read as Axis Bank") {
                assertEquals("Axis Bank", parseOne(CRED_SENDER, credPayment(token)).issuer)
            }
        }

    /**
     * A C2 payment block carrying [issuerToken] and nothing else variable, so a case that fails
     * can only have failed on the issuer. Card suffix and figures are invented (spec 06 §6.2).
     */
    private fun credPayment(issuerToken: String): String = """
        $issuerToken $BULLET$BULLET 1234
        payment details
        amount paid ${RUPEE}7,310.00
        payment date oct 5, 2026
    """.trimIndent()

    private fun parseOne(from: String, body: String): ParsedDue {
        val dues = parser.parse(
            EmailParserTestUtils.message(from = from, subject = "payment", body = body)
        )
        assertEquals(1, dues.size, "Expected exactly one reading, got $dues")
        return dues.first()
    }

    private companion object {
        const val CRED_SENDER = "protect@cred.club"
        const val ICICI_SENDER = "credit_cards@icici.bank.in"

        const val BULLET = "•"
        const val RUPEE = "₹"

        /** text/plain, as CRED writes it: lower-case prose, one field per line. */
        val CRED_AXIS_PAYMENT = """
            your credit card bill payment was successful
            axis bank $BULLET$BULLET 1234
            payment details
            amount paid ${RUPEE}51,204.00
            payment date sep 04, 2026
            UTR No.: 123456789012
        """.trimIndent()

        /** HTML-derived: table cells joined with " | " (spec 11 §11.5). */
        val CRED_ICICI_PAYMENT_HTML =
            "icici bank $BULLET$BULLET 5678 | payment details | amount paid | $RUPEE 3,918.60 | " +
                "payment date | September 22, 2026 | UTR No.: 123456789012 | view on cred"

        val CRED_SBI_PAYMENT_MASKED = """
            sbi card $BULLET xxxx-4321
            payment details
            amount paid ${RUPEE}9,148.00
            payment date oct 5, 2026
        """.trimIndent()

        val CRED_NEW_BILL = """
            hi there, your new bill is here.
            axis bank $BULLET$BULLET 1234
            bill summary
            total amount due ${RUPEE}12,480.55
            minimum due ${RUPEE}883.00
            due date october 2, 2026
            bill generated on sep 07, 2026
        """.trimIndent()

        val CRED_UNKNOWN_ISSUER_PAYMENT = """
            kotak bank $BULLET$BULLET 1234
            payment details
            amount paid ${RUPEE}9,148.00
            payment date oct 5, 2026
        """.trimIndent()

        val CRED_PAYMENT_NO_DATE = """
            axis bank $BULLET$BULLET 1234
            payment details
            amount paid ${RUPEE}51,204.00
            UTR No.: 123456789012
        """.trimIndent()

        /** I2, with the masked PAN the bank writes and an invented suffix. */
        val ICICI_PAYMENT =
            "Dear Customer, We have received payment of INR 4,480.00 on your ICICI Bank " +
                "Credit Card account 1234 XXXX XXXX 5678 on 04-Sep-2026. Thank you."

        /**
         * I1 — a purchase, from the same address. Only the template's shape matters: this parser
         * must not fire on it. Every figure is invented (spec 06 §6.2, "amounts changed"), the two
         * limits included — a credit ceiling identifies a real card as plainly as a balance does.
         */
        val ICICI_PURCHASE =
            "Your ICICI Bank Credit Card XX5678 has been used for a transaction of INR 1,236.50 " +
                "on Sep 04, 2026 at 14:27:05. Info: SOMEONE STORES. The Available Credit Limit " +
                "on your card is INR 2,05,318.40 and Total Credit Limit is INR 3,00,000.00"

        /** I3 — the statement, which `ICICIStatementDueParser` owns. */
        val ICICI_STATEMENT =
            "Total Amount Due | INR 4,480.00 | Minimum Amount Due | INR 224.00 | " +
                "Payment due by | 22-Sep-2026 | Card ending XX5678"
    }
}
