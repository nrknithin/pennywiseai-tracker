package com.pennywiseai.parser.core.text

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * A money value recovered from message text: the amount plus the currency the
 * text actually named. Kept separate from [com.pennywiseai.parser.core.ParsedTransaction]
 * so due/receipt parsers can reuse it.
 */
data class ParsedMoney(
    val amount: BigDecimal,
    val currency: String
)

/**
 * Amount extraction shared by every email parser (spec 12 §12.11).
 *
 * Bank mail is not consistent about grouping: HDFC's EMI template has sent both
 * `21,538.00` and `21538.00` in the same template across months, and at least one
 * template emits `2,53471.86` — neither Indian (`2,53,471.86`) nor Western
 * (`253,471.86`) grouping. Rather than guess which digit groups were intended,
 * a run of commas that matches no known grouping is treated as noise and dropped;
 * the digits themselves are never reordered or padded.
 */
object Money {

    /** Currency tokens that may prefix an amount, longest first so `INR` wins over `IN`. */
    private val CURRENCY_TOKENS = listOf(
        "INR" to "INR",
        "USD" to "USD",
        "EUR" to "EUR",
        "RS." to "INR",
        "RS" to "INR",
        "\u20B9" to "INR", // rupee sign
        "$" to "USD",
        "\u20AC" to "EUR" // euro sign
    )

    /** Indian grouping: `2,53,471` — a final group of 3, every earlier group 2 (the first may be 1-2). */
    private val INDIAN_GROUPING = Regex("""^\d{1,2}(?:,\d{2})*,\d{3}$""")

    /** Western grouping: `253,471` — a first group of 1-3, every later group exactly 3. */
    private val WESTERN_GROUPING = Regex("""^\d{1,3}(?:,\d{3})+$""")

    /**
     * Parses `raw` into an amount and the currency its tokens named.
     *
     * Returns null when `raw` carries no digits, carries more than one decimal
     * point, or has more than two decimal places — all of which mean the capture
     * group caught something that is not a single money value. Never guesses.
     */
    fun parse(raw: String?): ParsedMoney? {
        if (raw == null) return null
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        val currency = currencyOf(trimmed)
        val stripped = stripCurrencyTokens(trimmed)

        // Keep only the characters that can belong to a number. A leading minus is
        // honoured; minus signs anywhere else are noise from the surrounding text.
        val negative = stripped.startsWith("-")
        val digitsOnly = stripped.filter { it.isDigit() || it == ',' || it == '.' }
        if (digitsOnly.none { it.isDigit() }) return null
        if (digitsOnly.count { it == '.' } > 1) return null

        val integerPart: String
        val fractionPart: String
        val dot = digitsOnly.indexOf('.')
        if (dot >= 0) {
            integerPart = digitsOnly.substring(0, dot)
            fractionPart = digitsOnly.substring(dot + 1)
        } else {
            integerPart = digitsOnly
            fractionPart = ""
        }

        // A fraction is at most 2 digits and never contains a separator. Anything
        // longer means the capture ran past the amount (e.g. into a date or a ref).
        if (fractionPart.any { !it.isDigit() } || fractionPart.length > 2) return null

        val normalisedInteger = normaliseGrouping(integerPart) ?: return null
        if (normalisedInteger.isEmpty()) return null

        val assembled = buildString {
            if (negative) append('-')
            append(normalisedInteger)
            if (fractionPart.isNotEmpty()) {
                append('.')
                append(fractionPart)
            }
        }

        val value = try {
            BigDecimal(assembled)
        } catch (_: NumberFormatException) {
            return null
        }

        return ParsedMoney(value.setScale(2, RoundingMode.HALF_UP), currency)
    }

    /** Convenience for parsers that already know the currency. */
    fun amount(raw: String?): BigDecimal? = parse(raw)?.amount

    /**
     * The currency the text names, defaulting to INR. `Rs. INR 21,538.00` (HDFC's
     * EMI template really does emit both) resolves to INR either way.
     */
    fun currencyOf(raw: String): String {
        val upper = raw.uppercase()
        // Scan left to right so the first token that appears wins, rather than
        // whichever happens to sit earliest in the token list.
        var best: Pair<Int, String>? = null
        for ((token, code) in CURRENCY_TOKENS) {
            val at = upper.indexOf(token)
            if (at >= 0 && (best == null || at < best!!.first)) {
                best = at to code
            }
        }
        return best?.second ?: "INR"
    }

    private fun stripCurrencyTokens(raw: String): String {
        var out = raw
        // Repeat until stable: `Rs. INR 500` needs two passes.
        var changed = true
        while (changed) {
            changed = false
            val upper = out.uppercase()
            for ((token, _) in CURRENCY_TOKENS) {
                val at = upper.indexOf(token)
                if (at >= 0) {
                    out = out.removeRange(at, at + token.length)
                    changed = true
                    break
                }
            }
        }
        return out.trim()
    }

    /**
     * Returns the integer part with commas resolved, or null if it is not digits.
     *
     * Commas are kept only as a sanity check: if they form a recognised grouping the
     * digits are already correct, and if they do not (`1,42590`) the commas are
     * dropped rather than used to re-space the number.
     */
    private fun normaliseGrouping(integerPart: String): String? {
        if (integerPart.isEmpty()) return ""
        if (integerPart.any { !it.isDigit() && it != ',' }) return null
        if (!integerPart.contains(',')) return integerPart

        val withoutCommas = integerPart.replace(",", "")
        if (withoutCommas.isEmpty()) return null
        if (INDIAN_GROUPING.matches(integerPart) || WESTERN_GROUPING.matches(integerPart)) {
            return withoutCommas
        }
        // Unrecognised grouping — the digits stand, the commas go (spec 12 §12.11).
        return withoutCommas
    }
}
