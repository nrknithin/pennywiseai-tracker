package com.pennywiseai.tracker.data.email

import com.pennywiseai.parser.core.email.due.DueEscalation
import com.pennywiseai.parser.core.email.due.DueKind
import com.pennywiseai.parser.core.email.due.ParsedDue
import com.pennywiseai.tracker.data.database.entity.MatchRule
import com.pennywiseai.tracker.data.database.entity.NoticeKind
import com.pennywiseai.tracker.data.database.entity.SourceChannel
import com.pennywiseai.tracker.data.database.entity.TransactionSourceEntity
import com.pennywiseai.tracker.data.database.entity.TransactionType
import com.pennywiseai.tracker.domain.engine.DedupConfig
import com.pennywiseai.tracker.domain.engine.DedupEngine
import com.pennywiseai.tracker.domain.engine.OwnAccount
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Spec 11 §11.4, step by step, driven entirely on fakes.
 *
 * Three properties get the most attention here, because they are the ones a refactor breaks
 * silently:
 *
 *  - **order** — dues are parsed before transactions, so a bill and the payment that clears it
 *    arriving in one fetch still settle (spec 02 §2.4);
 *  - **containment** — a parser that throws costs its own message and nothing else;
 *  - **enrichment only fills holes** — a merge never overwrites a value that is already there,
 *    and never touches the category (spec 14 §14.4).
 *
 * Every value is invented, redacted per spec 06 §6.2.
 */
class EmailTransactionIngestorTest {

    /** Fixed, and UTC, so message timestamps and stored `date_time`s are the same wall clock. */
    private val clock: Clock = Clock.fixed(INGEST_NOW, ZoneOffset.UTC)

    private val log = CallLog()
    private val emailDao = FakeEmailMessageDao()
    private val sourceDao = FakeTransactionSourceDao()
    private val dueSourceDao = FakeDueSourceDao()
    private val store = FakeTransactionStore()
    private val saver = FakeTransactionSaver(log, store)
    private val dueWriter = FakeDueWriter(log)
    private val settler = FakeDueSettler(log)
    private val dueParsers = FakeDueParsing(log)
    private val transactionParsers = FakeTransactionParsing(log)
    private val unrecognised = FakeUnrecognisedRecorder()

    private val ingestor = EmailTransactionIngestor(
        emailMessageDao = emailDao,
        transactionSourceDao = sourceDao,
        dueSourceDao = dueSourceDao,
        transactions = store,
        saver = saver,
        dues = dueWriter,
        settler = settler,
        dedup = DedupEngine(clock),
        clock = clock,
        dueParsers = dueParsers,
        transactionParsers = transactionParsers,
        unrecognised = unrecognised
    )

    /**
     * A second ingestor over the same fakes, judged by different rules.
     *
     * [IngestConfig] is a per-run value rather than an injected singleton (see the note on
     * [EmailTransactionIngestor]), so a run that knows which accounts are the owner's has to be
     * built with them — which is what `IngestingEmailBatchProcessor.ingestConfigFor` does from
     * `RoomOwnAccountSource`.
     */
    private fun ingestorWith(config: IngestConfig) = EmailTransactionIngestor(
        emailMessageDao = emailDao,
        transactionSourceDao = sourceDao,
        dueSourceDao = dueSourceDao,
        transactions = store,
        saver = saver,
        dues = dueWriter,
        settler = settler,
        dedup = DedupEngine(clock),
        clock = clock,
        dueParsers = dueParsers,
        transactionParsers = transactionParsers,
        unrecognised = unrecognised,
        config = config
    )

    private fun parsedDue(
        issuer: String = "Axis Bank",
        amount: BigDecimal? = BigDecimal("1234.00"),
        dueDate: LocalDate = LocalDate.of(2026, 9, 20),
        escalation: DueEscalation? = null,
        autoPay: Boolean = false
    ) = ParsedDue(
        issuer = issuer,
        kind = DueKind.CARD_STATEMENT,
        amount = amount,
        dueDate = dueDate,
        minimumDue = BigDecimal("120.00"),
        accountLast4 = "5678",
        autoPay = autoPay,
        escalation = escalation,
        source = "AXIS"
    )

