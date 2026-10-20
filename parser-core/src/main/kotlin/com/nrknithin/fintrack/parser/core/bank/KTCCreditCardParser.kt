package com.nrknithin.fintrack.parser.core.bank

import com.nrknithin.fintrack.parser.core.ParsedTransaction
import com.nrknithin.fintrack.parser.core.TransactionType

/**
 * KTC Credit Card parser for Thai banking SMS messages.
 * Handles credit card spending with available limit extraction.
 */
class KTCCreditCardParser : BaseThailandBankParser() {

    override fun getBankName() = "KTC"

    override fun canHandle(sender: String): Boolean {
        val upperSender = sender.uppercase()
        return upperSender == "KTC" ||
                upperSender.contains("KRUNGTHAI CARD")
    }

    override fun parse(smsBody: String, sender: String, timestamp: Long): ParsedTransaction? {
        val parsed = super.parse(smsBody, sender, timestamp) ?: return null

        val creditLimit = extractAvailableLimit(smsBody)

        return parsed.copy(
            type = parsed.type ?: TransactionType.CREDIT,
            isFromCard = true,
            creditLimit = creditLimit
        )
    }
}
