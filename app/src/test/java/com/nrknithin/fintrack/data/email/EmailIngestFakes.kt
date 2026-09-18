package com.nrknithin.fintrack.data.email

import com.nrknithin.fintrack.parser.core.ParsedTransaction
import com.nrknithin.fintrack.parser.core.email.EmailMessage
import com.nrknithin.fintrack.parser.core.email.due.ParsedDue
import com.nrknithin.fintrack.data.database.dao.DueDao
import com.nrknithin.fintrack.data.database.dao.DueSourceDao
import com.nrknithin.fintrack.data.database.dao.EmailMessageDao
import com.nrknithin.fintrack.data.database.dao.TransactionSourceDao
import com.nrknithin.fintrack.data.database.entity.DueEntity
import com.nrknithin.fintrack.data.database.entity.DueKind
import com.nrknithin.fintrack.data.database.entity.DueSourceEntity
import com.nrknithin.fintrack.data.database.entity.DueStatus
import com.nrknithin.fintrack.data.database.entity.EmailMessageEntity
import com.nrknithin.fintrack.data.database.entity.SourceChannel
import com.nrknithin.fintrack.data.database.entity.TransactionEntity
import com.nrknithin.fintrack.data.database.entity.TransactionSourceEntity
import com.nrknithin.fintrack.data.database.entity.TransactionType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * In-memory stand-ins for everything [EmailTransactionIngestor] talks to.
 *
 * The pipeline was built to be driven without Room, Android or a network (spec 02 §2.3 rule 4),
 * and this file is what cashes that in: every DAO here is a `LinkedHashMap`, every seam is a
 * lambda a test can point wherever it likes, and [CallLog] records the order steps ran in —
 * which is the part of spec 11 §11.4 that is load-bearing.
 *
 * All data is invented. Per spec 06 §6.2 the corpus's shapes are kept and its values are not:
 * last-4s are `1234`/`5678`, references are `123456789012`, merchants are `Acme Store`, senders
 * are either `example.com` placeholders or addresses `EmailSenderRegistry` already knows.
 */

/** A bank sender the registry maps to an SMS alias — so `isBankSender` is true. */
internal const val INGEST_BANK_SENDER = "alerts@hdfcbank.bank.in"

/** A sender the registry knows with no SMS alias behind it: an aggregator, not a bank. */
internal const val INGEST_GATEWAY_SENDER = "protect@cred.club"

/** A second bank, so a transfer can have two ends that are not the same instrument. */
internal const val INGEST_OTHER_BANK_SENDER = "alerts@axis.bank.in"

/** A sender the registry has never heard of — drives `UNKNOWN_SENDER`. */
internal const val INGEST_UNKNOWN_SENDER = "billing@someshop.example.com"

/** Seeded in `IgnoreRules.MARKETING_SENDERS`; every message from it is dropped. */
internal const val INGEST_MARKETING_SENDER = "information@mailers.hdfcbank.bank.in"

/** A 12-digit UPI reference, as spec 06 §6.2 prescribes. */
internal const val INGEST_REFERENCE = "123456789012"

/**
 * "Now" for every test, fixed so nothing depends on the day they run (spec 02 §2.8).
 *
 * Deliberately *after* the fixture timestamps below: a sync processes mail that has already
 * arrived, so an `updated_at` stamped from this clock must land after the message it came from.
 */
internal val INGEST_NOW: Instant = Instant.parse("2026-09-17T19:00:00Z")

internal fun at(hour: Int, minute: Int = 0, day: Int = 17): LocalDateTime =
    LocalDateTime.of(2026, 9, day, hour, minute)

