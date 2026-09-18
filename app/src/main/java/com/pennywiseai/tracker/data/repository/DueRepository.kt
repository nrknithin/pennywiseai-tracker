package com.pennywiseai.tracker.data.repository

import com.pennywiseai.parser.core.email.due.ParsedDue
import com.pennywiseai.tracker.data.database.dao.DueDao
import com.pennywiseai.tracker.data.database.dao.DueSourceDao
import com.pennywiseai.tracker.data.database.entity.DueEntity
import com.pennywiseai.tracker.data.email.DueUpsert
import com.pennywiseai.tracker.data.email.DueWriter
import com.pennywiseai.tracker.domain.engine.DueMergeConfig
import com.pennywiseai.tracker.domain.engine.DueMergeEngine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Step b of spec 11 §11.4: the `dues` table's one writer for parsed bills (spec 20 §20.4).
 *
 * **Thin on purpose.** Every rule — the merge key, which rows are candidates, which of them
 * wins, what a notice may change about it, R-20-09's auto-dismissal — lives in
 * [DueMergeEngine], which is pure Kotlin and unit-tested without a database. What is left here
 * is the part only a repository can do: ask the DAOs for rows, hand them to the engine, and
 * write back what it returns. If a decision appears in this file, it is in the wrong file.
 *
 * Three queries make up the candidate set, because §20.4 step 2 matches on "accountLast4 equal
 * **or** reference equal" and `DueDao` has no single query for that:
 *  - [DueDao.getByDueId] — the same bill read twice. Exact, and the row it returns is this
 *    reading's row whatever its status, because `due_id` is unique and an insert would abort;
 *  - [DueDao.findMergeCandidates] — same issuer, kind and card, dated within tolerance, **all
 *    of them**;
 *  - [DueDao.getByReference] — the same invoice number or domain, dated anywhere.
 * The union is de-duplicated by row id and filtered by the engine, so a wide query costs a
 * little work and never costs correctness.
 *
 * **No query here chooses.** The card branch used to be `LIMIT 1`, so SQL picked the earliest
 * due date in the window and step 4 never ran on it. Over step 7's two-month payment window
 * that handed a late-September confirmation to August's statement, and October's bill never
 * got a `PAYMENT_CONFIRMED` source at all. Every branch now returns its whole set and
 * [DueMergeEngine.chooseTarget] is the only thing that picks — which is also the set M2's
 * scored `DueMatcher` (§20.5) is built on.
 *
 * One edge of §20.4 step 2 that v63's DAO still cannot reach, and which the engine handles the
 * moment a query does return the row: step 2 admits settled dues (`PAID_*`, `MARKED_PAID`) as
 * merge candidates while [DueDao.findMergeCandidates] filters `status = 'OPEN'`. Every due this
 * repository created is still reached after settlement, because [DueDao.getByDueId] is exact
 * and status-blind; a *settled* due created elsewhere — a commitment's, in M2 — would need a
 * query that does not exist yet.
 *
 * It deliberately does **not** write `due_sources`. `EmailTransactionIngestor` owns that row —
 * which mail said what, and with which sender — and writing it here as well would record every
 * notice twice. The sources are *read* here, because the precedence ladder is answered by them.
 */
@Singleton
class DueRepository @Inject constructor(
    private val dueDao: DueDao,
    private val dueSourceDao: DueSourceDao,
    private val engine: DueMergeEngine
) : DueWriter {

    /**
     * Spec 28 §28.4's `settings.dues.mergeDateToleranceDays` is read from a settings store that
     * arrives with the rest of the backbone in M2. Until then the documented default stands;
     * the overload below is what a caller — and every test — varies it through.
     */
    override suspend fun upsertFromParsed(parsed: ParsedDue, emailMessageId: Long): DueUpsert =
        upsertFromParsed(parsed, emailMessageId, DueMergeConfig())

    suspend fun upsertFromParsed(
        parsed: ParsedDue,
        emailMessageId: Long,
        config: DueMergeConfig
    ): DueUpsert =
        mergeIntoExisting(parsed, emailMessageId, config) ?: insert(parsed, emailMessageId)

    /**
     * §20.4 step 7's merge-only path. Same steps 2 and 4, without step 3's insert.
     *
     * Which readings come through here is the ingestor's decision, not this file's — see
     * [DueWriter.mergeIntoExisting] for why a payment confirmation must never create a row.
     */
    override suspend fun mergeIntoExisting(parsed: ParsedDue, emailMessageId: Long): DueUpsert? =
        mergeIntoExisting(parsed, emailMessageId, DueMergeConfig())

    suspend fun mergeIntoExisting(
        parsed: ParsedDue,
        emailMessageId: Long,
        config: DueMergeConfig
    ): DueUpsert? {
        val candidates = candidatesFor(parsed, config)
        val target = engine.chooseTarget(candidates, parsed, config) ?: return null

        // §20.4 step 4. An unchanged row is not written: a re-processed mail must not bump
        // `updated_at` and make an untouched due look as if it had just moved.
        val outcome = engine.merge(target, parsed, config)
        if (outcome.changed) dueDao.update(outcome.due)

        return DueUpsert(
            dueRowId = target.due.id,
            created = false,
            mergeRule = outcome.mergeRule
        )
    }

    /** §20.4 step 3. */
    private suspend fun insert(parsed: ParsedDue, emailMessageId: Long): DueUpsert {
        val row = engine.newDue(parsed, emailMessageId)
        return DueUpsert(
            dueRowId = dueDao.insert(row),
            created = true,
            mergeRule = engine.mergeRuleFor(parsed, target = null)
        )
    }

    /**
     * §20.4 step 2's candidate **set**, as far as SQL can answer it; the engine settles the rest.
     *
     * Every branch is a set query, so no row is discarded before the engine has seen it. The
     * cost is bounded by the merge key and the window — a handful of rows for one card — and
     * what it buys is that step 4's "most sources" contest and step 7's nearest-bill rule are
     * reachable in production, not only from a unit test that calls `chooseTarget` directly.
     */
    private suspend fun candidatesFor(
        parsed: ParsedDue,
        config: DueMergeConfig
    ): List<DueMergeEngine.Candidate> {
        val window = engine.mergeWindow(parsed, config)
        val rows = buildList {
            dueDao.getByDueId(engine.dueId(parsed))?.let { add(it) }
            addAll(
                dueDao.findMergeCandidates(
                    kind = engine.storedKind(parsed.kind),
                    issuer = parsed.issuer,
                    accountLast4 = parsed.accountLast4,
                    fromDate = window.from,
                    toDate = window.to
                )
            )
            parsed.reference?.let { reference -> addAll(dueDao.getByReference(reference)) }
        }

        return rows
            .distinctBy(DueEntity::id)
            .filter { engine.isCandidate(it, parsed, config) }
            .map { DueMergeEngine.Candidate(it, dueSourceDao.getForDue(it.id)) }
    }
}
