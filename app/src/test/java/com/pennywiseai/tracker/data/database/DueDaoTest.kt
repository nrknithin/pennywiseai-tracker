package com.pennywiseai.tracker.data.database

import android.database.sqlite.SQLiteConstraintException
import com.pennywiseai.tracker.data.database.dao.DueDao
import com.pennywiseai.tracker.data.database.entity.DueEntity
import com.pennywiseai.tracker.data.database.entity.DueKind
import com.pennywiseai.tracker.data.database.entity.DueStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * `DueDao` against a real database (spec 06 §6.1, "Repository / DAO tests").
 *
 * [DueDao.findMergeCandidates] is the query spec 20 §20.4 turns on: it decides whether the
 * issuer's statement, CRED's copy of it and the aggregator's reminder land on one `dues` row or
 * three. `DueMergeEngine` is pure Kotlin and heavily tested, but the engine never sees a due it
 * was not handed — if this query returns nothing, the engine is asked to merge against an empty
 * set and inserts a duplicate, and every test above it still passes. Three of its clauses are
 * SQL-only and cannot be exercised any other way: `LOWER(issuer) = LOWER(:issuer)`, the
 * three-way NULL handling on `account_last4`, and the `due_date ASC, id ASC` ordering.
 *
 * What it must **not** do is choose. The query returned one row until the set query replaced it
 * (§20.4 step 2 gathers, step 4 picks), so the cases below check the whole list where more than
 * one row qualifies: a second candidate that SQL used to swallow is a `PAYMENT_CONFIRMED` source
 * landing on the wrong month's bill.
 *
 * All issuers here are institutions rather than people, last-4s are the redacted `1234`/`5678`
 * of spec 06 §6.2, and no amount is anyone's real figure.
 */
@RunWith(RobolectricTestRunner::class)
class DueDaoTest {

    private lateinit var db: PennyWiseDatabase
    private lateinit var dao: DueDao

    @Before
    fun openDatabase() {
        db = RoomTestDb.open()
        dao = db.dueDao()
    }

    @After
    fun closeDatabase() {
        db.close()
    }

    // -------------------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------------------

    /** Fixed, so nothing here depends on the day the suite runs (spec 02 §2.8). */
    private val billed: LocalDate = LocalDate.of(2026, 9, 5)
    private val someTime: LocalDateTime = LocalDateTime.of(2026, 8, 20, 6, 30, 0)

    private fun due(
        dueId: String,
        issuer: String = "HDFC Bank",
        kind: DueKind = DueKind.CARD_STATEMENT,
        accountLast4: String? = "1234",
        dueDate: LocalDate = billed,
        status: DueStatus = DueStatus.OPEN,
        reference: String? = null,
        amount: String = "4250.00"
    ) = DueEntity(
        dueId = dueId,
        issuer = issuer,
        kind = kind,
        amount = BigDecimal(amount),
        dueDate = dueDate,
        accountLast4 = accountLast4,
        reference = reference,
        status = status,
        createdAt = someTime,
        updatedAt = someTime
    )

    /** The window `DueRepository` opens around a newly read due date (spec 20 §20.4). */
    private suspend fun candidatesFor(
        kind: DueKind = DueKind.CARD_STATEMENT,
        issuer: String = "HDFC Bank",
        accountLast4: String? = "1234",
        around: LocalDate = billed,
        slack: Long = 3
    ): List<DueEntity> = dao.findMergeCandidates(
        kind = kind,
        issuer = issuer,
        accountLast4 = accountLast4,
        fromDate = around.minusDays(slack),
        toDate = around.plusDays(slack)
    )

    /**
     * The head of that set, for the cases that are about *whether* a row qualifies at all.
     *
     * Every one of them stores a single due, so "the first row" and "the only row" are the same
     * answer; the cases that are about more than one candidate call [candidatesFor] directly.
     */
    private suspend fun candidateFor(
        kind: DueKind = DueKind.CARD_STATEMENT,
        issuer: String = "HDFC Bank",
        accountLast4: String? = "1234",
        around: LocalDate = billed,
        slack: Long = 3
    ): DueEntity? = candidatesFor(kind, issuer, accountLast4, around, slack).firstOrNull()

    // -------------------------------------------------------------------------------------
    // The merge key — spec 20 §20.4
    // -------------------------------------------------------------------------------------

    @Test
    fun `a second reading of the same bill finds the row already there`() = runBlocking {
        dao.insert(due(dueId = "bill-1"))

        // The issuer is spelled differently by every sender that describes one bill; the query
        // folds case in SQL, and SQL is the only place that folding happens.
        assertEquals("bill-1", candidateFor(issuer = "hdfc bank")?.dueId)
        assertEquals("bill-1", candidateFor(issuer = "HDFC BANK")?.dueId)
    }

