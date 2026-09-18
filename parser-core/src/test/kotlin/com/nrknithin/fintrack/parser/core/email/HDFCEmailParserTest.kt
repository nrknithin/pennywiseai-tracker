package com.nrknithin.fintrack.parser.core.email

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
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Templates H1–H8 and the H11 ignore set of spec 12 §12.1.
 *
 * Every amount keeps the shape the spec's sample states — the digits themselves are invented —
 * because the shape is what the template regexes have to survive: the broken grouping
 * `2,53471.86`, the `Rs. INR` double prefix, the single-decimal `530.5`, `07-SEP-2026`
 * against `15 Sep, 2026 at 16:14:34`. Dates are the spec's. Account numbers, card numbers,
 * names, VPAs, merchants, loan and mandate ids and amounts are invented — no real identifier
 * and no real amount appears in a fixture (spec 01 constraint C3).
 */
class HDFCEmailParserTest {

    // ------------------------------------------------------------------------
    // Fixtures — invented identifiers and amounts, spec dates
    // ------------------------------------------------------------------------

    private val hdfcAlerts = "alerts@hdfcbank.bank.in"

    /** Empty own-account sets: what the parser looks like before settings supply anything. */
    private val parser = HDFCEmailParser()

    /** The same parser after the app hands it the user's own VPAs and name. */
    private val configuredParser = HDFCEmailParser(
        ownVpas = setOf("owner-1@okaxis", "owner-*@ok*"),
        ownNames = setOf("Account Holder")
    )

    private val upiDebitBody =
        "Dear Customer, Rs.24317.85 is debited from your account ending 4321 towards VPA " +
            "someone@okbank (AXIS BANK LIMITED) on 16-09-26. UPI transaction reference no.: " +
            "123456789012. Never share your UPI PIN with anyone."

    private val creditReceivedBody =
        "Dear Customer, Rs.27500.00 has been successfully credited to your HDFC Bank account " +
            "ending in 4321. Transaction Details: a. Date: 27-08-26 b. Sender: PAYER NAME " +
            "(VPA: someone@okbank) c. UPI Reference No.: 210987654321"

    private val debitCardPurchaseBody =
        "Dear Customer, Rs.845.00 is debited from your HDFC Bank Debit Card ending 8765 at " +
            "SAMPLE SERVICES CYBS on 3 Sep, 2026 at 09:12:05. Your updated available balance " +
            "is visible in NetBanking."

    private val cardBillNetbankingBody =
        "Dear Customer, This is to inform you that an amount of Rs. 2,53471.86 has been debited " +
            "from your Account number XX4321 for Credit Card Payment done using HDFC Bank " +
            "Online Banking."

    private val loanEmiBody =
        "Dear Customer, Rs. INR 21538.00 is deducted from your account ending XX4321 and added " +
            "to EMI 900000123 Chq 000000 REF00987 account on 07-SEP-2026. The available balance " +
            "in your account is Rs. INR 412.00."

    private val loanEmiOlderWordingBody =
        "Dear Customer, Rs.INR 21,538.00 is debited from your account ending XX4321 and credited " +
            "to EMI 900000123 Chq 000000 REF00987 account on 07-OCT-2025."

    private val creditCardPurchaseBody =
        "Dear Customer, Rs. 3417.00 has been debited from your HDFC Bank Credit Card ending 5432 " +
            "towards ACME RETAIL *DEMO on 13 Sep, 2026 at 17:53:59. Not you? Report immediately."

    private val chargeWithoutOtpBody =
        "Dear Customer, Thank you for using your HDFC Bank Card XX5432 for Rs. 530.5 at TEST STREAMING CO " +
            "on 08-09-2026 06:30:11. Authorization code: 482913. If this was not you, call us."

    private val mandatePaidBody =
        "Dear Customer, Your TEST STREAMING CO bill, set up through E-mandate (Auto payment), has been " +
            "successfully paid using your HDFC Bank Credit Card ending 5432. Transaction " +
            "Details: Amount: INR 530.50 Date: 08/09/2026 Mandate ID: AB12cDefGH"

    /** The same template as it arrives when only the HTML part is usable (spec 11 §11.5). */
    private val mandatePaidHtmlDerivedBody =
        "Dear Customer, Your SAMPLE SERVICES bill, set up through E-mandate (Auto payment), has " +
            "been successfully paid using your HDFC Bank Debit Card ending 8765. | Transaction " +
            "Details: | Amount: INR 845.00 | Date: 03/09/2026 | Mandate ID: CD34eFghIJ |"

