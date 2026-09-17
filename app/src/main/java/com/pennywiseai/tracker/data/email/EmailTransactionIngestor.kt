package com.pennywiseai.tracker.data.email

import com.pennywiseai.parser.core.ParsedTransaction
import com.pennywiseai.parser.core.email.EmailMessage
import com.pennywiseai.parser.core.email.EmailParserFactory
import com.pennywiseai.parser.core.email.EmailSenderRegistry
import com.pennywiseai.parser.core.email.IgnoreOutcome
import com.pennywiseai.parser.core.email.IgnoreRules
import com.pennywiseai.parser.core.email.SenderMapping
import com.pennywiseai.parser.core.email.due.DueEscalation
import com.pennywiseai.parser.core.email.due.DueParserFactory
import com.pennywiseai.parser.core.email.due.ParsedDue
import com.pennywiseai.tracker.data.database.dao.DueDao
import com.pennywiseai.tracker.data.database.dao.DueSourceDao
import com.pennywiseai.tracker.data.database.dao.EmailMessageDao
import com.pennywiseai.tracker.data.database.dao.TransactionDao
import com.pennywiseai.tracker.data.database.dao.TransactionSourceDao
import com.pennywiseai.tracker.data.database.entity.DueEntity
import com.pennywiseai.tracker.data.database.entity.DueSourceEntity
import com.pennywiseai.tracker.data.database.entity.DueStatus
import com.pennywiseai.tracker.data.database.entity.EmailMessageEntity
import com.pennywiseai.tracker.data.database.entity.MatchRule
import com.pennywiseai.tracker.data.database.entity.MergeRule
import com.pennywiseai.tracker.data.database.entity.NoticeKind
import com.pennywiseai.tracker.data.database.entity.SourceChannel
import com.pennywiseai.tracker.data.database.entity.TransactionEntity
import com.pennywiseai.tracker.data.database.entity.TransactionSourceEntity
import com.pennywiseai.tracker.data.database.entity.TransactionType
import com.pennywiseai.tracker.data.manager.SmsTransactionProcessor
import com.pennywiseai.tracker.data.mapper.toEntityType
import com.pennywiseai.tracker.domain.engine.DedupCandidate
import com.pennywiseai.tracker.domain.engine.DedupConfig
import com.pennywiseai.tracker.domain.engine.DedupDecision
import com.pennywiseai.tracker.domain.engine.DedupEngine
import com.pennywiseai.tracker.domain.engine.DedupInput
import com.pennywiseai.tracker.domain.engine.MessageIdentity
import com.pennywiseai.tracker.domain.engine.SourceFingerprint
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs

// =============================================================================================
// What one message produced
// =============================================================================================

/**
 * The outcome of running spec 11 §11.4 over one logged email.
 *
 * Shaped so a run's `sync_runs` row is a fold over these and nothing else: the worker never has
 * to re-read `email_messages` to know what happened, and a test can assert the outcome of a
 * message without a database.
 */
data class IngestResult(
    val emailMessageId: Long,

    /** `email_messages.skip_reason`; null when the message produced or joined a transaction. */
    val skipReason: String? = null,

    /** The transaction this message created (step g) or was linked to (step f). */
    val transactionId: Long? = null,

    /** Step g created a new transaction — `sync_runs.parsed_txn`. */
    val createdTransaction: Boolean = false,

    /** Step f recognised an event already recorded — `sync_runs.linked_twin`. */
    val linkedTwin: Boolean = false,

    /** `dues.id` of every row this message created or merged into — `sync_runs.dues_upserted`. */
    val dueRowIds: List<Long> = emptyList(),

    /** The due this message's transaction settled, if any — `sync_runs.dues_settled`. */
    val settledDueId: Long? = null,

    /** Parser failures contained on this message — `sync_runs.errors`. */
    val errors: Int = 0
) {
    /**
     * True when this message moved something the money plan depends on (spec 11 §11.4 step h).
     *
     * Reported rather than acted on: spec 11 §11.3 step 22 marks the plan dirty **once per run**,
     * so the worker ORs this across the batch. Marking per message would be five hundred writes
     * to say one thing.
     */
    val changedMoney: Boolean
        get() = createdTransaction || linkedTwin || dueRowIds.isNotEmpty() || settledDueId != null
}

// =============================================================================================
// Per-run configuration
// =============================================================================================

/**
 * Everything the pipeline reads from settings, gathered once per run.
 *
 * Passed as a value rather than injected because it is derived from [EmailAccountConfig] and the
 * user's overrides at the moment the run starts — the same reasoning that keeps [DedupConfig] out
 * of [DedupEngine]'s constructor. Every message in one run is judged by one copy of these rules.
 */
data class IngestConfig(
    val dedup: DedupConfig = DedupConfig(),

    /** `settings.email.ignoredSenders`, handed to [IgnoreRules] (spec 12 §12.10). */
    val ignoredSenders: List<String> = emptyList(),

    /** `sender_overrides` (spec 11 §11.6). Empty in M1; the editor is a screen-47 item for M2. */
    val senderOverrides: Map<String, SenderMapping> = emptyMap()
)

/**
 * The `settings.dues.*` figures settlement reads (spec 28 §28.4).
 *
 * Only the ones M1's [PlausibleDueSettler] actually uses are here; `minMatchScore` and
 * `requireIssuerOrAccountMatch` arrive with the full scoring algorithm in M2.
 */
