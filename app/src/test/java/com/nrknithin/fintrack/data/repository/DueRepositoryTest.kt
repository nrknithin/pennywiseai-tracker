package com.nrknithin.fintrack.data.repository

import com.nrknithin.fintrack.parser.core.email.due.DueKind
import com.nrknithin.fintrack.parser.core.email.due.ParsedDue
import com.nrknithin.fintrack.data.database.dao.DueDao
import com.nrknithin.fintrack.data.database.dao.DueSourceDao
import com.nrknithin.fintrack.data.database.entity.DueEntity
import com.nrknithin.fintrack.data.database.entity.DueSourceEntity
import com.nrknithin.fintrack.data.database.entity.DueStatus
import com.nrknithin.fintrack.data.database.entity.MergeRule
import com.nrknithin.fintrack.data.database.entity.NoticeKind
import com.nrknithin.fintrack.data.database.entity.SourceChannel
import com.nrknithin.fintrack.domain.engine.DueMergeEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import com.nrknithin.fintrack.data.database.entity.DueKind as StoredDueKind

/**
 * Spec 11 §11.4 step b against spec 20 §20.4, on in-memory DAOs.
 *
 * The engine's own rules are pinned in `DueMergeEngineTest`; what is tested here is the part
 * that only shows up once rows have identities — that four notices about one card bill produce
 * **one** `dues` row and not four, that re-processing a mail writes nothing, and that the row
 * id handed back is the one `EmailTransactionIngestor` will hang the `due_sources` row on.
 *
 * The fakes below follow `EmailIngestFakes.kt`: a `LinkedHashMap` per table, the real DAO's
 * filtering re-stated in Kotlin, and an `insert` that assigns ids the way Room does — which
 * matters here, because the repository's whole answer is the id it comes back with.
 *
 * All data is invented (spec 06 §6.2): last-4s are `1234`/`5678`, references `123456789012`.
 */
class DueRepositoryTest {

    private val clock: Clock = Clock.fixed(Instant.parse("2026-09-17T19:00:00Z"), ZoneOffset.UTC)

    private val dueDao = FakeDueRowDao()
    private val dueSourceDao = FakeDueNoticeDao()
    private val engine = DueMergeEngine(clock)
    private val repository = DueRepository(dueDao, dueSourceDao, engine)

    // =========================================================================================
    // Step 3 — the first notice about a bill
    // =========================================================================================

    @Test
    fun `a bill nobody has seen creates one due`() = runBlocking {
        val result = repository.upsertFromParsed(statement(), emailMessageId = 7)

        assertTrue(result.created)
        assertEquals(1, dueDao.rows.size)

        val stored = dueDao.rows.getValue(result.dueRowId)
        assertEquals("Axis Bank", stored.issuer)
        assertEquals(BigDecimal("4321.50"), stored.amount)
        assertEquals(LocalDate.of(2026, 10, 1), stored.dueDate)
        assertEquals(DueStatus.OPEN, stored.status)
        assertEquals(7L, stored.emailMessageId)
        assertEquals(MergeRule.MERGE_KEY, result.mergeRule)
    }

    @Test
    fun `the row id handed back is the row that was written`() {
        // The ingestor hangs the `due_sources` row on it, so an id that is not a real row id
        // would orphan every notice.
        runBlocking {
            val result = repository.upsertFromParsed(statement(), emailMessageId = 7)

            assertTrue(result.dueRowId > 0)
            assertTrue(dueDao.rows.containsKey(result.dueRowId))
        }
    }

    @Test
    fun `re-processing the same mail does not create a second due`() = runBlocking {
        // R-11-10 replays unmatched mail, and a replay must be free.
        val first = repository.upsertFromParsed(statement(), emailMessageId = 7)
        val second = repository.upsertFromParsed(statement(), emailMessageId = 7)

        assertEquals(1, dueDao.rows.size)
        assertEquals(first.dueRowId, second.dueRowId)
        assertFalse(second.created)
        // Nothing about the bill changed, so the row must not be touched at all.
        assertTrue(dueDao.updates.isEmpty())
    }

