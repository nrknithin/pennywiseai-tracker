package com.pennywiseai.tracker.data.email

import androidx.room.withTransaction
import com.pennywiseai.tracker.data.database.PennyWiseDatabase
import com.pennywiseai.tracker.data.database.dao.EmailMessageDao
import com.pennywiseai.tracker.data.database.entity.EmailMessageEntity
import java.time.Clock
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What one committed batch did - the `sync_runs` counters, one batch at a time.
 *
 * Additive, so `EmailSyncWorker` folds batches without knowing what any of them contained,
 * and every field defaults to zero so a counter nothing has earned stays 0 rather than being
 * guessed at (spec 01 constraint C7).
 */
data class EmailBatchCounts(
    /** Rows written to `email_messages` (spec 11 R-11-05). */
    val logged: Int = 0,
    /** Messages already in the log - a re-fetch after a UIDVALIDITY reset (R-11-08). */
    val alreadyKnown: Int = 0,
    val parsedTxn: Int = 0,
    val linkedTwin: Int = 0,
    val duesUpserted: Int = 0,
    val duesSettled: Int = 0,
    val receipts: Int = 0,
    /** Messages understood and deliberately turned into nothing (IGNORE / NON_TXN / NO_PARSER). */
    val skipped: Int = 0,
    /** Parser failures and unreadable messages, both contained (spec 02 section 2.7). */
    val errors: Int = 0,
    /**
     * True when this batch moved money: spec 11 section 11.3 step 22 marks the plan dirty once
     * per run, so the worker ORs this rather than each message announcing itself.
     */
    val changedMoney: Boolean = false
) {
    operator fun plus(other: EmailBatchCounts): EmailBatchCounts = EmailBatchCounts(
        logged = logged + other.logged,
        alreadyKnown = alreadyKnown + other.alreadyKnown,
        parsedTxn = parsedTxn + other.parsedTxn,
        linkedTwin = linkedTwin + other.linkedTwin,
        duesUpserted = duesUpserted + other.duesUpserted,
        duesSettled = duesSettled + other.duesSettled,
        receipts = receipts + other.receipts,
        skipped = skipped + other.skipped,
        errors = errors + other.errors,
        changedMoney = changedMoney || other.changedMoney
    )

    companion object {
        val EMPTY = EmailBatchCounts()

        /**
         * Folds what [EmailTransactionIngestor] reported over a batch.
         *
         * `skipped` counts only messages that produced nothing at all: a `DUPLICATE:<id>` skip
         * reason belongs to a message that was linked to a transaction, and counting it as
         * both skipped and linked would make the Email Log's totals exceed the messages it
         * has rows for.
         */
        fun of(
            logged: Int,
            alreadyKnown: Int,
            unreadable: Int,
            results: List<IngestResult>
        ): EmailBatchCounts = EmailBatchCounts(
            logged = logged,
            alreadyKnown = alreadyKnown,
            parsedTxn = results.count { it.createdTransaction },
            linkedTwin = results.count { it.linkedTwin },
            duesUpserted = results.sumOf { it.dueRowIds.size },
            duesSettled = results.count { it.settledDueId != null },
            // Receipts arrive with M5; no result can report one yet.
            receipts = 0,
            skipped = results.count {
                it.skipReason != null &&
                    !it.createdTransaction &&
                    !it.linkedTwin &&
                    it.dueRowIds.isEmpty()
            },
            errors = results.sumOf { it.errors } + unreadable,
            changedMoney = results.any { it.changedMoney }
        )
    }
}

/**
 * Steps 11 to 18 of spec 11 section 11.3: log a batch, process it, in **one** transaction.
 *
 * This interface exists because of R-11-07. `EmailSyncWorker` advances the stored
 * `lastSyncedUid` the instant [commitBatch] returns, so an implementation that committed
 * lazily, on another dispatcher, or partially would move the watermark past mail that is not
 * in the log - the one bug in this channel that loses a message permanently, since no later
 * run ever looks below the watermark again.
 *
 * It is also the seam that keeps the worker's own algorithm testable: the sync tests drive a
 * batch committer that is a map in memory, with no Room and no Android.
 */
interface EmailBatchCommitter {

    /**
     * Logs [messages] to `email_messages` and runs section 11.4 over every new row.
     *
     * A message already logged - same `(mailbox, uid)`, or the same `message_id` after a
     * folder rebuild - is counted in [EmailBatchCounts.alreadyKnown] and left exactly as it
     * is: its `transaction_id` and `skip_reason` are the outcome of an earlier run and must
     * not be recomputed (R-11-08).
     *
     * [unreadable] carries the UIDs this batch could not turn into messages at all. They are
     * logged as `ERROR:<class>` rows so the Email Log can show them and so a later run
     * recognises them instead of trying again.
     */
    suspend fun commitBatch(
        mailbox: String,
        messages: List<FetchedMessage>,
        unreadable: List<UnreadableMessage>,
        config: EmailAccountConfig
    ): EmailBatchCounts
}

/**
 * Section 11.4 over the rows a batch just wrote - the half [EmailTransactionIngestor] owns.
 *
 * A seam rather than a direct call because the ingestor is built per run from settings, and
 * because its collaborators (the due writer above all) arrive with M1d. Whoever binds a real
 * implementation builds an [EmailTransactionIngestor] from [config] and returns
 * `ingestor.ingestAll(rows)`; nothing else in the worker moves.
 */
interface EmailBatchProcessor {

    /**
     * @param rows freshly logged messages, each with its real `id`, in fetch order.
     * @return one result per row, in any order. An empty list means nothing was processed.
     */
    suspend fun process(
        rows: List<EmailMessageEntity>,
        config: EmailAccountConfig
    ): List<IngestResult>
}

