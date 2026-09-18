package com.nrknithin.fintrack.data.database

import com.nrknithin.fintrack.data.database.dao.TransactionSourceDao
import com.nrknithin.fintrack.data.database.entity.MatchRule
import com.nrknithin.fintrack.data.database.entity.SourceChannel
import com.nrknithin.fintrack.data.database.entity.TransactionSourceEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDateTime

/**
 * `TransactionSourceDao` against a real database (spec 06 §6.1, "Repository / DAO tests").
 *
 * [TransactionSourceDao.countSameTemplate] is spec 14 §14.3's false-merge guard as this fork
 * states it. The spec's own wording — "email never twins another email" — would keep HDFC's two
 * templates for one card charge apart (H7 "charge without OTP" and H8 "e-mandate paid"), which
 * spec 10 §10.8 requires to collapse into one transaction; so the guard compares the *template*
 * as well as the channel. That widening is the whole reason the column exists, and until this
 * suite the only thing checking it was that the SQL parsed.
 *
 * Every reference here is invented (spec 06 §6.2): source refs are row ids, hashes are labels.
 */
@RunWith(RobolectricTestRunner::class)
class TransactionSourceDaoTest {

    private lateinit var db: FinTrackDatabase
    private lateinit var dao: TransactionSourceDao

    @Before
    fun openDatabase() {
        db = RoomTestDb.open()
        dao = db.transactionSourceDao()
    }

    @After
    fun closeDatabase() {
        db.close()
    }

    // -------------------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------------------

    /** Fixed, so nothing here depends on the day the suite runs (spec 02 §2.8). */
    private val seen: LocalDateTime = LocalDateTime.of(2026, 9, 12, 19, 5, 0)

    private fun source(
        transactionId: Long,
        sourceRef: String,
        channel: SourceChannel = SourceChannel.EMAIL,
        parserId: String? = "H7",
        receivedAt: LocalDateTime = seen,
        isPrimary: Boolean = false,
        matchRule: MatchRule? = null
    ) = TransactionSourceEntity(
        transactionId = transactionId,
        channel = channel,
        sourceRef = sourceRef,
        sender = "alerts@example.com",
        receivedAt = receivedAt,
        bodyHash = "hash-" + sourceRef,
        isPrimary = isPrimary,
        matchRule = matchRule,
        parserId = parserId
    )

    // -------------------------------------------------------------------------------------
    // The false-merge guard — spec 14 §14.3, widened per spec 10 §10.8
    // -------------------------------------------------------------------------------------

    @Test
    fun `two different templates describing one charge are not duplicates of each other`() =
        runBlocking {
            dao.insert(source(transactionId = 1, sourceRef = "email-1", parserId = "H7"))

            // The pair spec 10 §10.8 requires to collapse: the same charge announced twice by
            // one bank under two templates. Under the spec's literal "email never twins
            // another email" this would have been refused and become a second transaction.
            assertEquals(0, dao.countSameTemplate(1, SourceChannel.EMAIL, "H8"))
        }

    @Test
    fun `the same template arriving twice is recognised`() = runBlocking {
        dao.insert(source(transactionId = 1, sourceRef = "email-1", parserId = "H7"))

        // What the guard is genuinely protecting against: two independent alerts from one
        // template are two events, and absorbing the second would lose a real charge.
        assertEquals(1, dao.countSameTemplate(1, SourceChannel.EMAIL, "H7"))
    }

    @Test
    fun `a source recorded without a template never counts`() = runBlocking {
        dao.insert(source(transactionId = 1, sourceRef = "email-1", parserId = null))

        // `parser_id IS NOT NULL` is load-bearing rather than defensive: in SQL a NULL
        // compared to anything is NULL, so an unattributed source must be excluded
        // explicitly or it becomes a row that can never be reasoned about.
        assertEquals(0, dao.countSameTemplate(1, SourceChannel.EMAIL, "H7"))
        // It is still a source of the transaction, though — the guard ignores it, the log
        // does not.
        assertEquals(1, dao.getForTransaction(1).size)
    }

    @Test
    fun `another transaction's source with the same template does not count`() = runBlocking {
        dao.insert(source(transactionId = 2, sourceRef = "email-2", parserId = "H7"))

        // Two genuine charges of the same shape on the same day are two transactions
        // (spec 06 §6.3, Dedup). The guard is scoped to one transaction, and only the
        // `transaction_id` predicate keeps it that way.
        assertEquals(0, dao.countSameTemplate(1, SourceChannel.EMAIL, "H7"))
    }

