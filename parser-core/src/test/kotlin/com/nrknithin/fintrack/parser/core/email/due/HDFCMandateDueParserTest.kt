package com.nrknithin.fintrack.parser.core.email.due

import com.nrknithin.fintrack.parser.core.test.EmailParserTestUtils
import com.nrknithin.fintrack.parser.core.test.EmailParserTestUtils.DueCase
import com.nrknithin.fintrack.parser.core.test.EmailParserTestUtils.ExpectedDue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Template H9 (spec 12 §12.1) — the HDFC "upcoming E-mandate (Auto payment)" notice.
 *
 * Bodies follow the shapes spec 11 §11.5 produces: `text/plain` as the bank sent it, plus
 * one HTML-derived case whose table cells are joined with ` | `. Card digits, SI Hub ids
 * and the addressee are invented, and so are the merchants and the amounts; the amount
 * shapes and the dates are what exercise the parsing.
 */
class HDFCMandateDueParserTest {

    private val parser = HDFCMandateDueParser()

    private val sender = "alerts@hdfcbank.bank.in"
    private val subject = "View: Account update for your HDFC Bank Account"

    @TestFactory
    fun `upcoming e-mandate notices become auto-pay dues`(): List<DynamicTest> {
        val cases = listOf(
            DueCase(
                name = "Credit-card mandate states amount, merchant, card and debit date",
                from = sender,
                subject = subject,
                body = """
                    Dear ACCOUNT HOLDER,

                    There is an upcoming E-mandate (Auto payment) of INR 530.50 for TEST STREAMING CO. Amount will be debited from your HDFC Bank Credit Card ending 4321 on 08/09/2026. SI Hub ID: ZQM4KTRX7L

                    Warm regards,
                    HDFC Bank
                """.trimIndent(),
                expected = listOf(
                    ExpectedDue(
                        issuer = "TEST STREAMING CO",
                        kind = DueKind.SUBSCRIPTION,
                        amount = BigDecimal("530.50"),
                        currency = "INR",
                        dueDate = LocalDate.of(2026, 9, 8),
                        accountLast4 = "4321",
                        reference = "ZQM4KTRX7L",
                        autoPay = true,
                        autoPayInstrument = "HDFC …4321",
                        source = "HDFC"
                    )
                )
            ),
            DueCase(
                name = "Debit-card mandate is a subscription due on the debit card",
                from = sender,
                subject = subject,
                body = """
                    There is an upcoming E-mandate (Auto payment) of INR 812.00 for SAMPLE SERVICES.
                    Amount will be debited from your HDFC Bank Debit Card ending 5678 on 03/10/2026.
                    SI Hub ID: HUB20261003GW
                """.trimIndent(),
                expected = listOf(
                    ExpectedDue(
                        issuer = "SAMPLE SERVICES",
                        kind = DueKind.SUBSCRIPTION,
                        amount = BigDecimal("812.00"),
                        dueDate = LocalDate.of(2026, 10, 3),
                        accountLast4 = "5678",
                        reference = "HUB20261003GW",
                        autoPay = true,
                        autoPayInstrument = "HDFC …5678",
                        source = "HDFC"
                    )
                )
            ),
            DueCase(
                name = "HTML-derived body with pipe-joined cells and a grouped amount",
                from = sender,
                subject = subject,
                body = "Account update | There is an upcoming E-mandate (Auto payment) of " +
                    "INR 1,299.00 for AMAZON PRIME. | Amount will be debited from your HDFC Bank " +
                    "Credit Card ending 4321 on 25/09/2026. | SI Hub ID: HUB77A1B2C3 |",
                expected = listOf(
                    ExpectedDue(
                        issuer = "AMAZON PRIME",
                        kind = DueKind.SUBSCRIPTION,
                        amount = BigDecimal("1299.00"),
                        dueDate = LocalDate.of(2026, 9, 25),
                        accountLast4 = "4321",
                        reference = "HUB77A1B2C3",
                        autoPay = true
                    )
                )
            ),
            DueCase(
                name = "Single-decimal amount is read as rupees and paise",
                from = sender,
                subject = subject,
                body = "There is an upcoming E-mandate (Auto payment) of INR 530.5 for TEST STREAMING CO. " +
                    "Amount will be debited from your HDFC Bank Credit Card ending 4321 on " +
                    "20/09/2026. SI Hub ID: ZQM4KTRX7L",
                expected = listOf(
                    ExpectedDue(
                        amount = BigDecimal("530.50"),
                        dueDate = LocalDate.of(2026, 9, 20),
                        accountLast4 = "4321"
                    )
                )
            ),
            DueCase(
                name = "Card run written as XX4321 yields the trailing four digits",
                from = sender,
                subject = subject,
                body = "There is an upcoming E-mandate (Auto payment) of INR 199.00 for SPOTIFY INDIA. " +
                    "Amount will be debited from your HDFC Bank Credit Card ending XX4321 on " +
                    "21/09/2026. SI Hub ID: HUB5150ABCD",
                expected = listOf(
                    ExpectedDue(
                        issuer = "SPOTIFY INDIA",
                        amount = BigDecimal("199.00"),
                        dueDate = LocalDate.of(2026, 9, 21),
                        accountLast4 = "4321",
                        autoPayInstrument = "HDFC …4321"
                    )
                )
            ),
            DueCase(
                name = "Notice without an SI Hub ID still yields a due",
                from = sender,
                subject = subject,
                body = "There is an upcoming E-mandate (Auto payment) of INR 499.00 for ADOBE EXPRESS. " +
                    "Amount will be debited from your HDFC Bank Credit Card ending 4321 on 22/09/2026.",
                expected = listOf(
                    ExpectedDue(
                        issuer = "ADOBE EXPRESS",
                        amount = BigDecimal("499.00"),
                        dueDate = LocalDate.of(2026, 9, 22),
                        accountLast4 = "4321",
                        autoPay = true,
                        source = "HDFC"
                    )
                )
            )
        )

        return EmailParserTestUtils.runDueSuite(parser, cases)
    }

