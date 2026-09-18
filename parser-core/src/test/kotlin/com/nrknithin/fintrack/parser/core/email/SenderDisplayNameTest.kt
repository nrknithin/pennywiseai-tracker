package com.nrknithin.fintrack.parser.core.email

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * The text rules behind sender discovery: what counts as a name, and what counts as a domain.
 *
 * Both answers are load-bearing in a way that is easy to miss. A wrong name is written into
 * `sender_overrides.display_name` and is then what the owner sees on every proposal and every
 * settings row; a wrong domain either splits one merchant across several rows or merges two
 * merchants into one. Neither shows up as a failure anywhere else, so it is pinned here.
 *
 * Every address is invented (spec 06 §6.2). The merchant-ish ones use the reserved `.example`
 * TLD, and the one that needs a real multi-label suffix uses `co.in` with a placeholder
 * mailbox — `irctc.co.in` is the shape this heuristic exists for, but the registry already
 * knows that one, so a stand-in is used rather than a real order sender.
 */
class SenderDisplayNameTest {

    private data class PhraseCase(
        val name: String,
        val phrase: String?,
        val address: String,
        val expected: String?
    )

    @TestFactory
    fun `a phrase becomes a name only when it is one`(): List<DynamicTest> = listOf(
        // ---- kept -----------------------------------------------------------------------
        PhraseCase(
            name = "a plain brand phrase is kept exactly as sent",
            phrase = "Grocer Fresh",
            address = "orders@grocer.example",
            expected = "Grocer Fresh"
        ),
        PhraseCase(
            name = "the sender's own casing survives — it is the only authority for it",
            phrase = "GrocerFresh",
            address = "orders@grocer.example",
            expected = "GrocerFresh"
        ),
        PhraseCase(
            name = "surrounding quotes are unwrapped",
            phrase = "\"Grocer Fresh\"",
            address = "orders@grocer.example",
            expected = "Grocer Fresh"
        ),
        PhraseCase(
            name = "a comma inside a quoted phrase is part of the name",
            phrase = "\"Grocer Fresh, Ltd.\"",
            address = "orders@grocer.example",
            expected = "Grocer Fresh, Ltd."
        ),
        PhraseCase(
            name = "escaped quotes inside a quoted phrase are unescaped, not dropped",
            phrase = "\"Grocer \\\"Daily\\\" Fresh\"",
            address = "orders@grocer.example",
            expected = "Grocer \"Daily\" Fresh"
        ),
        PhraseCase(
            name = "an escaped backslash collapses to one",
            phrase = "\"Grocer \\\\ Fresh\"",
            address = "orders@grocer.example",
            expected = "Grocer \\ Fresh"
        ),
        PhraseCase(
            name = "surrounding whitespace is trimmed",
            phrase = "   Grocer Fresh   ",
            address = "orders@grocer.example",
            expected = "Grocer Fresh"
        ),
        PhraseCase(
            // The rule is "parses as an address", not "contains an at-sign": rejecting on the
            // character alone would throw away a name the sender really did send.
            name = "an at-sign inside a real phrase does not make it an address",
            phrase = "Coffee @ Grocer",
            address = "orders@grocer.example",
            expected = "Coffee @ Grocer"
        ),
        PhraseCase(
            name = "a phrase matching the first label of the domain is still a name",
            phrase = "Grocer",
            address = "orders@grocer.example",
            expected = "Grocer"
        ),
        PhraseCase(
            name = "a name of exactly the maximum length is kept",
            phrase = "G".repeat(SenderDisplayName.MAX_NAME_LENGTH),
            address = "orders@grocer.example",
            expected = "G".repeat(SenderDisplayName.MAX_NAME_LENGTH)
        ),
        PhraseCase(
            // Nothing to compare the phrase against, so the phrase has not been shown to be a
            // mailbox name. Weakening the check beats failing the call.
            name = "an unparseable address leaves the phrase standing",
            phrase = "Grocer Fresh",
            address = "not-an-address",
            expected = "Grocer Fresh"
        ),

        // ---- rejected -------------------------------------------------------------------
        PhraseCase(
            name = "a missing phrase is null, never a name invented from the address",
            phrase = null,
            address = "orders@grocer.example",
            expected = null
        ),
        PhraseCase(
            name = "a blank phrase is null",
            phrase = "   ",
            address = "orders@grocer.example",
            expected = null
        ),
        PhraseCase(
            name = "an empty quoted phrase is null",
            phrase = "\"\"",
            address = "orders@grocer.example",
            expected = null
        ),
        PhraseCase(
            name = "a phrase that is the address again is the sender repeating itself",
            phrase = "orders@grocer.example",
            address = "orders@grocer.example",
            expected = null
        ),
        PhraseCase(
            name = "a quoted address is rejected the same way",
            phrase = "\"noreply@grocer.example\"",
            address = "noreply@grocer.example",
            expected = null
        ),
        PhraseCase(
            name = "some other address is no more a name than this one's own",
            phrase = "someone@example.com",
            address = "orders@grocer.example",
            expected = null
        ),
        PhraseCase(
            name = "a phrase equal to the local part is a mailbox name",
            phrase = "orders",
            address = "orders@grocer.example",
            expected = null
        ),
        PhraseCase(
            name = "punctuation and case do not save a mailbox name",
            phrase = "No-Reply",
            address = "noreply@grocer.example",
            expected = null
        ),
        PhraseCase(
            name = "the reverse spelling is caught too",
            phrase = "noreply",
            address = "no-reply@grocer.example",
            expected = null
        ),
        PhraseCase(
            name = "a phrase equal to the whole domain is not a name",
            phrase = "grocer.example",
            address = "orders@grocer.example",
            expected = null
        ),
        PhraseCase(
            name = "a phrase with no letter at all is not a name",
            phrase = "12345",
            address = "orders@grocer.example",
            expected = null
        ),
        PhraseCase(
            name = "punctuation alone is not a name",
            phrase = "--- ---",
            address = "orders@grocer.example",
            expected = null
        ),
        PhraseCase(
            name = "a subject line pushed into the phrase is not a name",
            phrase = "G".repeat(SenderDisplayName.MAX_NAME_LENGTH + 1),
            address = "orders@grocer.example",
            expected = null
        )
    ).map { case ->
        dynamicTest(case.name) {
            assertEquals(case.expected, SenderDisplayName.fromPhrase(case.phrase, case.address))
        }
    }

