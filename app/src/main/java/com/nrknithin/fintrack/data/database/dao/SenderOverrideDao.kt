package com.nrknithin.fintrack.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.nrknithin.fintrack.data.database.entity.SenderOverrideEntity
import com.nrknithin.fintrack.data.database.entity.SenderOverrideSource
import com.nrknithin.fintrack.data.database.entity.SenderOverrideStatus
import com.nrknithin.fintrack.domain.engine.DiscoveredSender
import kotlinx.coroutines.flow.Flow
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The user's additions to `EmailSenderRegistry` (spec 03 §3.3, spec 11 §11.6, screen 47).
 *
 * Two readers matter and they ask different questions. The sync asks *"which senders may the
 * `OR(FROM …)` search include?"* — that is [active], and it is the single place the
 * `status AND is_enabled` pair is spelled out, so no caller has to remember both halves. The
 * discovery scan asks *"what has the owner already decided about?"* — that is [declinedKeys]
 * plus [active], fed back in so a second scan proposes neither.
 *
 * The write side is one method, [observe], and the idempotence rules live in it rather than
 * in its caller: a weekly scan that re-proposed what was already decided would make screen 47
 * un-clearable, which is the failure that kills this kind of feature.
 */
@Dao
interface SenderOverrideDao {

    // --- reads -------------------------------------------------------------------------

    /**
     * The rows §11.6 consults, and the only ones that widen the R-11-03 search.
     *
     * Both halves are required. `status = 'ACCEPTED'` is the owner's decision;
     * `is_enabled = 1` is the toggle that lets them switch an accepted sender off without
     * forgetting they ever accepted it.
     */
    @Query("SELECT * FROM sender_overrides WHERE status = 'ACCEPTED' AND is_enabled = 1")
    suspend fun active(): List<SenderOverrideEntity>

    /** Everything the owner has said no to, as §11.6 keys. Fed back into `SenderDiscovery`. */
    @Query("SELECT address_or_domain FROM sender_overrides WHERE status = 'DECLINED'")
    suspend fun declinedKeys(): List<String>

    /**
     * What screen 47 puts in front of the owner, heaviest sender first — `message_count`
     * descending, then the domain, so the order is stable when two senders tie.
     */
    @Query(
        """
        SELECT * FROM sender_overrides
        WHERE status = 'PROPOSED'
        ORDER BY message_count DESC, address_or_domain ASC
        """
    )
    fun proposals(): Flow<List<SenderOverrideEntity>>

    /** Every row, decided or not — screen 47's full list. */
    @Query(
        """
        SELECT * FROM sender_overrides
        ORDER BY status ASC, message_count DESC, address_or_domain ASC
        """
    )
    fun observeAll(): Flow<List<SenderOverrideEntity>>

    @Query("SELECT * FROM sender_overrides WHERE address_or_domain = :key LIMIT 1")
    suspend fun byKey(key: String): SenderOverrideEntity?

    @Query("SELECT COUNT(*) FROM sender_overrides WHERE status = :status")
    suspend fun countByStatus(status: SenderOverrideStatus): Int

    // --- writes ------------------------------------------------------------------------

    /**
     * IGNORE, not REPLACE. `address_or_domain` is unique, and REPLACE on a conflict deletes
     * the existing row and inserts a new one — which would throw away the owner's decision
     * and their edited name, and hand the row a fresh primary key. A conflict here means
     * "already known", and [observe] resolves it by reading the row rather than replacing it.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(override: SenderOverrideEntity): Long

    /**
     * Evidence only. Deliberately touches neither `status`, `display_name` nor `is_enabled`:
     * this is the statement "the scan saw this sender again", and it must be safe to run
     * against a row in any state.
     */
    @Query(
        """
        UPDATE sender_overrides
        SET message_count = :messageCount,
            first_seen_at = :firstSeenAt,
            last_seen_at = :lastSeenAt,
            last_discovered_at = :at
        WHERE address_or_domain = :key
        """
    )
    suspend fun refreshEvidence(
        key: String,
        messageCount: Int,
        firstSeenAt: LocalDateTime?,
        lastSeenAt: LocalDateTime?,
        at: LocalDateTime
    )

