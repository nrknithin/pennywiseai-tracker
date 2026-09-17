package com.pennywiseai.parser.core.text

/**
 * Merchant-name canonicalisation used when comparing records (spec 14 §14.2).
 *
 * Lives in `:parser-core` because it is text handling, and spec 02 §2.1 is explicit that no
 * parsing regex belongs in `:app`. `DedupEngine` compares merchants through this, so two
 * spellings of one merchant — `UPI-ACME STORE`, `acme store@okbank`, `ACME  STORE.` — have to
 * reduce to the same key or the same purchase is counted twice.
 */
object Cleaners {

    /** Payment-rail prefixes banks staple onto a merchant name. */
    private val RAIL_PREFIX = Regex("""^(?:upi[-/]|vpa\s+|pos\s+|ecom\s+|imps[-/]|neft[-/]|ach[-/]|si\s+|mandate\s+)+""", RegexOption.IGNORE_CASE)

    /** A trailing VPA handle: `acme store acme@okbank` -> `acme store`. */
    private val TRAILING_VPA = Regex("""\s*\S+@[a-z0-9.-]+\s*$""", RegexOption.IGNORE_CASE)

    /** Card-network noise: `XSOLLA *POKEMON` style asterisk segments and masked digits. */
    private val MASKED_DIGITS = Regex("""\b(?:x{2,}|\*{2,})\d{0,6}\b""", RegexOption.IGNORE_CASE)

    private val PUNCTUATION = Regex("""[^\p{L}\p{N}\s]+""")
    private val WHITESPACE = Regex("""\s+""")

    /**
     * Reduces a merchant name to a comparison key, or null when there is nothing left to
     * compare.
     *
     * Null in, null out — and a name that cleans down to nothing also returns null rather than
     * an empty string. That distinction matters: spec 14 §14.3 L3 lets a record with an
     * *unknown* merchant match one with a known merchant, so if two unknowns both became `""`
     * they would compare equal and two unrelated charges would merge.
     */
    fun cleanMerchant(raw: String?): String? {
        if (raw == null) return null

        var text = raw.trim()
        if (text.isEmpty()) return null

        text = RAIL_PREFIX.replace(text, "")
        text = TRAILING_VPA.replace(text, "")
        text = MASKED_DIGITS.replace(text, " ")
        text = PUNCTUATION.replace(text, " ")
        text = WHITESPACE.replace(text, " ").trim().lowercase()

        return text.ifEmpty { null }
    }

    /**
     * Similarity of two merchant names in the range 0.0 to 1.0, used by spec 14 §14.3 L3's
     * "merchant similarity >= 0.85" test.
     *
     * Jaro-Winkler, as the spec names. It is the right shape for this problem: it rewards a
     * shared prefix, which is exactly how banks truncate merchant names differently across
     * channels (`BOOK MY SHOW SMART GAT` in one alert, `BOOK MY SHOW` in another).
     */
    fun merchantSimilarity(a: String?, b: String?): Double {
        val left = cleanMerchant(a) ?: return 0.0
        val right = cleanMerchant(b) ?: return 0.0
        if (left == right) return 1.0
        return jaroWinkler(left, right)
    }

    private fun jaroWinkler(s1: String, s2: String): Double {
        val jaro = jaro(s1, s2)
        if (jaro < 0.7) return jaro // Winkler's own threshold: do not boost weak matches.

        var prefix = 0
        val max = minOf(4, minOf(s1.length, s2.length))
        while (prefix < max && s1[prefix] == s2[prefix]) prefix++

        return jaro + prefix * 0.1 * (1 - jaro)
    }

    private fun jaro(s1: String, s2: String): Double {
        if (s1.isEmpty() || s2.isEmpty()) return 0.0

        val window = maxOf(s1.length, s2.length) / 2 - 1
        val s1Matched = BooleanArray(s1.length)
        val s2Matched = BooleanArray(s2.length)

        var matches = 0
        for (i in s1.indices) {
            val start = maxOf(0, i - window)
            val end = minOf(i + window + 1, s2.length)
            for (j in start until end) {
                if (s2Matched[j] || s1[i] != s2[j]) continue
                s1Matched[i] = true
                s2Matched[j] = true
                matches++
                break
            }
        }
        if (matches == 0) return 0.0

        var transpositions = 0
        var k = 0
        for (i in s1.indices) {
            if (!s1Matched[i]) continue
            while (!s2Matched[k]) k++
            if (s1[i] != s2[k]) transpositions++
            k++
        }

        val m = matches.toDouble()
        return (m / s1.length + m / s2.length + (m - transpositions / 2.0) / m) / 3.0
    }
}