    @Test
    fun `two bills in one mail become two dues`() = runBlocking {
        // GoDaddy lists a due per expiring domain (spec 12 D1); the reference keeps them apart.
        val first = repository.upsertFromParsed(renewal(reference = "123456789012"), 7)
        val second = repository.upsertFromParsed(renewal(reference = "123456789013"), 7)

        assertEquals(2, dueDao.rows.size)
        assertTrue(second.created)
        assertTrue(first.dueRowId != second.dueRowId)
    }

    // =========================================================================================
    // Steps 4 and 5 — four senders, one bill
    // =========================================================================================

    @Test
    fun `the aggregator's copy and the issuer's statement land on one row`() = runBlocking {
        val fromCred = repository.upsertFromParsed(credNotice(BigDecimal("4100.00")), 7)
        noticeFor(fromCred.dueRowId, CRED_SENDER)

        val fromBank = repository.upsertFromParsed(statement(), emailMessageId = 8)

        assertEquals(1, dueDao.rows.size)
        assertEquals(fromCred.dueRowId, fromBank.dueRowId)
        assertFalse(fromBank.created)
        // §20.4 step 5: upgraded in place, to the issuer's figure, recorded as an aggregator join.
        assertEquals(BigDecimal("4321.50"), dueDao.rows.getValue(fromBank.dueRowId).amount)
        assertEquals(MergeRule.AGGREGATOR_KEY, fromBank.mergeRule)
    }

    @Test
    fun `the issuer's statement is not overwritten by a later aggregator copy`() = runBlocking {
        val fromBank = repository.upsertFromParsed(statement(), emailMessageId = 7)
        noticeFor(fromBank.dueRowId, AXIS_SENDER)

        val fromCred = repository.upsertFromParsed(credNotice(BigDecimal("4100.00")), 8)

        assertEquals(1, dueDao.rows.size)
        assertEquals(fromBank.dueRowId, fromCred.dueRowId)
        assertEquals(BigDecimal("4321.50"), dueDao.rows.getValue(fromCred.dueRowId).amount)
        assertEquals(MergeRule.AGGREGATOR_KEY, fromCred.mergeRule)
    }

    @Test
    fun `a notice dated a day off the stored due still merges`() = runBlocking {
        val first = repository.upsertFromParsed(statement(), emailMessageId = 7)
        noticeFor(first.dueRowId, AXIS_SENDER)

        val second = repository.upsertFromParsed(
            credNotice(dueDate = LocalDate.of(2026, 10, 2)),
            emailMessageId = 8
        )

        assertEquals(1, dueDao.rows.size)
        assertEquals(first.dueRowId, second.dueRowId)
    }

    @Test
    fun `a bill for another card is another due`() = runBlocking {
        repository.upsertFromParsed(statement(accountLast4 = "1234"), emailMessageId = 7)
        repository.upsertFromParsed(statement(accountLast4 = "5678"), emailMessageId = 8)

        assertEquals(2, dueDao.rows.size)
    }

    @Test
    fun `an invoice merges on its reference when no card is named`() = runBlocking {
        val first = repository.upsertFromParsed(renewal(), emailMessageId = 7)
        noticeFor(first.dueRowId, GODADDY_SENDER)

        val second = repository.upsertFromParsed(renewal(amount = BigDecimal("999.00")), 8)

        assertEquals(1, dueDao.rows.size)
        assertEquals(first.dueRowId, second.dueRowId)
        assertEquals(BigDecimal("999.00"), dueDao.rows.getValue(second.dueRowId).amount)
    }

    @Test
    fun `the candidate with the most sources wins`() = runBlocking {
        // Two rows for one invoice, a day apart — a re-sent renewal notice. Both come back
        // from the reference query, so §20.4 step 4 has a real choice to make, and it sends
        // the reading to the better-described row rather than to the nearer date.
        val thin = dueDao.seed(renewalDue(dueId = "stored-key-a", dueDate = LocalDate.of(2026, 10, 1)))
        val fat = dueDao.seed(renewalDue(dueId = "stored-key-b", dueDate = LocalDate.of(2026, 10, 2)))
        noticeFor(fat.id, GODADDY_SENDER)
        noticeFor(fat.id, CRED_SENDER)
        noticeFor(thin.id, CRED_SENDER)

        val result = repository.upsertFromParsed(renewal(), emailMessageId = 7)

        assertEquals(fat.id, result.dueRowId)
        assertEquals(2, dueDao.rows.size)
    }

