package com.pennywiseai.tracker.data.email

import com.pennywiseai.tracker.data.database.entity.DueStatus
import com.pennywiseai.tracker.data.database.entity.TransactionType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * M1's settlement seam: "find an open due this transaction plausibly settles, and mark it"
 * (spec 11 §11.4 h, reduced from spec 20 §20.5).
 *
 * The cases here are the subset of spec 06 §6.3's `DueMatcher` matrix that M1 can answer without
 * commitment columns: exact amount, tolerance, amount outside, the date windows on both sides,
 * issuer match by name and by account last-4, two candidates resolving to the nearest date, late
 * computation with grace, and the two halves of "never settles a due twice". The rest of that
 * matrix — payee regexes, foreign-currency conversion, partial payments — belongs to the M2
 * implementation of [DueSettler] and to its own test.
 *
 * Every value is invented, redacted per spec 06 §6.2.
 */
class PlausibleDueSettlerTest {

    private val clock: Clock = Clock.fixed(INGEST_NOW, ZoneOffset.UTC)

    private fun settler(dao: FakeDueDao, config: SettlementConfig = SettlementConfig()) =
        PlausibleDueSettler(dao, clock, config)

    /** A card payment that names the issuer, so the issuer half of §20.5's test is satisfied. */
    private fun cardPayment(
        id: Long = 42,
        amount: BigDecimal = BigDecimal("1234.00"),
        merchantName: String = "Axis Bank Card Payment",
        type: TransactionType = TransactionType.TRANSFER,
        dateTime: LocalDateTime = at(18, 0),
        currency: String = "INR",
        accountNumber: String? = null,
        isDeleted: Boolean = false
    ) = storedTransaction(
        id = id,
        amount = amount,
        merchantName = merchantName,
        type = type,
        dateTime = dateTime,
        bankName = "HDFC Bank",
        accountNumber = accountNumber,
        currency = currency,
        isDeleted = isDeleted
    )

    // -----------------------------------------------------------------------------------------
    // The happy paths
    // -----------------------------------------------------------------------------------------

    @Test
    fun `an exact amount from a payment that names the issuer settles the due on time`() {
        val dao = FakeDueDao(listOf(openDue(id = 7, dueDate = LocalDate.of(2026, 9, 20))))

        val settled = runBlocking { settler(dao).trySettle(cardPayment()) }

        assertEquals(7L, settled)
        val due = dao.rows.getValue(7)
        assertEquals(42L, due.settledTransactionId)
        assertEquals(DueStatus.PAID_ON_TIME, due.status)
        assertEquals(LocalDateTime.of(2026, 9, 17, 19, 0), due.settledAt)
    }

    @Test
    fun `a payment after the due date and its grace is late`() {
        val dao = FakeDueDao(listOf(openDue(id = 7, dueDate = LocalDate.of(2026, 9, 10))))

        val settled = runBlocking { settler(dao).trySettle(cardPayment()) }

        assertEquals(7L, settled)
        assertEquals(DueStatus.PAID_LATE, dao.rows.getValue(7).status)
    }

    @Test
    fun `an auto-pay due debited two days late is still on time`() {
        val dao = FakeDueDao(
            listOf(openDue(id = 7, dueDate = LocalDate.of(2026, 9, 15), autoPay = true))
        )

        runBlocking { settler(dao).trySettle(cardPayment()) }

        // Two days of grace, because an e-mandate debits when the bank gets round to it.
        assertEquals(DueStatus.PAID_ON_TIME, dao.rows.getValue(7).status)
    }

    @Test
    fun `the same due paid by hand two days late is late`() {
        val dao = FakeDueDao(listOf(openDue(id = 7, dueDate = LocalDate.of(2026, 9, 15))))

        runBlocking { settler(dao).trySettle(cardPayment()) }

        assertEquals(DueStatus.PAID_LATE, dao.rows.getValue(7).status)
    }

    @Test
    fun `a bill paid two weeks early settles, as spec 20 section 20_8 requires`() {
        // The worked case: an Axis statement due 1 October, paid in full on 16 September.
        val dao = FakeDueDao(listOf(openDue(id = 7, dueDate = LocalDate.of(2026, 10, 1))))

        val settled = runBlocking {
            settler(dao).trySettle(cardPayment(dateTime = at(11, 0, day = 16)))
        }

        assertEquals(7L, settled)
        assertEquals(DueStatus.PAID_ON_TIME, dao.rows.getValue(7).status)
    }