    @Test
    fun `a different kind is a different bill`() = runBlocking {
        dao.insert(due(dueId = "bill-1", kind = DueKind.CARD_STATEMENT))

        // A card statement and the loan EMI from the same lender are two obligations, and
        // merging them would hide one of them entirely.
        assertNull(candidateFor(kind = DueKind.LOAN_EMI))
    }

    @Test
    fun `a different issuer is a different bill`() = runBlocking {
        dao.insert(due(dueId = "bill-1", issuer = "HDFC Bank"))

        assertNull(candidateFor(issuer = "Axis Bank"))
    }

    @Test
    fun `a different card is a different bill`() = runBlocking {
        dao.insert(due(dueId = "bill-1", accountLast4 = "1234"))

        // Two cards with the same issuer bill separately and on different dates; a match on
        // issuer alone would fold one month's two statements into one.
        assertNull(candidateFor(accountLast4 = "5678"))
    }

    @Test
    fun `a card-less due and a reading that names a card do not merge`() = runBlocking {
        dao.insert(due(dueId = "bill-1", accountLast4 = null))

        // The clause is deliberately three-way: null matches only null. A reading that names
        // a card is *more* specific than a stored due that does not, and treating the two as
        // the same row would attach a known card's bill to an unattributed one.
        assertNull(candidateFor(accountLast4 = "1234"))
    }

    @Test
    fun `a reading with no card finds the card-less due`() = runBlocking {
        dao.insert(due(dueId = "bill-1", accountLast4 = null))

        // Two nulls are the same absence. Written in SQL as an explicit `IS NULL` pair,
        // because `NULL = NULL` is NULL and would silently never match.
        assertEquals("bill-1", candidateFor(accountLast4 = null)?.dueId)
    }

    @Test
    fun `a card-less due is invisible to a reading that names a card, both ways round`() =
        runBlocking {
            dao.insert(due(dueId = "carded", accountLast4 = "1234"))

            assertNull(candidateFor(accountLast4 = null))
        }

    @Test
    fun `a settled due is not a merge candidate`() = runBlocking {
        dao.insert(due(dueId = "bill-1", status = DueStatus.PAID_ON_TIME))
        dao.insert(due(dueId = "bill-2", status = DueStatus.DISMISSED))

        // Next month's statement must open a new row, not re-open last month's closed one.
        assertNull(candidateFor())
    }

    @Test
    fun `the near end of the date window is included`() = runBlocking {
        dao.insert(due(dueId = "early", dueDate = billed.minusDays(3)))

        assertEquals("early", candidateFor(slack = 3)?.dueId)
        assertNull("one day short of it and the row is out of scope", candidateFor(slack = 2))
    }

    @Test
    fun `the far end of the date window is included`() = runBlocking {
        dao.insert(due(dueId = "late", dueDate = billed.plusDays(3)))

        // Spec 20 §20.4 step 2 compares `|dueDate − parsed.dueDate| ≤ mergeDateToleranceDays`,
        // which is symmetric: the reading may arrive either side of the stored date, because
        // neither sender's idea of the due date is authoritative over the other's.
        assertEquals("late", candidateFor(slack = 3)?.dueId)
        assertNull("one day short of it and the row is out of scope", candidateFor(slack = 2))
    }

    @Test
    fun `a bill past the far end is the next statement, not this one`() = runBlocking {
        dao.insert(due(dueId = "next-cycle", dueDate = billed.plusDays(4)))

        // The one bound the rest of this suite cannot see. Every other row here sits at or
        // below `billed + 2`, so rewriting `due_date BETWEEN :fromDate AND :toDate` as
        // `due_date >= :fromDate` would leave them all green — and an unbounded upper edge is
        // the direction that loses money: next month's statement merges onto this month's,
        // one of the two bills stops existing, and its amount is never shown as owed.
        assertNull(candidateFor(slack = 3))

        // Widening the window past it finds the row, so the null above is the upper bound
        // doing its job rather than an insert that silently never landed.
        assertEquals("next-cycle", candidateFor(slack = 4)?.dueId)
    }

    @Test
    fun `a due outside the window is left alone`() = runBlocking {
        dao.insert(due(dueId = "last-month", dueDate = billed.minusDays(30)))

        assertNull(candidateFor())
    }

    @Test
    fun `every open due in the window comes back, soonest first`() = runBlocking {
        dao.insert(due(dueId = "later", dueDate = billed.plusDays(2)))
        dao.insert(due(dueId = "sooner", dueDate = billed.minusDays(2)))

        // The query gathers; §20.4 step 4 chooses. It used to end `LIMIT 1`, which silently made
        // "earliest due date" the choice — fine for two readings of one bill two days apart, and
        // wrong for a payment confirmation matched over sixty days, where the older row is last
        // month's statement and the money went to this month's.
        assertEquals(listOf("sooner", "later"), candidatesFor().map { it.dueId })
    }