    @Test
    fun `the better-described card bill wins, not the earlier-dated one`() = runBlocking {
        // The same contest as the test above, reached through the *card* branch rather than the
        // reference one. Two readings of one statement a day apart — the issuer's own and an
        // aggregator's, which do not agree on the date — and the query that finds them returns
        // both. While it was `LIMIT 1` over `due_date ASC` this could not happen: SQL answered
        // "30 September" and §20.4 step 4 was never consulted about a card bill at all.
        val thin = dueDao.seed(storedDue(dueId = "stored-key-a", dueDate = LocalDate.of(2026, 9, 30)))
        val fat = dueDao.seed(storedDue(dueId = "stored-key-b", dueDate = LocalDate.of(2026, 10, 1)))
        noticeFor(fat.id, AXIS_SENDER)
        noticeFor(fat.id, CRED_SENDER)
        noticeFor(thin.id, CRED_SENDER)

        val result = repository.upsertFromParsed(credNotice(), emailMessageId = 7)

        assertEquals(fat.id, result.dueRowId)
        assertEquals(2, dueDao.rows.size)
    }

    @Test
    fun `ties go to the earliest created`() = runBlocking {
        val later = dueDao.seed(
            renewalDue(dueId = "stored-key-a", createdAt = LocalDateTime.of(2026, 9, 16, 9, 0))
        )
        val earlier = dueDao.seed(
            renewalDue(dueId = "stored-key-b", createdAt = LocalDateTime.of(2026, 9, 14, 9, 0))
        )
        noticeFor(later.id, GODADDY_SENDER)
        noticeFor(earlier.id, GODADDY_SENDER)

        val result = repository.upsertFromParsed(renewal(), emailMessageId = 7)

        assertEquals(earlier.id, result.dueRowId)
    }

    // =========================================================================================
    // §20.7 and R-20-09
    // =========================================================================================

    @Test
    fun `a settled due absorbs the notice without changing`() = runBlocking {
        // The bill created this row, a payment settled it, and now the mail is re-processed.
        // `DueDao.findMergeCandidates` only sees OPEN rows, so it is the key that finds this one.
        val settled = dueDao.seed(
            storedDue(
                dueId = engine.dueId(statement()),
                status = DueStatus.PAID_ON_TIME,
                settledTransactionId = 11
            )
        )
        noticeFor(settled.id, AXIS_SENDER)

        val result = repository.upsertFromParsed(statement(amount = BigDecimal("4480.00")), 7)

        assertEquals(settled.id, result.dueRowId)
        assertEquals(1, dueDao.rows.size)
        assertEquals(BigDecimal("4321.50"), dueDao.rows.getValue(settled.id).amount)
        assertEquals(DueStatus.PAID_ON_TIME, dueDao.rows.getValue(settled.id).status)
        assertTrue(dueDao.updates.isEmpty())
    }

    @Test
    fun `a statement with nothing due is stored dismissed`() = runBlocking {
        val result = repository.upsertFromParsed(statement(amount = BigDecimal.ZERO), 7)

        val stored = dueDao.rows.getValue(result.dueRowId)
        assertEquals(DueStatus.DISMISSED, stored.status)
        // The dismissal is the app's, and the column says so: the note is only its caption.
        assertTrue(stored.autoDismissed)
        assertEquals("Nothing due (statement)", stored.note)
    }

    @Test
    fun `a bill that states no figure is stored flagged, not as a zero`() = runBlocking {
        // Constraint C7 through the repository: `dues.amount` is NOT NULL at v63, so the row
        // holds zero and `amount_stated` is what keeps that from meaning "nothing is owed".
        val result = repository.upsertFromParsed(statement(amount = null), 7)

        val stored = dueDao.rows.getValue(result.dueRowId)
        assertEquals(DueStatus.OPEN, stored.status)
        assertFalse(stored.amountStated)
        assertEquals(0, stored.amount.signum())
    }

