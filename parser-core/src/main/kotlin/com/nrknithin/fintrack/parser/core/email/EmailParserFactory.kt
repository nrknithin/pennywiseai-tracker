package com.nrknithin.fintrack.parser.core.email

import com.nrknithin.fintrack.parser.core.ParsedTransaction

/**
 * Picks the email parser for a sender and runs it (spec 11 §11.4 step e).
 *
 * Unlike `BankParserFactory`, which is an `object` holding a fixed list, this is a class:
 * two of its parsers need the user's own VPAs and names to tell a transfer between the
 * user's own accounts from an ordinary payment (spec 12 §12.1 H1/H2, §12.2 A2). Those are
 * runtime settings — `settings.accounts.own`, spec 14 §14.5 — so the factory is constructed
 * with them rather than reaching for a global. [DEFAULT] covers callers that have none, in
 * which case a self-transfer simply reads as an ordinary expense or income.
 *
 * Nothing here is hard-coded to a person: with no settings supplied, the own-account rules
 * are inert.
 */
class EmailParserFactory(
    ownVpas: Set<String> = emptySet(),
    ownNames: Set<String> = emptySet()
) {

    /**
     * Evaluated in order; the first parser that claims the sender wins. The order is only
     * significant where two parsers could claim one address, which today they cannot —
     * each owns a distinct domain — so this is alphabetical by bank for readability.
     */
    private val parsers: List<EmailTransactionParser> = listOf(
        AxisEmailParser(accountHolderNames = ownNames),
        CredPaymentEmailParser(),
        HDFCEmailParser(ownVpas = ownVpas, ownNames = ownNames),
        ICICIEmailParser(),
        OneCardEmailParser(),
        PGSharpPaymentEmailParser(),
        SBIEmailParser()
    )

    /** The parser that handles this sender, or null when none does. */
    fun parserFor(from: String?): EmailTransactionParser? {
        val address = EmailSenderRegistry.normaliseAddress(from) ?: return null
        return parsers.firstOrNull { it.canHandle(address) }
    }

    /**
     * The transaction this message describes, or null.
     *
     * Null covers three different situations the caller must distinguish, which is why the
     * pipeline checks them separately rather than relying on this return value alone
     * (spec 11 §11.4 step e): no parser claimed the sender, a parser claimed it but the
     * message is not about money moving, or a parser claimed it and could not read it.
     */
    fun parse(message: EmailMessage): ParsedTransaction? =
        parserFor(message.from)?.parse(message)

    /**
     * True when a parser recognises the sender but considers this message not to be about a
     * transaction — a mandate notice, an OTP, a lifecycle mail. Drives the `NON_TXN` skip
     * reason, which is distinct from `NO_PARSER`.
     */
    fun isNonTransactionEmail(message: EmailMessage): Boolean =
        parserFor(message.from)?.isNonTransactionEmail(message) ?: false

    /** Parser ids, for the Email Log and for `transaction_sources.parser_id`. */
    fun parserIds(): List<String> = parsers.map { it.id }

    companion object {
        /** For callers with no own-account settings, and for tests. */
        val DEFAULT = EmailParserFactory()
    }
}
