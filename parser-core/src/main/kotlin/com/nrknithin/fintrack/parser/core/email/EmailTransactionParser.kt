package com.nrknithin.fintrack.parser.core.email

import com.nrknithin.fintrack.parser.core.ParsedTransaction

/**
 * Reads a transaction out of one email (spec 12).
 *
 * Separate from `BankParser` because email carries a subject line, and several senders
 * can only be told apart by it — HDFC puts twelve different templates behind one address
 * and the subject is what distinguishes a card purchase from an OTP (spec 12 §12.1).
 *
 * The division of labour with the SMS parsers (spec 11 §11.6): the sender registry maps
 * the address to an SMS alias, `BankParserFactory` produces a first reading using the
 * bank's existing, well-tested SMS parser, and the email parser then corrects the fields
 * the SMS template does not cover. A parser that can do the whole job itself simply
 * ignores that first reading.
 */
interface EmailTransactionParser {

    /** A stable id used in the Email Log and in `skip_reason`, e.g. "HDFC_EMAIL". */
    val id: String

    /** True when this parser recognises the sender. */
    fun canHandle(from: String): Boolean

    /**
     * The transaction this mail describes, or null when it describes none.
     *
     * Null is the right answer for a bill, a statement, a mandate notice or a marketing
     * mail: those are expectations or noise, not money that moved. Creating a transaction
     * from a bill would double-count it when the payment alert arrives (spec 01, rule 1).
     */
    fun parse(message: EmailMessage): ParsedTransaction?

    /**
     * True when this mail is recognisably *not* about a transaction, letting the pipeline
     * record `NON_TXN` and stop before the generic parser has a chance to misread it
     * (spec 11 §11.4 step d). Distinct from returning null from [parse], which only says
     * "this parser found nothing".
     */
    fun isNonTransactionEmail(message: EmailMessage): Boolean = false
}