    @Test
    fun `a later figure is written to the row together with its flag`() = runBlocking {
        // The pair has to survive a round trip through the DAO: a row updated to hold money
        // while `amount_stated` stayed false would print "—" over a due that has a figure.
        val unknown = repository.upsertFromParsed(statement(amount = null), 7)
        noticeFor(unknown.dueRowId, AXIS_SENDER)

        val billed = repository.upsertFromParsed(statement(), emailMessageId = 8)

        assertEquals(unknown.dueRowId, billed.dueRowId)
        val stored = dueDao.rows.getValue(billed.dueRowId)
        assertEquals(BigDecimal("4321.50"), stored.amount)
        assertTrue(stored.amountStated)
    }

    @Test
    fun `a real bill after an auto-dismissal reaches the same row`() = runBlocking {
        // The key is identical, and `dues.due_id` is unique — a second row is not an option.
        val dismissed = repository.upsertFromParsed(statement(amount = BigDecimal.ZERO), 7)
        noticeFor(dismissed.dueRowId, AXIS_SENDER)

        val real = repository.upsertFromParsed(statement(), emailMessageId = 8)

        assertEquals(1, dueDao.rows.size)
        assertEquals(dismissed.dueRowId, real.dueRowId)
        val stored = dueDao.rows.getValue(real.dueRowId)
        assertEquals(DueStatus.OPEN, stored.status)
        assertEquals(BigDecimal("4321.50"), stored.amount)
    }

    // =========================================================================================
    // What this repository is not allowed to do
    // =========================================================================================

    @Test
    fun `the notices themselves stay the ingestor's job`() = runBlocking {
        // `EmailTransactionIngestor.recordDueSource` writes `due_sources`. Writing it here too
        // would record every notice twice and double every source count.
        repository.upsertFromParsed(statement(), emailMessageId = 7)

        assertTrue(dueSourceDao.rows.isEmpty())
    }

    @Test
    fun `a due id is stamped on every row it writes`() = runBlocking {
        val result = repository.upsertFromParsed(statement(), emailMessageId = 7)

        val stored = dueDao.rows.getValue(result.dueRowId)
        assertEquals(engine.dueId(statement()), stored.dueId)
        assertNull(stored.settledTransactionId)
    }

    // =========================================================================================
    // Step 7 — "payment received" notices merge, and only merge
    // =========================================================================================

    @Test
    fun `a payment confirmation for a bill nobody has seen writes no row`() = runBlocking {
        // §20.4 step 7. Inserting here would put a due in Pending whose date is a payment date
        // and whose amount is an amount paid, and `PlausibleDueSettler` would then settle the
        // payment against that invention instead of against the statement it really cleared.
        val result = repository.mergeIntoExisting(paymentConfirmation(), emailMessageId = 7)

        assertNull(result)
        assertEquals(0, dueDao.rows.size)
    }

    @Test
    fun `a payment confirmation lands on the bill it cleared, weeks apart`() = runBlocking {
        val bill = repository.upsertFromParsed(statement(), emailMessageId = 7)

        // Paid on 4 September against a bill due on 1 October — four weeks, where two readings
        // of one *bill* would be given two days.
        val notice = repository.mergeIntoExisting(paymentConfirmation(), emailMessageId = 8)

        assertEquals(bill.dueRowId, notice?.dueRowId)
        assertFalse(notice!!.created)
        assertEquals(1, dueDao.rows.size)
    }

