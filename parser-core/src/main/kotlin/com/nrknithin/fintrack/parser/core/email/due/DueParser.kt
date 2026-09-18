package com.nrknithin.fintrack.parser.core.email.due

import com.nrknithin.fintrack.parser.core.email.EmailMessage

/**
 * Reads dues out of one email (spec 12).
 *
 * Returns a *list* because a single mail can announce several: GoDaddy's renewal notice
 * carries one row per domain, each with its own date and price, and each becomes its own
 * due (spec 12 D1).
 *
 * A parser returns an empty list rather than guessing when a mandatory field is missing.
 * Creating a due with an invented date would put a wrong figure in front of the user,
 * which is worse than missing the bill entirely (spec 01, constraint C7).
 */
interface DueParser {

    /** A stable id used in `skip_reason` and in the Email Log, e.g. "HDFC_UPCOMING_MANDATE". */
    val id: String

    /** True when this parser recognises the sender. Cheap; the real test is [parse]. */
    fun canHandle(from: String): Boolean

    /** Zero or more dues. Never throws on malformed input — returns empty instead. */
    fun parse(message: EmailMessage): List<ParsedDue>
}
