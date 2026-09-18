package com.nrknithin.fintrack.domain.engine

import com.nrknithin.fintrack.parser.core.email.SenderMapping
import com.nrknithin.fintrack.data.email.SenderSighting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.random.Random

/**
 * What a discovery pass may conclude from a pile of ENVELOPE peeks.
 *
 * The engine is the whole feature's honesty check. It never writes anything: it turns
 * "these senders appeared in the Purchases category" into "these are the ones the app does
 * not know", and every number it reports is either counted from the sightings or read from
 * the injected clock. So the tests below are mostly about what it must *not* do — not invent
 * a name, not propose a sender the registry already covers, not re-propose one the owner
 * declined, and not depend on the order the server happened to return UIDs in.
 *
 * No Room, no Android, no network: plain data in, one report out (spec 02 §2.3 rule 4).
 *
 * Every address is invented (spec 06 §6.2). Unknown merchants use the reserved `.example`
 * TLD; the two known senders are real registry entries, which is the point of using them —
 * `alerts@hdfcbank.bank.in` is a bank the app already parses and `store-news@amazon.in` is a
 * marketing sender the registry deliberately knows about in order to ignore it.
 */
class SenderDiscoveryTest {

    /** Fixed so no test can depend on the day it runs (spec 02 §2.8). */
    private val scanClock: Clock = Clock.fixed(Instant.parse("2026-09-18T09:30:00Z"), ZoneOffset.UTC)

    private val discovery = SenderDiscovery(scanClock)

    /** Dates in the sightings, deliberately nowhere near the clock above. */
    private val march = Instant.parse("2026-03-04T06:15:00Z")
    private val june = Instant.parse("2026-06-21T13:45:00Z")
    private val august = Instant.parse("2026-08-30T19:05:00Z")

    /**
     * One ENVELOPE peek.
     *
     * [SenderSighting.uid] is never read by the engine — de-duplicating the pass is the
     * caller's job, since only it knows whether two UIDs are two messages — so the fixtures
     * leave it at a constant rather than pretending it matters.
     */
    private fun seen(
        address: String,
        phrase: String? = null,
        at: Instant = june,
        uid: Long = 1L
    ) = SenderSighting(uid = uid, address = address, displayPhrase = phrase, receivedAt = at)

    // -------------------------------------------------------------------------------------
    // Naming: the phrase is the only place a name can come from
    // -------------------------------------------------------------------------------------

    @Test
    fun `a from header with a display phrase names the discovered sender`() {
        val report = discovery.discover(listOf(seen("orders@grocer.example", "Grocer Fresh")))

        val sender = report.discovered.single()
        assertEquals("grocer.example", sender.domain)
        assertEquals("Grocer Fresh", sender.displayName)
        assertEquals(listOf("orders@grocer.example"), sender.addresses)
        assertEquals(1, sender.messageCount)
    }

    @Test
    fun `a from header with no display phrase leaves the name unknown rather than guessing one`() {
        val report = discovery.discover(listOf(seen("orders@grocer.example", phrase = null)))

        val sender = report.discovered.single()
        assertNull(
            "A name that was never sent cannot be recovered from `grocer`; the UI labels the " +
                "row by its domain instead (constraint C7).",
            sender.displayName
        )
        assertEquals("grocer.example", sender.domain)
    }

    @Test
    fun `a phrase that is itself an email address is the sender repeating itself, not naming itself`() {
        val report = discovery.discover(
            listOf(
                seen("noreply@grocer.example", "noreply@grocer.example"),
                seen("orders@grocer.example", "\"orders@grocer.example\"")
            )
        )

        assertNull(report.discovered.single().displayName)
    }

    @Test
    fun `a phrase carrying a comma or quotes survives intact`() {
        val report = discovery.discover(
            listOf(
                seen("orders@grocer.example", "\"Grocer Fresh, Ltd.\""),
                seen("orders@pharmacy.example", "\"Pharmacy \\\"Daily\\\" Care\"")
            )
        )

        val byDomain = report.discovered.associateBy { it.domain }
        assertEquals("Grocer Fresh, Ltd.", byDomain.getValue("grocer.example").displayName)
        assertEquals("Pharmacy \"Daily\" Care", byDomain.getValue("pharmacy.example").displayName)
    }

    @Test
    fun `the modal phrase wins, so one stray offers mail cannot outvote the brand`() {
        val report = discovery.discover(
            listOf(
                seen("orders@grocer.example", "Grocer Fresh", at = march),
                seen("orders@grocer.example", "Grocer Fresh", at = june),
                seen("offers@grocer.example", "Grocer Fresh Offers", at = august)
            )
        )

        assertEquals("Grocer Fresh", report.discovered.single().displayName)
    }

    @Test
    fun `an equally frequent pair of names is settled by the most recent sighting`() {
        val report = discovery.discover(
            listOf(
                seen("orders@grocer.example", "Grocer", at = march),
                seen("orders@grocer.example", "Grocer Fresh", at = august)
            )
        )

        assertEquals(
            "A rebrand should beat an equally frequent old name.",
            "Grocer Fresh",
            report.discovered.single().displayName
        )
    }

