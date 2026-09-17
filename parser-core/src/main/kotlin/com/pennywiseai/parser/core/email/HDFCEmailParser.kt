package com.pennywiseai.parser.core.email

import com.pennywiseai.parser.core.ParsedTransaction
import com.pennywiseai.parser.core.TransactionType
import com.pennywiseai.parser.core.text.DateParse
import com.pennywiseai.parser.core.text.Money
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The money-moved templates HDFC Bank sends from `alerts@hdfcbank.bank.in`
 * (spec 12 §12.1, templates H1–H8) plus the ignore set H11.
 *
 * Twelve templates share one address, so [parse] **classifies first** — on the subject
 * and the opening sentence together, in the order spec 12 §12.1 fixes — and then runs
 * exactly one template regex. Six of the twelve carry the same subject
 * ("View: Account update…"), which is why the body sentence, not the subject, ends up
 * being the discriminator; the subject is still part of the text searched, because H3,
 * H6 and H7 announce themselves there.
 *
 * Templates this parser deliberately does not answer: H9 (upcoming e-mandate) and H10
 * (mandate created / cancelled) are expectations and lifecycle events, not money that
 * moved, and belong to the due and subscription parsers; H12 (preclosure) is a due.
 * They classify as "no template" here, so none of them can become a transaction, and
 * [isNonTransactionEmail] reports that verdict to the pipeline (spec 11 §11.4 step d).
 *
 * @param ownVpas the user's own UPI addresses, from settings (the own-account registry of
 *   spec 14 §14.5). A UPI debit to one of these — or a credit from one — is a transfer
 *   between the user's own accounts, not an expense or income. Only the running app knows
 *   which addresses are the user's, so the set is **empty by default** and the parser then
 *   reads such a mail as an ordinary payment. Entries may use `*` as a wildcard
 *   (`owner-*@ok*`), matching how settings seeds them from observed handles.
 * @param ownNames the account holder's name as the bank spells it in a payee or sender
 *   field. Same reasoning: a runtime setting, empty here.
 */
