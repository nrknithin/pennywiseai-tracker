package com.nrknithin.fintrack.parser.core.text

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * DateParse against the date shapes each sender in spec 10/12 actually emits.
 */
class DateParseTest {

    private data class DateCase(val name: String, val raw: String, val expected: String)

    private data class SlashCase(
        val name: String,
        val raw: String,
        val preferDMY: Boolean,
        val expected: String
    )

    private data class TimeCase(
        val name: String,
        val rawDate: String,
        val rawTime: String,
        val expected: String
    )

    @TestFactory
    fun `date-only shapes from every sender`(): List<DynamicTest> = listOf(
        DateCase("Axis dd-MM-yy", "16-09-26", "2026-09-16"),
        DateCase("HDFC mandate charge dd-MM-yyyy", "16-09-2026", "2026-09-16"),
        DateCase("HDFC EMI dd-MMM-yyyy uppercase", "07-SEP-2026", "2026-09-07"),
        DateCase("HDFC e-mandate dd/MM/yyyy", "08/09/2026", "2026-09-08"),
        DateCase("HDFC card d MMM, yyyy", "15 Sep, 2026", "2026-09-15"),
        DateCase("Airtel d MMM yyyy", "28 Sep 2026", "2026-09-28"),
        DateCase("ICICI MMM d, yyyy", "Sep 17, 2026", "2026-09-17"),
        DateCase("CRED MMMM d, yyyy", "September 17, 2026", "2026-09-17"),
        DateCase("ordinal suffix stripped", "17th September 2026", "2026-09-17"),
        DateCase("ISO", "2026-09-17", "2026-09-17")
    ).map { case ->
        dynamicTest(case.name) {
            assertEquals(
                LocalDate.parse(case.expected),
                DateParse.parseDate(case.raw),
                "parsing '" + case.raw + "'"
            )
        }
    }

    @TestFactory
    fun `US ordering is honoured for senders flagged preferDMY false`(): List<DynamicTest> = listOf(
        // Unambiguous: a day past 12 fails day-first and falls through either way.
        SlashCase("Xsolla 09/17/2026", "09/17/2026", preferDMY = false, expected = "2026-09-17"),
        // Ambiguous: this is the case that makes the flag matter. 03/09 is
        // 9 March to Xsolla and 3 September to an Indian sender.
        SlashCase("Xsolla 03/09/2026 is 9 March", "03/09/2026", preferDMY = false, expected = "2026-03-09"),
        SlashCase("Indian 03/09/2026 is 3 September", "03/09/2026", preferDMY = true, expected = "2026-09-03")
    ).map { case ->
        dynamicTest(case.name) {
            assertEquals(
                LocalDate.parse(case.expected),
                DateParse.parseDate(case.raw, preferDMY = case.preferDMY),
                "parsing '" + case.raw + "' with preferDMY=" + case.preferDMY
            )
        }
    }

    @TestFactory
    fun `date and time captures combine`(): List<DynamicTest> = listOf(
        TimeCase("HDFC card purchase", "15 Sep, 2026", "16:14:34", "2026-09-15T16:14:34"),
        TimeCase("Axis card purchase", "15-09-2026", "12:04:06", "2026-09-15T12:04:06"),
        TimeCase("OneCard", "15/09/2026", "17:53:59", "2026-09-15T17:53:59")
    ).map { case ->
        dynamicTest(case.name) {
            assertEquals(
                LocalDateTime.parse(case.expected),
                DateParse.parse(case.rawDate, case.rawTime),
                "combining '" + case.rawDate + "' + '" + case.rawTime + "'"
            )
        }
    }

    @TestFactory
    fun `single-string date-times`(): List<DynamicTest> = listOf(
        DateCase("Google Play am/pm with zone", "1 Sep 2026 11:22:33 am IST", "2026-09-01T11:22:33"),
        DateCase("HDFC card with the word at", "15 Sep, 2026 at 16:14:34", "2026-09-15T16:14:34"),
        DateCase("ICICI with the word at", "Sep 17, 2026 at 12:04:06", "2026-09-17T12:04:06")
    ).map { case ->
        dynamicTest(case.name) {
            assertEquals(
                LocalDateTime.parse(case.expected),
                DateParse.parse(case.raw),
                "parsing '" + case.raw + "'"
            )
        }
    }

    @TestFactory
    fun `date-only input yields midnight, and nonsense yields null`(): List<DynamicTest> = listOf(
        dynamicTest("date only becomes midnight") {
            assertEquals(LocalDateTime.parse("2026-09-16T00:00:00"), DateParse.parse("16-09-26"))
        },
        dynamicTest("empty is null") { assertNull(DateParse.parseDate("")) },
        dynamicTest("prose is null") { assertNull(DateParse.parseDate("sometime next week")) },
        dynamicTest("impossible date is null") { assertNull(DateParse.parseDate("32-13-2026")) }
    )
}
