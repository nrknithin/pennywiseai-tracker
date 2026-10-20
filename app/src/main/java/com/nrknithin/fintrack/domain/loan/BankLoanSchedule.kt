package com.nrknithin.fintrack.domain.loan

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.pow

/**
 * The terms of a reducing-balance EMI loan. Any three of [principal], [annualRatePct],
 * [tenureMonths] and [emi] fix the fourth ([resolve]).
 *
 * [gstOnInterestPct] is the tax a card EMI charges on each month's interest (18% in
 * India); it is paid on top of the EMI, so it changes what leaves the account but not
 * the balance.
 *
 * [statementOutstanding] / [statementDate] re-anchor the schedule on a figure read from
 * the lender's statement (after a prepayment, or to correct drift): from the first EMI
 * due after [statementDate], the balance runs down from [statementOutstanding].
 */
data class BankLoanTerms(
    val principal: BigDecimal?,
    val annualRatePct: BigDecimal?,
    val tenureMonths: Int?,
    val emi: BigDecimal?,
    val firstEmiDate: LocalDate,
    val gstOnInterestPct: BigDecimal = BigDecimal.ZERO,
    val statementOutstanding: BigDecimal? = null,
    val statementDate: LocalDate? = null
)

/** One EMI. [payable] = [emi] + [gst] — what the account is actually debited. */
data class Instalment(
    val number: Int,
    val dueDate: LocalDate,
    val emi: BigDecimal,
    val interest: BigDecimal,
    val principalPart: BigDecimal,
    val gst: BigDecimal,
    val balanceAfter: BigDecimal
) {
    val payable: BigDecimal get() = emi + gst
}

/** The resolved terms plus the schedule, read at [today]. */
data class BankLoanProgress(
    val principal: BigDecimal,
    val annualRatePct: BigDecimal,
    val emi: BigDecimal,
    val schedule: List<Instalment>,
    val today: LocalDate
) {
    val totalEmis: Int get() = schedule.size
    val paid: List<Instalment> get() = schedule.filter { !it.dueDate.isAfter(today) }
    val remaining: List<Instalment> get() = schedule.filter { it.dueDate.isAfter(today) }
    val emisPaid: Int get() = paid.size
    val nextInstalment: Instalment? get() = remaining.firstOrNull()
    val payoffDate: LocalDate? get() = schedule.lastOrNull()?.dueDate
    val isPaidOff: Boolean get() = remaining.isEmpty()

    /** Principal still owed today (after every EMI due up to today). */
    val outstanding: BigDecimal
        get() = paid.lastOrNull()?.balanceAfter
            ?: schedule.firstOrNull()?.let { it.balanceAfter + it.principalPart }
            ?: BigDecimal.ZERO

    val remainingInterest: BigDecimal get() = remaining.sumOf { it.interest }
    val remainingGst: BigDecimal get() = remaining.sumOf { it.gst }
    val remainingPayable: BigDecimal get() = remaining.sumOf { it.payable }
}

object BankLoanSchedule {

    private const val MAX_MONTHS = 600
    private val ROUNDING_SLACK = BigDecimal.ONE
    private val MC = MathContext.DECIMAL64
    private val HUNDRED = BigDecimal(100)
    private val TWELVE_HUNDRED = BigDecimal(1200)

    /**
     * Fills in whichever of principal / rate / tenure / EMI is missing. Null when fewer
     * than three are known or they can't describe a loan (an EMI that never covers the
     * interest).
     */
    fun resolve(terms: BankLoanTerms): Resolved? {
        val p = terms.principal?.takeIf { it > BigDecimal.ZERO }
        val rate = terms.annualRatePct?.takeIf { it >= BigDecimal.ZERO }
        val n = terms.tenureMonths?.takeIf { it in 1..MAX_MONTHS }
        val emi = terms.emi?.takeIf { it > BigDecimal.ZERO }
        return when {
            p != null && rate != null && n != null ->
                Resolved(p, rate, n, emi ?: emiFor(p, rate, n))
            p != null && rate != null && emi != null ->
                tenureFor(p, rate, emi)?.let { Resolved(p, rate, it, emi) }
            p != null && n != null && emi != null ->
                rateFor(p, n, emi)?.let { Resolved(p, it, n, emi) }
            rate != null && n != null && emi != null ->
                Resolved(principalFor(rate, n, emi), rate, n, emi)
            else -> null
        }
    }

    data class Resolved(
        val principal: BigDecimal,
        val annualRatePct: BigDecimal,
        val tenureMonths: Int,
        val emi: BigDecimal
    )