    @Test
    fun `a payment confirmation reaches the nearer bill, not the oldest one open`() = runBlocking {
        // The case the settlement-span window of step 7 exists for, and the one it used to get
        // wrong. August's statement is still OPEN because it was paid from an account Fin cannot
        // see; October's is the bill this confirmation cleared. Both sit inside the window the
        // confirmation opens — [30 Jul, 12 Nov] around 28 September, 60 days back and 45 forward
        // — so production has to hand `chooseTarget` both rows and let §20.4 step 4's
        // payment-confirmation ordering decide.
        //
        // Every other signal points the wrong way on purpose: August is first by `due_date ASC`,
        // which is what the old `LIMIT 1` query returned, and it is also the row with the most
        // sources, which is what step 4 would prefer for a *bill*. Only the payment-confirmation
        // ordering reaches October, so this test fails for the right reason if either the query
        // narrows again or the engine's step-7 branch is lost.
        val august = dueDao.seed(storedDue(dueId = "stored-key-aug", dueDate = LocalDate.of(2026, 8, 5)))
        val october = dueDao.seed(storedDue(dueId = "stored-key-oct", dueDate = LocalDate.of(2026, 10, 1)))
        noticeFor(august.id, AXIS_SENDER)
        noticeFor(august.id, CRED_SENDER)

        val notice = repository.mergeIntoExisting(
            paymentConfirmation(paidOn = LocalDate.of(2026, 9, 28)),
            emailMessageId = 8
        )

        assertEquals(october.id, notice?.dueRowId)
        assertFalse(notice!!.created)
        // The ingestor hangs the PAYMENT_CONFIRMED source on the id this returns (spec 26's
        // UNMATCHED_PAYMENT_NOTICE reads it back), so landing on August would both credit the
        // wrong bill and leave October looking unpaid.
        assertEquals(2, dueDao.rows.size)
        assertTrue(dueDao.updates.isEmpty())
        assertEquals(LocalDate.of(2026, 8, 5), dueDao.rows.getValue(august.id).dueDate)
    }

    @Test
    fun `a payment confirmation changes nothing about the bill`() = runBlocking {
        val bill = repository.upsertFromParsed(statement(), emailMessageId = 7)

        repository.mergeIntoExisting(paymentConfirmation(), emailMessageId = 8)

        val stored = dueDao.rows.getValue(bill.dueRowId)
        assertEquals(BigDecimal("4321.50"), stored.amount)
        assertEquals(LocalDate.of(2026, 10, 1), stored.dueDate)
        assertEquals(DueStatus.OPEN, stored.status)
        assertNull(stored.settledTransactionId)
        // An untouched row is not written, so `updated_at` does not move either.
        assertTrue(dueDao.updates.isEmpty())
    }

    // =========================================================================================
    // Fixtures
    // =========================================================================================

    private fun statement(
        amount: BigDecimal? = BigDecimal("4321.50"),
        accountLast4: String? = "1234",
        dueDate: LocalDate = LocalDate.of(2026, 10, 1),
        source: String = "AXIS"
    ): ParsedDue = ParsedDue(
        issuer = "Axis Bank",
        kind = DueKind.CARD_STATEMENT,
        amount = amount,
        dueDate = dueDate,
        accountLast4 = accountLast4,
        source = source
    )

    /**
     * CRED's "payment successful" mail for the same card (spec 12 §12.6 C2).
     *
     * The amount is the amount paid — the user paid the minimum — and the date is the day it
     * was paid, not a due date. Neither may reach the row.
     */
    private fun paymentConfirmation(
        amount: BigDecimal? = BigDecimal("900.00"),
        paidOn: LocalDate = LocalDate.of(2026, 9, 4)
    ): ParsedDue = statement(amount = amount, dueDate = paidOn, source = "CRED")
        .copy(paymentConfirmed = true)

    private fun credNotice(
        amount: BigDecimal? = BigDecimal("4321.50"),
        dueDate: LocalDate = LocalDate.of(2026, 10, 1)
    ): ParsedDue = statement(amount = amount, dueDate = dueDate, source = "CRED")

    private fun renewal(
        reference: String = "123456789012",
        amount: BigDecimal? = BigDecimal("899.00")
    ): ParsedDue = ParsedDue(
        issuer = "GoDaddy",
        kind = DueKind.DOMAIN_RENEWAL,
        amount = amount,
        dueDate = LocalDate.of(2026, 10, 1),
        reference = reference,
        source = "GODADDY"
    )