    /**
     * A "payment received" notice for the same card as [parsedDue] — spec 20 §20.4 step 7.
     *
     * The amount is the amount *paid* and the date is the day it was paid, which is exactly why
     * neither may reach the due row.
     */
    private fun paymentConfirmation() = parsedDue(
        amount = BigDecimal("900.00"),
        dueDate = LocalDate.of(2026, 9, 16)
    ).copy(paymentConfirmed = true)

    // -----------------------------------------------------------------------------------------
    // a — ignore rules
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a marketing mail is dropped with the rule that dropped it, and reaches no parser`() {
        val row = emailDao.seed(emailRow(from = INGEST_MARKETING_SENDER))

        val result = runBlocking { ingestor.ingest(row) }

        assertTrue(
            "expected an IGNORE:<ruleId> reason, got " + result.skipReason,
            result.skipReason.orEmpty().startsWith(EmailTransactionIngestor.IGNORE_PREFIX)
        )
        assertTrue(result.skipReason!!.length > EmailTransactionIngestor.IGNORE_PREFIX.length)
        assertEquals(0, dueParsers.calls)
        assertEquals(0, transactionParsers.parseCalls)
        assertTrue(emailDao.rows.getValue(row.id).processed)
    }

    @Test
    fun `a dropped mail does not zero a due count an earlier run established`() {
        val row = emailDao.seed(emailRow(from = INGEST_MARKETING_SENDER, dueCount = 2))

        runBlocking { ingestor.ingest(row) }

        assertEquals(2, emailDao.rows.getValue(row.id).dueCount)
    }

    // -----------------------------------------------------------------------------------------
    // b — dues, and the ordering that makes same-fetch settlement possible
    // -----------------------------------------------------------------------------------------

    @Test
    fun `dues are parsed and written before the transaction is parsed and saved`() {
        dueParsers.dues = { listOf(parsedDue()) }
        transactionParsers.reader = { parsedTransaction() }

        runBlocking { ingestor.ingest(emailDao.seed(emailRow())) }

        // Spec 02 §2.4: were this the other way round, a bill and its payment in one fetch would
        // leave no open due for step h to settle.
        assertTrue(log.entries.toString(), log.indexOf("dues") < log.indexOf("transaction"))
        assertTrue(log.entries.toString(), log.indexOf("upsertDue") < log.indexOf("save"))
        assertTrue(log.entries.toString(), log.indexOf("save") < log.indexOf("settle"))
    }

    @Test
    fun `each parsed due is upserted, recorded as a source, and counted on the message`() {
        // One mail, two bills — the GoDaddy shape, where a due is listed per expiring domain.
        dueParsers.dues = { listOf(parsedDue(), parsedDue(issuer = "GoDaddy")) }

        val result = runBlocking { ingestor.ingest(emailDao.seed(emailRow())) }

        assertEquals(listOf(7L, 8L), result.dueRowIds)
        assertEquals(2, dueWriter.written.size)
        assertEquals(2, emailDao.rows.getValue(1).dueCount)
        assertEquals(2, dueSourceDao.rows.size)
        val source = dueSourceDao.rows.first { it.dueId == 7L }
        assertEquals(1L, source.emailMessageId)
        assertEquals(SourceChannel.EMAIL, source.channel)
        assertEquals(BigDecimal("1234.00"), source.amountSeen)
        assertEquals(BigDecimal("120.00"), source.minDueSeen)
        assertEquals(LocalDate.of(2026, 9, 20), source.dueDateSeen)
    }

    @Test
    fun `re-processing a message does not stack a second notice on the same due`() {
        dueParsers.dues = { listOf(parsedDue()) }
        val row = emailDao.seed(emailRow())

        runBlocking {
            ingestor.ingest(row)
            ingestor.ingest(emailDao.rows.getValue(row.id))
        }

        assertEquals(1, dueSourceDao.rows.size)
    }

    @Test
    fun `the notice kind is taken from the sender's own wording, not from the clock`() {
        dueParsers.dues = { listOf(parsedDue(escalation = DueEscalation.OVERDUE)) }