    private val otpBody =
        "Dear Customer, 482913 is the OTP for your online transaction of Rs. 356.00 at a merchant " +
            "site. This OTP is valid for 10 minutes. Never share it."

    // ------------------------------------------------------------------------
    // The eight money templates, parsed with no own-account settings
    // ------------------------------------------------------------------------

    @TestFactory
    fun `hdfc email parser reads each money template`(): List<DynamicTest> {
        val cases = listOf(
            EmailCase(
                name = "H1 UPI debit keeps the payee as merchant and the 12-digit UPI reference",
                from = hdfcAlerts,
                subject = "You have done a UPI txn. Check details!",
                body = upiDebitBody,
                expected = ExpectedTransaction(
                    amount = BigDecimal("24317.85"),
                    currency = "INR",
                    type = TransactionType.EXPENSE,
                    merchant = "AXIS BANK LIMITED",
                    reference = "123456789012",
                    accountLast4 = "4321",
                    isFromCard = false
                )
            ),
            EmailCase(
                name = "H1 accepts the XX-prefixed spelling of the account run",
                from = hdfcAlerts,
                subject = "You have done a UPI txn. Check details!",
                body = upiDebitBody.replace("account ending 4321", "account ending XX4321"),
                expected = ExpectedTransaction(
                    amount = BigDecimal("24317.85"),
                    currency = "INR",
                    type = TransactionType.EXPENSE,
                    accountLast4 = "4321"
                )
            ),
            EmailCase(
                name = "H1 penny-drop to a mandate-verification VPA stays an expense",
                from = hdfcAlerts,
                subject = "You have done a UPI txn. Check details!",
                body = "Dear Customer, Rs.2.00 is debited from your account ending 4321 towards " +
                    "VPA autopay-verify@axisb (AUTOPAY VERIFICATION) on 16-09-26. UPI " +
                    "transaction reference no.: 456789012345.",
                expected = ExpectedTransaction(
                    amount = BigDecimal("2.00"),
                    currency = "INR",
                    type = TransactionType.EXPENSE,
                    merchant = "AUTOPAY VERIFICATION",
                    reference = "456789012345"
                )
            ),
            EmailCase(
                name = "H1 without own-VPA settings reads a self-payment as an ordinary expense",
                from = hdfcAlerts,
                subject = "You have done a UPI txn. Check details!",
                body = upiDebitBody.replace("someone@okbank (AXIS BANK LIMITED)", "owner-1@okaxis (ACCOUNT HOLDER)"),
                expected = ExpectedTransaction(
                    amount = BigDecimal("24317.85"),
                    currency = "INR",
                    type = TransactionType.EXPENSE
                )
            ),
            EmailCase(
                name = "H1 with no UPI reference is rejected rather than half-parsed",
                from = hdfcAlerts,
                subject = "You have done a UPI txn. Check details!",
                body = "Dear Customer, Rs.24317.85 is debited from your account ending 4321 " +
                    "towards VPA someone@okbank (AXIS BANK LIMITED) on 16-09-26.",
                shouldParse = false
            ),

            EmailCase(
                name = "H2 credit received is income with the sender name and UPI reference",
                from = hdfcAlerts,
                subject = "View: Account update on your HDFC Bank Account",
                body = creditReceivedBody,
                expected = ExpectedTransaction(
                    amount = BigDecimal("27500.00"),
                    currency = "INR",
                    type = TransactionType.INCOME,
                    merchant = "PAYER NAME",
                    reference = "210987654321",
                    accountLast4 = "4321",
                    isFromCard = false
                )
            ),
            EmailCase(
                name = "H2 with the sender block missing is rejected",
                from = hdfcAlerts,
                subject = "View: Account update on your HDFC Bank Account",
                body = "Dear Customer, Rs.27500.00 has been successfully credited to your HDFC " +
                    "Bank account ending in 4321. Transaction Details: a. Date: 27-08-26 " +
                    "c. UPI Reference No.: 210987654321",
                shouldParse = false
            ),

            EmailCase(
                name = "H3 debit-card purchase is a card-flagged expense on the card last four",
                from = hdfcAlerts,
                subject = "Rs.845.00 debited via Debit Card **8765",
                body = debitCardPurchaseBody,
                expected = ExpectedTransaction(
                    amount = BigDecimal("845.00"),
                    currency = "INR",
                    type = TransactionType.EXPENSE,
                    merchant = "SAMPLE SERVICES CYBS",
                    accountLast4 = "8765",
                    isFromCard = true
                )
            ),
            EmailCase(
                name = "H3 debit-card payment to a card issuer is a transfer to that card",
                from = hdfcAlerts,
                subject = "Rs.45312.60 debited via Debit Card **8765",
                body = "Dear Customer, Rs.45312.60 is debited from your HDFC Bank Debit Card " +
                    "ending 8765 at FPL Technologies Priva on 15 Sep, 2026 at 16:14:34.",
                expected = ExpectedTransaction(
                    amount = BigDecimal("45312.60"),
                    currency = "INR",
                    type = TransactionType.TRANSFER,
                    merchant = "FPL Technologies Priva",
                    accountLast4 = "8765",
                    isFromCard = true,
                    fromAccount = "8765",
                    toAccount = "OneCard"
                )
            ),
            EmailCase(
                name = "H3 without the time of the purchase is rejected",
                from = hdfcAlerts,
                subject = "Rs.845.00 debited via Debit Card **8765",
                body = "Dear Customer, Rs.845.00 is debited from your HDFC Bank Debit Card " +
                    "ending 8765 at SAMPLE SERVICES CYBS on 3 Sep, 2026.",
                shouldParse = false
            ),

            EmailCase(
                name = "H4 card bill paid by NetBanking is a transfer out of the account",
                from = hdfcAlerts,
                subject = "View: Account update on your HDFC Bank Account",
                body = cardBillNetbankingBody,
                expected = ExpectedTransaction(
                    amount = BigDecimal("253471.86"),
                    currency = "INR",
                    type = TransactionType.TRANSFER,
                    merchant = "Credit Card Payment",
                    accountLast4 = "4321",
                    isFromCard = false,
                    fromAccount = "4321"
                )
            ),
            EmailCase(
                name = "H4 with no amount in the sentence is rejected",
                from = hdfcAlerts,
                subject = "View: Account update on your HDFC Bank Account",
                body = "Dear Customer, This is to inform you that an amount of Rs. has been " +
                    "debited from your Account number XX4321 for Credit Card Payment done " +
                    "using HDFC Bank Online Banking.",
                shouldParse = false
            ),

            EmailCase(
                name = "H5 loan EMI keeps the loan id as reference and the stated balance",
                from = hdfcAlerts,
                subject = "View: Account update on your HDFC Bank Account",
                body = loanEmiBody,
                expected = ExpectedTransaction(
                    amount = BigDecimal("21538.00"),
                    currency = "INR",
                    type = TransactionType.EXPENSE,
                    merchant = "Loan EMI",
                    reference = "900000123",
                    accountLast4 = "4321",
                    balance = BigDecimal("412.00"),
                    isFromCard = false
                )
            ),
            EmailCase(
                name = "H5 older debited-and-credited-to-EMI wording parses the same way",
                from = hdfcAlerts,
                subject = "View: Account update on your HDFC Bank Account",
                body = loanEmiOlderWordingBody,
                expected = ExpectedTransaction(
                    amount = BigDecimal("21538.00"),
                    currency = "INR",
                    type = TransactionType.EXPENSE,
                    merchant = "Loan EMI",
                    reference = "900000123",
                    accountLast4 = "4321"
                )
            ),
            EmailCase(
                name = "H5 with no amount after the double Rs INR prefix is rejected",
                from = hdfcAlerts,
                subject = "View: Account update on your HDFC Bank Account",
                body = "Dear Customer, Rs. INR is deducted from your account ending XX4321 and " +
                    "added to EMI 900000123 Chq 000000 account on 07-SEP-2026.",
                shouldParse = false
            ),

            EmailCase(
                name = "H6 credit-card purchase is CREDIT on the card, merchant kept raw",
                from = hdfcAlerts,
                subject = "A payment was made using your Credit Card",
                body = creditCardPurchaseBody,
                expected = ExpectedTransaction(
                    amount = BigDecimal("3417.00"),
                    currency = "INR",
                    type = TransactionType.CREDIT,
                    merchant = "ACME RETAIL *DEMO",
                    accountLast4 = "5432",
                    isFromCard = true
                )
            ),
            EmailCase(
                name = "H6 reads a multi-word merchant up to the date",
                from = hdfcAlerts,
                subject = "A payment was made using your Credit Card",
                body = "Dear Customer, Rs. 1050.00 has been debited from your HDFC Bank Credit " +
                    "Card ending 5432 towards DEMO MART TICKET COUNT on 13 Sep, 2026 at 11:02:44.",
                expected = ExpectedTransaction(
                    amount = BigDecimal("1050.00"),
                    currency = "INR",
                    type = TransactionType.CREDIT,
                    merchant = "DEMO MART TICKET COUNT",
                    accountLast4 = "5432",
                    isFromCard = true
                )
            ),
            EmailCase(
                name = "H6 without a merchant clause is rejected",
                from = hdfcAlerts,
                subject = "A payment was made using your Credit Card",
                body = "Dear Customer, Rs. 3417.00 has been debited from your HDFC Bank Credit " +
                    "Card ending 5432 on 13 Sep, 2026 at 17:53:59.",
                shouldParse = false
            ),

            EmailCase(
                name = "H7 charge without OTP keeps the authorization code and a one-decimal amount",
                from = hdfcAlerts,
                subject = "A charge just happened on your Credit Card without OTP",
                body = chargeWithoutOtpBody,
                expected = ExpectedTransaction(
                    amount = BigDecimal("530.50"),
                    currency = "INR",
                    type = TransactionType.CREDIT,
                    merchant = "TEST STREAMING CO",
                    reference = "482913",
                    accountLast4 = "5432",
                    isFromCard = true
                )
            ),
            EmailCase(
                name = "H7 with a non-numeric authorization code is rejected",
                from = hdfcAlerts,
                subject = "A charge just happened on your Credit Card without OTP",
                body = chargeWithoutOtpBody.replace("Authorization code: 482913", "Authorization code: NOTACODE"),
                shouldParse = false
            ),
            EmailCase(
                name = "H7 with no authorization code at all is rejected",
                from = hdfcAlerts,
                subject = "A charge just happened on your Credit Card without OTP",
                body = "Dear Customer, Thank you for using your HDFC Bank Card XX5432 for " +
                    "Rs. 530.5 at TEST STREAMING CO on 08-09-2026 06:30:11.",
                shouldParse = false
            ),

            EmailCase(
                name = "H8 e-mandate paid on a credit card is CREDIT with the mandate id",
                from = hdfcAlerts,
                subject = "View: Account update on your HDFC Bank Credit Card",
                body = mandatePaidBody,
                expected = ExpectedTransaction(
                    amount = BigDecimal("530.50"),
                    currency = "INR",
                    type = TransactionType.CREDIT,
                    merchant = "TEST STREAMING CO",
                    reference = "AB12cDefGH",
                    accountLast4 = "5432",
                    isFromCard = true
                )
            ),
            EmailCase(
                name = "H8 e-mandate paid on a debit card is an account expense, cells joined by pipes",
                from = hdfcAlerts,
                subject = "View: Account update on your HDFC Bank Account",
                body = mandatePaidHtmlDerivedBody,
                expected = ExpectedTransaction(
                    amount = BigDecimal("845.00"),
                    currency = "INR",
                    type = TransactionType.EXPENSE,
                    merchant = "SAMPLE SERVICES",
                    reference = "CD34eFghIJ",
                    accountLast4 = "8765",
                    isFromCard = true
                )
            ),
            EmailCase(
                name = "H8 without a mandate id is rejected",
                from = hdfcAlerts,
                subject = "View: Account update on your HDFC Bank Credit Card",
                body = "Dear Customer, Your TEST STREAMING CO bill, set up through E-mandate (Auto " +
                    "payment), has been successfully paid using your HDFC Bank Credit Card " +
                    "ending 5432. Transaction Details: Amount: INR 530.50 Date: 08/09/2026",
                shouldParse = false
            ),

            // --- H11 and the templates that are not transactions at all -----------
            EmailCase(
                name = "H11 an OTP alert never becomes a transaction",
                from = hdfcAlerts,
                subject = "OTP For online Ecom Transaction",
                body = otpBody,
                shouldParse = false
            ),
            EmailCase(
                name = "H11 a payee-added notice never becomes a transaction",
                from = hdfcAlerts,
                subject = "View: Account update on your HDFC Bank Account",
                body = "Dear Customer, You have added a payee someone@okbank to your HDFC Bank " +
                    "account. If this was not you, call us immediately.",
                shouldParse = false
            ),
            EmailCase(
                name = "H11 a transfer-limit change never becomes a transaction",
                from = hdfcAlerts,
                subject = "View: Account update on your HDFC Bank Account",
                body = "Dear Customer, Your transfer limit has been updated to Rs. 100000.00 " +
                    "per day with effect from 16-09-26.",
                shouldParse = false
            ),
            EmailCase(
                name = "H11 a Relationship Manager notice never becomes a transaction",
                from = hdfcAlerts,
                subject = "Your Relationship Manager tried to reach you",
                body = "Dear Customer, Your Relationship Manager tried to reach you today. " +
                    "Please call back at your convenience.",
                shouldParse = false
            ),
            EmailCase(
                name = "H9 an upcoming e-mandate is an expectation, not a charge",
                from = hdfcAlerts,
                subject = "View: Account update on your HDFC Bank Credit Card",
                body = "Dear Customer, There is an upcoming E-mandate (Auto payment) of INR " +
                    "530.50 for TEST STREAMING CO. Amount will be debited from your HDFC Bank Credit Card " +
                    "ending 5432 on 08/09/2026. SI Hub ID: SIH0000123",
                shouldParse = false
            ),
            EmailCase(
                name = "H10 a cancelled auto-pay is a lifecycle event, not a charge",
                from = hdfcAlerts,
                subject = "View: Account update on your HDFC Bank Credit Card",
                body = "Alert! TEST STREAMING CO AutoPay (E-mandate) Cancelled successfully! Merchant: " +
                    "TEST STREAMING CO Date:08/09/2026 Via: HDFC Bank Credit Card xx5432 SI Hub ID: " +
                    "SIH0000123",
                shouldParse = false
            ),
            EmailCase(
                name = "A perfectly shaped body from another bank is left to that bank's parser",
                from = "alerts@axis.bank.in",
                subject = "You have done a UPI txn. Check details!",
                body = upiDebitBody,
                shouldParse = false
            )
        )

        val handleCases = listOf(
            hdfcAlerts to true,
            "information@hdfcbank.bank.in" to true,
            "HDFC Bank Alerts <alerts@hdfcbank.bank.in>" to true,
            "alerts@axis.bank.in" to false,
            "credit_cards@icici.bank.in" to false,
            "no-reply@getonecard.app" to false,
            "information@mailers.hdfcbank.bank.in" to false
        )

        return EmailParserTestUtils.runTransactionSuite(parser, cases, handleCases)
    }