    @Test
    fun `a tie on frequency and on date falls to the lexicographically smaller name`() {
        val report = discovery.discover(
            listOf(
                seen("orders@grocer.example", "Zebra Grocer", at = june),
                seen("orders@grocer.example", "Alpha Grocer", at = june)
            )
        )

        assertEquals("Alpha Grocer", report.discovered.single().displayName)
    }

    @Test
    fun `a group whose every phrase is unusable is named by its domain alone`() {
        val report = discovery.discover(
            listOf(
                seen("orders@grocer.example", "orders"),
                seen("no-reply@grocer.example", "noreply"),
                seen("offers@grocer.example", "   ")
            )
        )

        val sender = report.discovered.single()
        assertNull(sender.displayName)
        assertEquals(3, sender.messageCount)
    }

    // -------------------------------------------------------------------------------------
    // Grouping
    // -------------------------------------------------------------------------------------

    @Test
    fun `several local parts under one domain collapse into a single row`() {
        val report = discovery.discover(
            listOf(
                seen("orders@grocer.example", "Grocer Fresh", at = march),
                seen("offers@grocer.example", "Grocer Fresh", at = june),
                seen("no-reply@grocer.example", "Grocer Fresh", at = august),
                seen("orders@grocer.example", "Grocer Fresh", at = june)
            )
        )

        val sender = report.discovered.single()
        assertEquals("grocer.example", sender.domain)
        assertEquals(
            "Distinct addresses, ascending — four sightings, three mailboxes.",
            listOf("no-reply@grocer.example", "offers@grocer.example", "orders@grocer.example"),
            sender.addresses
        )
        assertEquals(4, sender.messageCount)
        assertEquals(march, sender.firstSeen)
        assertEquals(august, sender.lastSeen)
    }

    @Test
    fun `a subdomain belongs to the domain that owns it`() {
        val report = discovery.discover(
            listOf(
                seen("orders@mail.grocer.example", "Grocer Fresh"),
                seen("orders@grocer.example", "Grocer Fresh")
            )
        )

        assertEquals(listOf("grocer.example"), report.discovered.map { it.domain })
        assertEquals(2, report.discovered.single().messageCount)
    }

    @Test
    fun `a co dot in sender keeps its three labels instead of merging with every other co dot in`() {
        val report = discovery.discover(
            listOf(
                seen("no-reply@rail-tickets.co.in", "Rail Tickets"),
                seen("noreply@ferry-tickets.co.in", "Ferry Tickets")
            )
        )

        assertEquals(
            "Two labels would have merged two unrelated merchants into `co.in`.",
            listOf("ferry-tickets.co.in", "rail-tickets.co.in"),
            report.discovered.map { it.domain }.sorted()
        )
    }

    @Test
    fun `the address list is capped while the message count is not`() {
        val sightings = (1..12).map { seen("orders-%02d@grocer.example".format(it), "Grocer Fresh") }

        val sender = discovery.discover(sightings).discovered.single()
        assertEquals(SenderDiscovery.MAX_ADDRESSES, sender.addresses.size)
        assertEquals(
            "The cap is on what the row shows, never on what it counted.",
            12,
            sender.messageCount
        )
        assertEquals("orders-01@grocer.example", sender.addresses.first())
        assertEquals("orders-08@grocer.example", sender.addresses.last())
    }

    @Test
    fun `discovered senders are ordered by message count, then by domain`() {
        val report = discovery.discover(
            listOf(
                seen("orders@pharmacy.example", "Pharmacy Care"),
                seen("orders@grocer.example", "Grocer Fresh"),
                seen("offers@grocer.example", "Grocer Fresh"),
                seen("orders@bookshop.example", "Book Shop")
            )
        )

        assertEquals(
            listOf("grocer.example", "bookshop.example", "pharmacy.example"),
            report.discovered.map { it.domain }
        )
    }

    // -------------------------------------------------------------------------------------
    // Exclusions
    // -------------------------------------------------------------------------------------

    @Test
    fun `a sender the registry already knows is counted, not proposed`() {
        val report = discovery.discover(
            listOf(
                seen("alerts@hdfcbank.bank.in", "HDFC Bank"),
                seen("orders@grocer.example", "Grocer Fresh")
            )
        )

        assertEquals(listOf("grocer.example"), report.discovered.map { it.domain })
        assertEquals(1, report.knownCount)
        assertEquals(2, report.scannedCount)
    }

    @Test
    fun `a marketing-only sender is known and so is never proposed`() {
        val report = discovery.discover(listOf(seen("store-news@amazon.in", "Amazon.in")))

        assertTrue(
            "The registry knows this sender in order to ignore it; proposing it would ask the " +
                "owner to accept a sender the app has already decided about.",
            report.discovered.isEmpty()
        )
        assertEquals(1, report.knownCount)
    }

