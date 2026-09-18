package com.nrknithin.fintrack.data.database

import com.nrknithin.fintrack.data.database.dao.SenderOverrideDao
import com.nrknithin.fintrack.data.database.entity.SenderOverrideEntity
import com.nrknithin.fintrack.data.database.entity.SenderOverrideSource
import com.nrknithin.fintrack.data.database.entity.SenderOverrideStatus
import com.nrknithin.fintrack.domain.engine.DiscoveredSender
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * `sender_overrides` against a real database (spec 06 §6.1, "Repository / DAO tests").
 *
 * The table is new at v63 and everything interesting about it is a *rule*, not a column: which
 * rows widen the R-11-03 search, and what a second scan is allowed to change about a row the
 * owner has already decided on. Both are stated in SQL here rather than in a fake, because a
 * fake that re-states the filter in Kotlin cannot disagree with itself — and the rule that
 * matters most, "a weekly scan must not re-propose what was declined", is precisely the kind
 * that fails silently by making a settings screen impossible to clear.
 *
 * Every domain here is invented and uses the reserved `.example` TLD (spec 06 §6.2).
 */
@RunWith(RobolectricTestRunner::class)
class SenderOverrideDaoTest {

    private lateinit var db: FinTrackDatabase
    private lateinit var dao: SenderOverrideDao

    @Before
    fun openDatabase() {
        db = RoomTestDb.open()
        dao = db.senderOverrideDao()
    }

    @After
    fun closeDatabase() {
        db.close()
    }

    // -------------------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------------------

    /** Fixed, so nothing here depends on the day the suite runs (spec 02 §2.8). */
    private val scannedAt: LocalDateTime = LocalDateTime.of(2026, 9, 18, 11, 0, 0)
    private val zone: ZoneId = ZoneId.systemDefault()

    private fun instantAt(day: Int): Instant =
        LocalDateTime.of(2026, 9, day, 8, 0, 0).atZone(zone).toInstant()

    private fun discovered(
        domain: String = "shop.example.net",
        displayName: String? = "Shop Example",
        messageCount: Int = 12,
        firstDay: Int = 2,
        lastDay: Int = 16
    ) = DiscoveredSender(
        domain = domain,
        displayName = displayName,
        addresses = listOf("orders@$domain"),
        messageCount = messageCount,
        firstSeen = instantAt(firstDay),
        lastSeen = instantAt(lastDay)
    )

    // -------------------------------------------------------------------------------------
    // What the sync may use
    // -------------------------------------------------------------------------------------

    @Test
    fun `only accepted and enabled rows widen the search`() = runBlocking {
        dao.insert(row("accepted.example.net", SenderOverrideStatus.ACCEPTED, enabled = true))
        dao.insert(row("switched-off.example.net", SenderOverrideStatus.ACCEPTED, enabled = false))
        dao.insert(row("proposed.example.net", SenderOverrideStatus.PROPOSED, enabled = true))
        dao.insert(row("declined.example.net", SenderOverrideStatus.DECLINED, enabled = true))

        assertEquals(
            "Only an accepted, enabled row may reach the OR(FROM …) search of R-11-03",
            listOf("accepted.example.net"),
            dao.active().map { it.addressOrDomain }
        )
    }

    @Test
    fun `declined keys come back for the next scan to skip`() = runBlocking {
        dao.insert(row("declined.example.net", SenderOverrideStatus.DECLINED))
        dao.insert(row("proposed.example.net", SenderOverrideStatus.PROPOSED))

        assertEquals(listOf("declined.example.net"), dao.declinedKeys())
    }

    @Test
    fun `proposals are offered heaviest sender first`() = runBlocking {
        dao.observe(discovered(domain = "light.example.net", messageCount = 3), scannedAt)
        dao.observe(discovered(domain = "heavy.example.net", messageCount = 41), scannedAt)
        dao.observe(discovered(domain = "middle.example.net", messageCount = 12), scannedAt)

        assertEquals(
            listOf("heavy.example.net", "middle.example.net", "light.example.net"),
            dao.proposals().first().map { it.addressOrDomain }
        )
    }

    // -------------------------------------------------------------------------------------
    // The defaults a lost field falls back to
    // -------------------------------------------------------------------------------------