    // ------------------------------------------------------------------------
    // The self-transfer rules, once the app supplies the user's own VPAs and name
    // ------------------------------------------------------------------------

    @TestFactory
    fun `hdfc email parser treats own-account payments as transfers when settings say so`(): List<DynamicTest> {
        val cases = listOf(
            EmailCase(
                name = "H1 a debit to the user's own VPA is a transfer to that handle's bank",
                from = hdfcAlerts,
                subject = "You have done a UPI txn. Check details!",
                body = upiDebitBody.replace("someone@okbank (AXIS BANK LIMITED)", "owner-1@okaxis (SOME PAYEE)"),
                expected = ExpectedTransaction(
                    amount = BigDecimal("24317.85"),
                    currency = "INR",
                    type = TransactionType.TRANSFER,
                    accountLast4 = "4321",
                    fromAccount = "4321",
                    toAccount = "Axis Bank"
                )
            ),
            EmailCase(
                name = "H1 a wildcard own-VPA entry covers the whole family of handles",
                from = hdfcAlerts,
                subject = "You have done a UPI txn. Check details!",
                body = upiDebitBody.replace("someone@okbank (AXIS BANK LIMITED)", "owner-7@okhdfcbank (SOME PAYEE)"),
                expected = ExpectedTransaction(
                    amount = BigDecimal("24317.85"),
                    currency = "INR",
                    type = TransactionType.TRANSFER,
                    fromAccount = "4321",
                    toAccount = "HDFC Bank"
                )
            ),
            EmailCase(
                name = "H1 a payee spelled like the account holder is a transfer",
                from = hdfcAlerts,
                subject = "You have done a UPI txn. Check details!",
                body = upiDebitBody.replace("someone@okbank (AXIS BANK LIMITED)", "stranger@oksbi (ACCOUNT HOLDER)"),
                expected = ExpectedTransaction(
                    amount = BigDecimal("24317.85"),
                    currency = "INR",
                    type = TransactionType.TRANSFER,
                    merchant = "ACCOUNT HOLDER",
                    toAccount = "State Bank of India"
                )
            ),
            EmailCase(
                name = "H1 an own VPA on a handle the spec does not map still transfers",
                from = hdfcAlerts,
                subject = "You have done a UPI txn. Check details!",
                body = upiDebitBody.replace("someone@okbank (AXIS BANK LIMITED)", "owner-3@okicici (SOME PAYEE)"),
                expected = ExpectedTransaction(
                    amount = BigDecimal("24317.85"),
                    currency = "INR",
                    type = TransactionType.TRANSFER,
                    fromAccount = "4321"
                )
            ),
            EmailCase(
                name = "H1 a payment to somebody else is still an expense",
                from = hdfcAlerts,
                subject = "You have done a UPI txn. Check details!",
                body = upiDebitBody,
                expected = ExpectedTransaction(
                    amount = BigDecimal("24317.85"),
                    currency = "INR",
                    type = TransactionType.EXPENSE,
                    merchant = "AXIS BANK LIMITED"
                )
            ),
            EmailCase(
                name = "H2 a credit from the user's own VPA is a transfer in, not income",
                from = hdfcAlerts,
                subject = "View: Account update on your HDFC Bank Account",
                body = creditReceivedBody.replace("PAYER NAME (VPA: someone@okbank)", "ACCOUNT HOLDER (VPA: owner-1@okaxis)"),
                expected = ExpectedTransaction(
                    amount = BigDecimal("27500.00"),
                    currency = "INR",
                    type = TransactionType.TRANSFER,
                    merchant = "ACCOUNT HOLDER",
                    accountLast4 = "4321",
                    fromAccount = "Axis Bank",
                    toAccount = "4321"
                )
            ),
            EmailCase(
                name = "H2 a credit whose sender name matches the account holder is a transfer",
                from = hdfcAlerts,
                subject = "View: Account update on your HDFC Bank Account",
                body = creditReceivedBody.replace("PAYER NAME (VPA: someone@okbank)", "account  holder (VPA: stranger@oksbi)"),
                expected = ExpectedTransaction(
                    amount = BigDecimal("27500.00"),
                    currency = "INR",
                    type = TransactionType.TRANSFER,
                    fromAccount = "State Bank of India",
                    toAccount = "4321"
                )
            ),
            EmailCase(
                name = "H2 a credit from a third party stays income",
                from = hdfcAlerts,
                subject = "View: Account update on your HDFC Bank Account",
                body = creditReceivedBody,
                expected = ExpectedTransaction(
                    amount = BigDecimal("27500.00"),
                    currency = "INR",
                    type = TransactionType.INCOME,
                    merchant = "PAYER NAME"
                )
            )
        )

        return EmailParserTestUtils.runTransactionSuite(configuredParser, cases)
    }

