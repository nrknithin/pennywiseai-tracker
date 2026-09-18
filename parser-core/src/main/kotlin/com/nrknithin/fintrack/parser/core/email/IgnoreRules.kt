package com.nrknithin.fintrack.parser.core.email

/**
 * What the pipeline should do with a message before any parser sees it.
 */
enum class IgnoreOutcome {
    /** Nothing matched — hand the message to the parsers. */
    PARSE,

    /** Marketing, OTP, or a known-noise sender. Logged with `IGNORE:<ruleId>` and dropped. */
    DROP,

    /**
     * From a real financial sender, but not about money moving — a password change, a
     * payee added. Logged `NON_TXN`, and still available for the due parsers, which run
     * before this check (spec 11 §11.4).
     */
    NON_TXN
}

/** The verdict plus the rule that produced it, so the Email Log can show *why*. */
data class IgnoreDecision(
    val outcome: IgnoreOutcome,
    val ruleId: String? = null
) {
    companion object {
        val PARSE = IgnoreDecision(IgnoreOutcome.PARSE)
    }
}

/**
 * The pre-parse filter (spec 12 §12.10).
 *
 * Runs before every parser so OTPs, marketing and lifecycle mail never reach one. The
 * seeded lists below are what the corpus showed; the user can extend them from
 * Settings → Email → Ignored senders, passed in as `extraIgnoredSenders`.
 *
 * **Two deliberate narrowings of the spec's wording.** Spec 12 §12.10 says a message is
 * dropped when the subject "contains" any of a keyword list that includes `offer`,
 * `deal`, `sale`, `IPO`, `survey` and `%`. Taken as plain substring matching, `sale`
 * drops anything saying "wholesale", `deal` drops "dealt with", and `%` drops any subject
 * quoting a rate — including a genuine bank mail. Dropping a real bill is silent data
 * loss, so those short words are matched on word boundaries, and `%` only when it follows
 * a digit (`50%`), which is the marketing shape the rule is aimed at. Distinctive phrases
 * are still matched as plain substrings.
 */
object IgnoreRules {

    /** Multi-word phrases that are unambiguous — matched anywhere in the subject. */
    private val SUBJECT_PHRASES = listOf(
        "one-time password",
        "security code",
        "login pin",
        "new device",
        "new login",
        "newsletter",
        "webinar",
        "bobcoins"
    )

    /**
     * Short words that need a word boundary. `password` is here rather than in the phrase
     * list so "password" matches but "passwordless" does not.
     */
    private val SUBJECT_WORDS = listOf(
        "otp",
        "password",
        "offer",
        "deal",
        "deals",
        "sale",
        "ipo",
        "survey"
    )

    /** A percentage figure, the shape the spec's bare `%` rule is actually aimed at. */
    private val PERCENTAGE = Regex("""\d\s*%""")

    /**
     * Body phrases that mark a *bank* mail as not-a-transaction. These produce NON_TXN
     * rather than DROP: the message stays in the log and the due parsers still see it.
     */
    private val BODY_NON_TXN_PHRASES = listOf(
        "is the otp",
        "added a payee",
        "transfer limit",
        "terms and conditions of using",
        "relationship manager"
    )

    /**
     * Senders whose every message is marketing. Entries are matched as a suffix or a
     * substring of the address, so `axis_system@` and `tatacliq@mall.` work as written in
     * the spec, and `*@getzype.com` is stored as the bare domain.
     */
    private val MARKETING_SENDERS = listOf(
        "information@mailers.hdfcbank.bank.in",
        "axis_system@",
        "services@custcomm.icici",
        "services@customer.icici",
        "sbi@communications.sbi",
        "notify@getonecard.app",
        "help@getonecard.app",
        "americanexpress@email.",
        "impinfo@mailer.airtel.com",
        "mail@info.paytm.com",
        "store-news@amazon.in",
        "marketplace-messages@amazon.in",
        // Deliberately NOT dropped, though spec 12 §12.10 seeds them:
        //   order-update@amazon.in, shipment-tracking@amazon.in
        // Spec 24 §24.2 names order-update@ as a receipt parser source, and R-24-01
        // requires the Ordered -> Shipped -> Delivered progression on one receipt. Since
        // ignore rules run first and DROP short-circuits everything (spec 11 §11.4 step a),
        // seeding them here would make that requirement unimplementable. Until the M5
        // receipt parsers exist these simply land as NO_PARSER, which is logged and
        // harmless; the user can still ignore them by hand from Settings.
        "tatacliq@mall.",
        "info@luxenotification.",
        "shop@fashioncollections.",
        "info@members.netflix.com",
        "noreply@groww.in",
        "support@cleartax.in",
        "no-reply@custcomm.icicilombard.com",
        "renewals@e.godaddy.com",
        "getzype.com",
        "talent@ibm.com",
        "ibm@email.ibm.com"
    )

    /**
     * Evaluates one message.
     *
     * @param extraIgnoredSenders the user's own additions, matched the same way as the
     *   seeded marketing list.
     */
    fun evaluate(
        message: EmailMessage,
        extraIgnoredSenders: Collection<String> = emptyList()
    ): IgnoreDecision {
        val from = EmailSenderRegistry.normaliseAddress(message.from) ?: message.from.lowercase()

        for (pattern in MARKETING_SENDERS) {
            if (from.contains(pattern.lowercase())) {
                return IgnoreDecision(IgnoreOutcome.DROP, "SENDER:$pattern")
            }
        }
        for (pattern in extraIgnoredSenders) {
            val cleaned = pattern.trim().lowercase()
            if (cleaned.isNotEmpty() && from.contains(cleaned)) {
                return IgnoreDecision(IgnoreOutcome.DROP, "USER_SENDER:$cleaned")
            }
        }

        val subject = message.subject.lowercase()
        for (phrase in SUBJECT_PHRASES) {
            if (subject.contains(phrase)) return IgnoreDecision(IgnoreOutcome.DROP, "SUBJECT:$phrase")
        }
        for (word in SUBJECT_WORDS) {
            if (containsWord(subject, word)) return IgnoreDecision(IgnoreOutcome.DROP, "SUBJECT:$word")
        }
        if (PERCENTAGE.containsMatchIn(subject)) {
            return IgnoreDecision(IgnoreOutcome.DROP, "SUBJECT:percentage")
        }

        val body = message.body.lowercase()
        for (phrase in BODY_NON_TXN_PHRASES) {
            if (body.contains(phrase)) return IgnoreDecision(IgnoreOutcome.NON_TXN, "BODY:$phrase")
        }

        return IgnoreDecision.PARSE
    }

    /**
     * Word-boundary containment that treats anything other than a letter or digit as a
     * boundary, so "offer" matches in "Special offer!" but not in "offering".
     */
    private fun containsWord(haystack: String, word: String): Boolean {
        var index = haystack.indexOf(word)
        while (index >= 0) {
            val beforeOk = index == 0 || !haystack[index - 1].isLetterOrDigit()
            val after = index + word.length
            val afterOk = after >= haystack.length || !haystack[after].isLetterOrDigit()
            if (beforeOk && afterOk) return true
            index = haystack.indexOf(word, index + 1)
        }
        return false
    }
}
