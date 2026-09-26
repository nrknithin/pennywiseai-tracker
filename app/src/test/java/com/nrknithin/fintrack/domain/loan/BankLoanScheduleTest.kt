package com.nrknithin.fintrack.domain.loan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class BankLoanScheduleTest {

    // A 12-month card EMI at 18% p.a. with 18% GST on interest — the shape of an
    // Indian credit-card EMI conversion. Figures are illustrative.
    private val cardEmi = BankLoanTerms(
        principal = BigDecimal("60000"),
        annualRatePct = BigDecimal("18"),
        tenureMonths = 12,
        emi = null,
        firstEmiDate = LocalDate.of(2026, 6, 17),
        gstOnInterestPct = BigDecimal("18")
    )

    private fun assertNear(expected: String, actual: BigDecimal, paise: Int = 5) {
        val diff = (BigDecimal(expected) - actual).abs()
        assertTrue("expected ≈$expected, was $actual", diff <= BigDecimal(paise).movePointLeft(2))
    }

    @Test
    fun `card EMI schedule splits interest, principal and GST each month`() {
        val status = BankLoanSchedule.progress(cardEmi, LocalDate.of(2026, 9, 26))!!
        val s = status.schedule
        assertEquals(BigDecimal("5500.80"), status.emi)
        assertEquals(12, s.size)

        assertEquals(BigDecimal("900.00"), s[0].interest) // 1.5% of 60,000
        assertEquals(BigDecimal("162.00"), s[0].gst)      // 18% of the interest
        assertEquals(BigDecimal("5662.80"), s[0].payable)
        assertEquals(BigDecimal("55399.20"), s[0].balanceAfter)

        assertEquals(LocalDate.of(2026, 10, 17), s[4].dueDate)
        assertEquals(BigDecimal("617.68"), s[4].interest)
        assertEquals(BigDecimal("5611.98"), s[4].payable)
        assertEquals(BigDecimal("36295.45"), s[4].balanceAfter)

        // The last EMI absorbs the rounding so the balance lands on exactly zero.
        assertEquals(LocalDate.of(2027, 5, 17), s[11].dueDate)
        assertEquals(BigDecimal("5500.79"), s[11].emi)
        assertEquals(BigDecimal("0.00"), s[11].balanceAfter)

        assertEquals(BigDecimal("66009.59"), s.sumOf { it.emi })
        assertEquals(BigDecimal("6009.59"), s.sumOf { it.interest })
        assertEquals(BigDecimal("1081.73"), s.sumOf { it.gst })
    }

    @Test
    fun `status counts EMIs due up to today as paid`() {
        val status = BankLoanSchedule.progress(cardEmi, LocalDate.of(2026, 9, 26))!!
        assertEquals(4, status.emisPaid) // Jun, Jul, Aug, Sep 17
        assertEquals(LocalDate.of(2026, 10, 17), status.nextInstalment!!.dueDate)
        assertEquals(status.schedule[3].balanceAfter, status.outstanding)
        assertEquals(LocalDate.of(2027, 5, 17), status.payoffDate)
        assertTrue(!status.isPaidOff)
    }

    @Test
    fun `before the first EMI the whole principal is outstanding`() {
        val status = BankLoanSchedule.progress(cardEmi, LocalDate.of(2026, 6, 1))!!
        assertEquals(0, status.emisPaid)
        assertEquals(BigDecimal("60000.00"), status.outstanding)
    }

    @Test
    fun `after the last EMI the loan is paid off`() {
        val status = BankLoanSchedule.progress(cardEmi, LocalDate.of(2027, 6, 1))!!
        assertTrue(status.isPaidOff)
        assertEquals(BigDecimal("0.00"), status.outstanding)
        assertNull(status.nextInstalment)
    }

    @Test
    fun `any three of principal, rate, tenure and EMI fix the fourth`() {
        val base = BankLoanTerms(null, null, null, null, LocalDate.of(2026, 10, 3))

        val emi = BankLoanSchedule.resolve(base.copy(principal = BigDecimal("300000"), annualRatePct = BigDecimal("12"), tenureMonths = 24))!!
        assertEquals(BigDecimal("14122.04"), emi.emi)

        val tenure = BankLoanSchedule.resolve(base.copy(principal = BigDecimal("300000"), annualRatePct = BigDecimal("12"), emi = BigDecimal("14122.04")))!!
        assertEquals(24, tenure.tenureMonths)

        val rate = BankLoanSchedule.resolve(base.copy(principal = BigDecimal("300000"), tenureMonths = 24, emi = BigDecimal("14122.04")))!!
        assertEquals(BigDecimal("12.00"), rate.annualRatePct)

        val principal = BankLoanSchedule.resolve(base.copy(annualRatePct = BigDecimal("12"), tenureMonths = 24, emi = BigDecimal("14122.04")))!!
        assertNear("300000", principal.principal, paise = 100)
    }

    @Test
    fun `too little information or an EMI below the interest gives no schedule`() {
        val base = BankLoanTerms(BigDecimal("300000"), null, null, BigDecimal("12228"), LocalDate.of(2026, 10, 3))
        assertNull(BankLoanSchedule.progress(base, LocalDate.of(2026, 9, 26)))

        val emiTooSmall = base.copy(annualRatePct = BigDecimal("12")) // interest alone is 3,000/month
        assertNull(BankLoanSchedule.resolve(emiTooSmall.copy(emi = BigDecimal("2000"))))
    }

    @Test
    fun `a zero-rate loan divides the principal evenly`() {
        val terms = BankLoanTerms(BigDecimal("12000"), BigDecimal.ZERO, 12, null, LocalDate.of(2026, 1, 5))
        val status = BankLoanSchedule.progress(terms, LocalDate.of(2026, 1, 1))!!
        assertEquals(12, status.totalEmis)
        assertTrue(status.schedule.all { it.emi == BigDecimal("1000.00") && it.interest.signum() == 0 })
    }

    @Test
    fun `a statement figure re-anchors the balance from the next EMI`() {
        // A prepayment brought the balance down to 20,000 after the Sep EMI.
        val anchored = cardEmi.copy(
            statementOutstanding = BigDecimal("20000"),
            statementDate = LocalDate.of(2026, 9, 20)
        )
        val status = BankLoanSchedule.progress(anchored, LocalDate.of(2026, 9, 26))!!
        val oct = status.schedule[4]
        assertEquals(LocalDate.of(2026, 10, 17), oct.dueDate)
        assertEquals(BigDecimal("300.00"), oct.interest) // 1.5% of 20,000
        assertTrue("fewer EMIs after the prepayment", status.totalEmis < 12)
        assertEquals(BigDecimal("0.00"), status.schedule.last().balanceAfter)
    }

    @Test
    fun `EMIs on the 31st fall on the last day of shorter months`() {
        val first = LocalDate.of(2027, 1, 31)
        assertEquals(LocalDate.of(2027, 2, 28), BankLoanSchedule.dueDate(first, 1))
        assertEquals(LocalDate.of(2027, 3, 31), BankLoanSchedule.dueDate(first, 2))
    }
}