/** One logged email, as `EmailSyncWorker` would have written it before parsing (R-11-05). */
internal fun emailRow(
    id: Long = 1,
    from: String = INGEST_BANK_SENDER,
    subject: String = "Transaction alert",
    body: String = "Rs 1,234.00 debited from account ending 1234. Ref $INGEST_REFERENCE.",
    bodyHash: String = "hash-$id",
    messageId: String? = "<msg-$id@example.com>",
    receivedAt: LocalDateTime = at(18, 30),
    processed: Boolean = false,
    dueCount: Int = 0,
    transactionId: Long? = null
): EmailMessageEntity = EmailMessageEntity(
    id = id,
    mailbox = "[Gmail]/All Mail",
    uid = id,
    messageId = messageId,
    fromAddress = from,
    subject = subject,
    body = body,
    bodyHash = bodyHash,
    receivedAt = receivedAt,
    processed = processed,
    transactionId = transactionId,
    dueCount = dueCount
)

/** A parsed email transaction, with the fields spec 14 §14.2 compares. */
internal fun parsedTransaction(
    amount: BigDecimal = BigDecimal("1234.00"),
    type: com.nrknithin.fintrack.parser.core.TransactionType = com.nrknithin.fintrack.parser.core.TransactionType.EXPENSE,
    merchant: String? = "Acme Store",
    reference: String? = INGEST_REFERENCE,
    accountLast4: String? = "1234",
    balance: BigDecimal? = null,
    bankName: String = "HDFC Bank",
    sender: String = INGEST_BANK_SENDER,
    occurredAt: LocalDateTime = at(18, 30),
    transactionHash: String? = null,
    currency: String = "INR",
    // The account the money left, as spec 12 §12.1 H1 writes it on the debit leg.
    fromAccount: String? = null,
    // The account the money arrived in, as H2 writes it on the credit leg.
    toAccount: String? = null
): ParsedTransaction = ParsedTransaction(
    amount = amount,
    type = type,
    merchant = merchant,
    reference = reference,
    accountLast4 = accountLast4,
    balance = balance,
    smsBody = "body",
    sender = sender,
    timestamp = occurredAt.toInstant(ZoneOffset.UTC).toEpochMilli(),
    bankName = bankName,
    transactionHash = transactionHash,
    currency = currency,
    fromAccount = fromAccount,
    toAccount = toAccount
)

/** An already-stored transaction, as the SMS channel would have saved it. */
internal fun storedTransaction(
    id: Long = 11,
    amount: BigDecimal = BigDecimal("1234.00"),
    merchantName: String = "Acme Store",
    category: String = "Shopping",
    type: TransactionType = TransactionType.EXPENSE,
    dateTime: LocalDateTime = at(18, 2),
    bankName: String? = "HDFC Bank",
    accountNumber: String? = "1234",
    reference: String? = null,
    balanceAfter: BigDecimal? = null,
    transactionHash: String = "txn-hash-$id",
    currency: String = "INR",
    isDeleted: Boolean = false,
    description: String? = null,
    fromAccount: String? = null,
    toAccount: String? = null
): TransactionEntity = TransactionEntity(
    id = id,
    amount = amount,
    merchantName = merchantName,
    category = category,
    transactionType = type,
    dateTime = dateTime,
    description = description,
    smsBody = "body",
    bankName = bankName,
    smsSender = "VM-HDFCBK",
    accountNumber = accountNumber,
    balanceAfter = balanceAfter,
    transactionHash = transactionHash,
    isDeleted = isDeleted,
    currency = currency,
    fromAccount = fromAccount,
    toAccount = toAccount,
    reference = reference,
    createdAt = at(18, 2),
    updatedAt = at(18, 2)
)

/** An open due, as a bill email would have created it. */
internal fun openDue(
    id: Long = 7,
    issuer: String = "Axis Bank",
    kind: DueKind = DueKind.CARD_STATEMENT,
    amount: BigDecimal = BigDecimal("1234.00"),
    minimumDue: BigDecimal? = null,
    dueDate: LocalDate = LocalDate.of(2026, 9, 20),
    accountLast4: String? = null,
    currency: String = "INR",
    autoPay: Boolean = false,
    status: DueStatus = DueStatus.OPEN,
    settledTransactionId: Long? = null
): DueEntity = DueEntity(
    id = id,
    dueId = "due-$id",
    issuer = issuer,
    kind = kind,
    amount = amount,
    currency = currency,
    minimumDue = minimumDue,
    dueDate = dueDate,
    accountLast4 = accountLast4,
    autoPay = autoPay,
    status = status,
    settledTransactionId = settledTransactionId
)

