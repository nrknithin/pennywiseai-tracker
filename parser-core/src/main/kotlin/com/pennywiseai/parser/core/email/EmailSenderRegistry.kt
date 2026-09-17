package com.pennywiseai.parser.core.email

/**
 * Maps a From-address to the bank/merchant behind it (spec 11 §11.6, corpus in spec 10).
 *
 * Two jobs:
 *  - **Server-side filtering.** [searchTerms] feeds the IMAP `OR(FROM …)` search so the
 *    phone downloads only mail that could possibly be financial (spec 11 R-11-03).
 *  - **Parser dispatch.** [resolve] returns the bank name and the SMS alias, so an email
 *    body can be routed to the bank's existing SMS parser before its email-specific
 *    parser corrects the fields.
 *
 * Resolution order for `a@b.c.d`, first hit wins: override by exact address → override by
 * domain walking parents → registry by exact address → registry by domain walking parents.
 * Walking parents means `alerts@x.hdfcbank.bank.in` still resolves via `hdfcbank.bank.in`.
 */
object EmailSenderRegistry {

    private const val HDFC = "HDFC Bank"
    private const val AXIS = "Axis Bank"
    private const val ICICI = "ICICI Bank"
    private const val SBI = "State Bank of India"
    private const val IDFC = "IDFC FIRST Bank"