    // ------------------------------------------------------------------------
    // Fields the shared expectation object cannot express
    // ------------------------------------------------------------------------

    @Test
    fun `the date inside the body wins over the time the mail arrived`() {
        val parsed = parser.parse(
            EmailParserTestUtils.message(
                from = hdfcAlerts,
                subject = "You have done a UPI txn. Check details!",
                body = upiDebitBody
            )
        )

        assertNotNull(parsed)
        assertEquals(istMillis(LocalDate.of(2026, 9, 16), LocalTime.MIDNIGHT), parsed!!.timestamp)
    }

    @Test
    fun `a template that states a date and a time keeps both`() {
        val parsed = parser.parse(
            EmailParserTestUtils.message(
                from = hdfcAlerts,
                subject = "Rs.45312.60 debited via Debit Card **8765",
                body = "Dear Customer, Rs.45312.60 is debited from your HDFC Bank Debit Card " +
                    "ending 8765 at FPL Technologies Priva on 15 Sep, 2026 at 16:14:34."
            )
        )

        assertNotNull(parsed)
        assertEquals(istMillis(LocalDate.of(2026, 9, 15), LocalTime.of(16, 14, 34)), parsed!!.timestamp)
    }

    @Test
    fun `a template with no date of its own falls back to the received time`() {
        val parsed = parser.parse(
            EmailParserTestUtils.message(
                from = hdfcAlerts,
                subject = "View: Account update on your HDFC Bank Account",
                body = cardBillNetbankingBody
            )
        )

        assertNotNull(parsed)
        assertEquals(EmailParserTestUtils.FIXED_RECEIVED_AT, parsed!!.timestamp)
        assertNull(parsed.toAccount, "The mail never names the card that was paid")
    }

