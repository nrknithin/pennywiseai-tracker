package com.pennywiseai.tracker.data.email

import com.pennywiseai.parser.core.email.due.DueKind
import com.pennywiseai.parser.core.email.due.ParsedDue
import com.pennywiseai.tracker.domain.engine.DedupEngine
import com.pennywiseai.tracker.domain.engine.OwnAccount
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The binding that turns the email channel on - spec 11 §11.3 step 16 into §11.4.
 *
 * What is worth testing here is *not* §11.4 itself (`EmailTransactionIngestorTest` owns that,
 * message by message) but the two things this class alone decides:
 *
 *  - the batch is really processed, where `DeferredEmailBatchProcessor` returned nothing;
 *  - the run's settings become the run's rules - the twin window of spec 14 §14.3 L2, the
 *    same-day fallback of L3, the own accounts of §14.5 and the user's ignored senders.
 *
 * Every value is invented, redacted per spec 06 §6.2.
 */
class IngestingEmailBatchProcessorTest {

    /** Fixed, and UTC, so message timestamps and stored `date_time`s share one wall clock. */
    private val clock: Clock = Clock.fixed(INGEST_NOW, ZoneOffset.UTC)

    private val emailDao = FakeEmailMessageDao()
    private val sourceDao = FakeTransactionSourceDao()
    private val dueSourceDao = FakeDueSourceDao()
    private val store = FakeTransactionStore()
    private val saver = FakeTransactionSaver(store = store)
    private val dueWriter = FakeDueWriter()
    private val settler = FakeDueSettler()
    private val dueParsers = FakeDueParsing()
    private val transactionParsers = FakeTransactionParsing()
    private val ownAccounts = FakeOwnAccountSource()

    private val processor = IngestingEmailBatchProcessor(
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
        ownAccounts = ownAccounts,
        senderOverrides = SenderOverrides.NONE
    )

    /** The settings a connected mailbox has by default (spec 11 §11.2). */
    private val config = EmailAccountConfig(address = "someone@example.com").coerced()

    /** A card statement, the shape spec 12 §12.4's parsers emit. */
    private fun parsedDue() = ParsedDue(
        issuer = "Axis Bank",
        kind = DueKind.CARD_STATEMENT,
        amount = BigDecimal("1234.00"),
        dueDate = LocalDate.of(2026, 9, 20),
        minimumDue = BigDecimal("120.00"),
        accountLast4 = "5678",
        source = "AXIS"
    )

    // -----------------------------------------------------------------------------------------
    // The pipeline runs
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a batch is parsed and saved, where the deferred processor returned nothing`() {
        // Two distinct charges, so the second is not legitimately deduped against the first
        // (spec 14 §14.3 L1) - this test is about the batch running, not about dedup.
        transactionParsers.reader = { message ->
            if (message.subject == "one") {
                parsedTransaction(amount = BigDecimal("111.00"), reference = "111111111111")
            } else {
                parsedTransaction(amount = BigDecimal("222.00"), reference = "222222222222")
            }
        }
        val rows = listOf(
            emailDao.seed(emailRow(id = 1, subject = "one")),
            emailDao.seed(emailRow(id = 2, subject = "two"))
        )

        val results = runBlocking { processor.process(rows, config) }

        assertEquals(2, results.size)
        assertEquals(2, saver.saved.size)
        assertTrue(results.all { it.createdTransaction })
        assertTrue(results.map { it.emailMessageId }.containsAll(listOf(1L, 2L)))

        // The contrast that made this task necessary: the M1c binding logged mail and left it.
        val deferred = runBlocking { DeferredEmailBatchProcessor().process(rows, config) }
        assertTrue(deferred.isEmpty())
    }

    @Test
    fun `a due-only mail still writes its due, so a bill in the same fetch can settle`() {
        dueParsers.dues = { listOf(parsedDue()) }
        transactionParsers.nonTransaction = true
        val row = emailDao.seed(emailRow())

        val results = runBlocking { processor.process(listOf(row), config) }

        assertEquals(1, results.size)
        assertEquals(1, dueWriter.written.size)
        assertEquals(listOf(7L), results.single().dueRowIds)
    }

    @Test
    fun `one message that blows up costs only itself`() {
        transactionParsers.reader = { message ->
            if (message.subject == "boom") throw IllegalStateException("parser") else parsedTransaction()
        }
        val rows = listOf(
            emailDao.seed(emailRow(id = 1, subject = "boom")),
            emailDao.seed(emailRow(id = 2))
        )

        val results = runBlocking { processor.process(rows, config) }

        assertEquals(2, results.size)
        assertEquals(1, results.first { it.emailMessageId == 1L }.errors)
        assertTrue(results.first { it.emailMessageId == 2L }.createdTransaction)
    }

    @Test
    fun `an empty batch reads nothing at all`() {
        val results = runBlocking { processor.process(emptyList(), config) }

        assertTrue(results.isEmpty())
        // An empty batch is routine - a run whose messages were all already logged ends with
        // one - and it must not cost the three own-account queries.
        assertEquals(0, ownAccounts.calls)
    }

    // -----------------------------------------------------------------------------------------
    // The run's settings become the run's rules
    // -----------------------------------------------------------------------------------------

    @Test
    fun `the twin window and the same-day fallback come from the user's settings`() {
        val tuned = config.copy(alertWindowMinutes = 90, sameDayFallback = false).coerced()

