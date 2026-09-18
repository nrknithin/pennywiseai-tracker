package com.nrknithin.fintrack.parser.core.email

/**
 * One email as the parsers see it: the sender address, the subject, and the body
 * already normalised to text by the app's `EmailBodyExtractor` (spec 11 §11.5).
 *
 * Parsers never see MIME structure, HTML, or attachments — keeping this module free
 * of Android and JavaMail types is what lets all of it run under plain JVM tests.
 */
data class EmailMessage(
    /** Bare address, lower-cased — `alerts@hdfcbank.bank.in`, never a display name. */
    val from: String,
    val subject: String,
    /** Text body: `text/plain` when usable, else HTML converted to text (cells joined with ` | `). */
    val body: String,
    /** IMAP INTERNALDATE as epoch millis. The received date, not any date inside the body. */
    val receivedAt: Long
) {
    /** Subject and body together, for triggers that may match either (spec 12 classification). */
    val searchText: String get() = subject + "\n" + body
}
