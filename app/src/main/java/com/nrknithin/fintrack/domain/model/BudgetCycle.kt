package com.nrknithin.fintrack.domain.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * A user-configurable "monthly" budget cycle that does not have to align with the
 * calendar month.
 *
 * For example, with [startDay] = 25 the current cycle spans from the 25th of the
 * previous month through the 24th of the current month.
 *
 * Days are 1..31. Months that don't have the requested day (e.g. February with
 * [startDay] = 30) clamp the cycle start to the last day of that month — so a
 * 30th-of-the-month start yields a Feb-28 / Feb-29 start, and the cycle still
 * ends on the day before the next cycle's start.
 *
 * [startDay] may also be [LAST_WORKING_DAY]: the cycle then starts on each month's
 * last working day (Monday–Friday, skipping [holidays]) — a salary paid on the last
 * working day, whatever date that falls on.
 *
 * Used by the budget repositories and the home/analytics surfaces to translate a
 * "month" (a [YearMonth] the user navigated to) into the actual spend window.
 */
object BudgetCycle {

    const val MIN_START_DAY = 1
    const val MAX_START_DAY = 31
    const val DEFAULT_START_DAY = 1

    /** Start-day value meaning "each month's last working day" (payday). */
    const val LAST_WORKING_DAY = -1

    /**
     * Bank holidays that move a [LAST_WORKING_DAY] cycle start earlier. Loaded from the
     * user's list at startup and replaced whenever it changes (PaydayHolidaysStore).
     */
    @Volatile
    var holidays: Set<LocalDate> = emptySet()

    /**
     * Coerces an arbitrary integer to the valid range [[MIN_START_DAY], [MAX_START_DAY]],
     * leaving [LAST_WORKING_DAY] as it is.
     * Useful when reading a user-typed or migration-provided value.
     */
    fun clampStartDay(value: Int): Int =
        if (value == LAST_WORKING_DAY) value else value.coerceIn(MIN_START_DAY, MAX_START_DAY)

    /** The last Monday–Friday of [month] that isn't one of [holidays]. */
    fun lastWorkingDay(month: YearMonth, holidays: Set<LocalDate> = this.holidays): LocalDate {
        var day = month.atEndOfMonth()
        while (day.dayOfMonth > 1 &&
            (day.dayOfWeek == DayOfWeek.SATURDAY || day.dayOfWeek == DayOfWeek.SUNDAY || day in holidays)
        ) {
            day = day.minusDays(1)
        }
        return day
    }

    /** The day in [month] on which a cycle with [startDay] begins. */
    fun cycleStartIn(month: YearMonth, startDay: Int): LocalDate {
        val safeStart = clampStartDay(startDay)
        return if (safeStart == LAST_WORKING_DAY) {
            lastWorkingDay(month)
        } else {
            month.atDay(1).withDayOfMonthSafe(safeStart)
        }
    }

    /**
     * The cycle that contains [today], for a cycle that starts on [startDay].
     *
     * If today is on or after this month's cycle start the cycle started this month;
     * otherwise it started in the previous month.
     *
     * @return pair of (cycle start inclusive, cycle end inclusive).
     */
    fun currentCycle(today: LocalDate, startDay: Int): Pair<LocalDate, LocalDate> {
        val month = YearMonth.from(today)
        val candidateStart = cycleStartIn(month, startDay)
        val start = if (!today.isBefore(candidateStart)) {
            candidateStart
        } else {
            cycleStartIn(month.minusMonths(1), startDay)
        }
        val end = nextCycleStart(start, startDay).minusDays(1)
        return start to end
    }

    /**
     * The cycle immediately before [cycle]. The returned start is the previous month's
     * cycle start — clamped to the month's length so a `startDay = 31` doesn't overflow
     * into March — which preserves the same start-day cadence even across short months.
     */
    fun previousCycle(cycle: Pair<LocalDate, LocalDate>, startDay: Int): Pair<LocalDate, LocalDate> {
        // The previous cycle is "last month's start to one day before the current
        // cycle's start".
        val prevStart = cycleStartIn(YearMonth.from(cycle.first).minusMonths(1), startDay)
        val end = cycle.first.minusDays(1)
        return prevStart to end
    }

    /**
     * The cycle immediately after [cycle]. The next start is always one [nextCycleStart]
     * step past [cycle.first], so cycles never overlap and never have gaps.
     */
    fun nextCycle(cycle: Pair<LocalDate, LocalDate>, startDay: Int): Pair<LocalDate, LocalDate> {
        val nextStart = nextCycleStart(cycle.first, startDay)
        val end = nextCycleStart(nextStart, startDay).minusDays(1)
        return nextStart to end
    }

    /**
     * The first day of the cycle that *starts* after [reference] for the given
     * [startDay]. If [reference] is on or after its own month's cycle start then the
     * answer is next month's; otherwise it is the same month's.
     *
     * This is the cadence primitive — every other helper in this object is built on
     * it, which guarantees the cycles tile the timeline with no overlaps or gaps.
     */
    fun nextCycleStart(reference: LocalDate, startDay: Int): LocalDate {
        val month = YearMonth.from(reference)
        val candidate = cycleStartIn(month, startDay)
        return if (!reference.isBefore(candidate)) {
            cycleStartIn(month.plusMonths(1), startDay)
        } else {
            candidate
        }
    }

    /**
     * Returns the [YearMonth] in which the cycle that *contains* [today] starts.
     * This is the value callers should pass to historical month navigation (the
     * Budget Groups screen) so the user lands on their actual current cycle, not
     * on the calendar month that happens to contain today's date.
     */
    fun currentCycleStartYearMonth(today: LocalDate, startDay: Int): YearMonth {
        val month = YearMonth.from(today)
        val candidate = cycleStartIn(month, startDay)
        return if (!today.isBefore(candidate)) month else month.minusMonths(1)
    }

    /**
     * `LocalDate.withDayOfMonth(...)` throws when the requested day exceeds the
     * month's length. This helper clamps to the last day of the month so a
     * `startDay = 31` works for February, April, etc.
     */
    private fun LocalDate.withDayOfMonthSafe(day: Int): LocalDate {
        val max = lengthOfMonth()
        return withDayOfMonth(day.coerceAtMost(max))
    }
}
