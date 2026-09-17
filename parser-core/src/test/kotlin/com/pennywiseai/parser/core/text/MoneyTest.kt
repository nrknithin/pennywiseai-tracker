package com.pennywiseai.parser.core.text

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import java.math.BigDecimal

/**
 * Money.parse against every amount shape spec 12 names, including the grouping
 * repair case (`2,53471.86`) and HDFC's `Rs. INR` double prefix.
 */
class MoneyTest {

    private data class Case(
        val name: String,
        val raw: String,
        val amount: String?,
        val currency: String = "INR"
    )

    @TestFactory
    fun `money parses every shape in the corpus`(): List<DynamicTest> = listOf(
        // --- grouping ---------------------------------------------------------
        Case("Indian grouping", "2,53,471.86", "253471.86"),
        Case("Western grouping", "253,471.86", "253471.86"),
        Case("broken grouping is repaired", "2,53471.86", "253471.86"),
        Case("no grouping", "253471.86", "253471.86"),
        Case("EMI template grouped", "21,538.00", "21538.00"),
        Case("EMI template ungrouped", "21538.00", "21538.00"),

        // --- currency tokens --------------------------------------------------
        Case("rupee sign", "\u20B9 1,845", "1599.00"),
        Case("Rs with period", "Rs. 14,660.00", "14660.00"),
        Case("Rs without period", "Rs 845", "845.00"),
        Case("bare INR", "INR 214", "106.00"),
        Case("HDFC double prefix", "Rs. INR 21,538.00", "21538.00"),
        Case("dollar sign", "$7.00", "7.00", "USD"),
        Case("USD code", "USD 7", "7.00", "USD"),
        Case("euro sign", "\u20AC 12.50", "12.50", "EUR"),

        // --- shapes that must not be guessed at --------------------------------
        Case("empty", "", null),
        Case("no digits", "Rs.", null),
        Case("two decimal points", "1.2.3", null),
        Case("three decimal places is not money", "12.345", null),

        // --- integers and single-decimal ---------------------------------------
        Case("integer only", "500", "500.00"),
        Case("single decimal place", "3417.5", "3417.50"),
        Case("zero", "0", "0.00")
    ).map { case ->
        dynamicTest(case.name) {
            val parsed = Money.parse(case.raw)
            if (case.amount == null) {
                assertNull(parsed, "Expected null for '${case.raw}' but got $parsed")
            } else {
                requireNotNull(parsed) { "Expected '${case.raw}' to parse" }
                assertEquals(BigDecimal(case.amount), parsed.amount, "amount for '${case.raw}'")
                assertEquals(case.currency, parsed.currency, "currency for '${case.raw}'")
            }
        }
    }

    @TestFactory
    fun `currency defaults to INR when no token is present`(): List<DynamicTest> = listOf(
        "500" to "INR",
        "1,234.56" to "INR"
    ).map { (raw, expected) ->
        dynamicTest("'$raw' -> $expected") {
            assertEquals(expected, Money.currencyOf(raw))
        }
    }
}
