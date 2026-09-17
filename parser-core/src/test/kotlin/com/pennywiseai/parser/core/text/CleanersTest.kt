package com.pennywiseai.parser.core.text

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * Merchant canonicalisation decides whether two records describe one purchase, so its failure
 * modes are "the same charge counted twice" and "two charges silently merged". Both are tested.
 */
class CleanersTest {

    private data class Case(val name: String, val raw: String?, val expected: String?)

    @TestFactory
    fun `merchant names reduce to a comparable key`(): List<DynamicTest> = listOf(
        Case("plain name", "ACME STORE", "acme store"),
        Case("UPI rail prefix", "UPI-ACME STORE", "acme store"),
        Case("UPI slash prefix", "UPI/ACME STORE", "acme store"),
        Case("VPA prefix", "VPA ACME STORE", "acme store"),
        Case("POS prefix", "POS ACME STORE", "acme store"),
        Case("trailing VPA handle", "ACME STORE someone@upi", "acme store"),
        Case("card-network asterisk", "ACME RETAIL *DEMO", "acme retail demo"),
        Case("masked digits dropped", "ACME STORE XX1234", "acme store"),
        Case("punctuation stripped", "ACME-STORE, LTD.", "acme store ltd"),
        Case("whitespace collapsed", "ACME    STORE", "acme store"),
        Case("already clean", "acme store", "acme store"),

        // The distinction that keeps unrelated charges apart.
        Case("null in, null out", null, null),
        Case("empty is null", "", null),
        Case("whitespace only is null", "   ", null),
        Case("punctuation only is null, not empty string", "***", null)
    ).map { case ->
        dynamicTest(case.name) {
            assertEquals(case.expected, Cleaners.cleanMerchant(case.raw), "cleaning '" + case.raw + "'")
        }
    }

    @Test
    fun `two unknown merchants do not compare equal`() {
        // If both cleaned to "" they would match, and spec 14 §14.3 L3 would merge two
        // unrelated same-amount charges on the same day. Null is what prevents that.
        assertNull(Cleaners.cleanMerchant(null))
        assertNull(Cleaners.cleanMerchant(""))
        assertEquals(0.0, Cleaners.merchantSimilarity(null, null), 0.0001)
        assertEquals(0.0, Cleaners.merchantSimilarity("", ""), 0.0001)
    }

    @TestFactory
    fun `similarity clears the 0_85 bar for truncations of one name`(): List<DynamicTest> = listOf(
        // Banks truncate merchant names differently per channel; these must still match.
        Triple("truncated tail", "DEMO MART TICKET COUNT", "DEMO MART TICKET"),
        Triple("rail prefix vs none", "UPI-ACME STORE", "ACME STORE"),
        Triple("case and spacing", "Acme   Store", "ACME STORE"),
        Triple("trailing handle vs none", "ACME STORE someone@upi", "ACME STORE")
    ).map { (name, a, b) ->
        dynamicTest(name) {
            val score = Cleaners.merchantSimilarity(a, b)
            assertTrue(
                score >= 0.85,
                "Expected '" + a + "' ~ '" + b + "' to score >= 0.85 for spec 14 L3, got " + score
            )
        }
    }

    @TestFactory
    fun `similarity stays below the bar for genuinely different merchants`(): List<DynamicTest> = listOf(
        Triple("unrelated names", "ACME STORE", "ZENITH FUEL"),
        Triple("different brands", "DEMO MART", "SAMPLE SERVICES"),
        Triple("short and unrelated", "JET CAB", "BOOK DEPOT")
    ).map { (name, a, b) ->
        dynamicTest(name) {
            val score = Cleaners.merchantSimilarity(a, b)
            assertTrue(
                score < 0.85,
                "Expected '" + a + "' vs '" + b + "' to score < 0.85 so they never merge, got " + score
            )
        }
    }

    @Test
    fun `identical names score exactly one`() {
        assertEquals(1.0, Cleaners.merchantSimilarity("ACME STORE", "acme store"), 0.0001)
    }

    @Test
    fun `cleaning is idempotent`() {
        // The key is stored and compared later, so cleaning a cleaned name must not drift.
        val once = Cleaners.cleanMerchant("UPI-ACME STORE someone@upi")
        val twice = Cleaners.cleanMerchant(once)
        assertEquals(once, twice)
    }

    @Test
    fun `distinct merchants keep distinct keys`() {
        assertNotEquals(Cleaners.cleanMerchant("ACME STORE"), Cleaners.cleanMerchant("ACME FUEL"))
    }
}
