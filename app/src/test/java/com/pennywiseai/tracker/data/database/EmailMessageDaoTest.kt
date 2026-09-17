package com.pennywiseai.tracker.data.database

import com.pennywiseai.tracker.data.database.dao.EmailMessageDao
import com.pennywiseai.tracker.data.database.entity.EmailMessageEntity
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
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * `EmailMessageDao` against a real database (spec 06 §6.1, "Repository / DAO tests").
 *
 * Two of its queries carry rules nothing else in the app can restate, and both were previously
 * verified only by Room's compile-time SQL check:
 *
 *  - [EmailMessageDao.trimBodiesBefore] is spec 04 §4.3's retention sweep. It must *truncate*
 *    to 512 characters rather than empty the column, it must be idempotent, and it must record
 *    on the row that it happened — a prefix is otherwise indistinguishable from a short mail.
 *    `EmailSyncWorkerTest` can only see the cutoff string the worker hands a fake DAO; whether
 *    the UPDATE that string lands in shortens a body, wipes it or flags it is invisible from
 *    there. That is what this suite settles.
 *  - [EmailMessageDao.getUnmatched] is the backlog spec 11 R-11-10 replays. Its filter decides
 *    which mail gets a second chance after the user adds a sender override, so a wrong `LIKE`
 *    either loses messages forever or re-rejects the ones already understood.
 *
 * Every value here is invented: senders are `example.com` addresses and no body carries a
 * figure, a reference or a name (spec 06 §6.2).
 */
@RunWith(RobolectricTestRunner::class)
class EmailMessageDaoTest {

    private lateinit var db: PennyWiseDatabase
    private lateinit var dao: EmailMessageDao

    @Before
    fun openDatabase() {
        db = RoomTestDb.open()
        dao = db.emailMessageDao()
    }

    @After
    fun closeDatabase() {
        db.close()
    }

    // -------------------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------------------

    /** Fixed, so nothing here depends on the day the suite runs (spec 02 §2.8). */
    private val cutoff: LocalDateTime = LocalDateTime.of(2026, 8, 1, 10, 15, 30)

    /** The cutoff exactly as `EmailSyncWorker.trimBodies` formats it before the DAO sees it. */
    private val cutoffText: String = cutoff.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

    private val allMail = "[Gmail]/All Mail"

    /**
     * Deterministic filler of an exact length carrying no digits at all — a stand-in for the
     * bulk of a statement mail that cannot accidentally look like an amount or a reference.
     */
    private fun filler(length: Int): String {
        val unit = "the quick brown fox jumps over the lazy dog. "
        val out = StringBuilder(length + unit.length)
        while (out.length < length) out.append(unit)
        return out.substring(0, length)
    }

    private fun message(
        uid: Long,
        receivedAt: LocalDateTime,
        body: String = filler(2_000),
        mailbox: String = allMail,
        processed: Boolean = true,
        skipReason: String? = null,
        transactionId: Long? = null
    ) = EmailMessageEntity(
        mailbox = mailbox,
        uid = uid,
        messageId = "<msg-" + uid + "@example.com>",
        fromAddress = "alerts@example.com",
        subject = "Statement",
        body = body,
        // Deliberately not a hash *of* the body: several assertions below turn on the sweep
        // never recomputing it, which a genuine hash would hide.
        bodyHash = "hash-" + uid,
        receivedAt = receivedAt,
        processed = processed,
        skipReason = skipReason,
        transactionId = transactionId,
        createdAt = receivedAt
    )

    /** The `received_at` text SQLite is actually holding — the value the sweep compares. */
    private fun storedReceivedAt(uid: Long): String {
        db.openHelper.writableDatabase
            .query("SELECT received_at FROM email_messages WHERE uid = " + uid)
            .use { cursor ->
                assertTrue("no row with uid " + uid, cursor.moveToFirst())
                return cursor.getString(0)
            }
    }

    private suspend fun bodyOf(uid: Long): String =
        requireNotNull(dao.getByUid(allMail, uid)) { "no row with uid " + uid }.body

    /**
     * Read back through the entity on purpose: `body_trimmed` is only useful if the column,
     * its NOT NULL default and the Kotlin property all agree, and a raw cursor read would
     * pass even if Room never mapped the field.
     */
    private suspend fun trimmedFlagOf(uid: Long): Boolean =
        requireNotNull(dao.getByUid(allMail, uid)) { "no row with uid " + uid }.bodyTrimmed

    // -------------------------------------------------------------------------------------
    // The retention sweep — spec 04 §4.3
    // -------------------------------------------------------------------------------------

