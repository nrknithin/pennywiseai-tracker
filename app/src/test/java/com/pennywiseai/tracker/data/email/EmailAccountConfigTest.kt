package com.pennywiseai.tracker.data.email

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Pure-Kotlin checks on the settings record: no DataStore, no `Context`, no clock.
 *
 * The defaults are asserted literally rather than against the constants, because the point
 * of the test is that the constants still say what spec 11 §11.2 says. Asserting
 * `config.syncIntervalMinutes == DEFAULT_SYNC_INTERVAL_MINUTES` would pass whatever either
 * of them became.
 */
class EmailAccountConfigTest {

    private val defaults = EmailAccountConfig()

    @Test
    fun `defaults match spec 11 section 11_2`() {
        assertEquals("", defaults.address)
        assertEquals("imap.gmail.com", defaults.host)
        assertEquals(993, defaults.port)
        assertEquals("[Gmail]/All Mail", defaults.folder)
        assertEquals(180, defaults.initialLookbackDays)
        assertEquals(30, defaults.syncIntervalMinutes)
        assertFalse(defaults.syncOnlyOnWifi)
        assertEquals(500, defaults.maxMessagesPerSync)
        assertEquals(50, defaults.batchSize)
        assertEquals(30, defaults.alertWindowMinutes)
        assertTrue(defaults.sameDayFallback)
        assertEquals(listOf("godaddy.com", "pgsharp.com"), defaults.extraSenders)
        assertEquals(emptyList<String>(), defaults.ignoredSenders)
        assertEquals(64, defaults.maxBodyKb)
        assertEquals(400, defaults.retainBodiesDays)
        assertEquals(0L, defaults.lastSyncedUid)
        assertEquals(0L, defaults.uidValidity)
        assertNull(defaults.lastSyncAt)
        assertEquals(EmailAccountState.NOT_CONFIGURED, defaults.state)
    }

    @Test
    fun `the defaults are already coerced`() {
        // If this ever fails, some default sits outside its own documented range.
        assertEquals(defaults, defaults.coerced())
    }

    @Test
    fun `numeric settings clamp to the documented ranges`() {
        val tooSmall = defaults.copy(
            syncIntervalMinutes = 1,
            maxMessagesPerSync = 0,
            alertWindowMinutes = 0,
            maxBodyKb = 1,
            retainBodiesDays = 5,
            batchSize = 0
        ).coerced()
        assertEquals(15, tooSmall.syncIntervalMinutes)
        assertEquals(50, tooSmall.maxMessagesPerSync)
        assertEquals(5, tooSmall.alertWindowMinutes)
        assertEquals(16, tooSmall.maxBodyKb)
        assertEquals(30, tooSmall.retainBodiesDays)
        assertEquals(1, tooSmall.batchSize)

        val tooLarge = defaults.copy(
            syncIntervalMinutes = 10_000,
            maxMessagesPerSync = 99_999,
            alertWindowMinutes = 10_000,
            maxBodyKb = 10_000
        ).coerced()
        assertEquals(720, tooLarge.syncIntervalMinutes)
        assertEquals(5000, tooLarge.maxMessagesPerSync)
        assertEquals(240, tooLarge.alertWindowMinutes)
        assertEquals(512, tooLarge.maxBodyKb)
    }

    @Test
    fun `retainBodiesDays has a floor but no ceiling`() {
        // Spec 11 section 11.2 writes the range as "30 to infinity".
        assertEquals(30, defaults.copy(retainBodiesDays = 29).coerced().retainBodiesDays)
        assertEquals(10_000, defaults.copy(retainBodiesDays = 10_000).coerced().retainBodiesDays)
    }

    @Test
    fun `an out of range port falls back instead of clamping`() {
        assertEquals(993, defaults.copy(port = 0).coerced().port)
        assertEquals(993, defaults.copy(port = -1).coerced().port)
        assertEquals(993, defaults.copy(port = 70_000).coerced().port)
        assertEquals(143, defaults.copy(port = 143).coerced().port)
    }

    @Test
    fun `an odd lookback widens to the next choice and never narrows`() {
        assertEquals(30, defaults.copy(initialLookbackDays = 1).coerced().initialLookbackDays)
        assertEquals(90, defaults.copy(initialLookbackDays = 45).coerced().initialLookbackDays)
        assertEquals(180, defaults.copy(initialLookbackDays = 180).coerced().initialLookbackDays)
        assertEquals(365, defaults.copy(initialLookbackDays = 200).coerced().initialLookbackDays)
        // Past the widest bounded choice there is only "all mail".
        assertEquals(0, defaults.copy(initialLookbackDays = 400).coerced().initialLookbackDays)
        // 0 is "all mail", the widest window of all, and must survive untouched.
        assertEquals(0, defaults.copy(initialLookbackDays = 0).coerced().initialLookbackDays)
        // Negative is nonsense, not a narrower window.
        assertEquals(180, defaults.copy(initialLookbackDays = -7).coerced().initialLookbackDays)
    }

