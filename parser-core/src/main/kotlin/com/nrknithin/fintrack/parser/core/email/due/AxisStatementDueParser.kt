package com.nrknithin.fintrack.parser.core.email.due

import com.nrknithin.fintrack.parser.core.email.EmailMessage
import com.nrknithin.fintrack.parser.core.email.EmailSenderRegistry
import com.nrknithin.fintrack.parser.core.text.DateParse
import com.nrknithin.fintrack.parser.core.text.Money
import java.math.BigDecimal

/**
 * Axis Bank credit-card statement → one `CARD_STATEMENT` due (spec 12 §12.2 A5, corpus
 * row `cc.statements@axis.bank.in`, spec 10 §10.2).
 *
 * Unlike every other due template in the corpus, this one carries **no labels next to its
 * figures**. The statement summary is an HTML table whose header row holds the labels and
 * whose value row holds `<total> Dr <min> Dr <dd/MM/yyyy>`; once `EmailBodyExtractor`
 * flattens the table (spec 11 §11.5) the three values arrive as one run, and their meaning
 * is fixed by position alone. `Dr` is the issuer's debit marker, and it is the only anchor
 * the run has.
 *
 * That is also why this parser is deliberately strict:
 *  - the run must be found **in order** — total, `Dr`, minimum, `Dr`, date. When it is
 *    not found, the parser returns no due. It never falls back to "the larger number is
 *    the total", because a minimum charged as a total, or the reverse, is exactly the
 *    kind of wrong figure spec 01 constraint C7 exists to prevent.
 *  - the two amounts must be consistent (`0 < minimum <= total`). A run failing that is
 *    not the summary row, whatever it looks like, so it is discarded rather than reordered.
 *
 * A statement with a credit balance writes `Cr` instead of `Dr`; it does not match, and it
 * has nothing to pay, so producing no due is the correct outcome there too.
 *
 * The card and the statement month come from the subject
 * (`<card name> ending XX<2-4> - <Month YYYY>`). Note the masked run can be **two** digits
 * — the same card appears as `XX1234` in Axis's own transaction alerts and as `XX34` here,
 * which is why the app merges cards by an endsWith match (spec 12 §12.6 C1).
 */
class AxisStatementDueParser : DueParser {

    override val id: String = "AXIS_CARD_STATEMENT"

    override fun canHandle(from: String): Boolean =
        EmailSenderRegistry.normaliseAddress(from) == SENDER

    override fun parse(message: EmailMessage): List<ParsedDue> =
        runCatching { readStatement(message) }.getOrDefault(emptyList())

    private fun readStatement(message: EmailMessage): List<ParsedDue> {
        if (!canHandle(message.from)) return emptyList()

        val match = POSITIONAL_SUMMARY.find(message.body) ?: return emptyList()
        val total = Money.parse(match.groupValues[1]) ?: return emptyList()
        val minimum = Money.amount(match.groupValues[2]) ?: return emptyList()
        val dueDate = DateParse.parseDate(match.groupValues[3]) ?: return emptyList()

        if (!isConsistent(total.amount, minimum)) return emptyList()

        return listOf(
            ParsedDue(
                issuer = ISSUER,
                kind = DueKind.CARD_STATEMENT,
                amount = total.amount,
                dueDate = dueDate,
                currency = total.currency,
                minimumDue = minimum,
                accountLast4 = last4(message.subject),
                periodLabel = periodLabel(message),
                source = SOURCE,
                description = cardName(message.subject)
            )
        )
    }

    /**
     * The summary row is only believable when the total is payable and the minimum is a
     * part of it. Anything else means the run matched something that is not the summary.
     */
    private fun isConsistent(total: BigDecimal, minimum: BigDecimal): Boolean =
        total.signum() > 0 && minimum.signum() >= 0 && minimum <= total

    /** `… ending XX34 - Sep 2026` → `34`; a four-digit mask yields all four. */
    private fun last4(subject: String): String? =
        SUBJECT_CARD.find(subject)?.groupValues?.get(1)?.takeLast(4)

    /**
     * The billing period as the statement states it, else the month from the subject.
     *
     * The corpus puts the period in the body but does not fix its wording, so a labelled
     * `Statement Period` is used when present and the subject's `- <Month YYYY>` — which
     * every sample carries — is the fallback.
     */
    private fun periodLabel(message: EmailMessage): String? {
        BODY_PERIOD.find(message.body)?.let { found ->
            return found.groupValues[1].trim() + " - " + found.groupValues[2].trim()
        }
        return SUBJECT_PERIOD.find(message.subject)?.groupValues?.get(1)?.trim()?.ifEmpty { null }
    }

    /** `Airtel Axis Bank Rupay Credit Card`, kept for the due-detail screen only. */
    private fun cardName(subject: String): String? =
        SUBJECT_CARD_NAME.find(subject)?.groupValues?.get(1)?.trim()?.ifEmpty { null }

    private companion object {
        const val SENDER = "cc.statements@axis.bank.in"
        const val ISSUER = "Axis Bank"
        const val SOURCE = "AXIS"

        const val AMOUNT = """([\d,]+(?:\.\d{1,2})?)"""

        /**
         * `Dr` as its own word, with the trailing dot the HTML sometimes carries. The word
         * boundaries keep it from matching inside `Drawn` or `Dr Name`… followed by digits.
         */
        const val DEBIT_MARKER = """\bDr\b\.?"""

        /**
         * Filler between the values: the flattened cell separators, and the label cells
         * themselves when the table interleaves label and value. It excludes digits, so the
         * run can never skip *past* a number to reach a later one — whatever sits between
         * two matched amounts is text, never another figure. The length cap only bounds how
         * far apart the cells may sit; it is the digit exclusion that keeps the reading honest.
         */
        const val FILLER = """[^\d]{0,80}"""

        /** `dd/MM/yyyy` as the corpus states it, tolerating dashes and a two-digit year. */
        const val DATE = """(\d{1,2}[/-]\d{1,2}[/-]\d{2,4})"""

        val POSITIONAL_SUMMARY = Regex(
            AMOUNT + """\s*\|?\s*""" + DEBIT_MARKER + FILLER +
                AMOUNT + """\s*\|?\s*""" + DEBIT_MARKER + FILLER + DATE,
            RegexOption.IGNORE_CASE
        )

        val SUBJECT_CARD = Regex("""ending\s+(?:X+\s*)?(\d{2,4})\b""", RegexOption.IGNORE_CASE)
        val SUBJECT_CARD_NAME = Regex("""^\s*(.+?)\s+ending\b""", RegexOption.IGNORE_CASE)

        /** The `- <Month YYYY>` tail of the subject; `\z` anchors it without a `$` in a raw string. */
        val SUBJECT_PERIOD = Regex("""[-\u2013]\s*([A-Za-z]{3,9}\s+\d{4})\s*\z""")

        /** Between the `Statement Period` label and its first date, flattened-cell aware. */
        const val PERIOD_GAP = """[\s:|]*"""

        /** One period endpoint: `13/09/2026`, `13 Sep 2026` or `Sep 13, 2026`. */
        const val PERIOD_DATE =
            """(\d{1,2}[/-]\d{1,2}[/-]\d{2,4}|\d{1,2}\s+[A-Za-z]{3,9},?\s+\d{4}|[A-Za-z]{3,9}\s+\d{1,2},?\s*\d{4})"""

        val BODY_PERIOD = Regex(
            """Statement\s+Period$PERIOD_GAP$PERIOD_DATE\s*(?:[-\u2013]|to|\|)\s*$PERIOD_DATE""",
            RegexOption.IGNORE_CASE
        )
    }
}
