package com.pennywiseai.tracker.data.repository

import com.pennywiseai.parser.core.email.SenderMapping
import com.pennywiseai.tracker.data.database.dao.SenderOverrideDao
import com.pennywiseai.tracker.data.database.entity.SenderOverrideEntity
import com.pennywiseai.tracker.data.database.entity.SenderOverrideSource
import com.pennywiseai.tracker.data.database.entity.SenderOverrideStatus
import com.pennywiseai.tracker.data.email.SenderOverrides
import com.pennywiseai.tracker.domain.engine.DiscoveryReport
import kotlinx.coroutines.flow.Flow
import java.time.Clock
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `sender_overrides` as the rest of the app wants to talk about it (spec 11 §11.6, screen 47).
 *
 * Three things live here that do not belong in the DAO.
 *
 * **Key normalisation.** A key typed on screen 47 and a key produced by
 * `SenderDisplayName.registrableDomain` have to be the same string, or the owner typing in a
 * sender discovery already proposed would create a second row and the two would disagree
 * about the same sender. Every method funnels through [normalise], and that is the only
 * definition of the key's shape in the app.
 *
 * **The clock.** The DAO takes the run's stamp as a parameter so it stays testable without a
 * clock; somebody has to read one, and it is this class (spec 02 §2.8's house rule that an
 * engine never calls `now()` for itself).
 *
 * **The translation to [SenderMapping].** [mappings] is the shape
 * `EmailSenderRegistry.resolve` expects, and [mappingOf] is the single place the fallback
 * `displayName ?: addressOrDomain` is written. With no name and no alias the domain is the
 * honest label — inventing a brand from a domain is what spec 01 constraint C7 forbids.
 */