    @Test
    fun `the older EMI wording leaves the balance unknown instead of carrying one over`() {
        val parsed = parser.parse(
            EmailParserTestUtils.message(
                from = hdfcAlerts,
                subject = "View: Account update on your HDFC Bank Account",
                body = loanEmiOlderWordingBody
            )
        )

        assertNotNull(parsed)
        assertNull(parsed!!.balance)
        assertEquals(istMillis(LocalDate.of(2025, 10, 7), LocalTime.MIDNIGHT), parsed.timestamp)
    }

    @Test
    fun `a UPI handle the spec does not map leaves the counterpart bank unnamed`() {
        val parsed = configuredParser.parse(
            EmailParserTestUtils.message(
                from = hdfcAlerts,
                subject = "You have done a UPI txn. Check details!",
                body = upiDebitBody.replace("someone@okbank (AXIS BANK LIMITED)", "owner-3@okicici (SOME PAYEE)")
            )
        )

        assertNotNull(parsed)
        assertEquals(TransactionType.TRANSFER, parsed!!.type)
        assertEquals("4321", parsed.fromAccount)
        assertNull(parsed.toAccount, "An unmapped handle names no bank rather than guessing one")
    }

    @Test
    fun `without own-account settings a self-payment claims no counterpart`() {
        val parsed = parser.parse(
            EmailParserTestUtils.message(
                from = hdfcAlerts,
                subject = "You have done a UPI txn. Check details!",
                body = upiDebitBody.replace("someone@okbank (AXIS BANK LIMITED)", "owner-1@okaxis (ACCOUNT HOLDER)")
            )
        )

        assertNotNull(parsed)
        assertEquals(TransactionType.EXPENSE, parsed!!.type)
        assertNull(parsed.fromAccount)
        assertNull(parsed.toAccount)
    }