data class SettlementConfig(
    val amountToleranceAbs: BigDecimal = BigDecimal("1.00"),
    val amountTolerancePct: BigDecimal = BigDecimal.ZERO,

    /**
     * How far **ahead of** the payment a due may be dated and still be the thing that was paid —
     * a 1 October card bill paid on 16 September (spec 20 §20.8).
     */
    val earlyPayDays: Long = 45,

    /** How far **behind** the payment a due may be dated: how late the user is allowed to be. */
    val latePayDays: Long = 60,

    /** Days after `due_date` that still count as on time. */
    val graceDays: Long = 0,

    /** Auto-pay dues debit a day or two after the printed date, so they get their own grace. */
    val autoPayGraceDays: Long = 2
)

// =============================================================================================
// Collaborator seams
//
// Every one of these exists so the pipeline can be driven with fakes: no Room, no Android, no
// network. Two are narrow views of things that already exist — [TransactionStore] over the
// fifty-method upstream `TransactionDao`, [ParsedTransactionSaver] over `SmsTransactionProcessor`,
// which needs a `Context`. The rest are components M2 brings and M1 stubs.
// =============================================================================================

/** Step b's due parsers. [FactoryDueParsing] is the production set. */
interface DueParsing {
    /**
     * Every due this message describes.
     *
     * [onError] is called instead of throwing when one parser fails, so a malformed bill costs
     * the app that bill and nothing else (spec 02 §2.7).
     */
    fun parseAll(
        message: EmailMessage,
        onError: (parserId: String, error: Throwable) -> Unit
    ): List<ParsedDue>
}

/** Steps d and e's transaction parsers. [FactoryTransactionParsing] is the production set. */
interface TransactionParsing {
    /** Spec 11 §11.4 step d: a parser recognises the sender but says this is not about money. */
    fun isNonTransactionEmail(message: EmailMessage): Boolean

    /** Spec 11 §11.4 step e. Null means "no transaction here", for any of three reasons. */
    fun parse(message: EmailMessage): ParsedTransaction?

    /** `transaction_sources.parser_id`: which template read this mail. Null when none claims it. */
    fun parserIdFor(message: EmailMessage): String?
}

/**
 * The three questions the pipeline asks the transaction table, and the one update it makes.
 *
 * A seam rather than the DAO itself because `TransactionDao` is an upstream file with fifty
 * methods: a fake of it in every test would bury the four lines that matter, and this pipeline
 * uses four of them. [RoomTransactionStore] is the production implementation.
 */
interface TransactionStore {
    /** Dedup candidates by time (spec 14 §14.3's windows). Excludes soft-deleted rows. */
    suspend fun between(from: LocalDateTime, to: LocalDateTime): List<TransactionEntity>

    /** L0's unbounded window, answered by the unique index rather than by a scan. */
    suspend fun byHash(transactionHash: String): TransactionEntity?

    suspend fun byId(id: Long): TransactionEntity?

    /** The enrichment write of spec 14 §14.4. Never called with a changed category. */
    suspend fun update(transaction: TransactionEntity)
}

/** What [ParsedTransactionSaver] did. A null [transactionId] means nothing was written. */
data class SavedTransaction(
    val transactionId: Long? = null,
    /** Why the save was refused — a blocking rule, a duplicate hash, a row the user deleted. */
    val refusal: String? = null
)

/**
 * Step g: the inherited save path — categories, merchant mappings, rules, subscriptions,
 * balances (spec 11 §11.4 g).
 *
 * A seam because the production implementation is `SmsTransactionProcessor`, which needs a
 * `Context` and a `PennyWiseDatabase`; see [ProcessorTransactionSaver].
 */
interface ParsedTransactionSaver {
    suspend fun save(parsed: ParsedTransaction, body: String): SavedTransaction
}

/** Where one parsed due landed (spec 20 §20.4). */
data class DueUpsert(
    /** `dues.id` — the row, not `dues.due_id`. */
    val dueRowId: Long,
    /** False when this reading merged into a due some other notice had already created. */
    val created: Boolean = true,
    val mergeRule: MergeRule = MergeRule.MERGE_KEY
)

/**
 * Step b's writer: spec 20 §20.4's merge, which `DueRepository` owns from M2.
 *
 * Deliberately narrow. The ingestor keeps the `due_sources` row for itself — which email said
 * what, and which sender it came from, is email-channel bookkeeping — so an implementation of
 * this only has to answer "which `dues` row is this reading part of?".
 */
interface DueWriter {
    suspend fun upsertFromParsed(parsed: ParsedDue, emailMessageId: Long): DueUpsert
}

/**
 * Step h's settlement seam (spec 20 §20.5).
 *
 * **M2 replaces the implementation, not this interface.** M1 needs only "find an open due this
 * transaction plausibly settles, and mark it" — [PlausibleDueSettler]. The scored version of
 * §20.5 (+50 amount / +30 issuer / +30 account / +20 card bill / +20 auto-pay, `minMatchScore`,
 * foreign-currency conversion, partial payments) is `DueMatcher`, and it arrives with the
 * commitments backbone in M2, because most of its signals are commitment columns that do not
 * exist at schema v63.
 *
 * @return the `dues.id` that was settled, or null when nothing plausibly matched.
 */
interface DueSettler {
    suspend fun trySettle(transaction: TransactionEntity): Long?
}

/**
 * Step e's "the sender is known but nothing parsed it" sink (spec 11 §11.4 e, screen 49).
 *
 * [NONE] by default, and that is the M1 behaviour: `unrecognized_sms` has no channel column at
 * schema v63, so filing an email in it would make the Unrecognised screen claim an email is an
 * SMS. Until screen 49 grows that column, the Email Log's `NO_PARSER` filter
 * (`EmailMessageDao.observeBySkipReason`) is where these messages are visible — nothing is lost,
 * because step e has already written the skip reason by the time this is called.
 */
interface UnrecognisedRecorder {
    suspend fun record(message: EmailMessageEntity)

    companion object {
        val NONE: UnrecognisedRecorder = object : UnrecognisedRecorder {
            override suspend fun record(message: EmailMessageEntity) = Unit
        }
    }
}