    @TestFactory
    fun `mail that announces no upcoming charge yields no due`(): List<DynamicTest> {
        val cases = listOf(
            DueCase(
                name = "An amount of zero is a failed capture, not a free month",
                from = sender,
                subject = subject,
                body = "There is an upcoming E-mandate (Auto payment) of INR 0.00 for TEST STREAMING CO. " +
                    "Amount will be debited from your HDFC Bank Credit Card ending 4321 on " +
                    "20/09/2026. SI Hub ID: ZQM4KTRX7L"
            ),
            DueCase(
                name = "Notice missing both the amount and the debit date",
                from = sender,
                subject = subject,
                body = "There is an upcoming E-mandate (Auto payment) for TEST STREAMING CO. Amount will be " +
                    "debited from your HDFC Bank Credit Card ending 4321."
            ),
            DueCase(
                name = "H8 e-mandate paid mail is a transaction, not a due",
                from = sender,
                subject = subject,
                body = "Your TEST STREAMING CO bill, set up through E-mandate (Auto payment), has been " +
                    "successfully paid using your HDFC Bank Credit Card ending 4321. Transaction " +
                    "Details: Amount: INR 530.50 Date: 08/09/2026 Mandate ID: ZQM4KTRX7L"
            ),
            DueCase(
                name = "H10 mandate cancellation is a lifecycle event, not a due",
                from = sender,
                subject = subject,
                body = "Alert! TEST STREAMING CO AutoPay (E-mandate) Cancelled successfully! " +
                    "Merchant: TEST STREAMING CO Date:16/09/2026 Via: HDFC Bank Credit Card xx4321 " +
                    "SI Hub ID: ZQM4KTRX7L"
            ),
            DueCase(
                name = "The same wording sent from another bank address",
                from = "alerts@axis.bank.in",
                subject = subject,
                body = "There is an upcoming E-mandate (Auto payment) of INR 530.50 for TEST STREAMING CO. " +
                    "Amount will be debited from your HDFC Bank Credit Card ending 4321 on " +
                    "20/09/2026. SI Hub ID: ZQM4KTRX7L"
            )
        )

        return EmailParserTestUtils.runDueSuite(parser, cases)
    }

    @TestFactory
    fun `handles HDFC bank mail only`(): List<DynamicTest> = EmailParserTestUtils.runDueSuite(
        parser,
        cases = emptyList(),
        handleCases = listOf(
            "alerts@hdfcbank.bank.in" to true,
            // A From header with a display name still resolves to the bare address.
            """ "HDFC Bank Alerts" <alerts@hdfcbank.bank.in>""".trim() to true,
            // A rotated local part still resolves through the domain (spec 11 §11.6).
            "alerts2@hdfcbank.bank.in" to true,
            // The marketing subdomain never carries a mandate notice.
            "information@mailers.hdfcbank.bank.in" to false,
            "alerts@axis.bank.in" to false,
            "no-reply@getonecard.app" to false,
            "not-an-address" to false
        )
    )
}