    @Test
    fun `an every-field message carries the sender and body through untouched`() {
        val message = EmailParserTestUtils.message(
            from = hdfcAlerts,
            subject = "A payment was made using your Credit Card",
            body = creditCardPurchaseBody
        )

        val parsed = parser.parse(message)

        assertNotNull(parsed)
        assertEquals(hdfcAlerts, parsed!!.sender)
        assertEquals(creditCardPurchaseBody, parsed.smsBody)
        assertEquals("HDFC Bank", parsed.bankName)
        assertNull(parsed.creditLimit, "No HDFC template states a credit limit")
    }

    // ------------------------------------------------------------------------
    // H11 · the ignore set, as the pipeline asks about it
    // ------------------------------------------------------------------------

    @TestFactory
    fun `hdfc email parser reports the ignore set as non-transaction mail`(): List<DynamicTest> {
        val nonTransactions = listOf(
            "an OTP for an online purchase" to Pair("OTP For online Ecom Transaction", otpBody),
            "a payee added to the account" to Pair(
                "View: Account update on your HDFC Bank Account",
                "Dear Customer, You have added a payee someone@okbank to your HDFC Bank account."
            ),
            "a transfer limit change" to Pair(
                "View: Account update on your HDFC Bank Account",
                "Dear Customer, Your transfer limit has been updated to Rs. 100000.00 per day."
            ),
            "a login PIN notice" to Pair(
                "Your Login PIN has been changed",
                "Dear Customer, Your Login PIN has been changed successfully."
            ),
            "a relationship manager call-back" to Pair(
                "Your Relationship Manager tried to reach you",
                "Dear Customer, Your Relationship Manager tried to reach you today."
            ),
            "the terms and conditions mailer" to Pair(
                "Revised terms and conditions",
                "Dear Customer, Please read the revised terms and conditions of using NetBanking."
            ),
            "an upcoming auto-pay charge" to Pair(
                "View: Account update on your HDFC Bank Credit Card",
                "Dear Customer, There is an upcoming E-mandate (Auto payment) of INR 530.50 for " +
                    "TEST STREAMING CO. Amount will be debited from your HDFC Bank Credit Card ending 5432 " +
                    "on 08/09/2026. SI Hub ID: SIH0000123"
            ),
            "a cancelled auto-pay" to Pair(
                "View: Account update on your HDFC Bank Credit Card",
                "Alert! TEST STREAMING CO AutoPay (E-mandate) Cancelled successfully! SI Hub ID: SIH0000123"
            )
        )

        val ignored = nonTransactions.map { (label, mail) ->
            DynamicTest.dynamicTest("Non-transaction: $label") {
                val message = EmailParserTestUtils.message(hdfcAlerts, mail.first, mail.second)
                assertTrue(parser.isNonTransactionEmail(message), "Should be reported as NON_TXN")
                assertNull(parser.parse(message), "Should never produce a transaction")
            }
        }

        val realAlerts = listOf(
            "a charge without OTP, whose subject says OTP" to
                Pair("A charge just happened on your Credit Card without OTP", chargeWithoutOtpBody),
            "a UPI debit whose footer mentions the UPI PIN" to
                Pair("You have done a UPI txn. Check details!", upiDebitBody),
            "an e-mandate charge that was really paid" to
                Pair("View: Account update on your HDFC Bank Credit Card", mandatePaidBody)
        ).map { (label, mail) ->
            DynamicTest.dynamicTest("Still a transaction: $label") {
                val message = EmailParserTestUtils.message(hdfcAlerts, mail.first, mail.second)
                assertFalse(parser.isNonTransactionEmail(message), "A money template is never NON_TXN")
                assertNotNull(parser.parse(message))
            }
        }

        return ignored + realAlerts
    }