    @Test
    fun `an aged body is truncated to 512 characters, not emptied`() = runBlocking {
        val original = filler(2_000)
        dao.insert(message(uid = 1, receivedAt = cutoff.minusDays(1), body = original))
        dao.insert(message(uid = 2, receivedAt = cutoff.plusDays(1), body = original))

        val trimmed = dao.trimBodiesBefore(cutoffText)

        assertEquals("only the aged row is in scope", 1, trimmed)

        val aged = bodyOf(1)
        // The three things spec 04 §4.3 actually asks for, asserted separately so a failure
        // says which half of "trims to its first 512 chars" broke.
        assertEquals("truncated to exactly the limit", 512, aged.length)
        assertEquals("kept the *first* 512 characters", original.substring(0, 512), aged)
        assertFalse("emptying the column would lose screen 48's source view", aged.isEmpty())

        assertEquals("a recent body is none of the sweep's business", 2_000, bodyOf(2).length)
    }

    @Test
    fun `a second sweep over the same cutoff writes nothing`() = runBlocking {
        dao.insert(message(uid = 1, receivedAt = cutoff.minusDays(1)))

        assertEquals(1, dao.trimBodiesBefore(cutoffText))
        val afterFirst = bodyOf(1)

        // Idempotence is what the `length(body) > 512` guard buys, and it is not cosmetic: the
        // sweep runs at the end of *every* completed sync, so a query that kept matching would
        // rewrite the whole aged backlog on every run, forever.
        assertEquals("nothing left to shorten", 0, dao.trimBodiesBefore(cutoffText))
        assertEquals("and the second run changed nothing", afterFirst, bodyOf(1))
    }

    @Test
    fun `trimming keeps the twin hash`() = runBlocking {
        dao.insert(message(uid = 1, receivedAt = cutoff.minusDays(1)))

        dao.trimBodiesBefore(cutoffText)

        // Spec 04 §4.3: "trims bodies older than N days to their first 512 chars (keeps the
        // twin hash)". `body_hash` is what spec 11 §11.5 step 4 dedups on, so a sweep that
        // recomputed it from the shortened text would make aged mail look new on a re-fetch.
        assertEquals("hash-1", requireNotNull(dao.getByUid(allMail, 1)).bodyHash)
    }

    @Test
    fun `a body already within the limit is left alone`() = runBlocking {
        val short = filler(100)
        dao.insert(message(uid = 1, receivedAt = cutoff.minusYears(1), body = short))

        assertEquals("nothing to shorten, so nothing is written", 0, dao.trimBodiesBefore(cutoffText))
        assertEquals(short, bodyOf(1))
    }

    @Test
    fun `a body of exactly 512 characters is not rewritten`() = runBlocking {
        val exact = filler(512)
        dao.insert(message(uid = 1, receivedAt = cutoff.minusYears(1), body = exact))
        dao.insert(message(uid = 2, receivedAt = cutoff.minusYears(1), body = filler(513)))

        // The guard is `length(body) > 512`, not `>=`. At exactly the limit `substr` would be
        // a no-op that still counted as a row written, and the idempotence assertion above
        // would be reported one row short of true on every sweep.
        assertEquals("only the 513-character body is over the limit", 1, dao.trimBodiesBefore(cutoffText))
        assertEquals(exact, bodyOf(1))
        assertEquals(512, bodyOf(2).length)
    }

    @Test
    fun `a message received exactly at the cutoff keeps its body`() = runBlocking {
        dao.insert(message(uid = 1, receivedAt = cutoff))

        // `received_at < :before`, strictly. Retention is "older than N days", and the row
        // sitting on the boundary is exactly N days old, not older.
        assertEquals(0, dao.trimBodiesBefore(cutoffText))
        assertEquals(2_000, bodyOf(1).length)
    }

    @Test
    fun `the sweep compares the exact text the converter writes`() =
        runBlocking {
            // `received_at` is TEXT and the sweep's `<` is a *string* comparison, so the
            // stored format is the whole reason it is chronological. Two properties the query
            // silently depends on are pinned here, and neither is visible from a fake — they
            // exist only once a TypeConverter has written a row:
            //
            //  - the seconds field is always written, even at zero. `LocalDateTime.toString()`
            //    drops it; `DateTimeFormatter.ISO_LOCAL_DATE_TIME`, which the converter uses,
            //    does not. Fixed width is what keeps every comparison in this schema aligned.
            //  - the separator is `T`. `Converters.toLocalDateTime` also *reads* the
            //    space-separated form, and ' ' sorts before 'T', so a row ever written that
            //    way would compare as older than it is against every cutoff the worker
            //    formats — and would be trimmed early, silently.
            dao.insert(message(uid = 1, receivedAt = LocalDateTime.of(2026, 8, 1, 10, 15, 0)))
            dao.insert(message(uid = 2, receivedAt = LocalDateTime.of(2026, 8, 1, 10, 15, 45)))

            assertEquals("2026-08-01T10:15:00", storedReceivedAt(1))
            assertEquals("2026-08-01T10:15:45", storedReceivedAt(2))

            assertEquals(
                "only the 10:15:00 message is before 10:15:30",
                1,
                dao.trimBodiesBefore(cutoffText)
            )
            assertEquals(512, bodyOf(1).length)
            assertEquals(2_000, bodyOf(2).length)
        }

