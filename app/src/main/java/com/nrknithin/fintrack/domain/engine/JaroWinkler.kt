package com.nrknithin.fintrack.domain.engine

import kotlin.math.max
import kotlin.math.min

/**
 * Jaro-Winkler similarity in `[0.0, 1.0]`, used by the L3 merchant test of spec 14 §14.2
 * (threshold `settings.dedup.merchantSimilarity`, default 0.85).
 *
 * Hand-written rather than pulled from `commons-text`: this is the only string metric the app
 * needs, and the library would add a transitive tree to an APK for thirty lines of arithmetic.
 *
 * The metric is the standard one — Jaro's matching-window score, plus Winkler's bonus for a
 * shared prefix, applied only above [BOOST_THRESHOLD]. The prefix bonus is what makes it the
 * right metric here: merchant strings from two channels usually agree at the start and diverge
 * at the end ("amazon" vs "amazon pay india"), which plain edit distance punishes far too hard.
 */
object JaroWinkler {

    /** Winkler's constant. At most [MAX_PREFIX] × this can be added to a Jaro score. */
    private const val PREFIX_SCALE = 0.1

    /** Winkler's cap on how many leading characters earn the bonus. */
    private const val MAX_PREFIX = 4

    /**
     * Winkler only boosts strings that already look alike; below this, a shared prefix is a
     * coincidence ("acme" vs "acropolis theatre") and boosting it would invent a match.
     */
    private const val BOOST_THRESHOLD = 0.7

    /**
     * Similarity of [a] and [b]. Symmetric, 1.0 for equal strings, 0.0 when either is empty.
     *
     * Comparison is done on the strings as given: callers normalise first (dedup compares
     * `Cleaners.cleanMerchant` output), so this function makes no assumptions about case or
     * punctuation of its own.
     */
    fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0

        val jaro = jaro(a, b)
        if (jaro < BOOST_THRESHOLD) return jaro

        var prefix = 0
        val prefixLimit = min(MAX_PREFIX, min(a.length, b.length))
        while (prefix < prefixLimit && a[prefix] == b[prefix]) prefix++

        return jaro + prefix * PREFIX_SCALE * (1.0 - jaro)
    }

    /**
     * Jaro's score: how many characters the two strings share within half the longer string's
     * length, discounted by how many of those shared characters are out of order.
     */
    private fun jaro(a: String, b: String): Double {
        // Characters further apart than this are not considered the same character at all.
        val window = max(max(a.length, b.length) / 2 - 1, 0)

        val aMatched = BooleanArray(a.length)
        val bMatched = BooleanArray(b.length)
        var matches = 0

        for (i in a.indices) {
            val from = max(0, i - window)
            val to = min(i + window + 1, b.length)
            var j = from
            while (j < to) {
                if (!bMatched[j] && a[i] == b[j]) {
                    aMatched[i] = true
                    bMatched[j] = true
                    matches++
                    break
                }
                j++
            }
        }

        if (matches == 0) return 0.0

        // Transpositions: matched characters that appear in a different order on each side.
        var transpositions = 0
        var k = 0
        for (i in a.indices) {
            if (!aMatched[i]) continue
            while (!bMatched[k]) k++
            if (a[i] != b[k]) transpositions++
            k++
        }

        val m = matches.toDouble()
        return (m / a.length + m / b.length + (m - transpositions / 2.0) / m) / 3.0
    }
}