    /**
     * Exact-address entries, stored lower-cased. The corpus writes some of these in mixed
     * case (`Notification@jio.com`, `ETollFFAdmin@…`); addresses are lower-cased on the way
     * in so both spellings resolve.
     */
    private val BY_ADDRESS: Map<String, SenderMapping> = mapOf(
        // --- HDFC ---------------------------------------------------------------
        "alerts@hdfcbank.bank.in" to SenderMapping(HDFC, "HDFCBK"),
        "information@hdfcbank.bank.in" to SenderMapping(HDFC, "HDFCBK"),
        "hdfcbanksmartstatement@hdfcbank.bank.in" to SenderMapping(HDFC, "HDFCBK"),
        "emailstatements.cards@hdfcbank.bank.in" to SenderMapping(HDFC, "HDFCBK"),
        "termloans.creditcard@hdfcbank.bank.in" to SenderMapping(HDFC, "HDFCBK"),
        "loansupport@hdfcbank.com" to SenderMapping(HDFC, "HDFCBK"),
        "no-reply@updates.hdfcbank.net" to SenderMapping(HDFC, "HDFCBK"),
        "information@mailers.hdfcbank.bank.in" to SenderMapping(HDFC, "HDFCBK", marketingOnly = true),

        // --- Axis ---------------------------------------------------------------
        "alerts@axis.bank.in" to SenderMapping(AXIS, "AXISBK"),
        "statements@axis.bank.in" to SenderMapping(AXIS, "AXISBK"),
        "cc.statements@axis.bank.in" to SenderMapping(AXIS, "AXISBK"),
        "axis_system@axis.bank.in" to SenderMapping(AXIS, "AXISBK", marketingOnly = true),

        // --- ICICI --------------------------------------------------------------
        "credit_cards@icici.bank.in" to SenderMapping(ICICI, "ICICIB"),
        "services@custcomm.icici.bank.in" to SenderMapping(ICICI, "ICICIB", marketingOnly = true),
        "services@customer.icici.bank.in" to SenderMapping(ICICI, "ICICIB", marketingOnly = true),

        // --- SBI ----------------------------------------------------------------
        "donotreply.sbiatm@alerts.sbi.co.in" to SenderMapping(SBI, "SBIINB"),
        "sbi@communications.sbi.co.in" to SenderMapping(SBI, "SBIINB", marketingOnly = true),

        // --- OneCard (issued on Federal Bank) ------------------------------------
        "no-reply@getonecard.app" to SenderMapping("OneCard", "FEDBNK"),
        "statement@getonecard.app" to SenderMapping("OneCard", "FEDBNK"),
        "notify@getonecard.app" to SenderMapping("OneCard", "FEDBNK", marketingOnly = true),
        "help@getonecard.app" to SenderMapping("OneCard", "FEDBNK", marketingOnly = true),

        // --- IDFC ---------------------------------------------------------------
        "etollffadmin@idfcfirstbank.com" to SenderMapping(IDFC, "IDFCFB"),

        // --- Aggregators and gateways -------------------------------------------
        "protect@cred.club" to SenderMapping("CRED"),
        "talk@cred.club" to SenderMapping("CRED"),
        "no-reply@amazonpay.in" to SenderMapping("Amazon Pay"),
        "hdfcbankbillpay@billdesk.in" to SenderMapping("BillDesk"),
        "no-reply@razorpay.com" to SenderMapping("Razorpay"),
        "care@cashfreemail.com" to SenderMapping("Cashfree"),

        // --- Subscriptions and SaaS ---------------------------------------------
        "googleplay-noreply@google.com" to SenderMapping("Google Play"),
        "mailer@xsolla.com" to SenderMapping("Xsolla", preferDMY = false),
        "noreply@pgsharp.com" to SenderMapping("PGSharp"),
        "noreply@notify.cloudflare.com" to SenderMapping("Cloudflare"),
        "renewals@godaddy.com" to SenderMapping("GoDaddy"),
        "renewals@e.godaddy.com" to SenderMapping("GoDaddy", marketingOnly = true),
        "noreply@x.ai" to SenderMapping("xAI", marketingOnly = true),
        "info@members.netflix.com" to SenderMapping("Netflix", marketingOnly = true),

        // --- Telecom and insurance ----------------------------------------------
        "ebill@airtel.com" to SenderMapping("Airtel"),
        "update@airtel.com" to SenderMapping("Airtel"),
        "impinfo@mailer.airtel.com" to SenderMapping("Airtel", marketingOnly = true),
        "notification@jio.com" to SenderMapping("Jio"),
        "communications@policybazaar.com" to SenderMapping("PolicyBazaar"),
        "noreply@godigit.com" to SenderMapping("Digit Insurance"),
        "no-reply@custcomm.icicilombard.com" to SenderMapping("ICICI Lombard", marketingOnly = true),

        // --- Shopping -------------------------------------------------------------
        "auto-confirm@amazon.in" to SenderMapping("Amazon.in"),
        "noreply@updates.tatacliq.com" to SenderMapping("Tata CLiQ"),
        "ss@jiomart.com" to SenderMapping("JioMart"),
        "order-update@amazon.in" to SenderMapping("Amazon.in", marketingOnly = true),
        "shipment-tracking@amazon.in" to SenderMapping("Amazon.in", marketingOnly = true),
        "marketplace-messages@amazon.in" to SenderMapping("Amazon.in", marketingOnly = true),
        "store-news@amazon.in" to SenderMapping("Amazon.in", marketingOnly = true),

        // --- Amex: alerts arrive by SMS only; all mail here is marketing or OTP ----
        "americanexpress@welcome.americanexpress.com" to SenderMapping("American Express", marketingOnly = true),
        "americanexpress@email.americanexpress.com" to SenderMapping("American Express", marketingOnly = true)
    )

    /**
     * Domain entries, used when the exact address is unknown. A bank rotating its local
     * part (`alerts2@hdfcbank.bank.in`) still resolves; the message then falls to the
     * bank's generic email parser rather than a template-specific one.
     */
    private val BY_DOMAIN: Map<String, SenderMapping> = mapOf(
        "hdfcbank.bank.in" to SenderMapping(HDFC, "HDFCBK"),
        "hdfcbank.com" to SenderMapping(HDFC, "HDFCBK"),
        "updates.hdfcbank.net" to SenderMapping(HDFC, "HDFCBK"),
        "axis.bank.in" to SenderMapping(AXIS, "AXISBK"),
        "icici.bank.in" to SenderMapping(ICICI, "ICICIB"),
        "alerts.sbi.co.in" to SenderMapping(SBI, "SBIINB"),
        "getonecard.app" to SenderMapping("OneCard", "FEDBNK"),
        "idfcfirstbank.com" to SenderMapping(IDFC, "IDFCFB"),
        "cred.club" to SenderMapping("CRED"),
        "amazonpay.in" to SenderMapping("Amazon Pay"),
        "billdesk.in" to SenderMapping("BillDesk"),
        "razorpay.com" to SenderMapping("Razorpay"),
        "cashfreemail.com" to SenderMapping("Cashfree"),
        "xsolla.com" to SenderMapping("Xsolla", preferDMY = false),
        "pgsharp.com" to SenderMapping("PGSharp"),
        "notify.cloudflare.com" to SenderMapping("Cloudflare"),
        "godaddy.com" to SenderMapping("GoDaddy"),
        "airtel.com" to SenderMapping("Airtel"),
        "jio.com" to SenderMapping("Jio"),
        "policybazaar.com" to SenderMapping("PolicyBazaar"),
        "godigit.com" to SenderMapping("Digit Insurance"),
        "updates.tatacliq.com" to SenderMapping("Tata CLiQ"),
        "jiomart.com" to SenderMapping("JioMart")
    )