    /**
     * The one rule that cannot be tested by example, because it is a rule about *not* guessing.
     *
     * A lower-case mailbox is not a brand and cannot be turned back into one — spec 01
     * constraint C7. So no title-casing, no capitalising, no falling back to the domain.
     */
    @Test
    fun `a name is never derived from the address when the phrase is missing`() {
        for (address in listOf("orders@grocer.example", "noreply@pharmacy.example", "no-reply@rail-tickets.co.in")) {
            assertNull(SenderDisplayName.fromPhrase(null, address), address)
            assertNull(SenderDisplayName.fromPhrase("", address), address)
        }
    }

    private data class DomainCase(val name: String, val address: String, val expected: String?)

    @TestFactory
    fun `addresses group into the domain that owns them`(): List<DynamicTest> = listOf(
        DomainCase("two labels are the whole domain", "orders@grocer.example", "grocer.example"),
        DomainCase("a subdomain collapses to its parent", "orders@mail.grocer.example", "grocer.example"),
        DomainCase("several subdomain levels collapse too", "orders@a.b.grocer.example", "grocer.example"),
        // The three-label case is proven rather than hypothetical: irctc.co.in is already in
        // the registry, so a two-label answer here would have split that sender in half.
        DomainCase("co.in keeps three labels", "no-reply@rail-tickets.co.in", "rail-tickets.co.in"),
        DomainCase("co.in keeps three labels under a subdomain", "no-reply@mail.rail-tickets.co.in", "rail-tickets.co.in"),
        DomainCase("case is folded", "ORDERS@Grocer.Example", "grocer.example"),
        DomainCase("an angle-bracketed header is normalised first", "\"SOMEONE\" <orders@grocer.example>", "grocer.example"),
        DomainCase("a domain with no dot cannot be grouped", "someone@localhost", null),
        DomainCase("text that is not an address at all", "not-an-address", null),
        DomainCase("an empty string", "", null),
        // A bare suffix is a nonsense sender either way; pinned so the heuristic's edge is a
        // decision rather than an accident. Three labels are only taken when there is a label
        // in front of the suffix to take.
        DomainCase("a bare multi-label suffix is returned as itself", "someone@co.in", "co.in")
    ).map { case ->
        dynamicTest(case.name) {
            assertEquals(case.expected, SenderDisplayName.registrableDomain(case.address))
        }
    }

    /**
     * Discovery groups by [SenderDisplayName.registrableDomain] and the registry resolves by
     * walking parent domains. If the two disagreed, a domain the owner accepted would be
     * written as a key the registry never consults, and the accepted sender would go on being
     * proposed for ever.
     */
    @Test
    fun `a grouped domain is a key the registry can resolve against`() {
        val address = "orders@mail.grocer.example"
        val domain = SenderDisplayName.registrableDomain(address)
        assertEquals("grocer.example", domain)

        val overrides = mapOf(domain!! to SenderMapping(bankName = "Grocer Fresh"))
        assertEquals("Grocer Fresh", EmailSenderRegistry.resolve(address, overrides)?.bankName)
    }
}