// =============================================================================================
// The pipeline
// =============================================================================================

/**
 * Spec 11 §11.4 — everything that happens to one already-logged email.
 *
 * The message reaches this class **after** `EmailSyncWorker` has written it to `email_messages`
 * (R-11-05), so nothing here can lose mail: the worst outcome is a row with a skip reason, which
 * "Re-process unmatched" (R-11-10) can replay once the app learns a sender or gains a parser.
 *
 * **The order of the eight steps is the behaviour.** Dues are parsed before transactions — step b
 * before step e — because spec 02 §2.4 requires a bill and its payment arriving in one fetch to
 * still settle: were the payment saved first, there would be no open due for step h to find.
 * Ignore rules run before everything (step a) so an OTP never reaches a parser, and the NON_TXN
 * check runs *after* the due parsers (step d) because a mandate notice is not a transaction and
 * is very much a due.
 *
 * **Containment.** A parser that throws costs this message and no other: the throw is caught, the
 * message is marked `ERROR:<class>`, the error is counted, and the batch carries on.
 * `CancellationException` is deliberately re-thrown — a cancelled sync must stay cancelled.
 *
 * **Enrichment never overwrites.** A merge copies a field onto an existing transaction only when
 * the existing value is null (spec 14 §14.4), and a merge never changes the category at all; the
 * rule is stated once, in [DedupEngine.enrich] and [DedupEngine.fill], and applied here.
 *
 * Nothing in this class opens a database or a network connection. Every collaborator arrives
 * through the constructor, including the clock, so the whole of §11.4 is a plain JVM unit test.
 *
 * Not Hilt-annotated on purpose: [config] is a per-run value built from [EmailAccountConfig], so
 * `EmailSyncWorker` constructs the ingestor for the run rather than injecting a singleton. The
 * collaborators that genuinely are singletons are annotated individually below.
 */