    @Test
    fun `the same template id on another channel does not count`() = runBlocking {
        dao.insert(
            source(transactionId = 1, sourceRef = "sms-1", channel = SourceChannel.SMS, parserId = "H7")
        )

        // A bank's SMS and its email are exactly the twin pair dedup is meant to join, so an
        // SMS must never make the email look like a duplicate of something already absorbed.
        assertEquals(0, dao.countSameTemplate(1, SourceChannel.EMAIL, "H7"))
    }

    @Test
    fun `the channel-wide count is the wider question, and gives a different answer`() =
        runBlocking {
            dao.insert(source(transactionId = 1, sourceRef = "email-1", parserId = "H7"))
            dao.insert(source(transactionId = 1, sourceRef = "email-2", parserId = "H8"))

            // Side by side, because the difference between the two is the deviation itself:
            // the channel-wide count sees the H7/H8 pair as two emails and would refuse the
            // merge; the template-aware count sees two different templates and allows it.
            assertEquals(2, dao.countSameChannel(1, SourceChannel.EMAIL))
            assertEquals(1, dao.countSameTemplate(1, SourceChannel.EMAIL, "H7"))
            assertEquals(1, dao.countSameTemplate(1, SourceChannel.EMAIL, "H8"))
        }

    // -------------------------------------------------------------------------------------
    // One source row per message
    // -------------------------------------------------------------------------------------

    @Test
    fun `re-processing a message updates nothing and duplicates nothing`() = runBlocking {
        assertTrue(dao.insert(source(transactionId = 1, sourceRef = "email-1")) > 0)

        val again = dao.insert(source(transactionId = 2, sourceRef = "email-1"))

        // The unique index is (channel, source_ref), and IGNORE is what makes R-11-10's
        // replay safe: a message already attributed stays attributed to the transaction it
        // was attributed to, and the second attempt is a no-op rather than a re-pointing.
        assertEquals(-1L, again)
        assertEquals(1, dao.getForTransaction(1).size)
        assertTrue(dao.getForTransaction(2).isEmpty())
    }

    @Test
    fun `an SMS id and an email id may collide without colliding`() = runBlocking {
        dao.insert(source(transactionId = 1, sourceRef = "7", channel = SourceChannel.EMAIL))
        dao.insert(source(transactionId = 1, sourceRef = "7", channel = SourceChannel.SMS))

        // `source_ref` is the SMS `_id`, the `email_messages.id` or a notification key — three
        // independent numbering schemes, which is why the channel is half of the index.
        assertEquals(2, dao.getForTransaction(1).size)
        assertNotNull(dao.getBySourceRef(SourceChannel.SMS, "7"))
        assertNotNull(dao.getBySourceRef(SourceChannel.EMAIL, "7"))
        assertNull(dao.getBySourceRef(SourceChannel.NOTIFICATION, "7"))
    }

    @Test
    fun `the sources of a transaction come back in arrival order`() = runBlocking {
        dao.insert(source(transactionId = 1, sourceRef = "late", receivedAt = seen.plusMinutes(10)))
        dao.insert(source(transactionId = 1, sourceRef = "first", receivedAt = seen))
        dao.insert(source(transactionId = 1, sourceRef = "middle", receivedAt = seen.plusMinutes(5)))

        // Screen 45 reads this as "seen in 3 messages", oldest first, so the user sees which
        // message arrived first rather than which row happened to be written first.
        assertEquals(
            listOf("first", "middle", "late"),
            dao.getForTransaction(1).map { it.sourceRef }
        )
    }

    @Test
    fun `merging two transactions by hand re-points every source`() = runBlocking {
        dao.insert(source(transactionId = 1, sourceRef = "email-1", isPrimary = true))
        dao.insert(source(transactionId = 2, sourceRef = "email-2", matchRule = MatchRule.HASH))
        dao.insert(source(transactionId = 2, sourceRef = "sms-2", channel = SourceChannel.SMS))

        val moved = dao.reassign(fromId = 2, toId = 1)

        // Spec 14 §14.6. The count matters as much as the move: a manual merge that silently
        // left a source behind would leave the absorbed transaction still holding evidence.
        assertEquals(2, moved)
        assertEquals(3, dao.getForTransaction(1).size)
        assertTrue(dao.getForTransaction(2).isEmpty())
        assertEquals(0, dao.reassign(fromId = 2, toId = 1))
    }

    @Test
    fun `sources are found by the body hash dedup matched on`() = runBlocking {
        dao.insert(source(transactionId = 1, sourceRef = "email-1"))
        dao.insert(source(transactionId = 2, sourceRef = "email-2"))

        // Layer L0 of spec 14 §14.3 asks this question of every incoming message.
        assertEquals(listOf(1L), dao.getByBodyHash("hash-email-1").map { it.transactionId })
        assertTrue(dao.getByBodyHash("hash-absent").isEmpty())
    }
}