    /** A row already in the table, as an earlier sync would have left it. */
    private fun storedDue(
        dueId: String = "stored-key",
        dueDate: LocalDate = LocalDate.of(2026, 10, 1),
        amount: BigDecimal = BigDecimal("4321.50"),
        status: DueStatus = DueStatus.OPEN,
        settledTransactionId: Long? = null,
        createdAt: LocalDateTime = LocalDateTime.of(2026, 9, 15, 10, 0)
    ): DueEntity = DueEntity(
        dueId = dueId,
        issuer = "Axis Bank",
        kind = StoredDueKind.CARD_STATEMENT,
        amount = amount,
        dueDate = dueDate,
        accountLast4 = "1234",
        status = status,
        settledTransactionId = settledTransactionId,
        createdAt = createdAt,
        updatedAt = createdAt
    )

    /** The same, for a due that identifies itself by an invoice reference rather than a card. */
    private fun renewalDue(
        dueId: String,
        dueDate: LocalDate = LocalDate.of(2026, 10, 1),
        createdAt: LocalDateTime = LocalDateTime.of(2026, 9, 15, 10, 0)
    ): DueEntity = DueEntity(
        dueId = dueId,
        issuer = "GoDaddy",
        kind = StoredDueKind.DOMAIN_RENEWAL,
        amount = BigDecimal("899.00"),
        dueDate = dueDate,
        reference = "123456789012",
        createdAt = createdAt,
        updatedAt = createdAt
    )

    /** What the ingestor writes after an upsert; the repository only ever reads these. */
    private fun noticeFor(dueRowId: Long, sender: String) {
        dueSourceDao.rows += DueSourceEntity(
            id = dueSourceDao.rows.size + 1L,
            dueId = dueRowId,
            channel = SourceChannel.EMAIL,
            sender = sender,
            noticeKind = NoticeKind.STATEMENT,
            receivedAt = LocalDateTime.of(2026, 9, 15, 10, 0)
        )
    }

    private companion object {
        const val AXIS_SENDER = "cc.statements@axis.bank.in"
        const val CRED_SENDER = "protect@cred.club"
        const val GODADDY_SENDER = "noreply@godaddy.com"
    }
}

// =============================================================================================
// Fakes
// =============================================================================================

private fun unused(name: String): Nothing =
    throw UnsupportedOperationException("$name is not exercised by the due repository tests")

/**
 * `dues` as a map, with Room's auto-generated ids.
 *
 * Named apart from `EmailIngestFakes`' `FakeDueDao` because it differs in the one way that
 * matters here: [insert] assigns the next id and returns it, which is what `DueRepository`
 * reports back to the ingestor.
 */
private class FakeDueRowDao : DueDao {
    val rows = LinkedHashMap<Long, DueEntity>()
    val updates = mutableListOf<DueEntity>()
    private var nextId = 1L

    fun seed(due: DueEntity): DueEntity {
        val stored = due.copy(id = nextId++)
        rows[stored.id] = stored
        return stored
    }

    override suspend fun insert(due: DueEntity): Long {
        // `due_id` is unique with ABORT on conflict; a fake that let a duplicate through would
        // hide exactly the bug the merge exists to prevent.
        require(rows.values.none { it.dueId == due.dueId }) { "duplicate due_id ${due.dueId}" }
        val stored = due.copy(id = nextId++)
        rows[stored.id] = stored
        return stored.id
    }

    override suspend fun update(due: DueEntity) {
        rows[due.id] = due
        updates += due
    }

    override suspend fun delete(due: DueEntity) {
        rows.remove(due.id)
    }

    override suspend fun getById(id: Long): DueEntity? = rows[id]

    override fun observeById(id: Long): Flow<DueEntity?> = flowOf(rows[id])

    override suspend fun getByDueId(dueId: String): DueEntity? =
        rows.values.firstOrNull { it.dueId == dueId }

    override fun observeOpen(): Flow<List<DueEntity>> =
        flowOf(rows.values.filter { it.status == DueStatus.OPEN })

    override fun observeOpenThrough(through: LocalDate): Flow<List<DueEntity>> =
        flowOf(rows.values.filter { it.status == DueStatus.OPEN && !it.dueDate.isAfter(through) })