/**
 * The M1c binding: messages are logged and left unprocessed.
 *
 * R-11-05 is the half that has to exist first - a fetched message is written down *before*
 * anything tries to understand it, so no crash, no missing parser and no half-built milestone
 * can lose mail. The rows land with `processed = false`, which is exactly what
 * `EmailMessageDao.getUnmatched` selects, so when M1d binds the real processor the backlog is
 * replayed from the database rather than re-downloaded from Gmail (R-11-10).
 *
 * It reports no counters it has not earned: parsed, linked and skipped all stay 0 until
 * something really parses, links or skips (spec 01 constraint C7).
 */
@Singleton
class DeferredEmailBatchProcessor @Inject constructor() : EmailBatchProcessor {
    override suspend fun process(
        rows: List<EmailMessageEntity>,
        config: EmailAccountConfig
    ): List<IngestResult> = emptyList()
}

/**
 * The production committer: one Room transaction per batch of 50 (R-11-07).
 *
 * Logging and processing share the transaction deliberately. Splitting them would leave a
 * window where a row exists with `processed = false` and no outcome - recoverable, since
 * "Re-process unmatched" replays exactly those, but it would also let the watermark advance
 * over a message whose processing was rolled back by the very same crash.
 */
@Singleton
class RoomEmailBatchCommitter @Inject constructor(
    private val database: PennyWiseDatabase,
    private val emailMessages: EmailMessageDao,
    private val processor: EmailBatchProcessor,
    private val clock: Clock
) : EmailBatchCommitter {

    override suspend fun commitBatch(
        mailbox: String,
        messages: List<FetchedMessage>,
        unreadable: List<UnreadableMessage>,
        config: EmailAccountConfig
    ): EmailBatchCounts {
        if (messages.isEmpty() && unreadable.isEmpty()) return EmailBatchCounts.EMPTY

        return database.withTransaction {
            val rows = ArrayList<EmailMessageEntity>(messages.size)
            var logged = 0
            var alreadyKnown = 0

            for (message in messages) {
                if (isAlreadyLogged(mailbox, message.header.uid, message.header.messageId)) {
                    alreadyKnown++
                    continue
                }
                // IGNORE on conflict, so a racing insert is recognised rather than
                // overwriting a row that already carries an outcome.
                val entity = toEntity(mailbox, message)
                val rowId = emailMessages.insert(entity)
                if (rowId == -1L) {
                    alreadyKnown++
                    continue
                }
                // The id matters: section 11.4 writes its outcome back onto this row, and the
                // insert's rowid IS the autogenerated id, so nothing has to be read back.
                rows += entity.copy(id = rowId)
                logged++
            }

            val unreadableLogged = logUnreadable(mailbox, unreadable)

            val results = if (rows.isEmpty()) emptyList() else processor.process(rows, config)

            EmailBatchCounts.of(
                logged = logged + unreadableLogged,
                alreadyKnown = alreadyKnown,
                unreadable = unreadable.size,
                results = results
            )
        }
    }

    /**
     * A UID the server handed over but the client could not read (spec 11, `UnreadableMessage`).
     *
     * The row records what is actually known - mailbox, UID, and the failure's class - and
     * nothing else: no sender, no body, no hash (spec 01 constraint C7, and an md5 of the
     * empty body would collide with every other empty one in dedup layer L0). An entry whose
     * UID itself was unreadable cannot be written at all, since `(mailbox, uid)` is the row's
     * identity; it is counted in `sync_runs.errors` and nowhere else.
     */
    private suspend fun logUnreadable(mailbox: String, unreadable: List<UnreadableMessage>): Int {
        var logged = 0
        for (entry in unreadable) {
            val uid = entry.uid ?: continue
            if (isAlreadyLogged(mailbox, uid, messageId = null)) continue
            val now = LocalDateTime.now(clock)
            val rowId = emailMessages.insert(
                EmailMessageEntity(
                    mailbox = mailbox,
                    uid = uid,
                    // The server's INTERNALDATE was part of what could not be read. "When we
                    // saw it" is the only honest answer, and the Email Log says so.
                    receivedAt = now,
                    createdAt = now,
                    processed = true,
                    skipReason = "ERROR:${entry.error}"
                )
            )
            if (rowId != -1L) logged++
        }
        return logged
    }

    /** Step 13's two identity keys; `message_id` is the one that survives a folder rebuild. */
    private suspend fun isAlreadyLogged(mailbox: String, uid: Long, messageId: String?): Boolean {
        if (emailMessages.countByUid(mailbox, uid) > 0) return true
        if (messageId == null) return false
        return emailMessages.getByMessageId(messageId) != null
    }

    private fun toEntity(mailbox: String, message: FetchedMessage): EmailMessageEntity {
        val header = message.header
        val body = message.body
        return EmailMessageEntity(
            mailbox = mailbox,
            uid = header.uid,
            messageId = header.messageId,
            // Already bare and lower-cased by the client (EmailSenderRegistry.normaliseAddress);
            // re-normalising here would put sender regex in :app, which spec 02 section 2.1 forbids.
            fromAddress = header.from,
            subject = header.subject.takeIf { it.isNotBlank() },
            body = body.text,
            bodyHash = body.bodyHash,
            // INTERNALDATE, in the device zone - the log is read by a human on this phone.
            receivedAt = LocalDateTime.ofInstant(header.receivedAt, clock.zone),
            processed = false,
            hasAttachments = body.hasAttachments,
            attachmentNames = EmailMessageEntity.encodeAttachmentNames(body.attachmentNames),
            createdAt = LocalDateTime.now(clock)
        )
    }
}