// ---------------------------------------------------------------------------------------------
// Call order
// ---------------------------------------------------------------------------------------------

/**
 * The order the pipeline's steps ran in.
 *
 * Spec 02 §2.4 makes the ordering a requirement rather than an implementation detail — dues are
 * parsed before transactions so a bill and its payment arriving in one fetch still settle — and a
 * requirement that is only true by inspection is one a refactor can quietly break.
 */
internal class CallLog {
    val entries = mutableListOf<String>()

    fun record(step: String) {
        entries += step
    }

    fun indexOf(step: String): Int = entries.indexOf(step)
}

// ---------------------------------------------------------------------------------------------
// Parser seams
// ---------------------------------------------------------------------------------------------

internal class FakeDueParsing(
    private val log: CallLog? = null,
    var dues: (EmailMessage) -> List<ParsedDue> = { emptyList() },
    /** Set to make one parser blow up, as `DueParserFactory` would report it. */
    var failure: Throwable? = null
) : DueParsing {
    var calls = 0
        private set

    override fun parseAll(
        message: EmailMessage,
        onError: (parserId: String, error: Throwable) -> Unit
    ): List<ParsedDue> {
        calls++
        log?.record("dues")
        failure?.let { onError("FAKE_DUE", it) }
        return dues(message)
    }
}

internal class FakeTransactionParsing(
    private val log: CallLog? = null,
    var nonTransaction: Boolean = false,
    var parserId: String? = "H1",
    /** Named `reader` rather than `parse` so it cannot be confused with the override below. */
    var reader: (EmailMessage) -> ParsedTransaction? = { null }
) : TransactionParsing {
    var parseCalls = 0
        private set

    override fun isNonTransactionEmail(message: EmailMessage): Boolean = nonTransaction

    override fun parse(message: EmailMessage): ParsedTransaction? {
        parseCalls++
        log?.record("transaction")
        return reader(message)
    }

    override fun parserIdFor(message: EmailMessage): String? = parserId
}

// ---------------------------------------------------------------------------------------------
// Store seams
// ---------------------------------------------------------------------------------------------

internal class FakeTransactionStore(
    initial: List<TransactionEntity> = emptyList()
) : TransactionStore {
    val rows = LinkedHashMap<Long, TransactionEntity>().apply {
        for (row in initial) put(row.id, row)
    }
    val updates = mutableListOf<TransactionEntity>()

    override suspend fun between(from: LocalDateTime, to: LocalDateTime): List<TransactionEntity> =
        rows.values.filter { !it.isDeleted && !it.dateTime.isBefore(from) && !it.dateTime.isAfter(to) }

    override suspend fun byHash(transactionHash: String): TransactionEntity? =
        rows.values.firstOrNull { it.transactionHash == transactionHash }

    override suspend fun byId(id: Long): TransactionEntity? = rows[id]

    override suspend fun update(transaction: TransactionEntity) {
        rows[transaction.id] = transaction
        updates += transaction
    }
}

/**
 * Stands in for `SmsTransactionProcessor.saveParsedTransaction`.
 *
 * It writes the row into [store] as the real one writes it into Room, because step h reads the
 * saved transaction back before trying settlement — a saver that saved nowhere would make that
 * step untestable, and would hide a regression where the read comes back null.
 */
internal class FakeTransactionSaver(
    private val log: CallLog? = null,
    private val store: FakeTransactionStore? = null,
    var nextId: Long? = 42,
    var refusal: String? = null
) : ParsedTransactionSaver {
    val saved = mutableListOf<ParsedTransaction>()

    override suspend fun save(parsed: ParsedTransaction, body: String): SavedTransaction {
        log?.record("save")
        saved += parsed
        val id = nextId ?: return SavedTransaction(refusal = refusal)
        store?.rows?.put(
            id,
            storedTransaction(
                id = id,
                amount = parsed.amount,
                merchantName = parsed.merchant ?: "Unknown Merchant",
                dateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(parsed.timestamp), ZoneOffset.UTC),
                bankName = parsed.bankName,
                accountNumber = parsed.accountLast4,
                reference = parsed.reference,
                balanceAfter = parsed.balance,
                transactionHash = parsed.transactionHash ?: "txn-hash-$id",
                currency = parsed.currency
            )
        )
        return SavedTransaction(transactionId = id, refusal = refusal)
    }
}