    @Test
    fun `the sweep keeps the row and everything the log says about it`() = runBlocking {
        dao.insert(
            message(
                uid = 1,
                receivedAt = cutoff.minusDays(1),
                processed = true,
                skipReason = "DUPLICATE:7",
                transactionId = 7
            )
        )

        dao.trimBodiesBefore(cutoffText)

        // Retention shortens text; it is not a delete. Screen 48 must still be able to say
        // what this message was and what became of it.
        assertEquals(1, dao.count())
        val row = requireNotNull(dao.getByUid(allMail, 1))
        assertTrue(row.processed)
        assertEquals("DUPLICATE:7", row.skipReason)
        assertEquals(7L, row.transactionId)
        assertEquals("<msg-1@example.com>", row.messageId)
    }

    @Test
    fun `the sweep records the truncation, and only on the row it truncated`() = runBlocking {
        dao.insert(message(uid = 1, receivedAt = cutoff.minusDays(1)))
        dao.insert(message(uid = 2, receivedAt = cutoff.plusDays(1)))
        dao.insert(message(uid = 3, receivedAt = cutoff.minusDays(1), body = filler(100)))

        // The default has to hold before anything sweeps, or every row would claim to be a
        // prefix and R-11-10 would have nothing left to replay.
        assertFalse("a freshly logged message is whole", trimmedFlagOf(1))

        assertEquals("only the aged, over-length row is in scope", 1, dao.trimBodiesBefore(cutoffText))

        // This is the fact that cannot be recovered afterwards: `body` is now 512 characters
        // either way and `body_hash` still describes the text that went, so the flag is the
        // only difference between uid 1 and a mail that was always this short.
        assertTrue("the row whose body was shortened says so", trimmedFlagOf(1))
        assertEquals(512, bodyOf(1).length)

        assertFalse("a body the sweep never touched is not a prefix", trimmedFlagOf(2))
        assertFalse("an aged body already under the limit was never shortened", trimmedFlagOf(3))
        assertEquals("and it is still whole", 100, bodyOf(3).length)
    }

    @Test
    fun `a second, empty sweep does not un-say what the first recorded`() = runBlocking {
        dao.insert(message(uid = 1, receivedAt = cutoff.minusDays(1)))

        dao.trimBodiesBefore(cutoffText)
        assertEquals("the guard keeps the row out of the second pass", 0, dao.trimBodiesBefore(cutoffText))

        // The sweep runs at the end of every completed sync. If the `length(body) > 512`
        // guard is ever loosened into a re-write, or the flag moved into a branch that only
        // fires on the first pass, this is the assertion that notices.
        assertTrue(trimmedFlagOf(1))
    }

    @Test
    fun `a body of exactly 512 characters is not marked as trimmed`() = runBlocking {
        dao.insert(message(uid = 1, receivedAt = cutoff.minusYears(1), body = filler(512)))

        dao.trimBodiesBefore(cutoffText)

        // `>` not `>=`: at exactly the limit `substr` would change nothing, and flagging that
        // row would tell R-11-10 to re-fetch a mail it already holds in full.
        assertFalse(trimmedFlagOf(1))
    }

    // -------------------------------------------------------------------------------------
    // The replay backlog — spec 11 R-11-10
    // -------------------------------------------------------------------------------------

    @Test
    fun `a trimmed message stays on the replay backlog, carrying the flag`() = runBlocking {
        dao.insert(message(uid = 1, receivedAt = cutoff.minusDays(1), processed = false))
        dao.insert(
            message(uid = 2, receivedAt = cutoff.plusDays(1), processed = false)
        )

        dao.trimBodiesBefore(cutoffText)

        // The two halves joined: the sweep shortens the oldest end of exactly the backlog
        // R-11-10 replays, so the flag has to survive the trip through `getUnmatched`. What
        // a consumer owes it is on that method's KDoc — re-fetch the message, or leave the
        // row alone; never parse the prefix as a whole mail (spec 01 constraint C7).
        val backlog = dao.getUnmatched()
        assertEquals(listOf(1L, 2L), backlog.map { it.uid })

        val aged = backlog.first()
        assertTrue("the replay must be able to see that this body is a prefix", aged.bodyTrimmed)
        assertEquals(512, aged.body.length)
        assertEquals("and the hash it would dedup on is still the whole mail's", "hash-1", aged.bodyHash)

        assertFalse("the mail that was never trimmed replays as it stands", backlog[1].bodyTrimmed)
    }

