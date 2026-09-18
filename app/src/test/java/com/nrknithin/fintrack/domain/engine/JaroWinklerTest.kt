package com.nrknithin.fintrack.domain.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The L3 merchant metric (spec 14 §14.2).
 *
 * Two kinds of case: the textbook pairs that pin the arithmetic to the published Jaro-Winkler
 * values, and the merchant-shaped pairs that pin the 0.85 threshold to behaviour dedup depends
 * on — one spelling of a shop must clear it, two different shops must not.
 */
class JaroWinklerTest {

    /** The threshold `settings.dedup.merchantSimilarity` defaults to (spec 28 §28.9). */
    private val threshold = 0.85

    @Test
    fun `identical strings score one`() {
        assertEquals(1.0, JaroWinkler.similarity("acme store", "acme store"), 0.0)
    }

    @Test
    fun `an empty string scores zero`() {
        assertEquals(0.0, JaroWinkler.similarity("", "acme store"), 0.0)
        assertEquals(0.0, JaroWinkler.similarity("acme store", ""), 0.0)
    }

    @Test
    fun `two empty strings are equal, not unknown`() {
        assertEquals(1.0, JaroWinkler.similarity("", ""), 0.0)
    }

    @Test
    fun `strings with nothing in common score zero`() {
        assertEquals(0.0, JaroWinkler.similarity("abc", "xyz"), 0.0)
    }

    @Test
    fun `the published value for the transposition case`() {
        // The textbook pair: one transposition, three shared leading characters.
        assertEquals(0.961, JaroWinkler.similarity("martha", "marhta"), 0.001)
    }

    @Test
    fun `the published value for the prefix case`() {
        assertEquals(0.813, JaroWinkler.similarity("dixon", "dicksonx"), 0.001)
    }

    @Test
    fun `the metric is symmetric`() {
        assertEquals(
            JaroWinkler.similarity("acme store", "acme stores"),
            JaroWinkler.similarity("acme stores", "acme store"),
            0.0
        )
    }

    @Test
    fun `a prefix bonus is never given to strings that barely match`() {
        // Same two leading characters, nothing else: below the boost threshold, so the score
        // stays at its Jaro value instead of being nudged over the line by a coincidence.
        val similarity = JaroWinkler.similarity("acme", "acropolis theatre")
        assertTrue("expected a low score, got $similarity", similarity < threshold)
    }

    @Test
    fun `one merchant spelled two ways clears the threshold`() {
        assertTrue(JaroWinkler.similarity("acme store", "acme stores") >= threshold)
        assertTrue(JaroWinkler.similarity("acme store", "acme store online") >= threshold)
        assertTrue(JaroWinkler.similarity("acme store", "acme storee") >= threshold)
    }

    @Test
    fun `two different merchants stay under the threshold`() {
        assertTrue(JaroWinkler.similarity("acme store", "zenith travels") < threshold)
        assertTrue(JaroWinkler.similarity("acme store", "bright cafe") < threshold)
    }

    @Test
    fun `a score is always inside the unit interval`() {
        val pairs = listOf(
            "acme store" to "acme stores",
            "a" to "acme store",
            "acme store" to "a",
            "zenith travels" to "acme store",
            "abcdefghij" to "jihgfedcba"
        )
        for ((a, b) in pairs) {
            val score = JaroWinkler.similarity(a, b)
            assertTrue("$a vs $b scored $score", score in 0.0..1.0)
        }
    }
}