    override fun observeOverdue(asOf: LocalDate): Flow<List<DueEntity>> =
        flowOf(rows.values.filter { it.status == DueStatus.OPEN && it.dueDate.isBefore(asOf) })

    override fun observeOpenCount(): Flow<Int> =
        flowOf(rows.values.count { it.status == DueStatus.OPEN })

    override fun observeCommittedTotal(through: LocalDate, currency: String): Flow<Double> =
        flowOf(0.0)

    override suspend fun findSettlementCandidates(from: LocalDate, to: LocalDate): List<DueEntity> =
        unused("findSettlementCandidates")

    /**
     * The SQL of `DueDao.findMergeCandidates`, re-stated: OPEN only, **every** row in the
     * window, ordered `due_date ASC, id ASC`.
     *
     * It used to end `minByOrNull { it.dueDate }`, faithfully restating a `LIMIT 1` query — and
     * faithfully restating its defect, which is why the suite could not see that production
     * never handed `chooseTarget` a choice. A fake that narrows what the database returns hides
     * exactly the bugs the repository exists to avoid, so this one narrows nothing.
     */
    override suspend fun findMergeCandidates(
        kind: StoredDueKind,
        issuer: String,
        accountLast4: String?,
        fromDate: LocalDate,
        toDate: LocalDate
    ): List<DueEntity> = rows.values
        .filter {
            it.status == DueStatus.OPEN &&
                it.kind == kind &&
                it.issuer.equals(issuer, ignoreCase = true) &&
                it.accountLast4 == accountLast4 &&
                !it.dueDate.isBefore(fromDate) && !it.dueDate.isAfter(toDate)
        }
        .sortedWith(compareBy({ it.dueDate }, { it.id }))

    override suspend fun getSettledBy(transactionId: Long): DueEntity? =
        rows.values.firstOrNull { it.settledTransactionId == transactionId }

    override fun observeSettled(limit: Int): Flow<List<DueEntity>> =
        flowOf(rows.values.filter { it.status != DueStatus.OPEN }.take(limit))

    override suspend fun getByReference(reference: String): List<DueEntity> =
        rows.values.filter { it.reference == reference }

    override fun observeForAccount(last4: String): Flow<List<DueEntity>> =
        flowOf(rows.values.filter { it.accountLast4 == last4 })

    override suspend fun getByStatus(status: DueStatus): List<DueEntity> =
        rows.values.filter { it.status == status }

    override suspend fun getByEmailMessageId(emailMessageId: Long): List<DueEntity> =
        rows.values.filter { it.emailMessageId == emailMessageId }

    override suspend fun count(): Int = rows.size

    override suspend fun deleteAll() = rows.clear()
}

/** `due_sources` as a list. The repository reads it; only the ingestor writes it. */
private class FakeDueNoticeDao : DueSourceDao {
    val rows = mutableListOf<DueSourceEntity>()
    private var nextId = 1L

    override suspend fun insert(source: DueSourceEntity): Long {
        val id = nextId++
        rows += source.copy(id = id)
        return id
    }

    override suspend fun update(source: DueSourceEntity) {
        val index = rows.indexOfFirst { it.id == source.id }
        if (index >= 0) rows[index] = source
    }

    override suspend fun getForDue(dueId: Long): List<DueSourceEntity> =
        rows.filter { it.dueId == dueId }.sortedBy { it.receivedAt }

    override fun observeForDue(dueId: Long): Flow<List<DueSourceEntity>> =
        flowOf(rows.filter { it.dueId == dueId })

    override suspend fun getByEmailMessageId(emailMessageId: Long): List<DueSourceEntity> =
        rows.filter { it.emailMessageId == emailMessageId }

    override suspend fun countForEmail(dueId: Long, emailMessageId: Long): Int =
        rows.count { it.dueId == dueId && it.emailMessageId == emailMessageId }

    override suspend fun countForDue(dueId: Long): Int = rows.count { it.dueId == dueId }

    override suspend fun deleteForDue(dueId: Long) {
        rows.removeAll { it.dueId == dueId }
    }

    override suspend fun deleteAll() = rows.clear()
}
