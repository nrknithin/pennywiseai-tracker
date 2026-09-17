package com.pennywiseai.tracker.data.email

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The fakes the sync worker's tests will be built on.
 *
 * Worth testing in their own right: a fake that quietly behaves unlike production turns
 * every test above it into a test of the fake. These checks pin the two behaviours the real
 * stores promise and that a naive in-memory double would get wrong — coercion on every
 * value, and watermark writes that leave user settings alone.
 */
class EmailStoreFakesTest {

    @Test
    fun `the config fake coerces its initial value the way DataStore does`() = runBlocking {
        val store = FakeEmailAccountConfigStore(EmailAccountConfig(syncIntervalMinutes = 1))
        assertEquals(15, store.current().syncIntervalMinutes)
        assertEquals(15, store.config.first().syncIntervalMinutes)
    }

    @Test
    fun `update coerces its result and returns what was stored`() = runBlocking {
        val store = FakeEmailAccountConfigStore()
        val returned = store.update { it.copy(maxBodyKb = 99_999, address = "  someone@example.com ") }

        assertEquals(512, returned.maxBodyKb)
        assertEquals("someone@example.com", returned.address)
        assertEquals(returned, store.current())
        assertEquals(1, store.updateCount)
    }

    @Test
    fun `a watermark write advances the watermark and leaves settings alone`() = runBlocking {
        val store = FakeEmailAccountConfigStore()
        store.update { it.copy(syncIntervalMinutes = 720, folder = "Archive/Bills") }

        store.setWatermark(uidValidity = 5L, lastSyncedUid = 100L)
        store.setWatermark(uidValidity = 5L, lastSyncedUid = 150L)

        val after = store.current()
        assertEquals(5L, after.uidValidity)
        assertEquals(150L, after.lastSyncedUid)
        assertEquals(720, after.syncIntervalMinutes)
        assertEquals("Archive/Bills", after.folder)

        // Spec 11 R-11-07: one watermark write per committed batch, and no settings rewrite.
        assertEquals(listOf(5L to 100L, 5L to 150L), store.watermarkWrites)
        assertEquals(1, store.updateCount)
    }

    @Test
    fun `resetWatermark clears the folder identity as well as the uid`() = runBlocking {
        val store = FakeEmailAccountConfigStore()
        store.setWatermark(uidValidity = 9L, lastSyncedUid = 4_000L)

        store.resetWatermark()

        val after = store.current()
        assertEquals(0L, after.uidValidity)
        assertEquals(0L, after.lastSyncedUid)
    }

    @Test
    fun `state and last sync time are recorded`() = runBlocking {
        val store = FakeEmailAccountConfigStore()
        assertEquals(EmailAccountState.NOT_CONFIGURED, store.current().state)
        assertNull(store.current().lastSyncAt)

        store.setState(EmailAccountState.NETWORK_ERROR)
        store.setState(EmailAccountState.OK)
        val at = Instant.parse("2026-09-17T09:00:00Z")
        store.markSynced(at)

        assertEquals(EmailAccountState.OK, store.current().state)
        assertEquals(at, store.current().lastSyncAt)
        assertEquals(
            listOf(EmailAccountState.NETWORK_ERROR, EmailAccountState.OK),
            store.stateWrites
        )
    }

    @Test
    fun `an empty credential store answers no and lends nothing`() = runBlocking {
        val store = FakeEmailCredentialStore()
        assertFalse(store.hasPassword())

        var ran = false
        val result = store.withPassword { ran = true; "connected" }

        // Null, not an exception: the worker reports "Email not configured" and stops.
        assertNull(result)
        assertFalse(ran)
        assertEquals(0, store.borrowCount)
    }

    @Test
    fun `a stored password is lent to the block and nowhere else`() = runBlocking {
        val store = FakeEmailCredentialStore()
        store.store(FakeEmailCredentialStore.samplePassword())

        assertTrue(store.hasPassword())
        assertEquals("connected", store.withPassword { "connected" })
        assertEquals(1, store.borrowCount)
        assertEquals(1, store.storeCount)
    }

    @Test
    fun `clear forgets the password and only the password`() = runBlocking {
        val store = FakeEmailCredentialStore.configured()
        assertTrue(store.hasPassword())

        store.clear()

        assertFalse(store.hasPassword())
        assertNull(store.withPassword { "connected" })
        assertEquals(1, store.clearCount)
    }
}
