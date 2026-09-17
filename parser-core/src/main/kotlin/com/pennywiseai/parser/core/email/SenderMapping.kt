package com.pennywiseai.parser.core.email

/**
 * What the sender registry knows about one From-address (spec 11 §11.6).
 *
 * [smsAlias] is the SMS sender id the bank's existing parser answers to, so an email
 * body can be handed to `BankParserFactory` and reuse 168 already-tested SMS parsers.
 * Senders with no SMS counterpart — GoDaddy, PGSharp, Xsolla — carry a null alias and
 * are served only by their own email or due parser.
 */
data class SenderMapping(
    val bankName: String,
    val smsAlias: String? = null,
    /**
     * False for senders that write US-ordered dates. Only Xsolla does, but the flag
     * lives here so `DateParse` never has to guess (spec 12 §12.11).
     */
    val preferDMY: Boolean = true,
    /** True for senders whose mail is marketing only and must never reach a parser. */
    val marketingOnly: Boolean = false
)
