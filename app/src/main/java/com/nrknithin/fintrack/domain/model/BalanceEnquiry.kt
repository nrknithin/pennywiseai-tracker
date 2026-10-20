package com.nrknithin.fintrack.domain.model

/**
 * Banks' missed-call balance enquiry numbers, from each bank's own site. A call from
 * the mobile number registered with the bank is cut after a ring, and the bank replies
 * by SMS with the balance; the SMS parser turns that reply into a balance update
 * (`parseBalanceEnquiryReply`). Keyed by the parser's bank name.
 */
object BalanceEnquiry {

    private val missedCallNumbers = mapOf(
        // SBI Quick (domestic). Registration once: SMS "REG <account number>" to 07208933148.
        "State Bank of India" to "+919223766666",
        "HDFC Bank" to "18002703333",
        "Axis Bank" to "18004195959"
    )

    /** SBI Quick needs a one-time registration before the missed call works. */
    const val SBI_QUICK_REGISTRATION_NUMBER = "07208933148"

    fun missedCallNumber(bankName: String): String? = missedCallNumbers[bankName]
}