    @Test
    fun `lookbackCutoff is null for all mail and a date otherwise`() {
        val now = Instant.parse("2026-09-17T10:15:30Z")
        assertNull(defaults.copy(initialLookbackDays = 0).lookbackCutoff(now))
        assertEquals(
            now.minus(30, ChronoUnit.DAYS),
            defaults.copy(initialLookbackDays = 30).lookbackCutoff(now)
        )
        assertEquals(
            now.minus(180, ChronoUnit.DAYS),
            defaults.lookbackCutoff(now)
        )
    }

    @Test
    fun `host and folder fall back when blanked, and the host is lower cased`() {
        val blanked = defaults.copy(host = "   ", folder = "\t").coerced()
        assertEquals("imap.gmail.com", blanked.host)
        assertEquals("[Gmail]/All Mail", blanked.folder)

        val shouty = defaults.copy(host = "  IMAP.EXAMPLE.COM ").coerced()
        assertEquals("imap.example.com", shouty.host)

        // A folder name is server data and is case-sensitive on IMAP: trim only.
        assertEquals("Inbox/Bills", defaults.copy(folder = " Inbox/Bills ").coerced().folder)
    }

    @Test
    fun `sender lists are trimmed, lower cased, de-duplicated and kept in order`() {
        val messy = defaults.copy(
            extraSenders = listOf(" GoDaddy.com ", "godaddy.com", "", "   ", "PgSharp.com")
        ).coerced()
        assertEquals(listOf("godaddy.com", "pgsharp.com"), messy.extraSenders)
    }

    @Test
    fun `watermarks cannot go negative`() {
        val fixed = defaults.copy(lastSyncedUid = -5L, uidValidity = -1L).coerced()
        assertEquals(0L, fixed.lastSyncedUid)
        assertEquals(0L, fixed.uidValidity)
    }

    @Test
    fun `hasAddress accepts a plausible address and nothing else`() {
        assertFalse(defaults.hasAddress)
        assertFalse(defaults.copy(address = "someone").hasAddress)
        assertFalse(defaults.copy(address = "@example.com").hasAddress)
        assertFalse(defaults.copy(address = "someone@example").hasAddress)
        assertFalse(defaults.copy(address = "someone@example.").hasAddress)
        assertFalse(defaults.copy(address = "someone@@example.com").hasAddress)
        assertFalse(defaults.copy(address = "some one@example.com").hasAddress)
        assertTrue(defaults.copy(address = "someone@example.com").hasAddress)
        // coerced() trims, and the check trims too, so a pasted address with padding works.
        assertTrue(defaults.copy(address = "  someone@example.com  ").hasAddress)
        assertEquals("someone@example.com", defaults.copy(address = "  someone@example.com  ").coerced().address)
    }

    @Test
    fun `state names round trip and anything unknown reads back as NOT_CONFIGURED`() {
        for (state in EmailAccountState.entries) {
            assertEquals(state, EmailAccountState.fromStored(state.name))
        }
        assertEquals(EmailAccountState.NOT_CONFIGURED, EmailAccountState.fromStored(null))
        assertEquals(EmailAccountState.NOT_CONFIGURED, EmailAccountState.fromStored(""))
        assertEquals(EmailAccountState.NOT_CONFIGURED, EmailAccountState.fromStored("ok"))
        assertEquals(EmailAccountState.NOT_CONFIGURED, EmailAccountState.fromStored("SOMETHING_NEW"))
        assertEquals(EmailAccountState.NOT_CONFIGURED, EmailAccountState.FALLBACK)
    }

    @Test
    fun `the choice lists the settings screen offers are inside their own ranges`() {
        for (choice in EmailAccountConfig.SYNC_INTERVAL_CHOICES) {
            assertTrue(
                "interval choice $choice is outside SYNC_INTERVAL_RANGE",
                choice in EmailAccountConfig.SYNC_INTERVAL_RANGE
            )
        }
        for (choice in EmailAccountConfig.LOOKBACK_CHOICES) {
            assertEquals(
                "lookback choice $choice does not survive coercion",
                choice,
                defaults.copy(initialLookbackDays = choice).coerced().initialLookbackDays
            )
        }
    }
}