    @Test
    fun `two dues sharing a date come back in a stable order`() = runBlocking {
        // A tie on `due_date` has to be broken inside SQL or not at all: SQLite is free to
        // return rows in any order it likes, and an engine handed the same two candidates in a
        // different order on the next sync could send the notice to a different row. `id ASC`
        // costs nothing here and makes the list reproducible.
        dao.insert(due(dueId = "first-in"))
        dao.insert(due(dueId = "second-in"))

        assertEquals(listOf("first-in", "second-in"), candidatesFor().map { it.dueId })
    }

    @Test
    fun `a settled due in the window is dropped without hiding the open one beside it`() =
        runBlocking {
            dao.insert(due(dueId = "paid", dueDate = billed.minusDays(2), status = DueStatus.PAID_ON_TIME))
            dao.insert(due(dueId = "open", dueDate = billed))

            // The settled row is earlier, so under `LIMIT 1` a status filter that ever regressed
            // would have taken the whole answer with it. Asserting the list keeps both halves
            // honest: what is excluded, and that excluding it costs nothing else.
            assertEquals(listOf("open"), candidatesFor().map { it.dueId })
        }

    // -------------------------------------------------------------------------------------
    // Lookup by the issuer's own identifier
    // -------------------------------------------------------------------------------------

    @Test
    fun `every due filed under one reference comes back, newest first`() = runBlocking {
        dao.insert(due(dueId = "aug", reference = "INV-123456789012", dueDate = LocalDate.of(2026, 8, 5)))
        dao.insert(due(dueId = "sep", reference = "INV-123456789012", dueDate = LocalDate.of(2026, 9, 5)))
        dao.insert(due(dueId = "other", reference = "INV-999999999999", dueDate = LocalDate.of(2026, 9, 6)))

        assertEquals(listOf("sep", "aug"), dao.getByReference("INV-123456789012").map { it.dueId })
    }

    @Test
    fun `an unknown reference gives an empty list rather than nothing at all`() = runBlocking {
        dao.insert(due(dueId = "sep", reference = "INV-123456789012"))

        // Spec 01 constraint C7 in the small: "no due carries this reference" is an answer,
        // and it has to be distinguishable from a failure to look.
        assertTrue(dao.getByReference("INV-000000000000").isEmpty())
    }

    @Test
    fun `reference matching is byte-exact`() = runBlocking {
        dao.insert(due(dueId = "sep", reference = "INV-123456789012"))

        // Pinned because it is a known divergence, not because it is desirable: `DueMergeEngine`
        // compares references trimmed and lower-cased, while this query compares them as stored.
        // A future change that adds `COLLATE NOCASE` here should fail this test and be a
        // deliberate edit, not a silent widening of what counts as the same bill.
        // (BUILD-STATUS "Next action" nit 4; docs/FORK-CHANGES.md.)
        assertTrue(dao.getByReference("inv-123456789012").isEmpty())
        assertTrue(dao.getByReference(" INV-123456789012 ").isEmpty())
    }

    // -------------------------------------------------------------------------------------
    // Identity
    // -------------------------------------------------------------------------------------

    @Test
    fun `a duplicate due_id is refused rather than replacing the row`() = runBlocking {
        dao.insert(due(dueId = "bill-1", amount = "4250.00"))

        // OnConflictStrategy.ABORT. A REPLACE would delete the row and insert a new one with a
        // fresh primary key, orphaning its `due_sources` and any settlement recorded against
        // it — which is exactly the failure the DAO's KDoc says it is avoiding, and which only
        // a real unique index can demonstrate.
        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking { dao.insert(due(dueId = "bill-1", amount = "9999.00")) }
        }

        assertEquals(1, dao.count())
        assertEquals(BigDecimal("4250.00"), requireNotNull(dao.getByDueId("bill-1")).amount)
    }

    @Test
    fun `the settlement guard finds the due a transaction already closed`() = runBlocking {
        val id = dao.insert(due(dueId = "bill-1"))
        dao.update(
            requireNotNull(dao.getById(id)).copy(
                status = DueStatus.PAID_ON_TIME,
                settledTransactionId = 77,
                settledAt = someTime
            )
        )

        // Spec 01 rule 3: a transaction settles at most one due. The repository enforces it by
        // asking this question first, so the question has to actually reach the column.
        assertEquals("bill-1", dao.getSettledBy(77)?.dueId)
        assertNull(dao.getSettledBy(78))
    }
}
