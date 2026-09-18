package com.nrknithin.fintrack.data.email

import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/**
 * The persistence mapping, tested against an in-memory `Preferences` rather than a real
 * DataStore file — `mutablePreferencesOf` is plain Kotlin, so this needs no device, no
 * `Context` and no Robolectric.
 *
 * This is the part of the store that can silently be wrong. A mistyped key or a field left
 * out of [EmailAccountConfigCodec.write] does not fail to compile; it fails months later as
 * a setting that quietly resets itself.
 */
class EmailAccountConfigCodecTest {

    @Test
    fun `an empty store reads back the documented defaults`() {
        assertEquals(EmailAccountConfig(), EmailAccountConfigCodec.read(mutablePreferencesOf()))
    }

    @Test
    fun `every field survives a write and a read`() {
        val config = EmailAccountConfig(
            address = "someone@example.com",
            host = "imap.example.com",
            port = 1993,
            folder = "Archive/Bills",
            initialLookbackDays = 365,
            syncIntervalMinutes = 120,
            syncOnlyOnWifi = true,
            maxMessagesPerSync = 1500,
            batchSize = 25,
            alertWindowMinutes = 45,
            sameDayFallback = false,
            extraSenders = listOf("one.example.com", "two.example.org"),
            ignoredSenders = listOf("three.example.net"),
            maxBodyKb = 256,
            retainBodiesDays = 900,
            lastSyncedUid = 42_000L,
            uidValidity = 7L,
            lastSyncAt = Instant.parse("2026-09-17T09:00:00Z"),
            state = EmailAccountState.AUTH_FAILED
        )

        val prefs = mutablePreferencesOf()
        EmailAccountConfigCodec.write(prefs, config)

        assertEquals(config, EmailAccountConfigCodec.read(prefs))
    }

    @Test
    fun `a never synced account keeps a null timestamp instead of the epoch`() {
        val prefs = mutablePreferencesOf()
        EmailAccountConfigCodec.write(prefs, EmailAccountConfig(lastSyncAt = Instant.ofEpochMilli(5)))
        assertEquals(Instant.ofEpochMilli(5), EmailAccountConfigCodec.read(prefs).lastSyncAt)

        // Writing null must REMOVE the key. Storing 0 instead would make "never synced"
        // read back as "synced in 1970", which the E11 relative time would render as a
        // fifty-year-old sync rather than "never".
        EmailAccountConfigCodec.writeLastSyncAt(prefs, null)
        assertNull(EmailAccountConfigCodec.read(prefs).lastSyncAt)
    }

    @Test
    fun `the watermark writer touches nothing else`() {
        val prefs = mutablePreferencesOf()
        val settings = EmailAccountConfig(
            address = "someone@example.com",
            syncIntervalMinutes = 720,
            maxBodyKb = 512
        )
        EmailAccountConfigCodec.write(prefs, settings)

        EmailAccountConfigCodec.writeWatermark(prefs, uidValidity = 11L, lastSyncedUid = 99L)

        val after = EmailAccountConfigCodec.read(prefs)
        assertEquals(11L, after.uidValidity)
        assertEquals(99L, after.lastSyncedUid)
        // Spec 11 R-11-07: a batch commit advances the watermark and nothing else.
        assertEquals(settings.address, after.address)
        assertEquals(settings.syncIntervalMinutes, after.syncIntervalMinutes)
        assertEquals(settings.maxBodyKb, after.maxBodyKb)
    }

    @Test
    fun `a negative watermark is refused at the storage boundary`() {
        val prefs = mutablePreferencesOf()
        EmailAccountConfigCodec.writeWatermark(prefs, uidValidity = -3L, lastSyncedUid = -9L)
        val stored = EmailAccountConfigCodec.read(prefs)
        assertEquals(0L, stored.uidValidity)
        assertEquals(0L, stored.lastSyncedUid)
    }

    @Test
    fun `the state writer stores the name, not the ordinal`() {
        val prefs = mutablePreferencesOf()
        EmailAccountConfigCodec.writeState(prefs, EmailAccountState.FOLDER_MISSING)
        assertEquals(EmailAccountState.FOLDER_MISSING, EmailAccountConfigCodec.read(prefs).state)
    }

    @Test
    fun `sender lists round trip and keep their order`() {
        val senders = listOf("one.example.com", "two.example.org", "three.example.net")
        val encoded = EmailAccountConfigCodec.encodeSenders(senders)
        assertEquals(senders, EmailAccountConfigCodec.decodeSenders(encoded, emptyList()))
    }

    @Test
    fun `an unset sender list gives the default but an emptied one stays empty`() {
        val fallback = EmailAccountConfig.DEFAULT_EXTRA_SENDERS

        // Never set: the user has not touched the chips, so they see the seeded pair.
        assertEquals(fallback, EmailAccountConfigCodec.decodeSenders(null, fallback))

        // Set to empty: the user deleted every chip. Falling back here would make the
        // seeded senders reappear on the next app start, which reads as a bug.
        assertEquals(emptyList<String>(), EmailAccountConfigCodec.decodeSenders("", fallback))
        assertNotEquals(fallback, EmailAccountConfigCodec.decodeSenders("", fallback))

        val prefs = mutablePreferencesOf()
        EmailAccountConfigCodec.write(prefs, EmailAccountConfig(extraSenders = emptyList()))
        assertEquals(emptyList<String>(), EmailAccountConfigCodec.read(prefs).extraSenders)
    }

    @Test
    fun `blank entries in a stored list are dropped`() {
        val separator = EmailAccountConfigCodec.SENDER_SEPARATOR
        val raw = listOf("one.example.com", "", "  ", "two.example.org").joinToString(separator)
        assertEquals(
            listOf("one.example.com", "two.example.org"),
            EmailAccountConfigCodec.decodeSenders(raw, emptyList())
        )
    }

    @Test
    fun `a stored value outside its range is bounded on the way out`() {
        val prefs = mutablePreferencesOf()
        // Simulates a hand-edited file or a restore written by a build with wider ranges.
        EmailAccountConfigCodec.write(
            prefs,
            EmailAccountConfig(syncIntervalMinutes = 1, maxBodyKb = 99_999, port = 0)
        )

        val raw = EmailAccountConfigCodec.read(prefs)
        assertEquals(1, raw.syncIntervalMinutes)

        // The store always coerces after reading; this is that step.
        val safe = raw.coerced()
        assertEquals(15, safe.syncIntervalMinutes)
        assertEquals(512, safe.maxBodyKb)
        assertEquals(EmailAccountConfig.DEFAULT_PORT, safe.port)
    }
}