    @Test
    fun `an account last-4 corroborates a payment whose merchant says nothing`() {
        val dao = FakeDueDao(
            listOf(openDue(id = 7, issuer = "GoDaddy", accountLast4 = "5678"))
        )

        val settled = runBlocking {
            settler(dao).trySettle(cardPayment(merchantName = "Acme Store", accountNumber = "5678"))
        }

        assertEquals(7L, settled)
    }

    @Test
    fun `paying the minimum due settles the statement`() {
        val dao = FakeDueDao(
            listOf(
                openDue(
                    id = 7,
                    amount = BigDecimal("5000.00"),
                    minimumDue = BigDecimal("250.00")
                )
            )
        )

        val settled = runBlocking { settler(dao).trySettle(cardPayment(amount = BigDecimal("250.00"))) }

        assertEquals(7L, settled)
    }

    @Test
    fun `the nearest due date wins when two dues could both be it`() {
        val dao = FakeDueDao(
            listOf(
                openDue(id = 7, dueDate = LocalDate.of(2026, 9, 20)),
                openDue(id = 8, dueDate = LocalDate.of(2026, 9, 18))
            )
        )

        val settled = runBlocking { settler(dao).trySettle(cardPayment()) }

        assertEquals(8L, settled)
        assertEquals(DueStatus.OPEN, dao.rows.getValue(7).status)
    }

    // -----------------------------------------------------------------------------------------
    // The refusals — each one is money the app would otherwise claim was settled
    // -----------------------------------------------------------------------------------------

    @Test
    fun `an amount nowhere near the bill settles nothing`() {
        val dao = FakeDueDao(listOf(openDue(id = 7)))

        val settled = runBlocking { settler(dao).trySettle(cardPayment(amount = BigDecimal("2000.00"))) }

        assertNull(settled)
        assertEquals(DueStatus.OPEN, dao.rows.getValue(7).status)
    }

    @Test
    fun `the right amount with nothing to corroborate it settles nothing`() {
        // Amount alone scores 50 in §20.5, below the threshold of 60; `requireIssuerOrAccountMatch`
        // is what stops a coincidence of figures from closing somebody's card bill.
        val dao = FakeDueDao(listOf(openDue(id = 7, issuer = "Axis Bank", accountLast4 = null)))

        val settled = runBlocking { settler(dao).trySettle(cardPayment(merchantName = "Acme Store")) }

        assertNull(settled)
    }

    @Test
    fun `a due in another currency is never settled by a rupee payment`() {
        val dao = FakeDueDao(listOf(openDue(id = 7, currency = "USD")))

        val settled = runBlocking { settler(dao).trySettle(cardPayment()) }

        assertNull(settled)
    }

    @Test
    fun `a zero-amount statement is not settled by a small payment`() {
        val dao = FakeDueDao(listOf(openDue(id = 7, amount = BigDecimal.ZERO)))

        val settled = runBlocking { settler(dao).trySettle(cardPayment(amount = BigDecimal("0.50"))) }

        assertNull(settled)
    }

    @Test
    fun `a due dated further out than earlyPayDays is outside the window`() {
        val dao = FakeDueDao(listOf(openDue(id = 7, dueDate = LocalDate.of(2026, 12, 1))))

        val settled = runBlocking { settler(dao).trySettle(cardPayment()) }

        assertNull(settled)
    }

    @Test
    fun `a transaction that already settled a due is never reused`() {
        val dao = FakeDueDao(
            listOf(
                openDue(id = 6, status = DueStatus.PAID_ON_TIME, settledTransactionId = 42),
                openDue(id = 7)
            )
        )

        val settled = runBlocking { settler(dao).trySettle(cardPayment(id = 42)) }

        assertNull(settled)
        assertEquals(DueStatus.OPEN, dao.rows.getValue(7).status)
    }

    @Test
    fun `a due that is already settled is not settled again`() {
        val dao = FakeDueDao(
            listOf(openDue(id = 7, status = DueStatus.PAID_ON_TIME, settledTransactionId = 99))
        )

        val settled = runBlocking { settler(dao).trySettle(cardPayment()) }

        assertNull(settled)
        assertEquals(99L, dao.rows.getValue(7).settledTransactionId)
    }

    @Test
    fun `money coming in settles nothing until commitments exist`() {
        val dao = FakeDueDao(listOf(openDue(id = 7)))

        val settled = runBlocking {
            settler(dao).trySettle(cardPayment(type = TransactionType.INCOME))
        }

        assertNull(settled)
    }

    @Test
    fun `a transaction the user deleted settles nothing`() {
        val dao = FakeDueDao(listOf(openDue(id = 7)))

        val settled = runBlocking { settler(dao).trySettle(cardPayment(isDeleted = true)) }

        assertNull(settled)
    }
}