    /**
     * Resolves a From-address, consulting caller-supplied overrides first.
     *
     * [overrides] is keyed by either a full address or a domain, both lower-cased; it
     * carries the user's `sender_overrides` rows so a new sender can be taught to the
     * app from the Email Log without a rebuild (spec 11 R-11-10).
     */
    fun resolve(from: String?, overrides: Map<String, SenderMapping> = emptyMap()): SenderMapping? {
        val address = normaliseAddress(from) ?: return null
        val domain = address.substringAfter("@", "")

        overrides[address]?.let { return it }
        for (candidate in parentDomains(domain)) {
            overrides[candidate]?.let { return it }
        }

        BY_ADDRESS[address]?.let { return it }
        for (candidate in parentDomains(domain)) {
            BY_DOMAIN[candidate]?.let { return it }
        }

        return null
    }

    /** True when the app knows this sender at all — drives `NO_PARSER` vs `UNKNOWN_SENDER`. */
    fun isKnownSender(from: String?, overrides: Map<String, SenderMapping> = emptyMap()): Boolean =
        resolve(from, overrides) != null

    /**
     * Terms for the IMAP server-side `OR(FROM …)` search. Domains are enough — IMAP's
     * FROM search is a substring match, so one domain term covers every local part and
     * keeps the search string short enough for Gmail to accept.
     *
     * [extraSenders] carries `settings.email.extraSenders` (default `godaddy.com;pgsharp.com`).
     */
    fun searchTerms(extraSenders: Collection<String> = emptyList()): List<String> {
        val terms = LinkedHashSet<String>()
        terms.addAll(BY_DOMAIN.keys)
        // Addresses whose domain is not itself in the registry still need their own term.
        for (address in BY_ADDRESS.keys) {
            val domain = address.substringAfter("@", "")
            if (parentDomains(domain).none { it in BY_DOMAIN }) terms.add(address)
        }
        for (extra in extraSenders) {
            val cleaned = extra.trim().lowercase()
            if (cleaned.isNotEmpty()) terms.add(cleaned)
        }
        return terms.toList()
    }

    /** All addresses and domains the registry knows, for the Email Settings screen. */
    fun knownSenders(): List<String> = (BY_ADDRESS.keys + BY_DOMAIN.keys).sorted()

    /**
     * Extracts the bare address from a From header, which may arrive as
     * `"HDFC Bank Alerts" <alerts@hdfcbank.bank.in>`.
     */
    fun normaliseAddress(from: String?): String? {
        if (from.isNullOrBlank()) return null
        val inAngles = from.substringAfter("<", "").substringBefore(">", "")
        val candidate = if (inAngles.contains("@")) inAngles else from
        val cleaned = candidate.trim().trim('<', '>', '"', ' ').lowercase()
        return if (cleaned.contains("@")) cleaned else null
    }

    /** `a.b.c.d` -> [a.b.c.d, b.c.d, c.d] — stops before the bare TLD. */
    private fun parentDomains(domain: String): List<String> {
        if (domain.isEmpty()) return emptyList()
        val parts = domain.split(".")
        if (parts.size < 2) return listOf(domain)
        return (0..parts.size - 2).map { parts.subList(it, parts.size).joinToString(".") }
    }
}