    /**
     * Fills a name in only where there is none.
     *
     * `AND display_name IS NULL` is the whole point and is enforced in SQL rather than in
     * Kotlin: the owner may have typed a better name than the sender's own phrase, and a
     * scan that overwrote it every week would make the field unusable.
     */
    @Query(
        """
        UPDATE sender_overrides SET display_name = :displayName
        WHERE address_or_domain = :key AND display_name IS NULL
        """
    )
    suspend fun fillDisplayName(key: String, displayName: String)

    /** The owner's decision. The one write that lets a sender into the search. */
    @Query("UPDATE sender_overrides SET status = :status WHERE address_or_domain = :key")
    suspend fun setStatus(key: String, status: SenderOverrideStatus)

    @Query("UPDATE sender_overrides SET is_enabled = :enabled WHERE address_or_domain = :key")
    suspend fun setEnabled(key: String, enabled: Boolean)

    /** Screen 47's rename. Unlike [fillDisplayName] this is the owner overwriting on purpose. */
    @Query("UPDATE sender_overrides SET display_name = :displayName WHERE address_or_domain = :key")
    suspend fun setDisplayName(key: String, displayName: String?)

    @Query("DELETE FROM sender_overrides WHERE address_or_domain = :key")
    suspend fun deleteByKey(key: String)

    @Query("DELETE FROM sender_overrides")
    suspend fun deleteAll()

    /**
     * Records one discovery result, keyed on `address_or_domain`.
     *
     * Refreshes counts and seen-dates; **never** overwrites a `display_name` the owner
     * edited, and **never** moves a row out of DECLINED or ACCEPTED back into PROPOSED. A
     * new row always lands as PROPOSED, from DISCOVERY.
     *
     * `first_seen_at` only ever moves backwards and `last_seen_at` only forwards, because the
     * scan window slides: a 365-day scan run next month cannot see the mail it saw last
     * month, and taking this run's figures literally would keep walking the first sighting
     * forward until the row claimed the sender was new.
     *
     * @param at the run's stamp, from the injected clock — the caller's
     *   `LocalDateTime.now(clock)`, so a test can freeze it.
     */
    @Transaction
    suspend fun observe(sender: DiscoveredSender, at: LocalDateTime) {
        // `DiscoveredSender` carries INTERNALDATEs as `Instant`, because that is what the
        // server sends. This table stores local date-times, as every other date column in
        // this schema does, and the zone is the system's — the same one the
        // `Clock.systemDefaultZone()` behind every `LocalDateTime.now(clock)` in the app
        // resolves with, so a row written here sorts against the rest of the schema.
        val zone = ZoneId.systemDefault()
        val seenFirst = LocalDateTime.ofInstant(sender.firstSeen, zone)
        val seenLast = LocalDateTime.ofInstant(sender.lastSeen, zone)

        val existing = byKey(sender.domain)
        if (existing == null) {
            insert(
                SenderOverrideEntity(
                    addressOrDomain = sender.domain,
                    displayName = sender.displayName,
                    status = SenderOverrideStatus.PROPOSED,
                    source = SenderOverrideSource.DISCOVERY,
                    messageCount = sender.messageCount,
                    firstSeenAt = seenFirst,
                    lastSeenAt = seenLast,
                    lastDiscoveredAt = at,
                    createdAt = at
                )
            )
            return
        }

        refreshEvidence(
            key = existing.addressOrDomain,
            messageCount = sender.messageCount,
            firstSeenAt = minOf(existing.firstSeenAt ?: seenFirst, seenFirst),
            lastSeenAt = maxOf(existing.lastSeenAt ?: seenLast, seenLast),
            at = at
        )
        // A name for a row that has none — including a hand-typed row the owner never named.
        sender.displayName?.let { fillDisplayName(existing.addressOrDomain, it) }
    }
}