    @Test
    fun `a sender known only through a registry domain entry is excluded too`() {
        // No `bigbasket.com` address is in the registry by name, but the domain is — the
        // resolution walk is what makes this row known, and discovery must use the same walk.
        val report = discovery.discover(listOf(seen("noreply@orders.bigbasket.com", "BigBasket")))

        assertTrue(report.discovered.isEmpty())
        assertEquals(1, report.knownCount)
    }

    @Test
    fun `an accepted override counts as known, so a second pass stops re-proposing it`() {
        val sightings = listOf(
            seen("orders@grocer.example", "Grocer Fresh"),
            seen("orders@pharmacy.example", "Pharmacy Care")
        )

        val first = discovery.discover(sightings)
        assertEquals(listOf("grocer.example", "pharmacy.example"), first.discovered.map { it.domain }.sorted())

        val second = discovery.discover(
            sightings,
            overrides = mapOf("grocer.example" to SenderMapping(bankName = "Grocer Fresh"))
        )
        assertEquals(listOf("pharmacy.example"), second.discovered.map { it.domain })
        assertEquals(1, second.knownCount)
    }

    @Test
    fun `a declined sender is dropped entirely, not counted as known`() {
        val report = discovery.discover(
            listOf(
                seen("orders@grocer.example", "Grocer Fresh"),
                seen("orders@pharmacy.example", "Pharmacy Care")
            ),
            declined = setOf("grocer.example")
        )

        assertEquals(listOf("pharmacy.example"), report.discovered.map { it.domain })
        assertEquals(
            "The owner declining a grocer is not the registry knowing that grocer.",
            0,
            report.knownCount
        )
        assertEquals(2, report.scannedCount)
    }

    @Test
    fun `a declined key may be one address rather than the whole domain`() {
        val report = discovery.discover(
            listOf(
                seen("offers@grocer.example", "Grocer Fresh"),
                seen("orders@grocer.example", "Grocer Fresh")
            ),
            declined = setOf("  OFFERS@Grocer.Example  ")
        )

        val sender = report.discovered.single()
        assertEquals(
            "Declining the offers mailbox must not decline the orders one.",
            listOf("orders@grocer.example"),
            sender.addresses
        )
        assertEquals(1, sender.messageCount)
    }

    @Test
    fun `a sighting whose address will not parse is scanned and nothing else`() {
        val report = discovery.discover(
            listOf(
                seen("not-an-address", "Grocer Fresh"),
                seen("someone@localhost", "Local Mail"),
                seen("orders@grocer.example", "Grocer Fresh")
            )
        )

        assertEquals(listOf("grocer.example"), report.discovered.map { it.domain })
        assertEquals(
            "An address with nothing to group by is not evidence that the registry knows it.",
            0,
            report.knownCount
        )
        assertEquals(3, report.scannedCount)
    }

    // -------------------------------------------------------------------------------------
    // The report itself
    // -------------------------------------------------------------------------------------

    @Test
    fun `first and last seen come from the messages and only the stamp comes from the clock`() {
        val report = discovery.discover(
            listOf(
                seen("orders@grocer.example", "Grocer Fresh", at = august),
                seen("orders@grocer.example", "Grocer Fresh", at = march),
                seen("offers@grocer.example", "Grocer Fresh", at = june)
            )
        )

        val sender = report.discovered.single()
        assertEquals(march, sender.firstSeen)
        assertEquals(august, sender.lastSeen)
        assertEquals(Instant.parse("2026-09-18T09:30:00Z"), report.scannedAt)
    }

    @Test
    fun `a pass that found nothing new is a real answer, not an empty one`() {
        val report = discovery.discover(listOf(seen("alerts@hdfcbank.bank.in", "HDFC Bank")))

        assertTrue(report.discovered.isEmpty())
        assertEquals(1, report.knownCount)
        assertEquals(1, report.scannedCount)
        assertEquals(Instant.parse("2026-09-18T09:30:00Z"), report.scannedAt)
    }

    @Test
    fun `scanning nothing reports nothing`() {
        val report = discovery.discover(emptyList())

        assertTrue(report.discovered.isEmpty())
        assertEquals(0, report.knownCount)
        assertEquals(0, report.scannedCount)
    }

    @Test
    fun `the report does not depend on the order the server returned the sightings in`() {
        val sightings = listOf(
            seen("orders@grocer.example", "Grocer Fresh", at = march),
            seen("offers@grocer.example", "Grocer Fresh Offers", at = august),
            seen("orders@grocer.example", "Grocer Fresh", at = june),
            seen("orders@pharmacy.example", "Pharmacy Care", at = june),
            seen("noreply@pharmacy.example", null, at = march),
            seen("alerts@hdfcbank.bank.in", "HDFC Bank", at = august),
            seen("no-reply@rail-tickets.co.in", "Rail Tickets", at = june)
        )

        val expected = discovery.discover(sightings)
        val random = Random(20260918)
        repeat(20) {
            assertEquals(expected, discovery.discover(sightings.shuffled(random)))
        }
    }
}