    @Test
    fun `a row written with no status and no source lands un-trusted`() = runBlocking {
        dao.insert(SenderOverrideEntity(addressOrDomain = "bare.example.net"))

        val stored = requireNotNull(dao.byKey("bare.example.net"))
        // PROPOSED, not ACCEPTED: the safe direction is "awaiting the owner", because the
        // unsafe one silently widens the search on the owner's behalf.
        assertEquals(SenderOverrideStatus.PROPOSED, stored.status)
        assertEquals(SenderOverrideSource.MANUAL, stored.source)
        assertTrue(stored.isEnabled)
        assertEquals(0, stored.messageCount)
        // Unknown stays null, never zero or "now" (spec 01 constraint C7).
        assertNull(stored.firstSeenAt)
        assertNull(stored.lastSeenAt)
        assertNull(stored.lastDiscoveredAt)
        assertNull(stored.bankAlias)
        assertNull(stored.displayName)
        assertTrue("A proposed row must not widen the search", dao.active().isEmpty())
    }

    @Test
    fun `address_or_domain is unique and a second insert does not replace the first`() =
        runBlocking {
            dao.insert(
                row("shop.example.net", SenderOverrideStatus.ACCEPTED).copy(
                    displayName = "The owner's own name for it"
                )
            )
            // IGNORE, not REPLACE: REPLACE would delete the row and take the decision and the
            // edited name with it.
            dao.insert(row("shop.example.net", SenderOverrideStatus.PROPOSED))

            assertEquals(1, dao.observeAll().first().size)
            val stored = requireNotNull(dao.byKey("shop.example.net"))
            assertEquals(SenderOverrideStatus.ACCEPTED, stored.status)
            assertEquals("The owner's own name for it", stored.displayName)
        }

    // -------------------------------------------------------------------------------------
    // observe(): what a second scan may and may not change
    // -------------------------------------------------------------------------------------

    @Test
    fun `a sender nobody has seen before is proposed, from discovery, with its evidence`() =
        runBlocking {
            dao.observe(discovered(), scannedAt)

            val stored = requireNotNull(dao.byKey("shop.example.net"))
            assertEquals(SenderOverrideStatus.PROPOSED, stored.status)
            assertEquals(SenderOverrideSource.DISCOVERY, stored.source)
            assertEquals("Shop Example", stored.displayName)
            assertEquals(12, stored.messageCount)
            assertEquals(LocalDateTime.ofInstant(instantAt(2), zone), stored.firstSeenAt)
            assertEquals(LocalDateTime.ofInstant(instantAt(16), zone), stored.lastSeenAt)
            assertEquals(scannedAt, stored.lastDiscoveredAt)
            // A merchant is not a bank, so there is no alias to invent (constraint C7).
            assertNull(stored.bankAlias)
        }

    @Test
    fun `a second scan refreshes the evidence without touching the decision`() = runBlocking {
        dao.observe(discovered(messageCount = 12), scannedAt)
        dao.setStatus("shop.example.net", SenderOverrideStatus.ACCEPTED)

        val later = scannedAt.plusDays(7)
        dao.observe(discovered(messageCount = 19, lastDay = 24), later)

        val stored = requireNotNull(dao.byKey("shop.example.net"))
        assertEquals(
            "An accepted sender must stay accepted, or the owner's list never clears",
            SenderOverrideStatus.ACCEPTED,
            stored.status
        )
        assertEquals(19, stored.messageCount)
        assertEquals(LocalDateTime.ofInstant(instantAt(24), zone), stored.lastSeenAt)
        assertEquals(later, stored.lastDiscoveredAt)
        assertEquals(1, dao.active().size)
    }

    @Test
    fun `a declined sender is never proposed again`() = runBlocking {
        dao.observe(discovered(), scannedAt)
        dao.setStatus("shop.example.net", SenderOverrideStatus.DECLINED)

        dao.observe(discovered(messageCount = 30), scannedAt.plusDays(7))

        assertEquals(
            SenderOverrideStatus.DECLINED,
            requireNotNull(dao.byKey("shop.example.net")).status
        )
        assertTrue(dao.proposals().first().isEmpty())
        // Still counted, though: the evidence is refreshed even for a row the owner refused,
        // so "you decided this 40 messages ago" stays answerable.
        assertEquals(30, requireNotNull(dao.byKey("shop.example.net")).messageCount)
    }

    @Test
    fun `a name the owner edited survives every later scan`() = runBlocking {
        dao.observe(discovered(displayName = "Shop Example"), scannedAt)
        dao.setDisplayName("shop.example.net", "Weekly groceries")

        dao.observe(discovered(displayName = "Shop Example Offers"), scannedAt.plusDays(7))

        assertEquals(
            "Only fillDisplayName's `IS NULL` guard stands between the owner's name and a " +
                "marketing phrase overwriting it every week",
            "Weekly groceries",
            requireNotNull(dao.byKey("shop.example.net")).displayName
        )
    }

