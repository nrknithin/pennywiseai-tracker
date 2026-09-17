package com.pennywiseai.parser.core.email

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * The sender registry decides two things that the rest of the channel depends on: which mail
 * is downloaded at all, and which bank's parser family a message is handed to (spec 11 §11.6).
 * A wrong answer here is invisible — the mail simply never arrives — so the resolution order
 * and the domain walk are pinned here rather than left to the parsers to discover.
 */
class EmailSenderRegistryTest {

    private data class ResolveCase(
        val name: String,
        val from: String,
        val bankName: String?,
        val smsAlias: String? = null
    )

    @TestFactory
    fun `known senders resolve to their bank and SMS alias`(): List<DynamicTest> = listOf(
        ResolveCase("HDFC alerts", "alerts@hdfcbank.bank.in", "HDFC Bank", "HDFCBK"),
        ResolveCase("HDFC preclosure desk", "information@hdfcbank.bank.in", "HDFC Bank", "HDFCBK"),
        ResolveCase("Axis alerts", "alerts@axis.bank.in", "Axis Bank", "AXISBK"),
        ResolveCase("Axis card statements", "cc.statements@axis.bank.in", "Axis Bank", "AXISBK"),
        ResolveCase("ICICI cards", "credit_cards@icici.bank.in", "ICICI Bank", "ICICIB"),
        ResolveCase("SBI ATM", "donotreply.sbiatm@alerts.sbi.co.in", "State Bank of India", "SBIINB"),
        // OneCard is issued on Federal Bank, so its mail can reuse the Federal SMS parsers
        // even though the user knows the product as OneCard.
        ResolveCase("OneCard", "no-reply@getonecard.app", "OneCard", "FEDBNK"),
        // Senders with no SMS counterpart carry no alias and are served only by email parsers.
        ResolveCase("GoDaddy", "renewals@godaddy.com", "GoDaddy", null),
        ResolveCase("PGSharp", "noreply@pgsharp.com", "PGSharp", null),
        ResolveCase("CRED", "protect@cred.club", "CRED", null)
    ).map { case ->
        dynamicTest(case.name) {
            val mapping = EmailSenderRegistry.resolve(case.from)
            assertNotNull(mapping, "Expected " + case.from + " to resolve")
            assertEquals(case.bankName, mapping!!.bankName, "bankName for " + case.from)
            assertEquals(case.smsAlias, mapping.smsAlias, "smsAlias for " + case.from)
        }
    }

    @TestFactory
    fun `addresses are normalised out of a From header`(): List<DynamicTest> = listOf(
        "bare address" to "alerts@hdfcbank.bank.in",
        "with display name" to "\"HDFC Bank Alerts\" <alerts@hdfcbank.bank.in>",
        "angle brackets only" to "<alerts@hdfcbank.bank.in>",
        "mixed case" to "Alerts@HDFCBank.Bank.In",
        "padded" to "   alerts@hdfcbank.bank.in   "
    ).map { (name, header) ->
        dynamicTest(name) {
            assertEquals("alerts@hdfcbank.bank.in", EmailSenderRegistry.normaliseAddress(header))
            assertEquals("HDFC Bank", EmailSenderRegistry.resolve(header)?.bankName)
        }
    }

    @Test
    fun `an unknown local part still resolves through its domain`() {
        // Banks rotate local parts without warning. The domain walk is what keeps a renamed
        // sender from silently disappearing from the sync.
        val mapping = EmailSenderRegistry.resolve("alerts2@hdfcbank.bank.in")
        assertEquals("HDFC Bank", mapping?.bankName)
        assertEquals("HDFCBK", mapping?.smsAlias)
    }

    @Test
    fun `a subdomain resolves through its parent domain`() {
        val mapping = EmailSenderRegistry.resolve("noreply@mailer.hdfcbank.bank.in")
        assertEquals("HDFC Bank", mapping?.bankName)
    }

    @Test
    fun `an unrelated sender resolves to nothing`() {
        assertNull(EmailSenderRegistry.resolve("someone@example.com"))
        assertFalse(EmailSenderRegistry.isKnownSender("someone@example.com"))
    }

    @Test
    fun `malformed input is rejected rather than guessed at`() {
        assertNull(EmailSenderRegistry.normaliseAddress(null))
        assertNull(EmailSenderRegistry.normaliseAddress(""))
        assertNull(EmailSenderRegistry.normaliseAddress("not-an-address"))
        assertNull(EmailSenderRegistry.resolve("not-an-address"))
    }

    @Test
    fun `an override beats the compiled registry`() {
        // This is how a user teaches the app a new sender from the Email Log without a
        // rebuild (spec 11 R-11-10), and how they redirect one the registry got wrong.
        val overrides = mapOf(
            "alerts@hdfcbank.bank.in" to SenderMapping("Test Bank", "TESTBK")
        )
        assertEquals("Test Bank", EmailSenderRegistry.resolve("alerts@hdfcbank.bank.in", overrides)?.bankName)
        // ...and does not leak into other senders.
        assertEquals("Axis Bank", EmailSenderRegistry.resolve("alerts@axis.bank.in", overrides)?.bankName)
    }

    @Test
    fun `a domain override applies to every local part under it`() {
        val overrides = mapOf("newbank.example" to SenderMapping("New Bank", "NEWBK"))
        assertEquals("New Bank", EmailSenderRegistry.resolve("alerts@newbank.example", overrides)?.bankName)
        assertEquals("New Bank", EmailSenderRegistry.resolve("statements@newbank.example", overrides)?.bankName)
    }

    @Test
    fun `marketing-only senders are known but flagged`() {
        // Knowing them matters: it is what makes their mail `IGNORE` rather than
        // `UNKNOWN_SENDER`, so the Email Log can explain why nothing was created.
        val mapping = EmailSenderRegistry.resolve("axis_system@axis.bank.in")
        assertNotNull(mapping)
        assertTrue(mapping!!.marketingOnly, "axis_system should be flagged marketing-only")
    }

    @Test
    fun `Xsolla is flagged as US-ordered dates`() {
        // The one sender in the corpus that writes MM/DD/YYYY. Getting this wrong misreads
        // every invoice dated on or before the 12th of a month.
        assertFalse(EmailSenderRegistry.resolve("mailer@xsolla.com")!!.preferDMY)
        assertTrue(EmailSenderRegistry.resolve("alerts@hdfcbank.bank.in")!!.preferDMY)
    }

    @Test
    fun `search terms cover every known sender and include the user's extras`() {
        val terms = EmailSenderRegistry.searchTerms(listOf("godaddy.com", "pgsharp.com"))

        assertTrue(terms.isNotEmpty(), "search terms must not be empty or nothing is fetched")
        assertTrue(terms.contains("hdfcbank.bank.in"), "expected the HDFC domain in the search terms")
        assertTrue(terms.contains("godaddy.com"), "expected the user's extra sender")

        // Every registry entry must be reachable by at least one term, or its mail is never
        // downloaded and the parser for it can never fire.
        for (sender in EmailSenderRegistry.knownSenders()) {
            assertTrue(
                terms.any { sender.endsWith(it) || sender == it },
                "No search term would fetch mail from " + sender
            )
        }
    }

    @Test
    fun `search terms are de-duplicated and lower-cased`() {
        val terms = EmailSenderRegistry.searchTerms(listOf("GoDaddy.com", "  godaddy.com  "))
        assertEquals(terms.size, terms.toSet().size, "search terms contain duplicates: " + terms)
        assertTrue(terms.all { it == it.lowercase() }, "search terms must be lower-cased")
    }
}
