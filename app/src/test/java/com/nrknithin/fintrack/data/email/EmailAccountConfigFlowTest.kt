package com.nrknithin.fintrack.data.email

import androidx.datastore.core.CorruptionException
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

/**
 * What a reader of [EmailAccountConfigStore.config] sees when the storage underneath it
 * cannot be read (spec 02 §2.7's "never silently wipes" applied to settings).
 *
 * The transform is tested rather than `DataStoreEmailAccountConfigStore` itself because the
 * interesting input — a `data` flow that throws — is exactly what a real DataStore will not
 * produce on demand. Everything Android is on the other side of [asEmailAccountConfig]: a
 * hand-built `Flow<Preferences>` reaches the same code the store does.
 */
class EmailAccountConfigFlowTest {

    /** A store that reads fine still decodes and coerces on the way out. */
    @Test
    fun `a readable store decodes and coerces`() = runBlocking {
        val prefs = mutablePreferencesOf()
        EmailAccountConfigCodec.write(
            prefs,
            EmailAccountConfig(folder = "Archive/Bills", syncIntervalMinutes = 1)
        )

        val config = flowOf<Preferences>(prefs).asEmailAccountConfig().first()

        assertEquals("Archive/Bills", config.folder)
        // 1 is below the 15-minute floor of spec 11 §11.2, so the reader never sees it.
        assertEquals(15, config.syncIntervalMinutes)
    }

    /**
     * The defect this guards: without recovery, an unreadable file throws out of every
     * collector — the sync worker, the Home banner, screen 47 — and the user is left with a
     * crash instead of a "not configured" state they can act on.
     */
    @Test
    fun `an unreadable store reads as the documented defaults`() = runBlocking {
        val unreadable: Flow<Preferences> = flow { throw IOException("fin_email_settings") }

        assertEquals(EmailAccountConfig(), unreadable.asEmailAccountConfig().first())
    }

    /** DataStore reports a damaged file as [CorruptionException], which is an [IOException]. */
    @Test
    fun `a corrupt preferences file reads as NOT_CONFIGURED`() = runBlocking {
        val corrupt: Flow<Preferences> = flow {
            throw CorruptionException("fin_email_settings is not valid preferences")
        }

        val config = corrupt.asEmailAccountConfig().first()

        assertEquals(EmailAccountState.NOT_CONFIGURED, config.state)
        assertEquals(EmailAccountConfig.DEFAULT_HOST, config.host)
        assertEquals(0L, config.lastSyncedUid)
    }

    /**
     * Recovery replaces the rest of the stream, it does not rewrite what was already read.
     * A run that had the real settings keeps them until the failure, which is what keeps a
     * mid-run failure from looking like a settings change.
     */
    @Test
    fun `values read before the failure survive, and the defaults follow`() = runBlocking {
        val prefs = mutablePreferencesOf()
        EmailAccountConfigCodec.write(prefs, EmailAccountConfig(folder = "Archive/Bills"))
        val failsLater: Flow<Preferences> = flow {
            emit(prefs)
            throw IOException("the file went away mid-collection")
        }

        val seen = failsLater.asEmailAccountConfig().toList()

        assertEquals(2, seen.size)
        assertEquals("Archive/Bills", seen[0].folder)
        assertEquals(EmailAccountConfig.DEFAULT_FOLDER, seen[1].folder)
    }

    /**
     * Only IO is recovered. A bug in the codec, or anything else, must still surface: a
     * catch-all here would turn "the settings are wrong" into "the settings are default",
     * which is the same silent data loss one layer up.
     */
    @Test
    fun `a failure that is not IO is not swallowed`() {
        val broken: Flow<Preferences> = flow { throw IllegalStateException("not an IO failure") }

        assertThrows(IllegalStateException::class.java) {
            runBlocking { broken.asEmailAccountConfig().first() }
        }
    }
}
