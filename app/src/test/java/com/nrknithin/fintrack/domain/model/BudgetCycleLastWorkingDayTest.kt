package com.nrknithin.fintrack.domain.model

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/**
 * A salary paid on the last working day: the cycle starts on each month's last
 * Monday–Friday, stepping back past any listed holiday. Month ends used here:
 * 31 Aug 2026 Mon, 30 Sep Wed, 31 Oct Sat, 30 Nov Mon, 31 Jan 2027 Sun,
 * 28 Feb 2027 Sun, 31 Mar 2027 Wed.
 */
class BudgetCycleLastWorkingDayTest {

    private val payday = BudgetCycle.LAST_WORKING_DAY

    @After
    fun resetHolidays() {
        BudgetCycle.holidays = emptySet()
    }

    @Test
    fun `a month ending on a weekday pays on that day`() {
        assertEquals(LocalDate.of(2026, 9, 30), BudgetCycle.lastWorkingDay(YearMonth.of(2026, 9)))
    }

    @Test
    fun `a month ending on a weekend pays on the Friday before`() {
        assertEquals(LocalDate.of(2026, 10, 30), BudgetCycle.lastWorkingDay(YearMonth.of(2026, 10)))
        assertEquals(LocalDate.of(2027, 1, 29), BudgetCycle.lastWorkingDay(YearMonth.of(2027, 1)))
        assertEquals(LocalDate.of(2027, 2, 26), BudgetCycle.lastWorkingDay(YearMonth.of(2027, 2)))
    }

    @Test
    fun `a holiday on the last working day moves payday to the working day before`() {
        BudgetCycle.holidays = setOf(LocalDate.of(2026, 10, 30))
        assertEquals(LocalDate.of(2026, 10, 29), BudgetCycle.lastWorkingDay(YearMonth.of(2026, 10)))
    }

    @Test
    fun `mid-month the cycle runs from last month's payday to the day before this one`() {
        val (start, end) = BudgetCycle.currentCycle(LocalDate.of(2026, 10, 5), payday)
        assertEquals(LocalDate.of(2026, 9, 30), start)
        assertEquals(LocalDate.of(2026, 10, 29), end)
    }

    @Test
    fun `on payday a new cycle starts`() {
        val (start, end) = BudgetCycle.currentCycle(LocalDate.of(2026, 10, 30), payday)
        assertEquals(LocalDate.of(2026, 10, 30), start)
        assertEquals(LocalDate.of(2026, 11, 29), end)
    }

    @Test
    fun `a holiday brings the next cycle forward a day`() {
        BudgetCycle.holidays = setOf(LocalDate.of(2026, 10, 30))

        val (before, beforeEnd) = BudgetCycle.currentCycle(LocalDate.of(2026, 10, 28), payday)
        assertEquals(LocalDate.of(2026, 9, 30), before)
        assertEquals(LocalDate.of(2026, 10, 28), beforeEnd)

        val (start, end) = BudgetCycle.currentCycle(LocalDate.of(2026, 10, 29), payday)
        assertEquals(LocalDate.of(2026, 10, 29), start)
        assertEquals(LocalDate.of(2026, 11, 29), end)
    }

    @Test
    fun `a February payday cycle runs to the day before the March payday`() {
        val (start, end) = BudgetCycle.currentCycle(LocalDate.of(2027, 2, 27), payday)
        assertEquals(LocalDate.of(2027, 2, 26), start)
        assertEquals(LocalDate.of(2027, 3, 30), end)
    }

    @Test
    fun `previous and next cycles tile with no gaps`() {
        val current = BudgetCycle.currentCycle(LocalDate.of(2026, 10, 5), payday)

        val (prevStart, prevEnd) = BudgetCycle.previousCycle(current, payday)
        assertEquals(LocalDate.of(2026, 8, 31), prevStart)
        assertEquals(LocalDate.of(2026, 9, 29), prevEnd)

        val (nextStart, nextEnd) = BudgetCycle.nextCycle(current, payday)
        assertEquals(LocalDate.of(2026, 10, 30), nextStart)
        assertEquals(LocalDate.of(2026, 11, 29), nextEnd)
    }

    @Test
    fun `clamping keeps the payday value and still bounds real days`() {
        assertEquals(payday, BudgetCycle.clampStartDay(payday))
        assertEquals(1, BudgetCycle.clampStartDay(-3))
        assertEquals(31, BudgetCycle.clampStartDay(40))
    }
}
