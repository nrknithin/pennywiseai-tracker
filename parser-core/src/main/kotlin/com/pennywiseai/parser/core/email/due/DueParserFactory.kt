package com.pennywiseai.parser.core.email.due

import com.pennywiseai.parser.core.email.EmailMessage
import com.pennywiseai.parser.core.email.EmailSenderRegistry

/**
 * Runs every due parser that claims a sender (spec 11 §11.4 step b).
 *
 * Deliberately *not* first-match-wins, unlike `EmailParserFactory`. One sender can announce
 * more than one kind of due — HDFC sends both upcoming e-mandates and loan preclosure
 * notices — and one message can carry several dues, since GoDaddy lists every expiring
 * domain in a single mail. So this collects across all matching parsers.
 *
 * A parser that finds nothing returns an empty list, and a parser that throws is contained
 * here rather than being allowed to abort the sync: one malformed bill must not cost the
 * user the rest of the batch (spec 02 §2.7, "Parser throws on one message").
 */
object DueParserFactory {

    private val parsers: List<DueParser> = listOf(
        AxisStatementDueParser(),
        CardPaymentConfirmationDueParser(),
        CloudflareInvoiceDueParser(),
        CredBillDueParser(),
        GoDaddyRenewalDueParser(),
        HDFCMandateDueParser(),
        HDFCPreclosureDueParser(),
        ICICIStatementDueParser(),
        PGSharpInvoiceDueParser()
    )

    /**
     * Every parser that claims this sender. Usually zero or one; three senders have two —
     * HDFC (mandates and preclosures), CRED (new bill and payment confirmed) and ICICI
     * (statement and payment confirmed).
     */
    fun parsersFor(from: String?): List<DueParser> {
        val address = EmailSenderRegistry.normaliseAddress(from) ?: return emptyList()
        return parsers.filter { it.canHandle(address) }
    }

    /**
     * All dues this message describes, across every parser that claims the sender.
     *
     * [onError] is called with the parser id and the throwable when a parser fails, so the
     * caller can record `ERROR:<class>` against the message and count it in the sync run
     * without the failure propagating.
     */
    fun parseAll(
        message: EmailMessage,
        onError: (parserId: String, error: Throwable) -> Unit = { _, _ -> }
    ): List<ParsedDue> {
        val found = mutableListOf<ParsedDue>()
        for (parser in parsersFor(message.from)) {
            try {
                found += parser.parse(message)
            } catch (error: Throwable) {
                onError(parser.id, error)
            }
        }
        return found
    }

    /** Parser ids, for the Email Log and for diagnosing which template matched. */
    fun parserIds(): List<String> = parsers.map { it.id }
}