/**
 * A [DueWriter] that merges on [ParsedDue.mergeKey], which is the part of spec 20 §20.4 the
 * ingestor's own behaviour depends on: two readings of one bill land on one `dues` row, and two
 * genuinely different bills in one mail — GoDaddy lists a due per expiring domain — land on two.
 */
internal class FakeDueWriter(
    private val log: CallLog? = null,
    var firstId: Long = 7
) : DueWriter {
    val written = mutableListOf<ParsedDue>()
    private val rowIds = LinkedHashMap<String, Long>()

    override suspend fun upsertFromParsed(parsed: ParsedDue, emailMessageId: Long): DueUpsert {
        log?.record("upsertDue")
        written += parsed
        val existing = rowIds[parsed.mergeKey]
        val id = existing ?: (firstId + rowIds.size).also { rowIds[parsed.mergeKey] = it }
        return DueUpsert(dueRowId = id, created = existing == null)
    }

    /**
     * §20.4 step 7's merge-only path: the same merge key, and no row created when it misses.
     *
     * A payment confirmation reaches a due only when some earlier notice already put that bill
     * on file, which here means a row already exists under its [ParsedDue.mergeKey] — the
     * fake's stand-in for the engine's candidate search.
     */
    override suspend fun mergeIntoExisting(parsed: ParsedDue, emailMessageId: Long): DueUpsert? {
        log?.record("mergeDue")
        val existing = rowIds[parsed.mergeKey] ?: return null
        written += parsed
        return DueUpsert(dueRowId = existing, created = false)
    }
}

internal class FakeDueSettler(
    private val log: CallLog? = null,
    var settles: (TransactionEntity) -> Long? = { null }
) : DueSettler {
    val seen = mutableListOf<TransactionEntity>()

    override suspend fun trySettle(transaction: TransactionEntity): Long? {
        log?.record("settle")
        seen += transaction
        return settles(transaction)
    }
}

internal class FakeUnrecognisedRecorder : UnrecognisedRecorder {
    val recorded = mutableListOf<Long>()

    override suspend fun record(message: EmailMessageEntity) {
        recorded += message.id
    }
}

// ---------------------------------------------------------------------------------------------
// DAOs
// ---------------------------------------------------------------------------------------------

private fun unused(name: String): Nothing =
    throw UnsupportedOperationException("$name is not exercised by the ingest tests")

internal class FakeEmailMessageDao : EmailMessageDao {
    val rows = LinkedHashMap<Long, EmailMessageEntity>()
    val updates = mutableListOf<EmailMessageEntity>()

    fun seed(row: EmailMessageEntity): EmailMessageEntity {
        rows[row.id] = row
        return row
    }

    override suspend fun insert(message: EmailMessageEntity): Long {
        if (rows.values.any { it.mailbox == message.mailbox && it.uid == message.uid }) return -1
        rows[message.id] = message
        return message.id
    }

    override suspend fun update(message: EmailMessageEntity) {
        rows[message.id] = message
        updates += message
    }

    override suspend fun getById(id: Long): EmailMessageEntity? = rows[id]

    override suspend fun getByUid(mailbox: String, uid: Long): EmailMessageEntity? =
        rows.values.firstOrNull { it.mailbox == mailbox && it.uid == uid }

    override suspend fun getByMessageId(messageId: String): EmailMessageEntity? =
        rows.values.firstOrNull { it.messageId == messageId }

    override suspend fun countByUid(mailbox: String, uid: Long): Int =
        rows.values.count { it.mailbox == mailbox && it.uid == uid }

    override suspend fun maxUid(mailbox: String): Long? =
        rows.values.filter { it.mailbox == mailbox }.maxOfOrNull { it.uid }