class EmailTransactionIngestor(
    private val emailMessageDao: EmailMessageDao,
    private val transactionSourceDao: TransactionSourceDao,
    private val dueSourceDao: DueSourceDao,
    private val transactions: TransactionStore,
    private val saver: ParsedTransactionSaver,
    private val dues: DueWriter,
    private val settler: DueSettler,
    private val dedup: DedupEngine,
    private val clock: Clock,
    private val dueParsers: DueParsing = FactoryDueParsing(),
    private val transactionParsers: TransactionParsing = FactoryTransactionParsing(),
    private val unrecognised: UnrecognisedRecorder = UnrecognisedRecorder.NONE,
    private val config: IngestConfig = IngestConfig()
) {

    /**
     * Runs §11.4 over one message.
     *
     * Never throws for anything a parser did. The only exceptions that escape are cancellation
     * and a failure of the final bookkeeping write — a database that cannot record an outcome
     * cannot be trusted with the rest of the batch either.
     */
    suspend fun ingest(row: EmailMessageEntity): IngestResult =
        try {
            process(row)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            // Spec 02 §2.7 "Parser throws on one message": contained here, not at the batch.
            finish(row, skipReason = errorReason(error), errors = 1)
        }

    /** Convenience for a committed batch (spec 11 §11.3 step 10); one bad mail stops nothing. */
    suspend fun ingestAll(rows: List<EmailMessageEntity>): List<IngestResult> = rows.map { ingest(it) }

    // -----------------------------------------------------------------------------------------
    // §11.4, step by step
    // -----------------------------------------------------------------------------------------

    private suspend fun process(row: EmailMessageEntity): IngestResult {
        val message = row.asEmailMessage()

        // -- a --------------------------------------------------------------------------- //
        // Marketing, OTPs, known noise. DROP short-circuits everything, including the due
        // parsers, which is why `order-update@amazon.in` is deliberately not on that list.
        val verdict = IgnoreRules.evaluate(message, config.ignoredSenders)
        if (verdict.outcome == IgnoreOutcome.DROP) {
            return finish(row, skipReason = IGNORE_PREFIX + (verdict.ruleId ?: UNSPECIFIED))
        }

        // -- b --------------------------------------------------------------------------- //
        // Dues first, so a bill and the payment that clears it can arrive in one fetch and still
        // settle (spec 02 §2.4). One mail can carry several: GoDaddy lists every expiring domain
        // in a single message.
        var errors = 0
        var firstError: String? = null
        val parsedDues = dueParsers.parseAll(message) { _, error ->
            errors++
            if (firstError == null) firstError = errorReason(error)
        }
        val dueRowIds = mutableListOf<Long>()
        for (due in parsedDues) {
            val upsert = dues.upsertFromParsed(due, row.id)
            dueRowIds += upsert.dueRowId
            recordDueSource(row, due, upsert)
        }

        // -- c --------------------------------------------------------------------------- //
        // M5 RECEIPTS HOOK — intentionally empty.
        //
        // Spec 11 §11.4 c is `ReceiptParserFactory.parse(...)` into `receiptRepo.upsert(...)`,
        // setting `email_messages.receipt_id`. Nothing is implemented because nothing exists to
        // implement against: there are no receipt parsers, no `receipts` table and no
        // `receipt_id` column at schema v63 (spec 07 puts all three in M5). When they land, the
        // call goes exactly here — after the dues, before the NON_TXN check — so an Amazon
        // "Ordered" mail becomes a receipt rather than a NO_PARSER row, and the `RECEIPT_ONLY`
        // skip reason that `EmailMessageEntity` already documents starts being written.

        // -- d --------------------------------------------------------------------------- //
        // A password change, an added payee, a mandate notice: real sender, no money moved.
        if (verdict.outcome == IgnoreOutcome.NON_TXN || transactionParsers.isNonTransactionEmail(message)) {
            return finish(row, skipReason = firstError ?: NON_TXN, dueRowIds = dueRowIds, errors = errors)
        }

        // -- e --------------------------------------------------------------------------- //
        val parsed = transactionParsers.parse(message)
        if (parsed == null) {
            val known = EmailSenderRegistry.isKnownSender(message.from, config.senderOverrides)
            val result = finish(
                row,
                // A parser that crashed is the more actionable fact than "nothing parsed it", and
                // both land in `EmailMessageDao.getUnmatched()`, so neither message is lost.
                skipReason = firstError ?: if (known) NO_PARSER else UNKNOWN_SENDER,
                dueRowIds = dueRowIds,
                errors = errors
            )
            if (known) unrecognised.record(row)
            return result
        }

        // -- f --------------------------------------------------------------------------- //
        val input = dedupInput(row, parsed)
        val candidates = loadCandidates(input)
        when (val decision = dedup.evaluate(input, candidates, config.dedup)) {
            is DedupDecision.Link ->
                return link(row, input, parsed, decision, candidates, dueRowIds, errors)

            is DedupDecision.LinkOnly ->
                // A gateway receipt that names no instrument: logged, never a second charge.
                return finish(
                    row,
                    skipReason = decision.reason,
                    transactionId = decision.transactionId,
                    dueRowIds = dueRowIds,
                    errors = errors
                )

            DedupDecision.NewTransaction -> Unit // falls through to g
        }

        // -- g --------------------------------------------------------------------------- //
        val saved = saver.save(parsed, row.body)
        val transactionId = saved.transactionId
            ?: return finish(
                row,
                skipReason = NOT_SAVED_PREFIX + slug(saved.refusal),
                dueRowIds = dueRowIds,
                errors = errors
            )

        transactionSourceDao.insert(
            sourceRow(row, input, transactionId, isPrimary = true, matchRule = null)
        )

        // -- h --------------------------------------------------------------------------- //
        // Balances are recorded inside the saver (`SmsTransactionProcessor.processBalanceUpdate`
        // — spec 11 §11.4 h's "balances.record"); doing it again here would double-count.
        // Instalment loans (23) and subscription detection (21) are M2/M4 and are deliberately
        // not stubbed: a stub that silently does nothing is harder to notice than an absence.
        // The plan is marked dirty once per run by the worker — see [IngestResult.changedMoney].
        val settledDueId = transactions.byId(transactionId)?.let { settler.trySettle(it) }

        return finish(
            row,
            skipReason = null,
            transactionId = transactionId,
            createdTransaction = true,
            dueRowIds = dueRowIds,
            settledDueId = settledDueId,
            errors = errors
        )
    }

    /**
     * Step f's body: this message describes an event already recorded.
     *
     * Order follows the spec — record the source, enrich, then try settlement. Enrichment is
     * additive only; see [enrich].
     */
    private suspend fun link(
        row: EmailMessageEntity,
        input: DedupInput,
        parsed: ParsedTransaction,
        decision: DedupDecision.Link,
        candidates: List<DedupCandidate>,
        dueRowIds: List<Long>,
        errors: Int
    ): IngestResult {
        val transactionId = decision.transactionId

        // Spec 10 §10.8 item 5: the bank's own alert takes the primary slot from a receipt. One
        // transaction has one primary source, so the incumbent steps down in the same breath.
        if (decision.promoteIncomingToPrimary) {
            for (existingSource in transactionSourceDao.getForTransaction(transactionId)) {
                if (existingSource.isPrimary) {
                    transactionSourceDao.update(existingSource.copy(isPrimary = false))
                }
            }
        }

        // (channel, source_ref) is unique and the DAO inserts with IGNORE, so re-processing the
        // same email attaches nothing twice.
        transactionSourceDao.insert(
            sourceRow(
                row,
                input,
                transactionId,
                isPrimary = decision.promoteIncomingToPrimary,
                matchRule = decision.rule
            )
        )

        val existing = transactions.byId(transactionId)
        val candidate = candidates.firstOrNull { it.transactionId == transactionId }
        val merged =
            if (existing != null && candidate != null) enrich(existing, candidate, input, parsed)
            else existing

        val settledDueId = merged?.let { settler.trySettle(it) }

        return finish(
            row,
            skipReason = DUPLICATE_PREFIX + transactionId,
            transactionId = transactionId,
            linkedTwin = true,
            dueRowIds = dueRowIds,
            settledDueId = settledDueId,
            errors = errors
        )
    }

    /**
     * Spec 14 §14.4, applied.
     *
     * Every field is filled through [DedupEngine.enrich] or [DedupEngine.fill], both of which mean
     * "take the incoming value only when the existing one is null". **Category is absent on
     * purpose** — §14.4 says a merge never changes it — and so is `description`, because
     * `ParsedTransaction` carries none for the email channel to copy.
     *
     * `updated_at` is bumped only when something actually changed, so re-processing a message does
     * not make an untouched row look edited.
     */
    private suspend fun enrich(
        existing: TransactionEntity,
        candidate: DedupCandidate,
        input: DedupInput,
        parsed: ParsedTransaction
    ): TransactionEntity {
        val enrichment = dedup.enrich(input, candidate)

        val enriched = existing.copy(
            reference = dedup.fill(existing.reference, enrichment.reference),
            // Non-null in Room, so the hole is spelled differently: `candidate.merchant` was built
            // with the mapper's placeholder normalised away, which is what lets the engine see it
            // as empty and offer a name. Null here means "keep what is already there".
            merchantName = enrichment.merchant ?: existing.merchantName,
            accountNumber = dedup.fill(existing.accountNumber, enrichment.accountLast4),
            balanceAfter = dedup.fill(existing.balanceAfter, parsed.balance)
        )
        if (enriched == existing) return existing

        val bumped = enriched.copy(updatedAt = now())
        transactions.update(bumped)
        return bumped
    }

    // -----------------------------------------------------------------------------------------
    // Dedup plumbing — every Room query the engine needs, and none inside it (spec 02 §2.3)
    // -----------------------------------------------------------------------------------------

    /** Spec 14 §14.2's normalisation of the incoming record. */
    private fun dedupInput(row: EmailMessageEntity, parsed: ParsedTransaction): DedupInput {
        val mapping = EmailSenderRegistry.resolve(row.fromAddress, config.senderOverrides)
        return DedupInput(
            amount = parsed.amount,
            currency = parsed.currency,
            type = parsed.type.toEntityType(),
            bankName = parsed.bankName,
            accountLast4 = parsed.accountLast4,
            reference = digitsOnly(parsed.reference),
            // Raw rather than pre-cleaned: the engine re-normalises for its own comparisons
            // anyway, and `enrich` needs the name as written — `cleanMerchant` lower-cases, and it
            // strips a bare VPA to nothing, which would defeat §14.4's "a real name replaces a VPA".
            merchant = parsed.merchant?.trim()?.takeIf { it.isNotEmpty() },
            occurredAt = parsed.timestamp.asLocalDateTime(),
            bodyHash = row.bodyHash,
            transactionHash = parsed.transactionHash,
            channel = SourceChannel.EMAIL,
            parserId = transactionParsers.parserIdFor(row.asEmailMessage()),
            // A bank or card issuer, as opposed to CRED/Razorpay/Cashfree/a merchant. The SMS
            // alias is how the registry says "this sender has a bank parser family behind it".
            isBankSender = mapping?.smsAlias != null,
            // Gateway order ids arrive with the M5 receipt parsers; L4's other branch
            // (amount + merchant) works without one.
            orderId = null,
            isFromCard = parsed.isFromCard,
            fromAccount = parsed.fromAccount,
            // Which leg of a transfer this is (spec 14 §14.5) needs `settings.accounts.own`, a
            // screen-42 editor in M2. Null means "read the leg off the type", which is right for
            // everything except the credit side of an own-account transfer.
            direction = null,
            identity = MessageIdentity(
                sender = row.fromAddress,
                messageId = row.messageId,
                receivedAt = row.receivedAt
            )
        )
    }

    /**
     * The transactions that could be the same event.
     *
     * Three queries rather than one, because L0's window is unbounded while every other layer's is
     * not: a time window wide enough for L1–L5 (the widest is L4's 48 h, and L3 needs the whole
     * calendar day), plus two index lookups that answer L0 without scanning the table — the
     * transaction hash, and any source that already carries this body hash.
     *
     * Soft-deleted transactions are excluded: attaching a source to a row the user deleted would
     * resurrect nothing and hide the message.
     */
    private suspend fun loadCandidates(input: DedupInput): List<DedupCandidate> {
        val hours = maxOf(config.dedup.gatewayWindowHours, config.dedup.upiRefWindowHours).toLong()
        val span = Duration.ofHours(hours)
        val day = input.occurredAt.toLocalDate()
        // Written out rather than with minOf/maxOf: LocalDateTime implements
        // Comparable<ChronoLocalDateTime<*>>, not Comparable<LocalDateTime>, so the stdlib
        // helpers cannot infer a type argument here and the file does not compile.
        val windowStart = input.occurredAt.minus(span)
        val dayStart = day.atStartOfDay()
        val from = if (windowStart.isBefore(dayStart)) windowStart else dayStart

        val windowEnd = input.occurredAt.plus(span)
        val dayEnd = day.atTime(LocalTime.MAX)
        val to = if (windowEnd.isAfter(dayEnd)) windowEnd else dayEnd

        val found = LinkedHashMap<Long, TransactionEntity>()
        for (entity in transactions.between(from, to)) found[entity.id] = entity

        input.transactionHash?.takeIf { it.isNotBlank() }?.let { hash ->
            transactions.byHash(hash)?.let { found.putIfAbsent(it.id, it) }
        }
        if (input.bodyHash.isNotBlank()) {
            for (source in transactionSourceDao.getByBodyHash(input.bodyHash)) {
                if (!found.containsKey(source.transactionId)) {
                    transactions.byId(source.transactionId)?.let { found[it.id] = it }
                }
            }
        }

        return found.values.filterNot { it.isDeleted }.map { asCandidate(it, input) }
    }

    private suspend fun asCandidate(entity: TransactionEntity, input: DedupInput): DedupCandidate {
        val sources = transactionSourceDao.getForTransaction(entity.id)
        // Spec 14 §14.3's intra-channel guard, in the resolved form `TransactionSourceDao`
        // documents: same channel *and* same template is a second charge. With no template id to
        // compare, fall back to the spec's stricter wording rather than risk a false merge.
        val sameTemplate = input.parserId
            ?.let { transactionSourceDao.countSameTemplate(entity.id, input.channel, it) }
            ?: transactionSourceDao.countSameChannel(entity.id, input.channel)

        return DedupCandidate(
            transactionId = entity.id,
            amount = entity.amount,
            currency = entity.currency,
            type = entity.transactionType,
            bankName = entity.bankName,
            accountLast4 = entity.accountNumber,
            reference = entity.reference,
            // The mapper writes a placeholder where the parser found no name; the engine has to
            // see that as the hole it is, or §14.4 could never fill it.
            merchant = entity.merchantName.takeUnless { it.isBlank() || it == UNKNOWN_MERCHANT },
            occurredAt = entity.dateTime,
            transactionHash = entity.transactionHash,
            sources = sources.map { source ->
                SourceFingerprint(
                    channel = source.channel,
                    parserId = source.parserId,
                    bodyHash = source.bodyHash,
                    // No Message-ID: `transaction_sources` does not store one, and joining
                    // `email_messages` per source would be a query per candidate per message.
                    // Sender plus the stamped time is enough for L0 — a re-delivery keeps both.
                    identity = MessageIdentity(
                        sender = source.sender,
                        messageId = null,
                        receivedAt = source.receivedAt
                    )
                )
            },
            sameTemplateSourceCount = sameTemplate,
            isGatewayOrigin = isGatewayOrigin(sources)
        )
    }

    /**
     * True when this transaction was first recorded from a non-bank sender.
     *
     * Read off the primary source: an email source whose address the registry knows but gives no
     * SMS alias is CRED, Razorpay, PGSharp and friends. A transaction with no sources at all —
     * every row the SMS pipeline wrote before `transaction_sources` existed — is not gateway
     * origin, which is the safe answer.
     */
    private fun isGatewayOrigin(sources: List<TransactionSourceEntity>): Boolean {
        val primary = sources.firstOrNull { it.isPrimary } ?: return false
        if (primary.channel != SourceChannel.EMAIL) return false
        val mapping = EmailSenderRegistry.resolve(primary.sender, config.senderOverrides) ?: return false
        return mapping.smsAlias == null
    }

    private fun sourceRow(
        row: EmailMessageEntity,
        input: DedupInput,
        transactionId: Long,
        isPrimary: Boolean,
        matchRule: MatchRule?
    ) = TransactionSourceEntity(
        transactionId = transactionId,
        channel = SourceChannel.EMAIL,
        sourceRef = row.id.toString(),
        sender = row.fromAddress,
        receivedAt = row.receivedAt,
        bodyHash = row.bodyHash,
        isPrimary = isPrimary,
        matchRule = matchRule,
        parserId = input.parserId
    )

    // -----------------------------------------------------------------------------------------
    // Due bookkeeping
    // -----------------------------------------------------------------------------------------

    /**
     * One `due_sources` row per (due, email), so the due detail screen can show that four senders
     * agreed — or that one of them disagreed (spec 20 §20.4, screen 34).
     *
     * Guarded by `countForEmail` rather than by a unique index: a due legitimately gains several
     * notices from one sender over time (statement, then reminder, then overdue), so uniqueness is
     * per message. Without the guard, "Re-process unmatched" would stack a duplicate notice on
     * every run.
     */
    private suspend fun recordDueSource(row: EmailMessageEntity, parsed: ParsedDue, upsert: DueUpsert) {
        if (dueSourceDao.countForEmail(upsert.dueRowId, row.id) > 0) return
        dueSourceDao.insert(
            DueSourceEntity(
                dueId = upsert.dueRowId,
                channel = SourceChannel.EMAIL,
                emailMessageId = row.id,
                sender = row.fromAddress,
                noticeKind = noticeKindOf(parsed),
                amountSeen = parsed.amount,
                minDueSeen = parsed.minimumDue,
                dueDateSeen = parsed.dueDate,
                receivedAt = row.receivedAt,
                mergeRule = upsert.mergeRule
            )
        )
    }

    /**
     * How the *sender* framed this notice (spec 12 C3), not how the app's clock reads the date.
     *
     * A reminder that says OVERDUE is worth surfacing even when today is still before the due
     * date — the sender knows something the app does not.
     */
    private fun noticeKindOf(parsed: ParsedDue): NoticeKind = when (parsed.escalation) {
        DueEscalation.OVERDUE -> NoticeKind.OVERDUE
        DueEscalation.DUE_TODAY -> NoticeKind.DUE_TODAY
        DueEscalation.DUE_SOON -> NoticeKind.REMINDER
        null -> if (parsed.autoPay) NoticeKind.AUTOPAY_UPCOMING else NoticeKind.STATEMENT
    }

    // -----------------------------------------------------------------------------------------
    // Bookkeeping
    // -----------------------------------------------------------------------------------------

    /**
     * Writes the message's outcome back to `email_messages` and reports it.
     *
     * `processed` is always true: every path through §11.4 that reaches here has finished with the
     * message, the error path included. Re-processing is driven by the skip reason, which
     * `EmailMessageDao.getUnmatched()` filters on, not by this flag.
     *
     * A null [dueRowIds] leaves `due_count` alone, which matters on the step-a path: a message
     * dropped by an ignore rule returns before the due parsers run, and must not zero a count an
     * earlier run established.
     */
    private suspend fun finish(
        row: EmailMessageEntity,
        skipReason: String?,
        transactionId: Long? = null,
        createdTransaction: Boolean = false,
        linkedTwin: Boolean = false,
        dueRowIds: List<Long>? = null,
        settledDueId: Long? = null,
        errors: Int = 0
    ): IngestResult {
        val resolvedTransactionId = transactionId ?: row.transactionId
        emailMessageDao.update(
            row.copy(
                processed = true,
                skipReason = skipReason,
                transactionId = resolvedTransactionId,
                dueCount = dueRowIds?.size ?: row.dueCount
            )
        )
        return IngestResult(
            emailMessageId = row.id,
            skipReason = skipReason,
            transactionId = resolvedTransactionId,
            createdTransaction = createdTransaction,
            linkedTwin = linkedTwin,
            dueRowIds = dueRowIds.orEmpty(),
            settledDueId = settledDueId,
            errors = errors
        )
    }

    private fun EmailMessageEntity.asEmailMessage(): EmailMessage = EmailMessage(
        from = fromAddress,
        subject = subject.orEmpty(),
        body = body,
        receivedAt = receivedAt.atZone(clock.zone).toInstant().toEpochMilli()
    )

    private fun Long.asLocalDateTime(): LocalDateTime =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(this), clock.zone)

    private fun now(): LocalDateTime = LocalDateTime.now(clock)

    companion object {
        /** Spec 11 §11.4 a. */
        const val IGNORE_PREFIX = "IGNORE:"

        /** Spec 11 §11.4 d. */
        const val NON_TXN = "NON_TXN"

        /** Spec 11 §11.4 e — the sender is known, nothing read it. */
        const val NO_PARSER = "NO_PARSER"

        /** Spec 11 §11.4 e — the registry has never heard of this sender. */
        const val UNKNOWN_SENDER = "UNKNOWN_SENDER"

        /** Spec 11 §11.4 f, with the id of the transaction this message joined. */
        const val DUPLICATE_PREFIX = "DUPLICATE:"

        /** A contained parser failure, with the throwable's simple name. */
        const val ERROR_PREFIX = "ERROR:"

        /**
         * Step g's save path refused the transaction — a blocking rule, a duplicate hash, a row
         * the user had deleted. Not in `EmailMessageEntity`'s list, because that list came from
         * the spec and the spec does not consider the case; the alternative was to log a refusal
         * as `ERROR:`, which would make a working rule look like a defect.
         */
        const val NOT_SAVED_PREFIX = "NOT_SAVED:"

        private const val UNSPECIFIED = "UNSPECIFIED"

        /** `ParsedTransactionMapper`'s stand-in for "the parser found no merchant". */
        internal const val UNKNOWN_MERCHANT = "Unknown Merchant"

        /** Longest refusal slug kept, so a throwable's message cannot fill the log column. */
        private const val MAX_SLUG = 40

        internal fun errorReason(error: Throwable): String =
            ERROR_PREFIX + (error::class.simpleName ?: "Throwable")

        /** §14.2: "reference = digits only". */
        private fun digitsOnly(raw: String?): String? =
            raw?.filter { it.isDigit() }?.takeIf { it.isNotEmpty() }

        /**
         * Reduces a refusal message to a short upper-case token.
         *
         * A refusal can carry a throwable's message, which can carry anything; capping it keeps an
         * unbounded string out of a column the Email Log renders.
         */
        internal fun slug(raw: String?): String {
            val builder = StringBuilder()
            for (character in raw.orEmpty().uppercase()) {
                when {
                    character.isLetterOrDigit() -> builder.append(character)
                    builder.isNotEmpty() && builder.last() != '_' -> builder.append('_')
                }
            }
            return builder.toString().trim('_').take(MAX_SLUG).ifEmpty { UNSPECIFIED }
        }
    }
}

