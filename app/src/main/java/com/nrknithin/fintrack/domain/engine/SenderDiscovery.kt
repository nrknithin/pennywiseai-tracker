package com.nrknithin.fintrack.domain.engine

import com.nrknithin.fintrack.parser.core.email.EmailSenderRegistry
import com.nrknithin.fintrack.parser.core.email.SenderDisplayName
import com.nrknithin.fintrack.parser.core.email.SenderMapping
import com.nrknithin.fintrack.data.email.SenderSighting
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** One sender the registry does not know, as evidence rather than as a claim. */
data class DiscoveredSender(
    /** Group key from [SenderDisplayName.registrableDomain], lower-case. */
    val domain: String,
    /** Modal usable phrase across the group, or null — see [SenderDisplayName.fromPhrase]. */
    val displayName: String?,
    /** Distinct addresses under this domain, ascending, capped at [SenderDiscovery.MAX_ADDRESSES] (8). */
    val addresses: List<String>,
    val messageCount: Int,
    /** Both from the sightings' own INTERNALDATE — never from the clock. */
    val firstSeen: Instant,
    val lastSeen: Instant
)

data class DiscoveryReport(
    /** Unknown senders only, by [DiscoveredSender.messageCount] descending then domain ascending. */
    val discovered: List<DiscoveredSender>,
    /** Sightings whose sender the registry or an override already covers. */
    val knownCount: Int,
    val scannedCount: Int,
    /** The only value that comes from the clock. */
    val scannedAt: Instant
)

/**
 * Groups the senders of a raw-search pass into what the owner has to decide about.
 *
 * Pure: no Room, no Android, no network, no regex (that is [SenderDisplayName]'s). The
 * injected [clock] is read exactly once, for [DiscoveryReport.scannedAt]; first/last-seen
 * come from the messages themselves, so a report is reproducible from its input and a
 * test can assert dates without freezing anything but the stamp.
 *
 * **The three counts are not an addition.** [DiscoveryReport.scannedCount] counts everything
 * the pass looked at and [DiscoveryReport.knownCount] the ones the registry or an override
 * covers; the remainder are proposals *except* for two kinds, which are dropped without
 * being either:
 *
 *  - a sighting whose sender the owner has already declined, and
 *  - a sighting whose address will not parse, or whose domain carries no dot — there is
 *    nothing to propose and nothing to group it under.
 *
 * Dropping rather than counting is spec 01 constraint C7 applied to a total: "the registry
 * knows this sender" is a claim, and neither of those two cases is evidence for it.
 */
@Singleton
class SenderDiscovery @Inject constructor(private val clock: Clock) {

    companion object {
        /**
         * How many addresses one proposed row carries.
         *
         * A cap, not a count: [DiscoveredSender.messageCount] still counts every sighting in
         * the group. Eight is enough to show the owner that a domain really does send from
         * several mailboxes without turning a settings row into a wall of addresses, and it
         * bounds what a sender that rotates its local part per message can write into one row.
         */
        const val MAX_ADDRESSES = 8
    }

    /**
     * @param sightings ENVELOPE peeks from the raw search, in any order.
     * @param overrides the user's `sender_overrides` rows, keyed as
     *   [EmailSenderRegistry.resolve] expects. An ACCEPTED override counts as known, so a
     *   second run stops re-proposing what the owner already took; a DECLINED one is
     *   passed in too (see [declined]) so it is not re-proposed either.
     * @param declined address-or-domain keys the owner has already said no to. Dropped
     *   from [DiscoveryReport.discovered] entirely rather than counted as known — the
     *   owner declining a grocer is not the registry knowing that grocer. A key matches a
     *   sighting's exact address or its [SenderDisplayName.registrableDomain], which are the
     *   two shapes a `sender_overrides` row is ever written with.
     */
    fun discover(
        sightings: List<SenderSighting>,
        overrides: Map<String, SenderMapping> = emptyMap(),
        declined: Set<String> = emptySet()
    ): DiscoveryReport {
        val declinedKeys = declined.asSequence()
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()

        var knownCount = 0
        // Insertion-ordered so grouping cannot smuggle in an order of its own; the result is
        // sorted explicitly below and never relies on this map's iteration order.
        val groups = LinkedHashMap<String, MutableList<Seen>>()

        for (sighting in sightings) {
            // Defensive: `SenderSighting.address` is documented as already normalised, but a
            // header that arrived as `"NAME" <someone@example.com>` must not become its own
            // domain group.
            val address = EmailSenderRegistry.normaliseAddress(sighting.address) ?: continue
            if (EmailSenderRegistry.resolve(address, overrides) != null) {
                knownCount++
                continue
            }
            val domain = SenderDisplayName.registrableDomain(address) ?: continue
            if (address in declinedKeys || domain in declinedKeys) continue

            groups.getOrPut(domain) { mutableListOf() } += Seen(address, sighting)
        }

        val discovered = groups
            .map { (domain, seen) -> summarise(domain, seen) }
            .sortedWith(compareByDescending<DiscoveredSender> { it.messageCount }.thenBy { it.domain })

        return DiscoveryReport(
            discovered = discovered,
            knownCount = knownCount,
            scannedCount = sightings.size,
            scannedAt = clock.instant()
        )
    }

    /** One sighting with its address already normalised, so that work is not done twice. */
    private data class Seen(val address: String, val sighting: SenderSighting)

    private fun summarise(domain: String, seen: List<Seen>): DiscoveredSender = DiscoveredSender(
        domain = domain,
        displayName = modalName(seen),
        addresses = seen.map { it.address }.distinct().sorted().take(MAX_ADDRESSES),
        messageCount = seen.size,
        firstSeen = seen.minOf { it.sighting.receivedAt },
        lastSeen = seen.maxOf { it.sighting.receivedAt }
    )

    /**
     * The most frequent usable phrase in the group, or null when no phrase was usable.
     *
     * Modal rather than "the first" or "the newest", because a domain that sends one
     * `Example Store Offers` mail among forty `Example Store` receipts must still be called
     * Example Store. Ties go to the phrase seen most recently — a rebrand should beat an
     * equally frequent old name — and then to the lexicographically smaller phrase, which is
     * what makes the answer independent of the order the sightings arrived in.
     *
     * Two spellings of one name compete as two candidates. Folding case would have to pick a
     * canonical casing, and `bigbasket` cannot be turned back into `BigBasket`: the whole
     * reason the phrase is carried out of the header is that the sender's own casing is the
     * only authority for it.
     */
    private fun modalName(seen: List<Seen>): String? {
        val tallies = HashMap<String, Tally>()
        for (row in seen) {
            val name = SenderDisplayName.fromPhrase(row.sighting.displayPhrase, row.address) ?: continue
            val at = row.sighting.receivedAt
            val running = tallies[name]
            tallies[name] = Tally(
                count = (running?.count ?: 0) + 1,
                latest = if (running == null || at.isAfter(running.latest)) at else running.latest
            )
        }
        return tallies.entries
            .sortedWith(
                compareByDescending<Map.Entry<String, Tally>> { it.value.count }
                    .thenByDescending { it.value.latest }
                    .thenBy { it.key }
            )
            .firstOrNull()
            ?.key
    }

    /** How often one candidate name was sent, and the most recent time it was. */
    private data class Tally(val count: Int, val latest: Instant)
}