    override suspend fun count(): Int = rows.size

    override fun countFlow(): Flow<Int> = flowOf(rows.size)

    override fun observeRecent(limit: Int, offset: Int): Flow<List<EmailMessageEntity>> =
        flowOf(rows.values.drop(offset).take(limit))

    override suspend fun getUnmatched(): List<EmailMessageEntity> = rows.values.filter {
        !it.processed ||
            it.skipReason?.startsWith("NO_PARSER") == true ||
            it.skipReason?.startsWith("UNKNOWN_SENDER") == true ||
            it.skipReason?.startsWith("ERROR:") == true
    }

    override fun observeBySkipReason(pattern: String): Flow<List<EmailMessageEntity>> =
        flowOf(rows.values.filter { it.skipReason != null })

    override suspend fun getByTransactionId(transactionId: Long): List<EmailMessageEntity> =
        rows.values.filter { it.transactionId == transactionId }

    override suspend fun trimBodiesBefore(before: String): Int = unused("trimBodiesBefore")

    override suspend fun deleteAll() = rows.clear()
}

internal class FakeTransactionSourceDao : TransactionSourceDao {
    val rows = mutableListOf<TransactionSourceEntity>()
    private var nextId = 1L

    fun seed(source: TransactionSourceEntity) {
        rows += source.copy(id = nextId++)
    }

    override suspend fun insert(source: TransactionSourceEntity): Long {
        // Mirrors the entity's unique (channel, source_ref) index with OnConflict.IGNORE.
        if (rows.any { it.channel == source.channel && it.sourceRef == source.sourceRef }) return -1
        val id = nextId++
        rows += source.copy(id = id)
        return id
    }

    override suspend fun update(source: TransactionSourceEntity) {
        val index = rows.indexOfFirst { it.id == source.id }
        if (index >= 0) rows[index] = source
    }

    override suspend fun getForTransaction(transactionId: Long): List<TransactionSourceEntity> =
        rows.filter { it.transactionId == transactionId }.sortedBy { it.receivedAt }

    override fun observeForTransaction(transactionId: Long): Flow<List<TransactionSourceEntity>> =
        flowOf(rows.filter { it.transactionId == transactionId })

    override suspend fun getBySourceRef(channel: SourceChannel, sourceRef: String): TransactionSourceEntity? =
        rows.firstOrNull { it.channel == channel && it.sourceRef == sourceRef }

    override suspend fun countSameTemplate(
        transactionId: Long,
        channel: SourceChannel,
        parserId: String
    ): Int = rows.count {
        it.transactionId == transactionId && it.channel == channel && it.parserId == parserId
    }

    override suspend fun countSameChannel(transactionId: Long, channel: SourceChannel): Int =
        rows.count { it.transactionId == transactionId && it.channel == channel }

    override suspend fun getByBodyHash(bodyHash: String): List<TransactionSourceEntity> =
        rows.filter { it.bodyHash == bodyHash }

    override suspend fun reassign(fromId: Long, toId: Long): Int = unused("reassign")

    override suspend fun deleteForTransaction(transactionId: Long) {
        rows.removeAll { it.transactionId == transactionId }
    }

    override suspend fun deleteAll() = rows.clear()
}

internal class FakeDueSourceDao : DueSourceDao {
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
        rows.filter { it.dueId == dueId }

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

internal class FakeDueDao(initial: List<DueEntity> = emptyList()) : DueDao {
    val rows = LinkedHashMap<Long, DueEntity>().apply {
        for (row in initial) put(row.id, row)
    }
    val updates = mutableListOf<DueEntity>()

    override suspend fun insert(due: DueEntity): Long {
        rows[due.id] = due
        return due.id
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
        rows.values
            .filter { it.status == DueStatus.OPEN && !it.dueDate.isBefore(from) && !it.dueDate.isAfter(to) }
            .sortedBy { it.dueDate }

    /** The whole candidate set (spec 20 §20.4 step 2), ordered as the SQL orders it. */
    override suspend fun findMergeCandidates(
        kind: DueKind,
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