    @Test
    fun `another bank's OTP is not this parser's verdict to give`() {
        val message = EmailParserTestUtils.message(
            from = "alerts@axis.bank.in",
            subject = "OTP For online Ecom Transaction",
            body = otpBody
        )

        assertFalse(parser.isNonTransactionEmail(message))
    }

    // ------------------------------------------------------------------------
    // Malformed input
    // ------------------------------------------------------------------------

    @TestFactory
    fun `hdfc email parser never throws on malformed mail`(): List<DynamicTest> {
        val bodies = mapOf(
            "empty body" to "",
            "subject only" to "   ",
            "template cut off mid-sentence" to
                "Dear Customer, Rs.24317.85 is debited from your account ending 4321 towards VPA",
            "amount where the digits should be" to
                "Dear Customer, Rs.,, is debited from your account ending 4321 towards VPA " +
                    "someone@okbank (SOME PAYEE) on 16-09-26. UPI transaction reference no.: " +
                    "123456789012.",
            "an impossible calendar date" to
                "Dear Customer, Rs.24317.85 is debited from your account ending 4321 towards VPA " +
                    "someone@okbank (SOME PAYEE) on 99-99-99. UPI transaction reference no.: " +
                    "123456789012.",
            "a wall of unrelated text" to "Dear Customer, thank you for banking with us."
        )

        return bodies.map { (label, body) ->
            DynamicTest.dynamicTest("Malformed: $label") {
                val message = EmailParserTestUtils.message(
                    hdfcAlerts,
                    "View: Account update on your HDFC Bank Account",
                    body
                )
                // Either a clean null, or — for the impossible date — a transaction that
                // falls back to the received time. Never an exception, never a wrong amount.
                val parsed = parser.parse(message)
                if (parsed != null) {
                    assertTrue(parsed.amount.signum() > 0, "An amount, once claimed, is positive")
                }
            }
        }
    }

    @Test
    fun `an impossible date keeps the money and falls back to the received time`() {
        val parsed = parser.parse(
            EmailParserTestUtils.message(
                from = hdfcAlerts,
                subject = "You have done a UPI txn. Check details!",
                body = upiDebitBody.replace("on 16-09-26", "on 99-99-99")
            )
        )

        assertNotNull(parsed)
        assertEquals(BigDecimal("24317.85"), parsed!!.amount)
        assertEquals(EmailParserTestUtils.FIXED_RECEIVED_AT, parsed.timestamp)
    }

    private fun istMillis(date: LocalDate, time: LocalTime): Long =
        ZonedDateTime.of(date.atTime(time), ZoneId.of("Asia/Kolkata")).toInstant().toEpochMilli()
}
