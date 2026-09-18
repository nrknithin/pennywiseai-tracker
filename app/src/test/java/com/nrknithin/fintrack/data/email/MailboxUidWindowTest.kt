package com.nrknithin.fintrack.data.email

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [MailboxUidWindow] is the shared half of spec 11 §11.3 steps 7 and 9 — the half the server
 * does not do for us. Its guarantees are what make the watermark safe to advance, so they are
 * tested on their own rather than only through the fake.
 */
class MailboxUidWindowTest {

    @Test
    fun `returns ascending order whatever the server answered in`() {
        assertEquals(
            listOf(3L, 7L, 11L, 12L),
            MailboxUidWindow.select(listOf(11L, 3L, 12L, 7L), sinceUid = 0, limit = 10)
        )
    }

    @Test
    fun `excludes the watermark itself and everything below it`() {
        // sinceUid is the highest UID already committed, so it must not come back.
        assertEquals(
            listOf(8L, 9L),
            MailboxUidWindow.select(listOf(5L, 7L, 8L, 9L), sinceUid = 7, limit = 10)
        )
    }

    @Test
    fun `caps after sorting so the oldest outstanding UIDs are taken first`() {
        // If the cap were applied before sorting, the run would commit a high watermark with
        // lower UIDs still unfetched, and step 21's follow-up would never go back for them.
        assertEquals(
            listOf(1L, 2L, 3L),
            MailboxUidWindow.select(listOf(9L, 2L, 7L, 1L, 3L), sinceUid = 0, limit = 3)
        )
    }

    @Test
    fun `drops duplicates so a batch never logs the same message twice`() {
        assertEquals(
            listOf(4L, 6L),
            MailboxUidWindow.select(listOf(4L, 6L, 4L), sinceUid = 0, limit = 10)
        )
    }

    @Test
    fun `drops the minus one JavaMail answers for a message the server forgot`() {
        // UIDFolder.getUID returns -1 for an unknown message; -1 must never reach
        // email_messages.uid, and must never be compared against a watermark.
        assertEquals(
            listOf(2L),
            MailboxUidWindow.select(listOf(-1L, 0L, 2L), sinceUid = 0, limit = 10)
        )
    }

    @Test
    fun `a non-positive limit fetches nothing`() {
        assertEquals(emptyList<Long>(), MailboxUidWindow.select(listOf(1L, 2L), sinceUid = 0, limit = 0))
        assertEquals(emptyList<Long>(), MailboxUidWindow.select(listOf(1L, 2L), sinceUid = 0, limit = -1))
    }
}