// =============================================================================================
// Production implementations of the seams
// =============================================================================================

/** The `:parser-core` due parsers (spec 11 §11.4 b). */
class FactoryDueParsing @Inject constructor() : DueParsing {
    override fun parseAll(
        message: EmailMessage,
        onError: (parserId: String, error: Throwable) -> Unit
    ): List<ParsedDue> = DueParserFactory.parseAll(message, onError)
}

/**
 * The `:parser-core` transaction parsers (spec 11 §11.4 d and e).
 *
 * [factory] carries the user's own VPAs and names, so an own-account transfer is typed TRANSFER
 * rather than an expense plus an income (spec 14 §14.5). `EmailParserFactory.DEFAULT` has none,
 * in which case those rules are simply inert.
 */
class FactoryTransactionParsing(
    private val factory: EmailParserFactory = EmailParserFactory.DEFAULT
) : TransactionParsing {
    override fun isNonTransactionEmail(message: EmailMessage): Boolean =
        factory.isNonTransactionEmail(message)

    override fun parse(message: EmailMessage): ParsedTransaction? = factory.parse(message)

    override fun parserIdFor(message: EmailMessage): String? = factory.parserFor(message.from)?.id
}

/** [TransactionStore] over the upstream DAO. */
class RoomTransactionStore @Inject constructor(
    private val dao: TransactionDao
) : TransactionStore {
    override suspend fun between(from: LocalDateTime, to: LocalDateTime): List<TransactionEntity> =
        dao.getTransactionsBetweenDatesList(from, to)

    override suspend fun byHash(transactionHash: String): TransactionEntity? =
        dao.getTransactionByHash(transactionHash)

    override suspend fun byId(id: Long): TransactionEntity? = dao.getTransactionById(id)

    override suspend fun update(transaction: TransactionEntity) = dao.updateTransaction(transaction)
}

