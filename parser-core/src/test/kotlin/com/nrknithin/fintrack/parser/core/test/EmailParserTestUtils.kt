package com.nrknithin.fintrack.parser.core.test

import com.nrknithin.fintrack.parser.core.ParsedTransaction
import com.nrknithin.fintrack.parser.core.email.EmailMessage
import com.nrknithin.fintrack.parser.core.email.EmailTransactionParser
import com.nrknithin.fintrack.parser.core.email.due.DueEscalation
import com.nrknithin.fintrack.parser.core.email.due.DueKind
import com.nrknithin.fintrack.parser.core.email.due.DueParser
import com.nrknithin.fintrack.parser.core.email.due.ParsedDue
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The email-channel counterpart to [ParserTestUtils] (`docs/parser-test-standards.md`).
 *
 * Same shape — dynamic tests, one per case, expectations declared as data — with two
 * differences the email channel forces:
 *
 *  - Cases carry a **subject** as well as a body, because several senders put a dozen
 *    templates behind one address and only the subject tells them apart.
 *  - The received time is **fixed**, not `System.currentTimeMillis()`. Email parsers
 *    resolve relative wording ("due today", "due tomorrow") against the received date,
 *    so a wall clock would make those cases pass or fail depending on the hour.
 *
 * Expectation fields left null are not asserted, matching [ExpectedTransaction]'s
 * convention: a parser is free to fill more than a case pins down.
 */
object EmailParserTestUtils {

    /**
     * 17 September 2026, 10:00 IST — inside the window the corpus in spec 10 describes,
     * so relative wording in the fixtures resolves to dates the samples actually name.
     */
    val FIXED_ZONE: ZoneId = ZoneId.of("Asia/Kolkata")
    val FIXED_RECEIVED_DATE: LocalDate = LocalDate.of(2026, 9, 17)
    val FIXED_RECEIVED_AT: Long =
        ZonedDateTime.of(FIXED_RECEIVED_DATE.atTime(10, 0), FIXED_ZONE).toInstant().toEpochMilli()

    fun message(
        from: String,
        subject: String,
        body: String,
        receivedAt: Long = FIXED_RECEIVED_AT
    ): EmailMessage = EmailMessage(from = from, subject = subject, body = body, receivedAt = receivedAt)

    // ========================================
    // Transactions
    // ========================================

    data class EmailCase(
        val name: String,
        val from: String,
        val subject: String,
        val body: String,
        val expected: ExpectedTransaction? = null,
        val shouldParse: Boolean = true,
        val receivedAt: Long = FIXED_RECEIVED_AT
    )

    fun runTransactionSuite(
        parser: EmailTransactionParser,
        cases: List<EmailCase>,
        handleCases: List<Pair<String, Boolean>> = emptyList()
    ): List<DynamicTest> {
        val tests = mutableListOf<DynamicTest>()

        cases.forEach { case ->
            tests.add(dynamicTest(case.name) {
                val parsed: ParsedTransaction? =
                    parser.parse(message(case.from, case.subject, case.body, case.receivedAt))

                if (case.shouldParse) {
                    assertNotNull(parsed, "Parser returned null but expected to parse: " + case.subject)
                    case.expected?.let { ParserTestUtils.validateResultDynamic(parsed!!, it) }
                } else {
                    assertNull(parsed, "Parser produced a transaction but should have rejected: " + parsed)
                }
            })
        }

        handleCases.forEach { (from, shouldHandle) ->
            tests.add(dynamicTest("Handle check: " + from) {
                assertEquals(shouldHandle, parser.canHandle(from), "canHandle mismatch for " + from)
            })
        }

        return tests
    }

    // ========================================
    // Dues
    // ========================================

    /** Null fields are not asserted. */
    data class ExpectedDue(
        val issuer: String? = null,
        val kind: DueKind? = null,
        val amount: BigDecimal? = null,
        val currency: String? = null,
        val dueDate: LocalDate? = null,
        val minimumDue: BigDecimal? = null,
        val accountLast4: String? = null,
        val reference: String? = null,
        val autoPay: Boolean? = null,
        val autoPayInstrument: String? = null,
        val lateAmount: BigDecimal? = null,
        val feesDetected: BigDecimal? = null,
        val escalation: DueEscalation? = null,
        val source: String? = null,
        /** Asserts the parser left the amount unknown rather than guessing one. */
        val expectNullAmount: Boolean = false
    )

    data class DueCase(
        val name: String,
        val from: String,
        val subject: String,
        val body: String,
        /** Expected dues in order. An empty list asserts the parser produced none. */
        val expected: List<ExpectedDue> = emptyList(),
        val receivedAt: Long = FIXED_RECEIVED_AT
    )

    fun runDueSuite(
        parser: DueParser,
        cases: List<DueCase>,
        handleCases: List<Pair<String, Boolean>> = emptyList()
    ): List<DynamicTest> {
        val tests = mutableListOf<DynamicTest>()

        cases.forEach { case ->
            tests.add(dynamicTest(case.name) {
                val dues = parser.parse(message(case.from, case.subject, case.body, case.receivedAt))

                assertEquals(
                    case.expected.size,
                    dues.size,
                    "Expected " + case.expected.size + " due(s) but got " + dues.size + ": " + dues
                )
                case.expected.forEachIndexed { index, expected ->
                    validateDue(dues[index], expected, "due[" + index + "]")
                }
            })
        }

        handleCases.forEach { (from, shouldHandle) ->
            tests.add(dynamicTest("Handle check: " + from) {
                assertEquals(shouldHandle, parser.canHandle(from), "canHandle mismatch for " + from)
            })
        }

        return tests
    }

    private fun validateDue(actual: ParsedDue, expected: ExpectedDue, label: String) {
        assertAll(
            label,
            { expected.issuer?.let { assertEquals(it, actual.issuer, "issuer") } },
            { expected.kind?.let { assertEquals(it, actual.kind, "kind") } },
            { expected.amount?.let { assertEquals(it, actual.amount, "amount") } },
            { expected.currency?.let { assertEquals(it, actual.currency, "currency") } },
            { expected.dueDate?.let { assertEquals(it, actual.dueDate, "dueDate") } },
            { expected.minimumDue?.let { assertEquals(it, actual.minimumDue, "minimumDue") } },
            { expected.accountLast4?.let { assertEquals(it, actual.accountLast4, "accountLast4") } },
            { expected.reference?.let { assertEquals(it, actual.reference, "reference") } },
            { expected.autoPay?.let { assertEquals(it, actual.autoPay, "autoPay") } },
            { expected.autoPayInstrument?.let { assertEquals(it, actual.autoPayInstrument, "autoPayInstrument") } },
            { expected.lateAmount?.let { assertEquals(it, actual.lateAmount, "lateAmount") } },
            { expected.feesDetected?.let { assertEquals(it, actual.feesDetected, "feesDetected") } },
            { expected.escalation?.let { assertEquals(it, actual.escalation, "escalation") } },
            { expected.source?.let { assertEquals(it, actual.source, "source") } },
            {
                if (expected.expectNullAmount) {
                    assertNull(actual.amount, "Expected no amount but got " + actual.amount)
                }
            },
            {
                // Guards spec 01 constraint C7 from the other side: a due that claims an
                // amount must claim a positive one — a zero here means a failed capture
                // reached the user as a real figure.
                actual.amount?.let {
                    assertTrue(it.signum() > 0, "Amount must be positive when stated, got " + it)
                }
            }
        )
    }
}
