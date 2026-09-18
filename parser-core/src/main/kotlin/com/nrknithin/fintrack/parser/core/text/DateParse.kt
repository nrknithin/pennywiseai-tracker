package com.nrknithin.fintrack.parser.core.text

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Tolerant date parsing shared by every email parser (spec 12 §12.11).
 *
 * The corpus carries at least ten shapes across senders — `16-09-26` (Axis),
 * `07-SEP-2026` (HDFC EMI), `15 Sep, 2026` (HDFC card), `Sep 17, 2026` (ICICI),
 * `September 17, 2026` (CRED), `08/09/2026` (HDFC mandate) and `09/17/2026`
 * (Xsolla, US order). Patterns are tried in a fixed order and the first that
 * parses wins; nothing is inferred from a failed parse.
 *
 * Times default to midnight. The device zone is applied by the caller, never here
 * — this object stays free of zone assumptions so it is testable without a clock.
 */
object DateParse {

    /** Date-and-time shapes, tried before the date-only shapes. */
    private val DATE_TIME_PATTERNS = listOf(
        "d MMM, yyyy HH:mm:ss",
        "d MMM yyyy HH:mm:ss",
        "d MMM yyyy h:mm:ss a",
        "d MMM, yyyy h:mm:ss a",
        "MMM d, yyyy HH:mm:ss",
        "MMM d, yyyy h:mm:ss a",
        "MMMM d, yyyy HH:mm:ss",
        "dd-MM-yyyy HH:mm:ss",
        "dd-MM-yy HH:mm:ss",
        "dd/MM/yyyy HH:mm:ss",
        "dd-MMM-yyyy HH:mm:ss",
        "yyyy-MM-dd HH:mm:ss"
    )

    /**
     * Date-only shapes in the order spec 12 §12.11 fixes. Numeric day-first shapes
     * precede month-name shapes; `MM/dd/yyyy` is handled separately because it is
     * only reachable when the sender is known to use US ordering.
     */
    private val DATE_PATTERNS_DMY = listOf(
        "dd-MM-yy",
        "dd-MM-yyyy",
        "dd/MM/yyyy",
        "dd/MM/yy",
        "dd-MMM-yy",
        "dd-MMM-yyyy",
        "d MMM, yyyy",
        "d MMM yyyy",
        "d MMMM yyyy",
        "MMM d, yyyy",
        "MMMM d, yyyy",
        "EEEE, MMMM d, yyyy",
        "yyyy-MM-dd"
    )

    /**
     * US-ordered slash shapes. When a sender is flagged `preferDMY = false` these are
     * tried *before* the day-first shapes, not after: `09/17/2026` would fail day-first
     * and fall through safely, but `03/09/2026` would silently parse as 3 September when
     * Xsolla meant 9 March. Spec 12 §12.11 lists `MM/dd/yyyy` last in a single ordered
     * list; taken literally that misreads every Xsolla date whose day is ≤ 12, so the
     * ordering is conditioned on the flag instead.
     */
    private val DATE_PATTERNS_MDY = listOf(
        "MM/dd/yyyy",
        "MM/dd/yy"
    )

    private val ORDINAL_SUFFIX = Regex("""(\d{1,2})(st|nd|rd|th)\b""", RegexOption.IGNORE_CASE)
    private val TIMEZONE_WORDS = Regex("""\b(IST|UTC|GMT|Z)\b""", RegexOption.IGNORE_CASE)
    private val AT_SEPARATOR = Regex("""\s+at\s+""", RegexOption.IGNORE_CASE)
    private val WHITESPACE = Regex("""\s+""")

    private val formatterCache = ConcurrentHashMap<String, DateTimeFormatter>()

    /**
     * Parses a date, optionally with a time, returning midnight when the text carries
     * no time. Returns null rather than guessing when nothing matches.
     */
    fun parse(raw: String?, preferDMY: Boolean = true): LocalDateTime? {
        val text = normalise(raw) ?: return null

        for (pattern in DATE_TIME_PATTERNS) {
            runCatching { LocalDateTime.parse(text, formatter(pattern)) }
                .getOrNull()
                ?.let { return it }
        }

        return parseDate(raw, preferDMY)?.atStartOfDay()
    }

    /** Parses a date only, ignoring any time the text carries. */
    fun parseDate(raw: String?, preferDMY: Boolean = true): LocalDate? {
        val text = normalise(raw) ?: return null

        val ordered = if (preferDMY) {
            DATE_PATTERNS_DMY + DATE_PATTERNS_MDY
        } else {
            DATE_PATTERNS_MDY + DATE_PATTERNS_DMY
        }

        // The whole string first, then its leading date token — templates often hand
        // over a capture with a trailing time that the date-only patterns cannot eat.
        for (candidate in listOf(text, text.substringBefore(' '))) {
            if (candidate.isEmpty()) continue
            for (pattern in ordered) {
                runCatching { LocalDate.parse(candidate, formatter(pattern)) }
                    .getOrNull()
                    ?.let { return it }
            }
        }

        // A date with a trailing time that no combined pattern matched: retry the
        // date-only patterns against progressively shorter prefixes.
        val tokens = text.split(' ')
        for (take in tokens.size - 1 downTo 1) {
            val candidate = tokens.take(take).joinToString(" ")
            for (pattern in ordered) {
                runCatching { LocalDate.parse(candidate, formatter(pattern)) }
                    .getOrNull()
                    ?.let { return it }
            }
        }

        return null
    }

    /**
     * Combines a date capture and a separate time capture — the shape most templates
     * use, because their regexes bracket the two independently.
     */
    fun parse(rawDate: String?, rawTime: String?, preferDMY: Boolean = true): LocalDateTime? {
        val date = parseDate(rawDate, preferDMY) ?: return null
        val time = parseTime(rawTime) ?: LocalTime.MIDNIGHT
        return date.atTime(time)
    }

    /** Parses `16:14:34`, `4:14:34 pm`, or `16:14`. Returns null when nothing matches. */
    fun parseTime(raw: String?): LocalTime? {
        val text = normalise(raw) ?: return null
        for (pattern in listOf("HH:mm:ss", "h:mm:ss a", "HH:mm", "h:mm a")) {
            runCatching { LocalTime.parse(text, formatter(pattern)) }
                .getOrNull()
                ?.let { return it }
        }
        return null
    }

    /**
     * Strips what the templates wrap around dates: ordinal suffixes (`17th`), zone
     * labels (`IST`), the word `at` between date and time, and stray commas left by
     * HTML-to-text conversion.
     */
    private fun normalise(raw: String?): String? {
        if (raw == null) return null
        var text = raw.trim()
        if (text.isEmpty()) return null

        text = ORDINAL_SUFFIX.replace(text) { it.groupValues[1] }
        text = TIMEZONE_WORDS.replace(text, " ")
        text = AT_SEPARATOR.replace(text, " ")
        text = text.replace(",", ", ")
        text = WHITESPACE.replace(text, " ").trim()
        text = text.replace(" ,", ",").trimEnd(',', '.', ' ')

        return text.ifEmpty { null }
    }

    private fun formatter(pattern: String): DateTimeFormatter =
        formatterCache.getOrPut(pattern) {
            DateTimeFormatterBuilder()
                .parseCaseInsensitive()
                .appendPattern(pattern)
                .toFormatter(Locale.ENGLISH)
        }
}