@Singleton
class SenderOverrideRepository @Inject constructor(
    private val dao: SenderOverrideDao,
    private val clock: Clock
) : SenderOverrides {

    // --- reads ---------------------------------------------------------------------------

    /** Screen 47's "new senders" list, heaviest first. */
    fun proposals(): Flow<List<SenderOverrideEntity>> = dao.proposals()

    /** Every row, whatever the owner decided — screen 47's full list. */
    fun all(): Flow<List<SenderOverrideEntity>> = dao.observeAll()

    /**
     * The accepted, enabled overrides as spec 11 §11.6's map: keyed by address or domain,
     * valued by what the registry should answer for it.
     *
     * Read per sync rather than cached. A sender accepted on screen 47 must be in the very
     * next run's `OR(FROM …)` search, and a cache here would mean it was not.
     */
    override suspend fun mappings(): Map<String, SenderMapping> =
        dao.active().associate { row -> row.addressOrDomain to mappingOf(row) }

    /** The search terms an accepted override contributes to R-11-03's `OR(FROM …)`. */
    override suspend fun searchTerms(): List<String> = dao.active().map { it.addressOrDomain }

    /** Keys the owner has refused, so a later scan does not propose them again. */
    suspend fun declinedKeys(): Set<String> = dao.declinedKeys().toSet()

    suspend fun find(addressOrDomain: String): SenderOverrideEntity? =
        dao.byKey(normalise(addressOrDomain))

    // --- the discovery side --------------------------------------------------------------

    /**
     * Writes one scan's findings, and answers how many senders it recorded.
     *
     * Every [DiscoveryReport.discovered] row goes through [SenderOverrideDao.observe], which
     * is idempotent by design: a sender already proposed has its evidence refreshed, and one
     * already accepted or declined keeps that decision. So running a scan twice in a row
     * leaves the same rows in the same states, which is what makes a weekly schedule safe.
     *
     * The returned count is senders *recorded*, not senders *new* — a distinction screen 47
     * needs, because "12 senders seen" and "12 senders you have never been asked about" are
     * different sentences and only the first is true.
     */
    suspend fun record(report: DiscoveryReport): Int {
        if (report.discovered.isEmpty()) return 0
        val at = LocalDateTime.now(clock)
        for (sender in report.discovered) dao.observe(sender, at)
        return report.discovered.size
    }

    // --- the owner's decisions -----------------------------------------------------------

    /**
     * Take a proposed sender: the next sync's search includes it and §11.6 resolves through
     * it.
     *
     * Accepting also enables it, because a row accepted-but-disabled is a state the owner did
     * not ask for. [displayName] is applied only when given, so accepting does not silently
     * blank a name discovery found.
     *
     * @return false when there is no such row — the caller is looking at a stale list.
     */
    suspend fun accept(addressOrDomain: String, displayName: String? = null): Boolean {
        val key = normalise(addressOrDomain)
        if (dao.byKey(key) == null) return false
        displayName?.let { dao.setDisplayName(key, it.trim().ifEmpty { null }) }
        dao.setEnabled(key, true)
        dao.setStatus(key, SenderOverrideStatus.ACCEPTED)
        return true
    }

    /**
     * Refuse a proposed sender.
     *
     * The row is kept rather than deleted, and that is the whole point: a deleted row is
     * re-proposed by the next scan, and the list the owner just cleared fills up again.
     */
    suspend fun decline(addressOrDomain: String): Boolean {
        val key = normalise(addressOrDomain)
        if (dao.byKey(key) == null) return false
        dao.setStatus(key, SenderOverrideStatus.DECLINED)
        return true
    }

    /**
     * The `is_enabled` toggle of §3.3 — switch an accepted sender off without forgetting that
     * it was accepted, so it is not proposed again either.
     */
    suspend fun setEnabled(addressOrDomain: String, enabled: Boolean): Boolean {
        val key = normalise(addressOrDomain)
        if (dao.byKey(key) == null) return false
        dao.setEnabled(key, enabled)
        return true
    }

    /**
     * Screen 47's hand-add path (spec 11 R-11-10): the owner types a sender in, so it is
     * ACCEPTED from the start and its source is MANUAL.
     *
     * A key that already exists is *not* duplicated — it is accepted in place, evidence and
     * all. Typing in a sender you previously declined is how you change your mind.
     *
     * @return false when [addressOrDomain] normalises to nothing.
     */
    suspend fun addManual(
        addressOrDomain: String,
        displayName: String? = null,
        bankAlias: String? = null
    ): Boolean {
        val key = normalise(addressOrDomain)
        if (key.isEmpty()) return false

        val existing = dao.byKey(key)
        if (existing != null) {
            displayName?.trim()?.takeIf { it.isNotEmpty() }?.let { dao.setDisplayName(key, it) }
            dao.setEnabled(key, true)
            dao.setStatus(key, SenderOverrideStatus.ACCEPTED)
            return true
        }

        val now = LocalDateTime.now(clock)
        dao.insert(
            SenderOverrideEntity(
                addressOrDomain = key,
                bankAlias = bankAlias?.trim()?.takeIf { it.isNotEmpty() },
                displayName = displayName?.trim()?.takeIf { it.isNotEmpty() },
                status = SenderOverrideStatus.ACCEPTED,
                source = SenderOverrideSource.MANUAL,
                isEnabled = true,
                // Evidence stays 0/null: nothing was measured, and a hand-typed row that
                // claimed a message count would be inventing one (constraint C7).
                createdAt = now
            )
        )
        return true
    }

    /** Rename a row. Unlike discovery's fill-if-empty, this overwrites — the owner meant it. */
    suspend fun rename(addressOrDomain: String, displayName: String?): Boolean {
        val key = normalise(addressOrDomain)
        if (dao.byKey(key) == null) return false
        dao.setDisplayName(key, displayName?.trim()?.takeIf { it.isNotEmpty() })
        return true
    }

    /**
     * Forget a row entirely. Note what this does *not* do: a forgotten sender that is still
     * in the mailbox will be proposed again by the next scan, which is why declining is the
     * normal answer and this is reserved for "Forget all email data".
     */
    suspend fun forget(addressOrDomain: String) = dao.deleteByKey(normalise(addressOrDomain))

    suspend fun forgetAll() = dao.deleteAll()

    companion object {

        /**
         * Lower-cased and trimmed, and angle brackets stripped so a From header pasted whole
         * still lands on the address inside it.
         *
         * No regex: upstream `CLAUDE.md` constraint 5 keeps text-understanding regex in
         * `:parser-core`, and this is a trim, not an understanding.
         */
        internal fun normalise(raw: String): String =
            raw.trim().trim('<', '>').trim().lowercase()

        /**
         * What the registry should answer for one row.
         *
         * `bankName = displayName ?: addressOrDomain` is deliberate: with no name and no
         * alias the domain is the honest label, and title-casing one into a brand is the
         * guess spec 01 constraint C7 forbids.
         */
        internal fun mappingOf(row: SenderOverrideEntity): SenderMapping = SenderMapping(
            bankName = row.displayName ?: row.addressOrDomain,
            smsAlias = row.bankAlias
        )
    }
}