/**
 * [ParsedTransactionSaver] over the inherited save path (spec 11 §11.4 g).
 *
 * Everything the SMS channel already does — merchant mappings, the rule engine, subscription
 * matching, balances, the widget refresh — happens for email because an email transaction goes
 * through the same door as an SMS one.
 */
class ProcessorTransactionSaver @Inject constructor(
    private val processor: SmsTransactionProcessor
) : ParsedTransactionSaver {
    override suspend fun save(parsed: ParsedTransaction, body: String): SavedTransaction {
        val result = processor.saveParsedTransaction(parsed, body)
        return SavedTransaction(transactionId = result.transactionId, refusal = result.reason)
    }
}

/**
 * M1's settlement: "an open due this transaction plausibly settles" (spec 11 §11.4 h).
 *
 * **This is the seam M2 replaces.** Spec 20 §20.5's `DueMatcher` scores candidates (+50 amount,
 * +30 issuer, +30 account, +20 card bill, +20 auto-pay, `minMatchScore` 60) and handles foreign
 * currency and partial payments. Most of those signals — `payee_match`, `pay_from_bank`,
 * `commitment_id`, `paid_amount` — are commitment columns that do not exist at schema v63, so
 * scoring them here would mean inventing them. What M1 implements is the part of §20.5 that is
 * unambiguous and needs nothing new:
 *
 *  - the amount must agree within `amountToleranceAbs`/`amountTolerancePct`, or equal the minimum
 *    due — §20.5 step 4 rejects outright when it does not;
 *  - the issuer **or** the account must corroborate it (`requireIssuerOrAccountMatch`, true by
 *    default: amount alone scores 50, below the threshold of 60);
 *  - the currency must match, because money is currency-tagged and conversion is M2's
 *    `fxTolerancePct`;
 *  - nearest due date wins, ties by row id, so the result never depends on query order.
 *
 * Spec 01 rule 3's one-to-one invariant is enforced both ways: a transaction that already settled
 * a due is not reused, and a due that already names a transaction is not re-settled.
 */