    @Test
    fun `a row with no name gets one as soon as a scan finds a usable phrase`() = runBlocking {
        dao.observe(discovered(displayName = null), scannedAt)
        assertNull(requireNotNull(dao.byKey("shop.example.net")).displayName)

        dao.observe(discovered(displayName = "Shop Example"), scannedAt.plusDays(7))

        assertEquals("Shop Example", requireNotNull(dao.byKey("shop.example.net")).displayName)
    }

    @Test
    fun `first seen only moves backwards and last seen only forwards`() = runBlocking {
        dao.observe(discovered(firstDay = 5, lastDay = 16), scannedAt)

        // The window slid: this scan can no longer see the oldest mail, and has seen newer.
        dao.observe(discovered(firstDay = 9, lastDay = 24), scannedAt.plusDays(7))

        val stored = requireNotNull(dao.byKey("shop.example.net"))
        assertEquals(
            "Taking this run's first sighting literally would keep walking it forward until " +
                "the row claimed a long-standing sender was new",
            LocalDateTime.ofInstant(instantAt(5), zone),
            stored.firstSeenAt
        )
        assertEquals(LocalDateTime.ofInstant(instantAt(24), zone), stored.lastSeenAt)
    }

    @Test
    fun `running the same scan twice changes nothing the second time`() = runBlocking {
        dao.observe(discovered(), scannedAt)
        val afterFirst = requireNotNull(dao.byKey("shop.example.net"))

        dao.observe(discovered(), scannedAt)
        val afterSecond = requireNotNull(dao.byKey("shop.example.net"))

        assertEquals(1, dao.observeAll().first().size)
        assertEquals(afterFirst, afterSecond)
    }

    @Test
    fun `a hand-typed row keeps its manual provenance when discovery finds it too`() =
        runBlocking {
            dao.insert(
                row("shop.example.net", SenderOverrideStatus.ACCEPTED).copy(
                    source = SenderOverrideSource.MANUAL,
                    displayName = null
                )
            )

            dao.observe(discovered(), scannedAt)

            val stored = requireNotNull(dao.byKey("shop.example.net"))
            assertEquals(
                "observe() records evidence; it does not rewrite how a row got here",
                SenderOverrideSource.MANUAL,
                stored.source
            )
            assertEquals(SenderOverrideStatus.ACCEPTED, stored.status)
            // It does fill the name the owner never gave it, which is the one thing a scan
            // knows and a hand-typed row does not.
            assertEquals("Shop Example", stored.displayName)
            assertEquals(12, stored.messageCount)
        }

    // -------------------------------------------------------------------------------------
    // The rest of the write surface
    // -------------------------------------------------------------------------------------

    @Test
    fun `enabling and disabling moves a row in and out of the search`() = runBlocking {
        dao.insert(row("shop.example.net", SenderOverrideStatus.ACCEPTED, enabled = true))
        assertEquals(1, dao.active().size)

        dao.setEnabled("shop.example.net", false)
        assertTrue(dao.active().isEmpty())
        assertFalse(requireNotNull(dao.byKey("shop.example.net")).isEnabled)

        dao.setEnabled("shop.example.net", true)
        assertEquals(1, dao.active().size)
    }

    @Test
    fun `counting by status is what screen 47 shows without loading every row`() = runBlocking {
        dao.insert(row("a.example.net", SenderOverrideStatus.PROPOSED))
        dao.insert(row("b.example.net", SenderOverrideStatus.PROPOSED))
        dao.insert(row("c.example.net", SenderOverrideStatus.ACCEPTED))

        assertEquals(2, dao.countByStatus(SenderOverrideStatus.PROPOSED))
        assertEquals(1, dao.countByStatus(SenderOverrideStatus.ACCEPTED))
        assertEquals(0, dao.countByStatus(SenderOverrideStatus.DECLINED))
    }

    @Test
    fun `deleting by key removes exactly one row`() = runBlocking {
        dao.insert(row("a.example.net", SenderOverrideStatus.ACCEPTED))
        dao.insert(row("b.example.net", SenderOverrideStatus.ACCEPTED))

        dao.deleteByKey("a.example.net")

        assertNull(dao.byKey("a.example.net"))
        assertNotNull(dao.byKey("b.example.net"))
    }

    private fun row(
        key: String,
        status: SenderOverrideStatus,
        enabled: Boolean = true
    ) = SenderOverrideEntity(
        addressOrDomain = key,
        status = status,
        isEnabled = enabled,
        createdAt = scannedAt
    )
}
