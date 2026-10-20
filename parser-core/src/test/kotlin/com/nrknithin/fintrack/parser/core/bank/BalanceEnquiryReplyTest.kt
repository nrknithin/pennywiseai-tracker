package com.nrknithin.fintrack.parser.core.bank

import com.nrknithin.fintrack.parser.core.test.ParserTestCase
import com.nrknithin.fintrack.parser.core.test.ParserTestUtils
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.math.BigDecimal

/**
 * Replies to a missed-call / SMS "BAL" balance enquiry: a balance, not a transaction.
 * Account numbers and amounts are illustrative.
 */
class BalanceEnquiryReplyTest {

    private data class Reply(
        val name: String,
        val parser: BaseIndianBankParser,
        val sender: String,
        val message: String,
        val accountLast4: String,
        val balance: String
    )

    private val replies = listOf(
        Reply(
            "Axis missed-call reply",
            AxisBankParser(), "AD-AXISBK-S",
            "Bal for your Axis Bank A/c no. XX1234 - INR 5,678.90. Click https://ccm.axis.bank.in/AXISBK/abc123 to avail special benefits.",
            "1234", "5678.90"
        ),
        Reply(
            "HDFC missed-call reply",
            HDFCBankParser(), "AD-HDFCBK-S",
            "Avl bal in A/c XX1234 as on 30-SEP-26 is INR 12,345.67. Use HDFC Bank MobileBanking app for more services.",
            "1234", "12345.67"
        ),
        Reply(
            "SBI Quick reply (has Avl Bal of)",
            SBIBankParser(), "AD-CBSSBI-S",
            "Dear Customer, Your A/C XXXXX654321 has Avl Bal of Rs 23,456.78 as on 30/09/26. -SBI",
            "4321", "23456.78"
        ),
        Reply(
            "SBI Quick reply (Avl. Bal in ... is)",
            SBIBankParser(), "AD-SBIPSG-S",
            "Your Avl. Bal in A/C XXXXX654321 is Rs.23,456.78 as on 30-09-2026 -State Bank of India",
            "4321", "23456.78"
        )
    )

    @TestFactory
    fun `balance enquiry replies are not transactions`(): List<DynamicTest> =
        replies.groupBy { it.parser::class }.flatMap { (_, group) ->
            ParserTestUtils.runTestSuite(
                group.first().parser,
                group.map { ParserTestCase(name = it.name, message = it.message, sender = it.sender, shouldParse = false) }
            )
        }

    @TestFactory
    fun `balance enquiry replies update the account balance`(): List<DynamicTest> =
        replies.map { reply ->
            DynamicTest.dynamicTest(reply.name) {
                assertTrue(reply.parser.isBalanceUpdateNotification(reply.message))
                val info = reply.parser.parseBalanceUpdate(reply.message)
                assertNotNull(info)
                assertEquals(reply.parser.getBankName(), info!!.bankName)
                assertEquals(reply.accountLast4, info.accountLast4)
                assertEquals(BigDecimal(reply.balance), info.balance)
            }
        }

    @TestFactory
    fun `alerts that mention a balance are not balance updates`(): List<DynamicTest> = listOf(
        "Axis card decline with Avl Limit" to (AxisBankParser() to
            "Txn of INR 999 declined due to insufficient balance on your Axis Bank Credit Card XX1234 Avl Limit: INR 9.90 Pay your dues at https://ccm.axis.bank.in/AXISBK/abc123"),
        "Axis debit with WhatsApp BAL hint" to (AxisBankParser() to
            "Debit INR 500.00 Axis Bank A/c XX1234 01-09-26 10:00:00 UPI/P2A/123456789012/SHOP WhatsApp BAL to 919999999999 Not You? SMS BLOCKALL CustID to 919999999999"),
        "HDFC EMI due reminder" to (HDFCBankParser() to
            "Alert: EMI of Rs. 12345 is due on 07-Oct-2026, for HDFC Bank Loan A/c 123456789. Please ensure sufficient balance to avoid bounce and delayed interest charges."),
        "HDFC card refund with updated-balance link" to (HDFCBankParser() to
            "Alert! Rs. 100 refunded by SOME STORE MUMBAI IN on 10/MAY/2026 & adjusted against HDFC Bank Credit Card 1234 View updated balance here: https://hdfcbk.io/HDFCBK/s/abc123"),
        "SBI debit carrying Avl Bal" to (SBIBankParser() to
            "Dear Customer, Your A/C XXXXX654321 has a debit by transfer of Rs 500.00 on 01/09/26. Avl Bal Rs 12,345.67.-SBI"),
        "SBI ATM withdrawal carrying Available Balance" to (SBIBankParser() to
            "Dear SBI Customer, Rs.2000 withdrawn at SBI ATM S1BC000000001 from A/cX4321 on 01Sep26 Transaction Number 1234. Available Balance Rs.5000.00.")
    ).map { (name, pair) ->
        val (parser, message) = pair
        DynamicTest.dynamicTest(name) {
            assertNull(parser.parseBalanceUpdate(message))
            if (parser !is HDFCBankParser) assertFalse(parser.isBalanceUpdateNotification(message))
        }
    }
}