    /** The schedule and where it stands at [today]; null when the terms can't be resolved. */
    fun progress(terms: BankLoanTerms, today: LocalDate): BankLoanProgress? {
        val resolved = resolve(terms) ?: return null
        val monthlyRate = resolved.annualRatePct.divide(TWELVE_HUNDRED, MC)
        val gstRate = terms.gstOnInterestPct.divide(HUNDRED, MC)
        val anchorDate = terms.statementDate
        val anchorBalance = terms.statementOutstanding

        val schedule = mutableListOf<Instalment>()
        var balance = resolved.principal.money()
        var number = 0
        while (balance > BigDecimal.ZERO && number < MAX_MONTHS) {
            number++
            val due = dueDate(terms.firstEmiDate, number - 1)
            // Re-anchor on the statement figure at the first EMI after the statement.
            if (anchorDate != null && anchorBalance != null && due.isAfter(anchorDate) &&
                schedule.none { it.dueDate.isAfter(anchorDate) }
            ) {
                balance = anchorBalance.money()
                if (balance <= BigDecimal.ZERO) break
            }
            val interest = (balance * monthlyRate).money()
            // A rounded EMI leaves a few paise over; like the lenders, fold that into
            // the last EMI rather than adding a token extra one.
            val isLast = balance + interest <= resolved.emi.money() + ROUNDING_SLACK
            val principalPart = if (isLast) balance else resolved.emi.money() - interest
            val emi = if (isLast) balance + interest else resolved.emi.money()
            balance -= principalPart
            schedule += Instalment(
                number = number,
                dueDate = due,
                emi = emi,
                interest = interest,
                principalPart = principalPart,
                gst = (interest * gstRate).money(),
                balanceAfter = balance
            )
        }
        return BankLoanProgress(resolved.principal, resolved.annualRatePct, resolved.emi, schedule, today)
    }

    /** EMI number [index] (0-based) falls on the first EMI's day of month, clamped to short months. */
    fun dueDate(firstEmiDate: LocalDate, index: Int): LocalDate {
        val month = YearMonth.from(firstEmiDate).plusMonths(index.toLong())
        return month.atDay(firstEmiDate.dayOfMonth.coerceAtMost(month.lengthOfMonth()))
    }

    fun emiFor(principal: BigDecimal, annualRatePct: BigDecimal, months: Int): BigDecimal {
        val r = annualRatePct.toDouble() / 1200.0
        val p = principal.toDouble()
        val emi = if (r == 0.0) p / months else p * r * (1 + r).pow(months) / ((1 + r).pow(months) - 1)
        return BigDecimal(emi).money()
    }

    private fun tenureFor(principal: BigDecimal, annualRatePct: BigDecimal, emi: BigDecimal): Int? {
        val r = annualRatePct.toDouble() / 1200.0
        val p = principal.toDouble()
        val e = emi.toDouble()
        val months = if (r == 0.0) p / e else {
            if (e <= p * r) return null // the EMI never covers the interest
            -ln(1 - p * r / e) / ln(1 + r)
        }
        // A hundredth of a month over is an EMI rounded to the paisa, not an extra EMI.
        return ceil(months - 0.01).toInt().takeIf { it in 1..MAX_MONTHS }
    }

    /** Annual rate (%) for which [months] EMIs of [emi] repay [principal] — by bisection. */
    private fun rateFor(principal: BigDecimal, months: Int, emi: BigDecimal): BigDecimal? {
        val p = principal.toDouble()
        val e = emi.toDouble()
        if (e * months < p) return null // pays back less than was borrowed
        fun emiAt(annual: Double): Double {
            val r = annual / 1200.0
            return if (r == 0.0) p / months else p * r * (1 + r).pow(months) / ((1 + r).pow(months) - 1)
        }
        var lo = 0.0
        var hi = 100.0
        if (emiAt(hi) < e) return null
        repeat(100) {
            val mid = (lo + hi) / 2
            if (emiAt(mid) < e) lo = mid else hi = mid
        }
        return BigDecimal((lo + hi) / 2).setScale(2, RoundingMode.HALF_UP)
    }

    private fun principalFor(annualRatePct: BigDecimal, months: Int, emi: BigDecimal): BigDecimal {
        val r = annualRatePct.toDouble() / 1200.0
        val e = emi.toDouble()
        val p = if (r == 0.0) e * months else e * (1 - (1 + r).pow(-months)) / r
        return BigDecimal(p).money()
    }

    private fun BigDecimal.money(): BigDecimal = setScale(2, RoundingMode.HALF_UP)
}