class HDFCEmailParser(
    private val ownVpas: Set<String> = emptySet(),
    private val ownNames: Set<String> = emptySet()
) : EmailTransactionParser {

    override val id: String = "HDFC_EMAIL"

    /** Own-VPA patterns compiled once per parser instance, never per message. */
    private val ownVpaMatchers: List<Regex> = ownVpas.mapNotNull { globToRegex(it) }

    /** Own names reduced to a comparison key, so spacing and case cannot defeat the rule. */
    private val ownNameKeys: Set<String> = ownNames.mapNotNull { nameKey(it) }.toSet()

    override fun canHandle(from: String): Boolean {
        val mapping = EmailSenderRegistry.resolve(from) ?: return false
        return mapping.bankName == BANK && !mapping.marketingOnly
    }

    override fun parse(message: EmailMessage): ParsedTransaction? {
        if (!canHandle(message.from)) return null

        // Classification decides the single template that runs. Anything that is not one
        // of the eight money templates — an OTP, a mandate notice, a statement — stops
        // here, which is what keeps H11 from ever producing a transaction.
        return when (classify(message)) {
            Template.UPI_DEBIT -> parseUpiDebit(message)
            Template.CREDIT_RECEIVED -> parseCreditReceived(message)
            Template.DEBIT_CARD_PURCHASE -> parseDebitCardPurchase(message)
            Template.CARD_BILL_NETBANKING -> parseCardBillNetbanking(message)
            Template.LOAN_EMI -> parseLoanEmi(message)
            Template.CREDIT_CARD_PURCHASE -> parseCreditCardPurchase(message)
            Template.CARD_CHARGE_NO_OTP -> parseCardChargeWithoutOtp(message)
            Template.EMANDATE_PAID -> parseMandatePaid(message)
            null -> null
        }
    }

    /**
     * H11 and its neighbours: mail from this sender that is recognisably *not* about money
     * moving — an OTP, a payee added, a limit change, a PIN, an RM call-back, terms and
     * conditions, and the two mandate templates (H9, H10) that describe a charge that has
     * not happened yet or a subscription that changed state.
     *
     * The money templates are checked first on purpose. H7's own subject is "A charge just
     * happened on your Credit Card **without OTP**": a bare keyword scan would throw away a
     * real transaction, and a footer quoting "terms and conditions" would do the same. A
     * mail that matches a money template is a transaction, whatever its boilerplate says.
     */
    override fun isNonTransactionEmail(message: EmailMessage): Boolean {
        if (!canHandle(message.from)) return false
        if (classify(message) != null) return false
        val text = message.searchText
        return NON_TRANSACTION_MARKERS.any { text.contains(it, ignoreCase = true) }
    }

    // ------------------------------------------------------------------------
    // Classification (spec 12 §12.1 table order — first match wins)
    // ------------------------------------------------------------------------

    private enum class Template {
        UPI_DEBIT,
        CREDIT_RECEIVED,
        DEBIT_CARD_PURCHASE,
        CARD_BILL_NETBANKING,
        LOAN_EMI,
        CREDIT_CARD_PURCHASE,
        CARD_CHARGE_NO_OTP,
        EMANDATE_PAID
    }

    private fun classify(message: EmailMessage): Template? {
        val text = message.searchText
        fun has(marker: String) = text.contains(marker, ignoreCase = true)

        return when {
            has("is debited from your account ending") && has("towards VPA") ->
                Template.UPI_DEBIT

            has("has been successfully credited to your HDFC Bank account") ->
                Template.CREDIT_RECEIVED

            has("is debited from your HDFC Bank Debit Card ending") ->
                Template.DEBIT_CARD_PURCHASE

            has("for Credit Card Payment done using HDFC Bank Online Banking") ->
                Template.CARD_BILL_NETBANKING

            // The 2025 wording is "is debited … and credited to EMI"; the current one is
            // "is deducted … and added to EMI". Either ending settles the template.
            has("added to EMI") || has("credited to EMI") ->
                Template.LOAN_EMI

            has("has been debited from your HDFC Bank Credit Card ending") ->
                Template.CREDIT_CARD_PURCHASE

            has("HDFC Bank Card XX") && has("Authorization code") ->
                Template.CARD_CHARGE_NO_OTP

            has("set up through E-mandate") && has("successfully paid") ->
                Template.EMANDATE_PAID

            else -> null
        }
    }

    // ------------------------------------------------------------------------
    // H1 · UPI debit
    // ------------------------------------------------------------------------

    /**
     * `Rs.24317.85 is debited from your account ending 1234 towards VPA someone@okbank
     * (PAYEE) on 16-09-26. UPI transaction reference no.: 123456789012.`
     *
     * The payee name is the merchant and the 12-digit UPI reference is what later links
     * this debit to the other bank's credit (spec 14 §14.3 L1), so both are mandatory.
     */
    private fun parseUpiDebit(message: EmailMessage): ParsedTransaction? {
        val match = UPI_DEBIT.find(message.body) ?: return null
        val account = last4(match.groupValues[2])
        val vpa = match.groupValues[3].trim()
        val payee = cleanText(match.groupValues[4])
        val at = DateParse.parse(match.groupValues[5])

        // Self-transfer rule (spec 12 §12.1 H1): paying one's own VPA, or a payee spelled
        // like the account holder, moves money between the user's own accounts.
        val ownTransfer = isOwnVpa(vpa) || isOwnName(payee)

        return build(
            message = message,
            amountRaw = match.groupValues[1],
            type = if (ownTransfer) TransactionType.TRANSFER else TransactionType.EXPENSE,
            // The payee name is the useful label; ParsedTransaction has no description
            // field, so the VPA itself survives only as the counterpart bank below.
            merchant = payee,
            reference = match.groupValues[6],
            accountLast4 = account,
            at = at,
            fromAccount = if (ownTransfer) account else null,
            toAccount = if (ownTransfer) bankOfVpa(vpa) else null
        )
    }

    // ------------------------------------------------------------------------
    // H2 · Credit received
    // ------------------------------------------------------------------------

    /**
     * `Rs.27500.00 has been successfully credited to your HDFC Bank account ending in 1234.
     * Transaction Details: a. Date: 27-08-26 b. Sender: NAME (VPA: someone@okbank)
     * c. UPI Reference No.: 123456789012`
     *
     * Income unless the sender is the user: a credit from one's own Axis account is a
     * transfer, and counting it as income would inflate every month's total (spec 14 §14.5).
     */
    private fun parseCreditReceived(message: EmailMessage): ParsedTransaction? {
        val match = CREDIT_RECEIVED.find(message.body) ?: return null
        val account = last4(match.groupValues[2])
        val at = DateParse.parse(match.groupValues[3])
        val senderName = cleanText(match.groupValues[4])
        val vpa = match.groupValues[5].trim()

        val ownTransfer = isOwnVpa(vpa) || isOwnName(senderName)

        return build(
            message = message,
            amountRaw = match.groupValues[1],
            type = if (ownTransfer) TransactionType.TRANSFER else TransactionType.INCOME,
            merchant = senderName,
            reference = match.groupValues[6],
            accountLast4 = account,
            at = at,
            fromAccount = if (ownTransfer) bankOfVpa(vpa) else null,
            toAccount = if (ownTransfer) account else null
        )
    }

    // ------------------------------------------------------------------------
    // H3 · Debit-card purchase
    // ------------------------------------------------------------------------

    /**
     * `Rs.845.00 is debited from your HDFC Bank Debit Card ending 1234 at MERCHANT on
     * 3 Sep, 2026 at 16:14:34.`
     *
     * `accountLast4` is the **card's** last four, not the savings account's: the mail names
     * only the card, and the app joins card to account through its own card table. Guessing
     * the account here would be inventing a number.
     */
    private fun parseDebitCardPurchase(message: EmailMessage): ParsedTransaction? {
        val match = DEBIT_CARD_PURCHASE.find(message.body) ?: return null
        val card = last4(match.groupValues[2])
        val merchant = cleanText(match.groupValues[3])
        val at = DateParse.parse(match.groupValues[4], match.groupValues[5])

        // Issuer-match rule (spec 12 §12.1 H3): paying a card issuer from the debit card is
        // a transfer to that card, not spending. The table holds issuer names only, never a
        // user's own numbers, so it is safe to compile in.
        val issuer = merchant?.let { name ->
            ISSUER_MERCHANTS.entries.firstOrNull { name.contains(it.key, ignoreCase = true) }?.value
        }

        return build(
            message = message,
            amountRaw = match.groupValues[1],
            type = if (issuer != null) TransactionType.TRANSFER else TransactionType.EXPENSE,
            merchant = merchant,
            accountLast4 = card,
            at = at,
            isFromCard = true,
            fromAccount = if (issuer != null) card else null,
            toAccount = issuer
        )
    }

    // ------------------------------------------------------------------------
    // H4 · Card bill paid via NetBanking
    // ------------------------------------------------------------------------

    /**
     * `an amount of Rs. 2,53471.86 has been debited from your Account number XX1234 for
     * Credit Card Payment done using HDFC Bank Online Banking.`
     *
     * A transfer out of the savings account. `toAccount` stays null: the mail never names
     * the card that was paid, and the app settles that by matching the amount against open
     * card dues. The template carries no date, so the received time stands in.
     */
    private fun parseCardBillNetbanking(message: EmailMessage): ParsedTransaction? {
        val match = CC_PAYMENT_NETBANKING.find(message.body) ?: return null
        val account = last4(match.groupValues[2])

        return build(
            message = message,
            amountRaw = match.groupValues[1],
            type = TransactionType.TRANSFER,
            merchant = "Credit Card Payment",
            accountLast4 = account,
            at = null,
            fromAccount = account,
            toAccount = null
        )
    }

    // ------------------------------------------------------------------------
    // H5 · Personal-loan EMI auto-debit
    // ------------------------------------------------------------------------

    /**
     * `Rs. INR 21538.00 is deducted from your account ending XX1234 and added to EMI
     * 900000123 Chq 000000 REF account on 07-SEP-2026. The available balance in your
     * account is Rs. INR 412.00.`
     *
     * Two quirks the corpus forced: the `Rs. INR` double prefix (which `Money` resolves to
     * INR either way), and the older wording `is debited … and credited to EMI`, which the
     * same regex accepts.
     *
     * The loan account number goes into `reference`: it is the only identifier the mail
     * carries, and it is what links the debit to the loan the app tracks. The balance the
     * mail states goes into `balance`; when the older wording omits that sentence the field
     * stays null rather than carrying over last month's figure.
     */
    private fun parseLoanEmi(message: EmailMessage): ParsedTransaction? {
        val match = LOAN_EMI.find(message.body) ?: return null
        val at = DateParse.parse(match.groupValues[4])
        val balanceRaw = match.groupValues.getOrNull(5)?.takeIf { it.isNotBlank() }

        return build(
            message = message,
            amountRaw = match.groupValues[1],
            type = TransactionType.EXPENSE,
            merchant = "Loan EMI",
            reference = match.groupValues[3],
            accountLast4 = last4(match.groupValues[2]),
            balanceRaw = balanceRaw,
            at = at
        )
    }

    // ------------------------------------------------------------------------
    // H6 · Credit-card purchase
    // ------------------------------------------------------------------------

    /**
     * `Rs. 3417.00 has been debited from your HDFC Bank Credit Card ending 1234 towards
     * MERCHANT on 13 Sep, 2026 at 17:53:59.`
     *
     * Type CREDIT — spending on a credit card is borrowing, and the app keeps it apart from
     * an account debit. The merchant is kept raw; normalising it is the app's job.
     */
    private fun parseCreditCardPurchase(message: EmailMessage): ParsedTransaction? {
        val match = CREDIT_CARD_PURCHASE.find(message.body) ?: return null

        return build(
            message = message,
            amountRaw = match.groupValues[1],
            type = TransactionType.CREDIT,
            merchant = cleanText(match.groupValues[3]),
            accountLast4 = last4(match.groupValues[2]),
            at = DateParse.parse(match.groupValues[4], match.groupValues[5]),
            isFromCard = true
        )
    }

    // ------------------------------------------------------------------------
    // H7 · Charge without OTP (the mandate charge's twin)
    // ------------------------------------------------------------------------

    /**
     * `Thank you for using your HDFC Bank Card XX1234 for Rs. 530.5 at MERCHANT on
     * 08-09-2026 12:00:00. Authorization code: 482913`
     *
     * Arrives seconds apart from H8 for the same charge; the app's dedup collapses the two
     * and prefers H8, which carries the mandate id. The authorization code is this mail's
     * only identifier, so it becomes the reference. Note the amount here is often written
     * with a single decimal (`530.5`).
     */
    private fun parseCardChargeWithoutOtp(message: EmailMessage): ParsedTransaction? {
        val match = CARD_CHARGE_NO_OTP.find(message.body) ?: return null

        return build(
            message = message,
            amountRaw = match.groupValues[2],
            type = TransactionType.CREDIT,
            merchant = cleanText(match.groupValues[3]),
            reference = match.groupValues[6],
            accountLast4 = last4(match.groupValues[1]),
            at = DateParse.parse(match.groupValues[4], match.groupValues[5]),
            isFromCard = true
        )
    }

    // ------------------------------------------------------------------------
    // H8 · E-mandate paid
    // ------------------------------------------------------------------------

    /**
     * `Your MERCHANT bill, set up through E-mandate (Auto payment), has been successfully
     * paid using your HDFC Bank Credit Card ending 1234. Transaction Details: Amount: INR
     * 530.50 Date: 08/09/2026 Mandate ID: AB12cDefGH`
     *
     * The card word decides the type: a credit-card charge is CREDIT, a debit-card charge
     * (the monthly service bill in the corpus) is an account EXPENSE. The mandate id is
     * the reference, which is what settles the auto-pay due H9 raised.
     */
    private fun parseMandatePaid(message: EmailMessage): ParsedTransaction? {
        val match = EMANDATE_PAID.find(message.body) ?: return null
        val isCreditCard = match.groupValues[2].equals("Credit", ignoreCase = true)

        return build(
            message = message,
            amountRaw = match.groupValues[4],
            type = if (isCreditCard) TransactionType.CREDIT else TransactionType.EXPENSE,
            merchant = cleanText(match.groupValues[1]),
            reference = cleanReference(match.groupValues[6]),
            accountLast4 = last4(match.groupValues[3]),
            at = DateParse.parse(match.groupValues[5]),
            isFromCard = true
        )
    }

    // ------------------------------------------------------------------------
    // Shared construction and small helpers
    // ------------------------------------------------------------------------

    /**
     * Assembles the transaction, or returns null when the amount — the one field no
     * template may be missing — does not parse (spec 01 constraint C7).
     *
     * [at] is the date *inside* the body, which wins over the received time (spec 14 §14.2).
     * A template with no date of its own, or a date the tolerant parser cannot read, falls
     * back to the received time: that is an observed fact about the message rather than a
     * guess, and losing a real debit over a malformed date would be the worse failure.
     */
    private fun build(
        message: EmailMessage,
        amountRaw: String,
        type: TransactionType,
        merchant: String?,
        reference: String? = null,
        accountLast4: String?,
        balanceRaw: String? = null,
        at: LocalDateTime?,
        isFromCard: Boolean = false,
        fromAccount: String? = null,
        toAccount: String? = null
    ): ParsedTransaction? {
        val money = Money.parse(amountRaw) ?: return null

        return ParsedTransaction(
            amount = money.amount,
            type = type,
            merchant = merchant,
            reference = reference,
            accountLast4 = accountLast4,
            balance = balanceRaw?.let { Money.amount(it) },
            creditLimit = null,
            smsBody = message.body,
            sender = message.from,
            timestamp = at?.toEpochMillis() ?: message.receivedAt,
            bankName = BANK,
            isFromCard = isFromCard,
            currency = money.currency,
            fromAccount = fromAccount,
            toAccount = toAccount
        )
    }

    private fun isOwnVpa(vpa: String): Boolean {
        if (ownVpaMatchers.isEmpty()) return false
        val candidate = vpa.trim().lowercase()
        return ownVpaMatchers.any { it.matches(candidate) }
    }

    private fun isOwnName(name: String?): Boolean {
        if (ownNameKeys.isEmpty()) return false
        val key = nameKey(name) ?: return false
        return key in ownNameKeys
    }

    /**
     * The bank behind a UPI handle, for the `toAccount`/`fromAccount` of a self-transfer
     * (spec 12 §12.1 H1). An unknown handle leaves the counterpart null — the transfer is
     * still recorded, it just does not claim a bank it cannot know.
     */
    private fun bankOfVpa(vpa: String): String? =
        VPA_HANDLE_BANKS[vpa.substringAfter('@', "").trim().lowercase()]

    private fun LocalDateTime.toEpochMillis(): Long = atZone(IST).toInstant().toEpochMilli()

    private companion object {

        private const val BANK = "HDFC Bank"

        /** Every date in this sender's mail is written in Indian local time. */
        private val IST: ZoneId = ZoneId.of("Asia/Kolkata")

        // --- Template regexes, compiled once ---------------------------------
        //
        // Taken from spec 12 §12.1. Where one was adjusted, the comment says why.

        /**
         * H1, spec regex with two tolerances: the account run may carry the bank's `XX`
         * prefix and be longer than four digits (§12.1 writes it both ways within the same
         * section), and the space before the payee's bracket is optional, since the VPA
         * capture before it is lazy and non-space anyway.
         */
        private val UPI_DEBIT = Regex(
            """Rs\.?\s*([\d,]+\.?\d*)\s+is debited from your account ending\s+(?:XX)?(\d{4,})""" +
                """\s+towards VPA\s+(\S+?)\s*\(([^)]+)\)\s+on\s+(\d{2}-\d{2}-\d{2})\.?""" +
                """\s*UPI transaction reference no\.?\s*:?\s*(\d{12})""",
            RegexOption.IGNORE_CASE
        )

        /**
         * H2, spec regex with the same `XX`/longer-run tolerance on the account, and
         * optional whitespace before the reference's colon.
         */
        private val CREDIT_RECEIVED = Regex(
            """Rs\.?\s*([\d,]+\.?\d*)\s+has been successfully credited to your HDFC Bank account""" +
                """\s+ending in\s+(?:XX)?(\d{4,})[\s\S]{0,80}?Date:\s*(\d{2}-\d{2}-\d{2})""" +
                """[\s\S]{0,40}?Sender:\s*([^(]+?)\s*\(VPA:\s*([^)]+)\)""" +
                """[\s\S]{0,40}?UPI Reference No\.?\s*:\s*(\d{12})""",
            RegexOption.IGNORE_CASE
        )

        /** H3, spec regex verbatim. */
        private val DEBIT_CARD_PURCHASE = Regex(
            """Rs\.?\s*([\d,]+\.?\d*)\s+is debited from your HDFC Bank Debit Card ending\s+(\d{4})""" +
                """\s+at\s+(.+?)\s+on\s+(\d{1,2}\s+[A-Za-z]{3},?\s+\d{4})\s+at\s+(\d{2}:\d{2}:\d{2})""",
            RegexOption.IGNORE_CASE
        )

        /**
         * H4. Spec 12 §12.1 leaves this one to the parser ("as built in
         * `HDFCEmailParser.ccPaymentNetbanking`"), so it is written from the template
         * sentence: the amount, then the account run the template itself prefixes with `XX`.
         */
        private val CC_PAYMENT_NETBANKING = Regex(
            """an amount of\s+Rs\.?\s*(?:INR\s*)?([\d,]+\.?\d*)\s+has been debited from your""" +
                """\s+Account number\s+(?:XX)?(\d{4,})\s+for Credit Card Payment done using""" +
                """\s+HDFC Bank Online Banking""",
            RegexOption.IGNORE_CASE
        )

        /** H5, spec regex verbatim — it already covers both wordings and the optional balance. */
        private val LOAN_EMI = Regex(
            """Rs\.?\s*INR\s*([\d,]+\.?\d*)\s+is (?:deducted|debited) from your account ending""" +
                """\s+(?:XX)?(\d{4})\s+and (?:added|credited) to EMI\s+(\d{6,})""" +
                """[\s\S]{0,80}?on\s+(\d{2}-[A-Z]{3}-\d{4})""" +
                """(?:[\s\S]{0,80}?available balance in your account is\s+Rs\.?\s*INR\s*([\d,]+\.?\d*))?""",
            RegexOption.IGNORE_CASE
        )

        /** H6, spec regex verbatim. */
        private val CREDIT_CARD_PURCHASE = Regex(
            """Rs\.?\s*([\d,]+\.?\d*)\s+has been debited from your HDFC Bank Credit Card ending""" +
                """\s+(\d{4})\s+towards\s+(.+?)\s+on\s+(\d{1,2}\s+[A-Za-z]{3},?\s+\d{4})""" +
                """\s+at\s+(\d{2}:\d{2}:\d{2})""",
            RegexOption.IGNORE_CASE
        )

        /** H7, spec regex verbatim. */
        private val CARD_CHARGE_NO_OTP = Regex(
            """HDFC Bank Card\s+XX(\d{4})\s+for\s+Rs\.?\s*([\d,]+\.?\d*)\s+at\s+(.+?)""" +
                """\s+on\s+(\d{2}-\d{2}-\d{4})\s+(\d{2}:\d{2}:\d{2})\.?\s*Authorization code:\s*(\d+)""",
            RegexOption.IGNORE_CASE
        )

        /**
         * H8, spec regex with the two field separators widened from `\s+` to `[\s|]+`: the
         * transaction details sit in a table in the HTML part, and spec 11 §11.5 joins table
         * cells with ` | `, so a body extracted from HTML would otherwise miss the template.
         */
        private val EMANDATE_PAID = Regex(
            """Your\s+(.+?)\s+bill, set up through E-mandate[\s\S]{0,60}?paid using your""" +
                """\s+HDFC Bank (Credit|Debit) Card ending\s+(\d{4})[\s\S]{0,60}?""" +
                """Amount:\s*INR\s*([\d,]+\.?\d*)[\s|]+Date:\s*(\d{2}/\d{2}/\d{4})""" +
                """[\s|]+Mandate ID:\s*(\S+)""",
            RegexOption.IGNORE_CASE
        )

        /**
         * H11's ignore set, plus H9 and H10 — the two mandate templates that describe a
         * charge that has not happened yet or a subscription that changed state. Checked
         * only after the money templates have been ruled out, so a real alert's footer
         * cannot silence it.
         */
        private val NON_TRANSACTION_MARKERS = listOf(
            "is the OTP",
            "OTP For online Ecom Transaction",
            "One-Time Password",
            "One Time Password",
            "added a payee",
            "transfer limit",
            "Login PIN",
            "Relationship Manager",
            "terms and conditions",
            "There is an upcoming E-mandate",
            "AutoPay (E-mandate)"
        )

        /**
         * UPI handles whose bank spec 12 §12.1 states. An unlisted handle resolves to null
         * rather than to a guessed bank.
         */
        private val VPA_HANDLE_BANKS = mapOf(
            "okaxis" to "Axis Bank",
            "oksbi" to "State Bank of India",
            "okhdfcbank" to BANK
        )

        /**
         * Merchants that are really another card issuer being paid, which makes the debit a
         * transfer rather than spending (spec 12 §12.1 H3).
         */
        private val ISSUER_MERCHANTS = mapOf(
            "FPL Technologies" to "OneCard"
        )

        private val WHITESPACE = Regex("""\s+""")

        /** Trailing four digits of a captured run — `XX561234` is account `1234` (spec 12). */
        private fun last4(raw: String?): String? {
            val digits = raw?.filter { it.isDigit() } ?: return null
            return if (digits.length >= 4) digits.takeLast(4) else null
        }

        /** Collapses the whitespace HTML-to-text leaves behind and drops cell punctuation. */
        private fun cleanText(raw: String?): String? {
            if (raw == null) return null
            val cleaned = WHITESPACE.replace(raw, " ").trim().trim('|', '.', ',', ' ').trim()
            return cleaned.ifEmpty { null }
        }

        /** A reference can pick up the sentence's full stop; the id itself does not have one. */
        private fun cleanReference(raw: String?): String? {
            val cleaned = raw?.trim()?.trimEnd('.', ',', '|', ';')?.trim()
            return cleaned?.ifEmpty { null }
        }

        /** Case- and spacing-insensitive key for comparing a payee against the user's name. */
        private fun nameKey(raw: String?): String? {
            if (raw == null) return null
            val cleaned = WHITESPACE.replace(raw, " ").trim().lowercase()
            return cleaned.ifEmpty { null }
        }

        /**
         * Turns a settings entry into a matcher. `*` is the only wildcard, because that is
         * how the own-VPA list is written when one entry stands for a family of handles;
         * every other character is matched literally.
         */
        private fun globToRegex(pattern: String): Regex? {
            val cleaned = pattern.trim().lowercase()
            if (cleaned.isEmpty()) return null
            val body = cleaned.split("*").joinToString(".*") { Regex.escape(it) }
            return runCatching { Regex(body) }.getOrNull()
        }
    }
}