    @Test
    fun `unmatched holds what could parse later and nothing understood and rejected`() =
        runBlocking {
            val t = LocalDateTime.of(2026, 9, 1, 9, 0, 0)
            dao.insert(message(uid = 1, receivedAt = t, processed = false, skipReason = null))
            dao.insert(message(uid = 2, receivedAt = t.plusMinutes(1), skipReason = "NO_PARSER"))
            dao.insert(message(uid = 3, receivedAt = t.plusMinutes(2), skipReason = "UNKNOWN_SENDER"))
            dao.insert(
                message(
                    uid = 4,
                    receivedAt = t.plusMinutes(3),
                    skipReason = "ERROR:IllegalStateException"
                )
            )
            // Understood and rejected on purpose — replaying these would only re-reject them.
            dao.insert(message(uid = 5, receivedAt = t.plusMinutes(4), skipReason = "IGNORE:promo"))
            dao.insert(message(uid = 6, receivedAt = t.plusMinutes(5), skipReason = "NON_TXN"))
            // Already became a transaction.
            dao.insert(
                message(uid = 7, receivedAt = t.plusMinutes(6), skipReason = null, transactionId = 9)
            )

            assertEquals(listOf(1L, 2L, 3L, 4L), dao.getUnmatched().map { it.uid })
        }

    @Test
    fun `unmatched comes back oldest first`() = runBlocking {
        dao.insert(message(uid = 1, receivedAt = LocalDateTime.of(2026, 9, 3, 8, 0, 0), processed = false))
        dao.insert(message(uid = 2, receivedAt = LocalDateTime.of(2026, 9, 1, 8, 0, 0), processed = false))
        dao.insert(message(uid = 3, receivedAt = LocalDateTime.of(2026, 9, 2, 8, 0, 0), processed = false))

        // R-11-10 replays in arrival order, so a later mail cannot settle a due before the
        // earlier one that opened it.
        assertEquals(listOf(2L, 3L, 1L), dao.getUnmatched().map { it.uid })
    }

    // -------------------------------------------------------------------------------------
    // Identity — spec 11 R-11-08
    // -------------------------------------------------------------------------------------

    @Test
    fun `re-fetching a uid is ignored rather than replacing what the first pass learned`() =
        runBlocking {
            val first = message(uid = 1, receivedAt = cutoff, skipReason = "NO_PARSER")
            assertTrue("first insert gets a row id", dao.insert(first) > 0)

            val rowId = dao.insert(message(uid = 1, receivedAt = cutoff, skipReason = null))

            // OnConflictStrategy.IGNORE, and the unique index that makes it fire, both exist
            // only in the database — the entity's `indices` are a compile-time declaration
            // until a row is actually written twice.
            assertEquals("IGNORE reports the conflict as -1", -1L, rowId)
            assertEquals(1, dao.count())
            assertEquals("NO_PARSER", requireNotNull(dao.getByUid(allMail, 1)).skipReason)
        }

    @Test
    fun `the same uid in another mailbox is another message`() = runBlocking {
        dao.insert(message(uid = 1, receivedAt = cutoff, mailbox = allMail))
        dao.insert(message(uid = 1, receivedAt = cutoff, mailbox = "INBOX"))

        // The index is (mailbox, uid), not uid: UIDs are unique only within one folder.
        assertEquals(2, dao.count())
        assertNotNull(dao.getByUid("INBOX", 1))
    }

    @Test
    fun `the watermark is recovered per mailbox`() = runBlocking {
        dao.insert(message(uid = 10, receivedAt = cutoff, mailbox = allMail))
        dao.insert(message(uid = 42, receivedAt = cutoff, mailbox = allMail))
        dao.insert(message(uid = 99, receivedAt = cutoff, mailbox = "INBOX"))

        assertEquals(42L, dao.maxUid(allMail))
        assertEquals(99L, dao.maxUid("INBOX"))
        // MAX() over no rows is SQL NULL, which the resumable sync reads as "start from the
        // beginning". A fake returning 0 would look identical here and is not the same thing.
        assertNull(dao.maxUid("[Gmail]/Spam"))
    }

    @Test
    fun `a message is found by its Message-ID after a uidvalidity reset`() = runBlocking {
        dao.insert(message(uid = 1, receivedAt = cutoff))

        assertEquals(1L, requireNotNull(dao.getByMessageId("<msg-1@example.com>")).uid)
        assertNull(dao.getByMessageId("<absent@example.com>"))
    }
}