class PlausibleDueSettler(
    private val dueDao: DueDao,
    private val clock: Clock,
    private val config: SettlementConfig = SettlementConfig()
) : DueSettler {

    override suspend fun trySettle(transaction: TransactionEntity): Long? {
        if (transaction.isDeleted) return null
        // §20.5 step 1. INCOME settles IN-direction dues (salary), which only exist once
        // commitments do; until then an incoming credit settles nothing.
        if (transaction.transactionType !in SETTLING_TYPES) return null
        // §20.5 step 2 / spec 01 rule 3: one transaction settles at most one due.
        if (dueDao.getSettledBy(transaction.id) != null) return null

        val paidOn = transaction.dateTime.toLocalDate()
        // Spec 20 §20.5 step 3 writes the window as `[date − before, date + after]` with
        // `before/after = earlyPayDays/latePayDays`, which reads backwards: `earlyPayDays` is how
        // far *ahead* a bill may be dated and still be the thing just paid — §20.8's own worked
        // case is a 1 October statement paid on 16 September, "early payment inside earlyPayDays".
        // So early bounds the future side and late the past side.
        val candidates = dueDao.findSettlementCandidates(
            from = paidOn.minusDays(config.latePayDays),
            to = paidOn.plusDays(config.earlyPayDays)
        )

        val best = candidates
            .filter { it.settledTransactionId == null && it.status == DueStatus.OPEN }
            .filter { it.currency.equals(transaction.currency, ignoreCase = true) }
            .filter { amountAgrees(transaction, it) }
            .filter { issuerAgrees(transaction, it) || accountAgrees(transaction, it) }
            .minWithOrNull(compareBy<DueEntity> { daysBetween(paidOn, it.dueDate) }.thenBy { it.id })
            ?: return null

        val grace = if (best.autoPay) config.autoPayGraceDays else config.graceDays
        val status =
            if (!paidOn.isAfter(best.dueDate.plusDays(grace))) DueStatus.PAID_ON_TIME
            else DueStatus.PAID_LATE

        val settledAt = LocalDateTime.now(clock)
        dueDao.update(
            best.copy(
                settledTransactionId = transaction.id,
                settledAt = settledAt,
                status = status,
                updatedAt = settledAt
            )
        )
        return best.id
    }

    /**
     * §20.5 step 4's amount test, which is a gate rather than a score: a payment whose amount is
     * nowhere near the bill is not that bill, however well everything else lines up.
     *
     * A zero-amount due ("nothing due this month") matches nothing — otherwise every small payment
     * would settle it.
     */
    private fun amountAgrees(transaction: TransactionEntity, due: DueEntity): Boolean {
        val paid = transaction.amount.abs()
        if (due.amount.signum() > 0) {
            val tolerance = config.amountToleranceAbs.max(due.amount.multiply(config.amountTolerancePct))
            if ((paid - due.amount).abs() <= tolerance) return true
        }
        val minimum = due.minimumDue
        if (minimum != null && minimum.signum() > 0) {
            if ((paid - minimum).abs() <= config.amountToleranceAbs) return true
        }
        return false
    }

    /** §20.5 step 4 "issuer": the payment names who wanted the money. */
    private fun issuerAgrees(transaction: TransactionEntity, due: DueEntity): Boolean {
        val issuer = squash(due.issuer) ?: return false
        if (issuer.length < MIN_ISSUER_TOKEN) return false
        val haystacks = listOfNotNull(
            squash(transaction.merchantName),
            squash(transaction.bankName),
            squash(transaction.description),
            squash(transaction.toAccount)
        )
        return haystacks.any {
            it.length >= MIN_ISSUER_TOKEN && (it.contains(issuer) || issuer.contains(it))
        }
    }

    /** §20.5 step 4 "account": the payment names the card or account being billed. */
    private fun accountAgrees(transaction: TransactionEntity, due: DueEntity): Boolean {
        val last4 = due.accountLast4?.filter { it.isDigit() }?.takeIf { it.length == 4 } ?: return false
        return listOfNotNull(
            transaction.accountNumber,
            transaction.toAccount,
            transaction.fromAccount,
            transaction.description,
            transaction.merchantName
        ).any { field -> field.filter { it.isDigit() }.contains(last4) }
    }

    private fun daysBetween(paidOn: LocalDate, dueDate: LocalDate): Long =
        abs(ChronoUnit.DAYS.between(paidOn, dueDate))

    private companion object {
        /** §20.5 step 1: money going out. */
        val SETTLING_TYPES = setOf(
            TransactionType.EXPENSE,
            TransactionType.TRANSFER,
            TransactionType.CREDIT
        )

        /**
         * Shortest issuer token compared. Below this, substring matching is noise: a three-letter
         * fragment appears in half the merchant names in the table.
         */
        const val MIN_ISSUER_TOKEN = 4

        /** Letters and digits only, lower-cased, so "Axis Bank" and "AXIS BANK CC" line up. */
        fun squash(raw: String?): String? =
            raw?.filter { it.isLetterOrDigit() }?.lowercase()?.takeIf { it.isNotEmpty() }
    }
}
