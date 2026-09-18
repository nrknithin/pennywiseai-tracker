package com.nrknithin.fintrack.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import java.time.LocalDateTime

/**
 * `sender_overrides` — spec 03 §3.3 and spec 11 §11.6, brought forward from M2 and widened
 * so that a *discovered* sender and a *hand-typed* one are the same row in two states.
 *
 * **Why it comes forward.** §3.3 files this table under M2 (v64) because it was conceived as
 * a hand-editing surface: the owner sees a sender in the Email Log, types it in, and the next
 * sync downloads it. Sender discovery inverts that — the app finds the sender and the owner
 * only says yes or no — and discovery has nowhere to write its findings without this table.
 * Schema 63 is unreleased, so this is an **entity addition, not a migration**: no device has
 * ever opened a v63 database, `AutoMigration(62, 63)` is regenerated with the extra
 * `CREATE TABLE` in it, and 62→63 stays a single additive step.
 *
 * **One table, not two.** What discovery needs and §3.3 lacks is a lifecycle ([status]) and
 * evidence ([messageCount], [firstSeenAt], [lastSeenAt], [lastDiscoveredAt]). A second table
 * would mean two places to ask "may the search use this sender?", and the two would drift.
 *
 * Every field has a Kotlin default (upstream `CLAUDE.md` constraint 6, spec 01 constraint
 * C4); the defaults are chosen so a field lost in deserialisation degrades towards "not yet
 * trusted", never towards trusted.
 */
@Entity(
    tableName = "sender_overrides",
    indices = [
        // §3.3's `address_or_domain TEXT NN unique`. It is the identity a discovery run
        // upserts on, so uniqueness is what makes a weekly scan idempotent rather than
        // additive.
        Index(value = ["address_or_domain"], unique = true),
        // `active()` and `declinedKeys()` both filter on it, once per sync and once per scan.
        Index(value = ["status"])
    ]
)
@Serializable
data class SenderOverrideEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    /**
     * Lower-cased address or registrable domain — the two shapes spec 11 §11.6 resolves
     * against, and what [com.nrknithin.fintrack.parser.core.email.SenderDisplayName.registrableDomain]
     * produces. §11.6 prefers an exact address, so an address row wins over a domain row for
     * the same sender by virtue of the resolution order, not by anything stored here.
     */
    @ColumnInfo(name = "address_or_domain")
    val addressOrDomain: String = "",

    /**
     * **Nullable**, against spec 03 §3.3's `bank_alias TEXT NN`.
     *
     * A discovered sender has no bank alias — a grocer is not a bank — and
     * `SenderMapping.smsAlias` is already nullable for exactly that reason
     * (`SenderMapping("IRCTC")` carries none). `NN` would force one to be invented, which is
     * spec 01 constraint C7. Recorded as a deviation in docs/FORK-CHANGES.md.
     */
    @ColumnInfo(name = "bank_alias")
    val bankAlias: String? = null,

    /**
     * The name the owner sees. Null when no From phrase in the corpus was usable — the UI
     * then shows [addressOrDomain], because a name that was never sent cannot be recovered
     * from an address (constraint C7; see `SenderDisplayName.fromPhrase`).
     */
    @ColumnInfo(name = "display_name")
    val displayName: String? = null,

    /**
     * PROPOSED · ACCEPTED · DECLINED. Only `ACCEPTED && is_enabled` rejoins the R-11-03
     * search and the §11.6 resolution order.
     *
     * Default PROPOSED in Kotlin *and* in SQL; screen 47's hand-add path passes ACCEPTED
     * explicitly. The safe direction: a value lost in a backup round-trip must degrade to
     * "awaiting the owner", not to "already widening the search".
     */
    @ColumnInfo(name = "status", defaultValue = "PROPOSED")
    val status: SenderOverrideStatus = SenderOverrideStatus.PROPOSED,

    /** MANUAL · DISCOVERY. Provenance, so screen 47 can say "found in your Purchases mail". */
    @ColumnInfo(name = "source", defaultValue = "MANUAL")
    val source: SenderOverrideSource = SenderOverrideSource.MANUAL,

    /** §3.3's user toggle: switch an accepted sender off without forgetting the decision. */
    @ColumnInfo(name = "is_enabled", defaultValue = "1")
    val isEnabled: Boolean = true,

    /**
     * How many messages the last scan saw from this sender. Evidence, and the order screen
     * 47 lists proposals in — the sender you buy from weekly should be the first one offered.
     */
    @ColumnInfo(name = "message_count", defaultValue = "0")
    val messageCount: Int = 0,

    /** Oldest sighting's INTERNALDATE. Null for a hand-typed row — unknown stays unknown. */
    @ColumnInfo(name = "first_seen_at")
    @Contextual
    val firstSeenAt: LocalDateTime? = null,

    /** Newest sighting's INTERNALDATE. Null for a hand-typed row. */
    @ColumnInfo(name = "last_seen_at")
    @Contextual
    val lastSeenAt: LocalDateTime? = null,

    /**
     * When a discovery run last *saw* this sender, as opposed to when the sender last wrote.
     *
     * The pair distinguishes a stale row from a gone one: a sender with an old [lastSeenAt]
     * and a recent [lastDiscoveredAt] has simply stopped writing, while one whose
     * [lastDiscoveredAt] stopped moving fell out of the scan's window entirely.
     */
    @ColumnInfo(name = "last_discovered_at")
    @Contextual
    val lastDiscoveredAt: LocalDateTime? = null,

    @ColumnInfo(name = "created_at")
    @Contextual
    val createdAt: LocalDateTime = LocalDateTime.now()
)