        runBlocking { ingestor.ingest(emailDao.seed(emailRow())) }

        assertEquals(NoticeKind.OVERDUE, dueSourceDao.rows.single().noticeKind)
    }

    @Test
    fun `an auto-pay notice with no escalation is recorded as an upcoming auto-pay`() {
        dueParsers.dues = { listOf(parsedDue(autoPay = true)) }

        runBlocking { ingestor.ingest(emailDao.seed(emailRow())) }

        assertEquals(NoticeKind.AUTOPAY_UPCOMING, dueSourceDao.rows.single().noticeKind)
    }

    // -----------------------------------------------------------------------------------------
    // b, step 7 — "payment received" notices
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a payment confirmation records a PAYMENT_CONFIRMED source on the bill it cleared`() {
        // The bill first, as it would have arrived weeks earlier.
        dueParsers.dues = { listOf(parsedDue()) }
        runBlocking { ingestor.ingest(emailDao.seed(emailRow(id = 1))) }

        dueParsers.dues = { listOf(paymentConfirmation()) }
        val result = runBlocking { ingestor.ingest(emailDao.seed(emailRow(id = 2))) }

        assertEquals(listOf(7L), result.dueRowIds)
        val notice = dueSourceDao.rows.single { it.emailMessageId == 2L }
        assertEquals(7L, notice.dueId)
        assertEquals(NoticeKind.PAYMENT_CONFIRMED, notice.noticeKind)
        // What the sender claimed is kept on the source row, which is where spec 26's
        // UNMATCHED_PAYMENT_NOTICE reads it back from.
        assertEquals(BigDecimal("900.00"), notice.amountSeen)
    }

    @Test
    fun `a payment confirmation for a bill nobody has seen creates no due`() {
        // §20.4 step 7: the notice annotates a due, it never invents one. A row created here
        // would carry a payment date as its due date and the amount paid as its amount — and
        // step h would then settle the payment against that invention.
        dueParsers.dues = { listOf(paymentConfirmation()) }

        val result = runBlocking { ingestor.ingest(emailDao.seed(emailRow())) }

        assertEquals(emptyList<Long>(), result.dueRowIds)
        assertEquals(0, dueSourceDao.rows.size)
        assertEquals(0, dueWriter.written.size)
        assertFalse(log.entries.toString(), log.entries.contains("upsertDue"))
    }

    @Test
    fun `a payment confirmation does not settle the due by itself`() {
        dueParsers.dues = { listOf(parsedDue()) }
        runBlocking { ingestor.ingest(emailDao.seed(emailRow(id = 1))) }

        // The mail confirms a payment and carries no transaction Fin can read — the case the
        // spec is about, where the money left an account the app cannot see.
        dueParsers.dues = { listOf(paymentConfirmation()) }
        settler.settles = { 7L }
        val result = runBlocking { ingestor.ingest(emailDao.seed(emailRow(id = 2))) }

        assertNull(result.settledDueId)
        assertTrue(settler.seen.toString(), settler.seen.isEmpty())
    }

    @Test
    fun `the payment a confirmation describes still settles its bill through the transaction`() {
        dueParsers.dues = { listOf(parsedDue()) }
        runBlocking { ingestor.ingest(emailDao.seed(emailRow(id = 1))) }

        // CRED's C2 and ICICI's I2 are read by both a due parser and a transaction parser. The
        // notice annotates; the transaction settles — and settlement is unchanged by any of it.
        dueParsers.dues = { listOf(paymentConfirmation()) }
        transactionParsers.reader = { parsedTransaction() }
        settler.settles = { 7L }

        val result = runBlocking { ingestor.ingest(emailDao.seed(emailRow(id = 2))) }

        assertEquals(7L, result.settledDueId)
        assertEquals(1, settler.seen.size)
    }

    // -----------------------------------------------------------------------------------------
    // d — not a transaction
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a non-transaction mail is skipped but its dues are kept`() {
        dueParsers.dues = { listOf(parsedDue()) }
        transactionParsers.nonTransaction = true

        val result = runBlocking { ingestor.ingest(emailDao.seed(emailRow())) }

        assertEquals(EmailTransactionIngestor.NON_TXN, result.skipReason)
        assertEquals(listOf(7L), result.dueRowIds)
        assertEquals(0, transactionParsers.parseCalls)
    }

    @Test
    fun `a body the ignore rules call non-transactional still reaches the due parsers`() {
        dueParsers.dues = { listOf(parsedDue()) }

        val result = runBlocking {
            ingestor.ingest(emailDao.seed(emailRow(body = "You added a payee to your account.")))
        }

        assertEquals(EmailTransactionIngestor.NON_TXN, result.skipReason)
        assertEquals(1, dueParsers.calls)
        assertEquals(listOf(7L), result.dueRowIds)
    }

    // -----------------------------------------------------------------------------------------
    // e — nothing parsed it
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a known sender with no parser is NO_PARSER and is offered to the unrecognised sink`() {
        val result = runBlocking { ingestor.ingest(emailDao.seed(emailRow(from = INGEST_BANK_SENDER))) }

        assertEquals(EmailTransactionIngestor.NO_PARSER, result.skipReason)
        assertEquals(listOf(1L), unrecognised.recorded)
    }

    @Test
    fun `a sender the registry has never seen is UNKNOWN_SENDER and is not offered`() {
        val result = runBlocking { ingestor.ingest(emailDao.seed(emailRow(from = INGEST_UNKNOWN_SENDER))) }

        assertEquals(EmailTransactionIngestor.UNKNOWN_SENDER, result.skipReason)
        assertTrue(unrecognised.recorded.isEmpty())
    }

    // -----------------------------------------------------------------------------------------
    // f — dedup, linking and enrichment
    // -----------------------------------------------------------------------------------------

    @Test
    fun `an email twin of a stored sms links instead of creating a second transaction`() {
        store.rows[11] = storedTransaction(id = 11, reference = null, dateTime = at(18, 2))
        transactionParsers.reader = { parsedTransaction(occurredAt = at(18, 30)) }

        val result = runBlocking { ingestor.ingest(emailDao.seed(emailRow())) }

        assertEquals("DUPLICATE:11", result.skipReason)
        assertTrue(result.linkedTwin)
        assertFalse(result.createdTransaction)
        assertEquals(11L, result.transactionId)
        assertTrue("the saver must not run for a twin", saver.saved.isEmpty())

        val source = sourceDao.rows.single()
        assertEquals(11L, source.transactionId)
        assertEquals(SourceChannel.EMAIL, source.channel)
        assertEquals("1", source.sourceRef)
        assertEquals(MatchRule.AMOUNT_WINDOW, source.matchRule)
        assertFalse(source.isPrimary)
        assertEquals(11L, emailDao.rows.getValue(1).transactionId)
    }

    @Test
    fun `enrichment fills only the null fields, and never the category`() {
        store.rows[11] = storedTransaction(
            id = 11,
            reference = null,
            merchantName = "Unknown Merchant",
            category = "Shopping",
            accountNumber = "1234",
            balanceAfter = null,
            dateTime = at(18, 2)
        )
        transactionParsers.reader = {
            parsedTransaction(
                merchant = "Acme Store",
                reference = INGEST_REFERENCE,
                balance = BigDecimal("5000.00"),
                occurredAt = at(18, 30)
            )
        }

        runBlocking { ingestor.ingest(emailDao.seed(emailRow())) }

        val merged = store.rows.getValue(11)
        assertEquals(INGEST_REFERENCE, merged.reference)
        assertEquals("Acme Store", merged.merchantName)
        assertEquals(BigDecimal("5000.00"), merged.balanceAfter)
        // Already populated, so the merge leaves it alone (spec 14 §14.4).
        assertEquals("1234", merged.accountNumber)
        // Never changed by a merge, whatever the incoming record says.
        assertEquals("Shopping", merged.category)
        assertTrue(merged.updatedAt.isAfter(at(18, 2)))
    }

    @Test
    fun `a merge that has nothing to add does not touch the row`() {
        store.rows[11] = storedTransaction(
            id = 11,
            reference = INGEST_REFERENCE,
            merchantName = "Acme Store",
            accountNumber = "1234",
            balanceAfter = BigDecimal("5000.00"),
            dateTime = at(18, 2)
        )
        transactionParsers.reader = { parsedTransaction(occurredAt = at(18, 30)) }

        runBlocking { ingestor.ingest(emailDao.seed(emailRow())) }

        assertTrue("no field was null, so nothing should have been written", store.updates.isEmpty())
    }

    @Test
    fun `two charges of the same amount on one day with different references stay apart`() {
        store.rows[11] = storedTransaction(id = 11, reference = "999888777666", dateTime = at(18, 2))
        transactionParsers.reader = { parsedTransaction(reference = INGEST_REFERENCE, occurredAt = at(18, 30)) }

        val result = runBlocking { ingestor.ingest(emailDao.seed(emailRow())) }

        assertTrue("a second genuine charge must not merge", result.createdTransaction)
        assertEquals(42L, result.transactionId)
    }

    @Test
    fun `the same mail delivered twice attaches one source and creates one transaction`() {
        transactionParsers.reader = { parsedTransaction() }
        val row = emailDao.seed(emailRow())

        val first = runBlocking { ingestor.ingest(row) }
        val second = runBlocking { ingestor.ingest(emailDao.rows.getValue(row.id)) }

        assertTrue(first.createdTransaction)
        assertEquals("DUPLICATE:42", second.skipReason)
        assertEquals(1, sourceDao.rows.size)
        assertEquals(1, saver.saved.size)
    }

    @Test
    fun `the bank's own alert takes the primary slot from a gateway receipt`() {
        store.rows[11] = storedTransaction(id = 11, reference = null, dateTime = at(18, 2))
        sourceDao.seed(
            TransactionSourceEntity(
                transactionId = 11,
                channel = SourceChannel.EMAIL,
                sourceRef = "99",
                sender = INGEST_GATEWAY_SENDER,
                receivedAt = at(18, 2),
                bodyHash = "hash-99",
                isPrimary = true,
                parserId = "C2"
            )
        )
        transactionParsers.reader = { parsedTransaction(occurredAt = at(18, 30)) }

        runBlocking { ingestor.ingest(emailDao.seed(emailRow())) }

        val receipt = sourceDao.rows.single { it.sourceRef == "99" }
        val bank = sourceDao.rows.single { it.sourceRef == "1" }
        assertFalse("the receipt steps down", receipt.isPrimary)
        assertTrue("the bank alert takes over", bank.isPrimary)
    }

    // -----------------------------------------------------------------------------------------
    // §14.5 — an own-account transfer is one TRANSFER, not an expense plus an income
    // -----------------------------------------------------------------------------------------

    @Test
    fun `the credit leg of an own transfer re-types the stored debit into one TRANSFER`() {
        store.rows[11] = storedTransaction(
            id = 11,
            type = TransactionType.EXPENSE,
            bankName = "HDFC Bank",
            accountNumber = "1234",
            reference = INGEST_REFERENCE,
            dateTime = at(18, 2)
        )
        transactionParsers.reader = {
            parsedTransaction(
                type = com.pennywiseai.parser.core.TransactionType.INCOME,
                bankName = "Axis Bank",
                sender = INGEST_OTHER_BANK_SENDER,
                accountLast4 = "5678",
                reference = INGEST_REFERENCE,
                toAccount = "5678",
                occurredAt = at(18, 30)
            )
        }

        val result = runBlocking {
            ingestor.ingest(emailDao.seed(emailRow(from = INGEST_OTHER_BANK_SENDER)))
        }

        assertTrue("one movement is one row", result.linkedTwin)
        assertFalse(result.createdTransaction)
        assertEquals(MatchRule.TRANSFER_LINK, sourceDao.rows.single().matchRule)
        // The hole this closes: without the re-typing the survivor stays EXPENSE, and every total
        // goes on counting the owner's own money as spending (spec 14 §14.5).
        assertEquals(TransactionType.TRANSFER, store.rows.getValue(11).transactionType)
    }

    @Test
    fun `a credit banked as INCOME stops counting as income once its debit arrives`() {
        store.rows[11] = storedTransaction(
            id = 11,
            type = TransactionType.INCOME,
            category = "Income",
            merchantName = "SOMEONE",
            bankName = "Axis Bank",
            accountNumber = "5678",
            reference = INGEST_REFERENCE,
            toAccount = "5678",
            dateTime = at(18, 2)
        )
        transactionParsers.reader = {
            parsedTransaction(
                type = com.pennywiseai.parser.core.TransactionType.TRANSFER,
                bankName = "HDFC Bank",
                accountLast4 = "1234",
                reference = INGEST_REFERENCE,
                fromAccount = "1234",
                toAccount = "Axis Bank",
                occurredAt = at(18, 30)
            )
        }

        val result = runBlocking { ingestor.ingest(emailDao.seed(emailRow())) }

        assertTrue(result.linkedTwin)
        assertEquals(MatchRule.TRANSFER_LINK, sourceDao.rows.single().matchRule)

        val merged = store.rows.getValue(11)
        assertEquals(TransactionType.TRANSFER, merged.transactionType)
        // §14.3 L5: the surviving row names both ends, and only from what a parser read.
        assertEquals("1234", merged.fromAccount)
        assertEquals("5678", merged.toAccount)
        // §14.4 is still absolute about the category: a merge never re-categorises, so the stale
        // label outlives the re-typing and screen 45 is where the user fixes it.
        assertEquals("Income", merged.category)
    }

    @Test
    fun `with no shared reference it is the own-account registry that pairs the two legs`() {
        seedTransferLegs()

        val result = runBlocking {
            ingestorWith(withOwnAccounts()).ingest(emailDao.seed(emailRow()))
        }

        // Both legs are typed TRANSFER, so only the accounts they name can tell them apart.
        assertTrue(result.linkedTwin)
        assertEquals(MatchRule.TRANSFER_LINK, sourceDao.rows.single().matchRule)
        assertEquals(TransactionType.TRANSFER, store.rows.getValue(11).transactionType)
    }

    @Test
    fun `the same two legs stay apart when neither account is known to be the owner's`() {
        seedTransferLegs()

        // The same pair on the default config: `settings.accounts.own` is empty, so nothing says
        // these two banks belong to one person and L5's reference-less branch must not fire.
        val result = runBlocking { ingestor.ingest(emailDao.seed(emailRow())) }

        assertTrue(result.createdTransaction)
        assertEquals(TransactionType.TRANSFER, store.rows.getValue(11).transactionType)
        assertEquals(42L, result.transactionId)
    }

    @Test
    fun `an ordinary twin merge leaves the type where it was`() {
        store.rows[11] = storedTransaction(
            id = 11,
            type = TransactionType.CREDIT,
            reference = null,
            dateTime = at(18, 2)
        )
        transactionParsers.reader = { parsedTransaction(occurredAt = at(18, 30)) }

        runBlocking { ingestor.ingest(emailDao.seed(emailRow())) }

        // Only L5 says "these are the two ends of one movement". Every other layer says "this is
        // the same event, seen twice", and re-typing on one of those would rewrite real history.
        assertEquals(MatchRule.AMOUNT_WINDOW, sourceDao.rows.single().matchRule)
        assertEquals(TransactionType.CREDIT, store.rows.getValue(11).transactionType)
    }

    /** The owner's two banks, as `RoomOwnAccountSource` would report them. */
    private fun withOwnAccounts() = IngestConfig(
        dedup = DedupConfig(
            ownAccounts = listOf(
                OwnAccount(bank = "HDFC Bank", last4 = "1234"),
                OwnAccount(bank = "Axis Bank", last4 = "5678")
            )
        )
    )

    /**
     * A transfer whose two legs carry no reference at all: the Axis credit already stored, and the
     * HDFC debit about to arrive. Both are typed TRANSFER, five minutes apart, and each names the
     * account the money left and the one it reached — which is all there is to tell them apart.
     */
    private fun seedTransferLegs() {
        store.rows[11] = storedTransaction(
            id = 11,
            type = TransactionType.TRANSFER,
            bankName = "Axis Bank",
            accountNumber = "5678",
            reference = null,
            fromAccount = "HDFC Bank",
            toAccount = "5678",
            dateTime = at(18, 25)
        )
        transactionParsers.reader = {
            parsedTransaction(
                type = com.pennywiseai.parser.core.TransactionType.TRANSFER,
                bankName = "HDFC Bank",
                accountLast4 = "1234",
                reference = null,
                fromAccount = "1234",
                toAccount = "Axis Bank",
                occurredAt = at(18, 30)
            )
        }
    }

    // -----------------------------------------------------------------------------------------
    // g and h — saving, and what follows
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a new transaction is saved, recorded as its primary source, and offered for settlement`() {
        transactionParsers.reader = { parsedTransaction() }
        settler.settles = { 7L }

        val result = runBlocking { ingestor.ingest(emailDao.seed(emailRow())) }

        assertNull(result.skipReason)
        assertTrue(result.createdTransaction)
        assertEquals(42L, result.transactionId)
        assertEquals(7L, result.settledDueId)
        assertTrue(result.changedMoney)

        val source = sourceDao.rows.single()
        assertTrue(source.isPrimary)
        assertNull("the first source has no rule to record", source.matchRule)
        assertEquals("H1", source.parserId)
        assertEquals(42L, emailDao.rows.getValue(1).transactionId)
    }

    @Test
    fun `settlement is attempted for a twin as well as for a new transaction`() {
        store.rows[11] = storedTransaction(id = 11, reference = null, dateTime = at(18, 2))
        transactionParsers.reader = { parsedTransaction(occurredAt = at(18, 30)) }
        settler.settles = { 7L }

        val result = runBlocking { ingestor.ingest(emailDao.seed(emailRow())) }

        assertTrue(result.linkedTwin)
        assertEquals(7L, result.settledDueId)
        assertEquals(listOf(11L), settler.seen.map { it.id })
    }

    @Test
    fun `a save the rule engine refuses is recorded, not silently dropped`() {
        transactionParsers.reader = { parsedTransaction() }
        saver.nextId = null
        saver.refusal = "Blocked by rule: Rent"

        val result = runBlocking { ingestor.ingest(emailDao.seed(emailRow())) }

        assertEquals("NOT_SAVED:BLOCKED_BY_RULE_RENT", result.skipReason)
        assertFalse(result.createdTransaction)
        assertTrue(sourceDao.rows.isEmpty())
    }

    // -----------------------------------------------------------------------------------------
    // Containment
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a transaction parser that throws costs one message and not the batch`() {
        transactionParsers.reader = { message ->
            when (message.subject) {
                "boom" -> error("template changed")
                "first" -> parsedTransaction(amount = BigDecimal("111.00"), reference = null)
                else -> parsedTransaction(amount = BigDecimal("333.00"), reference = null)
            }
        }
        val rows = listOf(
            emailDao.seed(emailRow(id = 1, subject = "first")),
            emailDao.seed(emailRow(id = 2, subject = "boom")),
            emailDao.seed(emailRow(id = 3, subject = "third"))
        )

        val results = runBlocking { ingestor.ingestAll(rows) }

        assertTrue(results[0].createdTransaction)
        assertEquals("ERROR:IllegalStateException", results[1].skipReason)
        assertEquals(1, results[1].errors)
        assertTrue("the batch carried on", results[2].createdTransaction)
        assertTrue(emailDao.rows.getValue(2).processed)
    }

    @Test
    fun `a due parser that throws is counted, and the mail still becomes its transaction`() {
        dueParsers.failure = IllegalArgumentException("unreadable due date")
        transactionParsers.reader = { parsedTransaction() }

        val result = runBlocking { ingestor.ingest(emailDao.seed(emailRow())) }

        assertEquals(1, result.errors)
        assertNull("the transaction is the outcome; the failure is only counted", result.skipReason)
        assertTrue(result.createdTransaction)
    }

    @Test
    fun `a due parser that throws becomes the skip reason when nothing else was produced`() {
        dueParsers.failure = IllegalArgumentException("unreadable due date")

        val result = runBlocking { ingestor.ingest(emailDao.seed(emailRow())) }

        assertEquals("ERROR:IllegalArgumentException", result.skipReason)
        assertEquals(1, result.errors)
    }
}