        val ingest = runBlocking { processor.ingestConfigFor(tuned) }

        assertEquals(90, ingest.dedup.alertWindowMinutes)
        assertFalse(ingest.dedup.sameDayFallback)
    }

    @Test
    fun `own accounts are seeded from the source, once per batch`() {
        ownAccounts.accounts = listOf(OwnAccount(bank = "HDFC Bank", last4 = "1234"))
        transactionParsers.reader = { parsedTransaction() }
        val rows = listOf(emailDao.seed(emailRow(id = 1)), emailDao.seed(emailRow(id = 2)))

        val ingest = runBlocking { processor.ingestConfigFor(config) }
        runBlocking { processor.process(rows, config) }

        assertEquals(ownAccounts.accounts, ingest.dedup.ownAccounts)
        // Once for ingestConfigFor above, once for the two-message batch - not once per message.
        assertEquals(2, ownAccounts.calls)
    }

    @Test
    fun `everything settings cannot yet say keeps its documented default`() {
        val ingest = runBlocking { processor.ingestConfigFor(config) }

        // `settings.dedup.*` (spec 28 §28.9) has no editor and no store before M2, so these
        // are the spec's figures and not a guess read out of an empty preferences file.
        assertEquals(48, ingest.dedup.gatewayWindowHours)
        assertEquals(10, ingest.dedup.transferWindowMinutes)
        assertEquals(24, ingest.dedup.upiRefWindowHours)
        // `sender_overrides` is an M2 table; the compiled registry answers alone until then.
        assertTrue(ingest.senderOverrides.isEmpty())
    }

    @Test
    fun `a sender the user muted is dropped by the run's ignore rules`() {
        transactionParsers.reader = { parsedTransaction() }
        val muted = config.copy(ignoredSenders = listOf("hdfcbank.bank.in")).coerced()
        val row = emailDao.seed(emailRow(from = INGEST_BANK_SENDER))

        val result = runBlocking { processor.process(listOf(row), muted) }.single()

        assertEquals(
            EmailTransactionIngestor.IGNORE_PREFIX + "USER_SENDER:hdfcbank.bank.in",
            result.skipReason
        )
        assertTrue(saver.saved.isEmpty())
    }

    @Test
    fun `the compiled sender registry decides NO_PARSER from UNKNOWN_SENDER`() {
        // Nothing parses either message; the two skip reasons differ only by whether the
        // registry knows the sender, which is what `senderOverrides` would otherwise change.
        val known = emailDao.seed(emailRow(id = 1, from = INGEST_BANK_SENDER))
        val stranger = emailDao.seed(emailRow(id = 2, from = INGEST_UNKNOWN_SENDER))

        val results = runBlocking { processor.process(listOf(known, stranger), config) }

        assertEquals(
            EmailTransactionIngestor.NO_PARSER,
            results.first { it.emailMessageId == 1L }.skipReason
        )
        assertEquals(
            EmailTransactionIngestor.UNKNOWN_SENDER,
            results.first { it.emailMessageId == 2L }.skipReason
        )
    }
}

/** An [OwnAccountSource] that counts its reads, so "once per batch" is checkable. */
internal class FakeOwnAccountSource(
    var accounts: List<OwnAccount> = emptyList()
) : OwnAccountSource {
    var calls = 0
        private set

    override suspend fun ownAccounts(): List<OwnAccount> {
        calls++
        return accounts
    }
}
