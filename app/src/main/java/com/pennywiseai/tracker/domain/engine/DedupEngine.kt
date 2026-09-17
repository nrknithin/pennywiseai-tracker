package com.pennywiseai.tracker.domain.engine

import com.pennywiseai.parser.core.text.Cleaners
import com.pennywiseai.tracker.data.database.entity.MatchRule
import com.pennywiseai.tracker.data.database.entity.SourceChannel
import com.pennywiseai.tracker.data.database.entity.TransactionType
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Present" for the string fields of this file: absent, empty and whitespace are one state.
 *
 * File-private so that [MessageIdentity] and [DedupEngine] cannot drift apart on what counts as a
 * value — the engine compares fields that both of them read.
 */
private fun String?.orNullIfBlank(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

/**
 * An own account from `settings.accounts.own` (spec 14 §14.5).
 *
 * Drives L5 and, upstream of this engine, the INCOME-vs-TRANSFER decision: money arriving from
 * an account the user also owns is a transfer, not income, so moving savings between two of the
 * owner's banks never inflates the month's income.
 */
data class OwnAccount(
    val bank: String,
    val last4: String,
    val vpa: String? = null
)

/**
 * Every tunable of spec 14, with the defaults of spec 28 §28.9 (`settings.dedup.*`,
 * `settings.email.*`).
 *
 * Passed in on every call rather than read from a store, because the engine is pure: the worker
 * builds one of these per run and every message in that run is judged by the same rules.
 */
data class DedupConfig(
    /** Bank-vs-bank amount tolerance. Zero by default: two bank alerts for one charge agree exactly. */
    val amountToleranceAbs: BigDecimal = BigDecimal.ZERO,
    /** Gateway-vs-bank tolerance, to absorb the rounding a payment gateway applies. */
    val gatewayToleranceAbs: BigDecimal = BigDecimal.ONE,
    val alertWindowMinutes: Int = 30,
    val sameDayFallback: Boolean = true,
    val gatewayWindowHours: Int = 48,
    val transferWindowMinutes: Int = 10,
    val merchantSimilarity: Double = 0.85,
    val upiRefWindowHours: Int = 24,
    val ownAccounts: List<OwnAccount> = emptyList()
)

/**
 * Which way the money moved, for the records whose [TransactionType] cannot say.
 *
 * Spec 14 §14.5 types *both* legs of an own-account transfer TRANSFER — the credit side is
 * TRANSFER rather than INCOME precisely so that moving savings between two of the owner's banks
 * never counts as income. That makes the type useless for telling the two legs apart, so the leg
 * is carried alongside it.
 */
enum class MoneyDirection {
    /** Money arrived in this record's account: an INCOME, or the credit leg of a transfer. */
    IN,

    /** Money left it: every other type, and the debit leg of a transfer. */
    OUT
}

/**
 * Which message this is, independently of the words inside it (spec 14 §14.3 L0).
 *
 * L0 exists for exactly one case: the **same** message arriving twice — Gmail hands a mail back
 * after a UIDVALIDITY reset, an SMS restore replays the inbox. Body text alone cannot establish
 * that. `BodyNormaliser` keeps digits so that most bodies differ between two charges, but a
 * template with no date, no reference and a fixed amount ("Rs 49.00 debited for your
 * subscription") produces byte-identical bodies for two genuinely separate debits, and merging
 * those would delete real money from the user's history.
 *
 * What separates a re-delivery from a second charge is identity: a re-delivery keeps the sender,
 * the Message-ID and the timestamp the message was first stamped with; a second charge does not.
 * Every field is nullable because a channel may not have one, and an identity that cannot be
 * established is treated as "not the same message" rather than as a match.
 */
data class MessageIdentity(
    /** Normalised sender: the lower-case from-address for email, the sender id for SMS. */
    val sender: String? = null,
    /**
     * RFC 5322 Message-ID (`email_messages.message_id`). It survives a UIDVALIDITY reset, which is
     * why spec 03 §3.2 calls it the second dedup key; null on channels that have none.
     */
    val messageId: String? = null,
    /**
     * The message's own timestamp — IMAP `INTERNALDATE`, the SMS `date` column — and never a date
     * read out of the body. A re-delivered mail keeps its INTERNALDATE and a restored SMS keeps
     * its `date`, so this is stable across exactly the deliveries L0 is meant to catch.
     */
    val receivedAt: LocalDateTime? = null
) {

    /**
     * True when [other] identifies the same message as this one.
     *
     * Two keys, tried in strength order. A Message-ID known on both sides settles it outright, in
     * either direction: equal ids are one message, different ids are two. Failing that, a message
     * is identified by who sent it and when it was stamped — a pair that a second charge from the
     * same template cannot reproduce, because it arrives at a different time. Anything less
     * (no sender, no timestamp, no identity at all) is not enough to claim two records are one
     * message, so it is false.
     */
    fun isSameMessageAs(other: MessageIdentity?): Boolean {
        if (other == null) return false

        val id = messageId.orNullIfBlank()
        val otherId = other.messageId.orNullIfBlank()
        if (id != null && otherId != null) return id.equals(otherId, ignoreCase = true)

        val from = sender.orNullIfBlank() ?: return false
        val otherFrom = other.sender.orNullIfBlank() ?: return false
        if (!from.equals(otherFrom, ignoreCase = true)) return false

        val stampedAt = receivedAt ?: return false
        return stampedAt == other.receivedAt
    }
}

/**
 * One source already absorbed by a candidate: which channel and template it came through, the
 * body it carried, and which message it was.
 *
 * The body hash lives here rather than in a bare set on [DedupCandidate] on purpose: L0 may only
 * merge a body hash it can attribute to a *specific* earlier message, so the type makes a naked
 * hash — a hash with no source identity attached — impossible to hand the engine.
 */
data class SourceFingerprint(
    val channel: SourceChannel,
    val parserId: String?,
    /** `transaction_sources.body_hash`. Null or blank when the message had no usable body. */
    val bodyHash: String? = null,
    /** `transaction_sources.sender` + `received_at`, and the Message-ID when the caller joins it. */
    val identity: MessageIdentity? = null
)

/**
 * The incoming record, normalised per spec 14 §14.2.
 *
 * Merchants arrive already `Cleaners.cleanMerchant`ed; references arrive digits-only. The engine
 * re-normalises anyway (cleaning is idempotent) because the *candidate* side comes straight out
 * of Room, where merchant names are stored as the parser found them.
 */
data class DedupInput(
    val amount: BigDecimal,
    val currency: String,
    val type: TransactionType,
    val bankName: String?,
    val accountLast4: String?,
    /** Digits only, or null. */
    val reference: String?,
    val merchant: String?,
    /** The transaction date from inside the body when the parser found one, else the message timestamp. */
    val occurredAt: LocalDateTime,
    val bodyHash: String,
    val transactionHash: String?,
    val channel: SourceChannel,
    /** `EmailTransactionParser.id` / bank SMS parser id. Null when unknown — see [DedupCandidate.sameTemplateSourceCount]. */
    val parserId: String?,
    /**
     * True when the sender is a bank or card issuer, i.e. `SenderMapping.smsAlias != null`.
     * False for CRED, Razorpay, Cashfree, Amazon Pay, PGSharp, Google Play, merchants — the
     * senders L4 is about.
     */
    val isBankSender: Boolean,
    /** Gateway/merchant order or payment id (`pay_…`, an order number) for L4. */
    val orderId: String? = null,
    val isFromCard: Boolean = false,
    val fromAccount: String? = null,
    /**
     * Which leg of the movement this is, when [type] cannot say (spec 14 §14.5).
     *
     * Required on the credit leg of an own-account transfer: the ingestor re-types such a credit
     * from INCOME to TRANSFER, and setting this to [MoneyDirection.IN] at the same moment is what
     * keeps L5 able to see a debit and a credit rather than two debits. Null means "read the leg
     * off [type]", which is right for every record whose type is not TRANSFER.
     */
    val direction: MoneyDirection? = null,
    /**
     * Who sent this message and which copy of it it is, for L0 (spec 14 §14.3).
     *
     * Null disables L0's body-hash branch for this record — an unidentifiable message cannot be
     * shown to be a re-delivery, so it falls through to the layers that judge it on its content.
     */
    val identity: MessageIdentity? = null
)

/**
 * One existing transaction that could be the same event, with the facts the layers need.
 *
 * Built by the ingestor from a narrow DAO query — the engine itself never touches Room
 * (spec 02 §2.3 rule 4).
 */
data class DedupCandidate(
    val transactionId: Long,
    val amount: BigDecimal,
    val currency: String,
    val type: TransactionType,
    val bankName: String?,
    val accountLast4: String?,
    val reference: String?,
    val merchant: String?,
    val occurredAt: LocalDateTime,
    val transactionHash: String?,
    /**
     * Every source already on this transaction: its channel, its template, the body it carried
     * and which message it was. L0 reads the body hashes from here, which is what ties each one
     * to the message that delivered it.
     */
    val sources: List<SourceFingerprint>,
    /**
     * `TransactionSourceDao.countSameTemplate(transactionId, incoming.channel, incoming.parserId)`.
     *
     * This is the false-merge guard of spec 14 §14.3 in its resolved form. The spec says "email
     * never twins another email", but HDFC sends H7 ("charge without OTP") and H8 ("e-mandate
     * paid") for one charge and spec 10 §10.8 item 4 requires them to collapse. Same channel AND
     * same template is a duplicate; same channel, different template is that twin pair.
     *
     * When `incoming.parserId` is null the ingestor fills this from `countSameChannel` instead —
     * unable to tell templates apart, it falls back to the spec's stricter wording rather than
     * risk a false merge.
     */
    val sameTemplateSourceCount: Int,
    /** True when this transaction was created from a non-bank sender; the bank's later alert takes over as primary. */
    val isGatewayOrigin: Boolean = false,
    /**
     * Which leg this stored transaction is, when its type cannot say (spec 14 §14.5).
     *
     * Null is read as [MoneyDirection.OUT] for a TRANSFER, because that is how this app stores
     * one: the row sits on the account the money left, with `from_account`/`to_account` naming
     * the two sides. A transfer whose stored row is the credit side must say so here, or L5 sees
     * two debits and refuses to pair them.
     */
    val direction: MoneyDirection? = null
)

/** What the engine decided, and under which rule (recorded on `transaction_sources.match_rule`). */
sealed interface DedupDecision {

    /** Attach the incoming message to [transactionId] as a source; enrich per spec 14 §14.4. */
    data class Link(
        val transactionId: Long,
        val rule: MatchRule,
        /**
         * True when the incoming record is the bank's own alert and the existing transaction was
         * gateway-origin: the bank source becomes `is_primary` (spec 10 §10.8 item 5 — the bank
         * alert wins for account and balance, the receipt keeps winning for merchant and items).
         */
        val promoteIncomingToPrimary: Boolean = false
    ) : DedupDecision

    /**
     * L4: a gateway/merchant message that must NOT become a transaction, either because it
     * matched a bank charge ([transactionId] non-null) or because it names no instrument to
     * debit ([transactionId] null → logged `GATEWAY_LINK_ONLY`, picked up by a receipt in M5).
     *
     * [evaluate] only ever returns the second shape. A gateway receipt that *did* match comes
     * back as a [Link] carrying [MatchRule.GATEWAY_ORDER], because §14.3 requires every hit to
     * record its rule and this type has nowhere to put one; the ingestor reads that rule to
     * decide between the `DUPLICATE:<id>` and `GATEWAY_LINK_ONLY` skip reasons. Either way the
     * message never creates a transaction — see [mayCreateTransaction].
     */
    data class LinkOnly(val transactionId: Long?, val reason: String) : DedupDecision

    data object NewTransaction : DedupDecision
}

/**
 * The fields a merge may copy onto the existing transaction (spec 14 §14.4).
 *
 * Only ever *additive*: a field appears here when the existing transaction has nothing in it and
 * the incoming record does. Category is deliberately absent — §14.4 says a merge never changes
 * it, and leaving it out of the type makes that impossible to get wrong.
 */
data class Enrichment(
    val reference: String? = null,
    val merchant: String? = null,
    val accountLast4: String? = null
) {
    /** True when the merge has nothing to copy, so the caller can skip the UPDATE entirely. */
    val isEmpty: Boolean
        get() = reference == null && merchant == null && accountLast4 == null
}

/**
 * The six layers of spec 14 §14.3, in order, first hit wins.
 *
 * Pure Kotlin: no Room, no Android, and no `now()` except through the injected [clock]
 * (spec 02 §2.3 rule 4). It is handed the candidate transactions and the fingerprints of the
 * sources already attached to them, and returns a decision; every query that produced those
 * candidates belongs to `EmailTransactionIngestor`.
 *
 * Three guards decide whether this engine is correct, and each is stated once, as a named
 * function, so a reviewer can check them without reading the layers:
 *
 *  - [conflictingReference] — two genuine charges of the same amount on the same day with
 *    different references must never merge.
 *  - [blocksSameChannelMerge] — a transaction that already absorbed a message from the same
 *    channel *and* the same template cannot absorb another one; that is a second charge.
 *  - [isRedelivery] — L0 runs with no window and no other guard, so it may only fire for a
 *    message that is provably the *same message* the transaction already holds, never for a
 *    second message that merely reads the same.
 */
@Singleton
class DedupEngine @Inject constructor(
    private val clock: Clock
) {

    /** A layer: the rule it records when it fires, and the predicate that decides one candidate. */
    private data class Layer(
        val rule: MatchRule,
        val matches: (DedupInput, DedupCandidate, DedupConfig) -> Boolean
    )

    private val layers = listOf(
        Layer(MatchRule.HASH, ::matchesHash),
        Layer(MatchRule.UPI_REF, ::matchesUpiRef),
        Layer(MatchRule.AMOUNT_WINDOW, ::matchesAmountWindow),
        Layer(MatchRule.SAME_DAY, ::matchesSameDay),
        Layer(MatchRule.GATEWAY_ORDER, ::matchesGatewayOrder),
        Layer(MatchRule.TRANSFER_LINK, ::matchesTransferLink)
    )

    /**
     * The engine's only source of wall-clock time.
     *
     * Nothing in spec 14's six layers needs "now" — every window is measured between the two
     * records being compared, which is what lets the whole engine be tested with fixed
     * timestamps. It is exposed anyway so that the rules which *will* need it (§14.6's
     * never-merge pairs, any staleness cap) have somewhere to read it from other than
     * `LocalDateTime.now()`, which would quietly break spec 02 §2.3 rule 4.
     */
    fun now(): LocalDateTime = LocalDateTime.now(clock)

    /**
     * Decides whether [incoming] describes an event one of [candidates] already records.
     *
     * Layers are tried in spec order and the first one that fires wins. Within a layer, when
     * several candidates match, the one nearest in time wins (ties broken by the lower
     * transaction id, so the result never depends on query order).
     *
     * A [DedupDecision.NewTransaction] is only returned when [mayCreateTransaction] also allows
     * it; otherwise a gateway message that matched nothing becomes [DedupDecision.LinkOnly] with
     * no transaction, which the ingestor logs as [GATEWAY_LINK_ONLY] and M5 picks up as a receipt.
     */
    fun evaluate(
        incoming: DedupInput,
        candidates: List<DedupCandidate>,
        config: DedupConfig = DedupConfig()
    ): DedupDecision {
        for (layer in layers) {
            val hit = candidates
                .filter { layer.matches(incoming, it, config) }
                .minWithOrNull(nearestTo(incoming))
                ?: continue
            return DedupDecision.Link(
                transactionId = hit.transactionId,
                rule = layer.rule,
                // Spec 10 §10.8 item 5: once the bank itself reports the charge, its alert is the
                // authority for account and balance, so it takes the primary slot from the receipt.
                promoteIncomingToPrimary = incoming.isBankSender && hit.isGatewayOrigin
            )
        }

        return if (mayCreateTransaction(incoming, DedupDecision.NewTransaction)) {
            DedupDecision.NewTransaction
        } else {
            DedupDecision.LinkOnly(transactionId = null, reason = GATEWAY_LINK_ONLY)
        }
    }

    /**
     * The gateway rule, stated as a predicate so it can be tested on its own and quoted in review.
     *
     * A non-bank message may create a transaction **iff all three hold**:
     *  1. a `:parser-core` transaction parser claimed it — implied by a [DedupInput] existing at
     *     all, since the ingestor only builds one from a `ParsedTransaction`. Razorpay/Cashfree/
     *     Amazon have no such parser yet and land as receipts in M5;
     *  2. no candidate matched at L0–L5 inside the gateway window, i.e. [matched] is
     *     [DedupDecision.NewTransaction];
     *  3. it names the instrument that was debited:
     *     `accountLast4 != null || isFromCard || fromAccount != null`.
     *
     * This is the reading that satisfies both spec 14 L4 ("never creates a transaction") and
     * spec 12 (RZ1/CS1/C2/P2 → transactions): L4 means never create a *second* transaction for
     * money a bank already reported. A foreign-currency charge on a card no tracked bank reports
     * is still recorded rather than lost (constraint C7 — never invent, but never discard either).
     */
    fun mayCreateTransaction(incoming: DedupInput, matched: DedupDecision): Boolean {
        // A link of any kind is already the answer; only an unmatched record can create anything.
        if (matched !is DedupDecision.NewTransaction) return false
        // A bank's own alert is the money's primary record by definition.
        if (incoming.isBankSender) return true
        return incoming.accountLast4 != null || incoming.isFromCard || incoming.fromAccount != null
    }

    /**
     * Which layer — if any — joins [incoming] to this one [candidate].
     *
     * Exposed for tests and for the "why were these merged?" row on screen 45 (S45.E80).
     */
    fun explain(incoming: DedupInput, candidate: DedupCandidate, config: DedupConfig): MatchRule? =
        layers.firstOrNull { it.matches(incoming, candidate, config) }?.rule

    /**
     * Spec 14 §14.4: what a merge may copy onto the existing transaction.
     *
     * Copies only into holes. The one exception the spec calls out is the merchant name: a stored
     * VPA is a placeholder for a name nobody has yet, so a later message that carries a real name
     * replaces it. Nothing else is ever overwritten, and category is never touched.
     */
    fun enrich(incoming: DedupInput, candidate: DedupCandidate): Enrichment {
        val incomingRef = digitsOnly(incoming.reference)
        val incomingMerchant = trimmed(incoming.merchant)
        val incomingLast4 = trimmed(incoming.accountLast4)

        val merchantUpgrade = incomingMerchant != null &&
            (
                candidate.merchant.isNullOrBlank() ||
                    (looksLikeVpa(candidate.merchant) && !looksLikeVpa(incomingMerchant))
                )

        return Enrichment(
            reference = incomingRef.takeIf { digitsOnly(candidate.reference) == null },
            merchant = incomingMerchant.takeIf { merchantUpgrade },
            accountLast4 = incomingLast4.takeIf { trimmed(candidate.accountLast4) == null }
        )
    }

    /**
     * Spec 14 §14.4 in one line, for the fields this engine never sees.
     *
     * `balance_after` and `description` live on `ParsedTransaction`, not on [DedupInput], so the
     * ingestor applies the rule itself — through this, so there is still only one statement of it.
     */
    fun <T> fill(existing: T?, incoming: T?): T? = existing ?: incoming

    // ---------------------------------------------------------------------------------------
    // Layers
    // ---------------------------------------------------------------------------------------

    /**
     * **L0 HASH** — the same message, delivered twice. Window ∞.
     *
     * Deliberately skips the intra-channel and reference guards: an exact re-delivery *is* a
     * second message from the same template, so applying the intra-channel guard here would turn
     * every re-delivery into a duplicate transaction, which is the opposite of what the guard is
     * for. That is also why it may not merge on body text alone — see [isRedelivery].
     *
     * The `transaction_hash` branch needs no such constraint. That column is `transactions`'
     * unique index (and upstream's own duplicate key), so a hash equal to a stored one can only
     * ever belong to that row; there is no second transaction for it to wrongly absorb.
     */
    private fun matchesHash(
        incoming: DedupInput,
        candidate: DedupCandidate,
        config: DedupConfig
    ): Boolean {
        if (!sameCurrency(incoming, candidate)) return false
        val byTransactionHash = !incoming.transactionHash.isNullOrBlank() &&
            incoming.transactionHash == candidate.transactionHash
        return byTransactionHash || isRedelivery(incoming, candidate)
    }

    /**
     * L0's body branch: this exact message is already on this transaction.
     *
     * Equal body text is **not** on its own evidence of a duplicate. A template that prints no
     * date, no reference and an unchanging amount — a fixed subscription debit, a wallet top-up —
     * hashes identically for two genuinely separate charges, and with L0's infinite window and no
     * other guard those two charges would collapse into one and half the money would vanish.
     * Spec 14 §14.3 scopes L0 to "exact re-delivery (Gmail duplicate, SMS restore)", so the match
     * requires the source identity to agree as well: the same channel, and a
     * [MessageIdentity.isSameMessageAs] on the source that carried that body.
     *
     * The template id is deliberately *not* compared. A mail re-delivered months later may be
     * claimed by a parser the app did not have the first time, and refusing the merge then would
     * produce the duplicate this layer exists to prevent.
     */
    private fun isRedelivery(incoming: DedupInput, candidate: DedupCandidate): Boolean {
        val bodyHash = incoming.bodyHash.orNullIfBlank() ?: return false
        val identity = incoming.identity ?: return false
        return candidate.sources.any { source ->
            source.channel == incoming.channel &&
                source.bodyHash.orNullIfBlank() == bodyHash &&
                identity.isSameMessageAs(source.identity)
        }
    }

    /**
     * **L1 UPI_REF** — same reference, same amount, same direction, within `upiRefWindowHours`.
     *
     * No intra-channel guard: two genuine charges do not share a bank reference, so equal refs are
     * stronger evidence than the channel is. Opposite directions fall through to L5, which is what
     * turns a debit and its matching credit into one transfer instead of two halves of nothing.
     */
    private fun matchesUpiRef(
        incoming: DedupInput,
        candidate: DedupCandidate,
        config: DedupConfig
    ): Boolean {
        val ref = digitsOnly(incoming.reference) ?: return false
        val candidateRef = digitsOnly(candidate.reference) ?: return false
        if (ref.length < MIN_REFERENCE_DIGITS || ref != candidateRef) return false
        if (!sameCurrency(incoming, candidate)) return false
        if (directionOf(incoming) != directionOf(candidate)) return false
        if (!amountsMatch(incoming, candidate, config)) return false
        return within(incoming, candidate, Duration.ofHours(config.upiRefWindowHours.toLong()))
    }

    /**
     * **L2 AMOUNT_WINDOW** — same amount, bank, account last-4 and direction inside
     * `alertWindowMinutes`. The SMS-plus-email pair for one card purchase, ATM withdrawal or EMI.
     *
     * Both last-4s must be present: §14.2 allows a null account to match anything only at L3.
     */
    private fun matchesAmountWindow(
        incoming: DedupInput,
        candidate: DedupCandidate,
        config: DedupConfig
    ): Boolean {
        if (blocksMerge(incoming, candidate)) return false
        if (conflictingReference(incoming, candidate)) return false
        if (!sameCurrency(incoming, candidate)) return false
        if (directionOf(incoming) != directionOf(candidate)) return false
        if (!sameBank(incoming, candidate)) return false

        val last4 = trimmed(incoming.accountLast4) ?: return false
        val candidateLast4 = trimmed(candidate.accountLast4) ?: return false
        if (!last4.equals(candidateLast4, ignoreCase = true)) return false

        if (!amountsMatch(incoming, candidate, config)) return false
        return within(incoming, candidate, Duration.ofMinutes(config.alertWindowMinutes.toLong()))
    }

    /**
     * **L3 SAME_DAY** — the delayed-email fallback: same amount, bank, direction and calendar day,
     * with merchants that agree (or one of them unknown). Only runs when `sameDayFallback` is on.
     *
     * This is the widest layer, so it leans hardest on the two guards: without them it would merge
     * two identical-looking charges at the same shop on the same day, which is a thing people do.
     */
    private fun matchesSameDay(
        incoming: DedupInput,
        candidate: DedupCandidate,
        config: DedupConfig
    ): Boolean {
        if (!config.sameDayFallback) return false
        if (blocksMerge(incoming, candidate)) return false
        if (conflictingReference(incoming, candidate)) return false
        if (!sameCurrency(incoming, candidate)) return false
        if (directionOf(incoming) != directionOf(candidate)) return false
        if (!sameBank(incoming, candidate)) return false

        // §14.2: a null last-4 matches anything here, but two different ones are two instruments.
        val last4 = trimmed(incoming.accountLast4)
        val candidateLast4 = trimmed(candidate.accountLast4)
        if (last4 != null && candidateLast4 != null && !last4.equals(candidateLast4, ignoreCase = true)) {
            return false
        }

        if (!amountsMatch(incoming, candidate, config)) return false
        if (incoming.occurredAt.toLocalDate() != candidate.occurredAt.toLocalDate()) return false
        return merchantsAgree(incoming.merchant, candidate.merchant, config.merchantSimilarity)
    }

    /**
     * **L4 GATEWAY_ORDER** — a gateway or merchant receipt matched to a bank charge within
     * `gatewayWindowHours`, by order id or by amount plus merchant.
     *
     * Only non-bank senders reach this layer; a bank alert is never "a receipt for" anything.
     * Amount is required on both branches: an order id that matches a charge of a *different*
     * amount is a partial capture or a second charge on the same order, and linking those would
     * hide real money. Unlike L3, an unknown merchant is not a pass — a gateway receipt carries no
     * account number, so amount alone inside 48 h is far too weak.
     */
    private fun matchesGatewayOrder(
        incoming: DedupInput,
        candidate: DedupCandidate,
        config: DedupConfig
    ): Boolean {
        if (incoming.isBankSender) return false
        if (blocksMerge(incoming, candidate)) return false
        if (conflictingReference(incoming, candidate)) return false
        if (!sameCurrency(incoming, candidate)) return false
        if (directionOf(incoming) != directionOf(candidate)) return false
        if (!amountsMatch(incoming, candidate, config)) return false
        if (!within(incoming, candidate, Duration.ofHours(config.gatewayWindowHours.toLong()))) return false

        val orderId = token(incoming.orderId)
        val candidateRef = token(candidate.reference)
        if (orderId != null && candidateRef != null && orderId == candidateRef) return true

        val merchant = cleanMerchant(incoming.merchant) ?: return false
        val candidateMerchant = cleanMerchant(candidate.merchant) ?: return false
        return JaroWinkler.similarity(merchant, candidateMerchant) >= config.merchantSimilarity
    }

    /**
     * **L5 TRANSFER_LINK** — the debit in one own account and the credit in the other: same
     * amount, opposite directions, joined either by a shared reference or, when both sides are
     * own accounts, by landing inside `transferWindowMinutes`.
     *
     * "Opposite directions" is read from [DedupInput.direction] / [DedupCandidate.direction] and
     * only then from the type, because §14.5 has already re-typed the credit leg of an own-account
     * transfer from INCOME to TRANSFER by the time this layer sees it. Both legs then carry the
     * same type, and a leg that does not say which one it is looks exactly like the other.
     *
     * The two sides must be different instruments. A credit and a debit of the same amount on the
     * *same* account with the same reference is a reversal or a refund — a second, real movement
     * of money — and absorbing it into the original charge would silently delete it.
     *
     * The reference branch is bounded by `upiRefWindowHours` rather than left open: bank
     * references are recycled eventually, and the two legs of a transfer never arrive a day apart.
     */
    private fun matchesTransferLink(
        incoming: DedupInput,
        candidate: DedupCandidate,
        config: DedupConfig
    ): Boolean {
        if (!sameCurrency(incoming, candidate)) return false
        if (directionOf(incoming) == directionOf(candidate)) return false
        if (!amountsMatch(incoming, candidate, config)) return false
        if (sameInstrument(incoming, candidate)) return false

        val ref = digitsOnly(incoming.reference)
        val candidateRef = digitsOnly(candidate.reference)
        val joinedByRef = ref != null && candidateRef != null &&
            ref.length >= MIN_REFERENCE_DIGITS && ref == candidateRef
        if (joinedByRef) {
            return within(incoming, candidate, Duration.ofHours(config.upiRefWindowHours.toLong()))
        }

        if (conflictingReference(incoming, candidate)) return false
        if (!isOwnSide(incoming, config) || !isOwnSide(candidate, config)) return false
        return within(incoming, candidate, Duration.ofMinutes(config.transferWindowMinutes.toLong()))
    }

    // ---------------------------------------------------------------------------------------
    // §14.2 normalisation and the shared predicates
    // ---------------------------------------------------------------------------------------

    /**
     * The intra-channel guard, plus a belt-and-braces re-derivation of it.
     *
     * [blocksSameChannelMerge] reads the count the ingestor obtained from
     * `TransactionSourceDao.countSameTemplate`; the second half re-derives the same answer from
     * the fingerprints the candidate carries, so a caller that forgot to fill the count in still
     * cannot produce a false merge.
     */
    private fun blocksMerge(incoming: DedupInput, candidate: DedupCandidate): Boolean {
        if (blocksSameChannelMerge(candidate)) return true
        val parserId = incoming.parserId ?: return false
        return candidate.sources.any { it.channel == incoming.channel && it.parserId == parserId }
    }

    /**
     * Spec 14 says "same type"; this compares direction instead.
     *
     * One card charge really is typed EXPENSE by one parser, CREDIT by another and TRANSFER by a
     * third (a card bill paid from savings), so requiring identical [TransactionType] would split
     * every twin pair in §14.1. Direction is the part that cannot differ for one event: money
     * either left the account or arrived in it.
     *
     * The type alone cannot carry it, though. §14.5 types the credit leg of an own-account
     * transfer TRANSFER rather than INCOME, so reading the leg off the type would call both legs
     * of one transfer "out" — L1 would then merge them on their shared reference and one leg of
     * the movement would be gone, while L5, which wants opposite legs, would never fire. When a
     * record knows which leg it is, it says so, and that wins over the type.
     */
    private fun directionOf(incoming: DedupInput): MoneyDirection =
        incoming.direction ?: directionOf(incoming.type)

    private fun directionOf(candidate: DedupCandidate): MoneyDirection =
        candidate.direction ?: directionOf(candidate.type)

    private fun directionOf(type: TransactionType): MoneyDirection =
        if (type == TransactionType.INCOME) MoneyDirection.IN else MoneyDirection.OUT

    /**
     * Currency is never crossed (upstream `CLAUDE.md` constraints 1 and 2): a USD receipt and an
     * INR charge are not the same record even when a conversion would line them up.
     *
     * An unknown currency does not compare equal to a known one. Treating blank as a wildcard
     * would merge a record whose parser left the field empty into an INR transaction and thereby
     * *assign* it a currency nobody read off a message — inventing the one value that decides
     * which total a rupee belongs to (spec 01 constraint C7). Two blanks still match: neither
     * record claims a currency, so nothing is being assigned to either.
     */
    private fun sameCurrency(incoming: DedupInput, candidate: DedupCandidate): Boolean {
        val a = incoming.currency.orNullIfBlank()
        val b = candidate.currency.orNullIfBlank()
        if (a == null || b == null) return a == null && b == null
        return a.equals(b, ignoreCase = true)
    }

    /**
     * §14.2's amount tolerance: gateway-vs-bank gets the looser one, bank-vs-bank must agree to
     * the paisa.
     *
     * "Gateway-vs-bank" is a property of the *pair*, not of the incoming record. Spec 10 §10.8
     * item 5 has the bank's alert arrive second and take the primary slot from a receipt that was
     * recorded first, and it is that receipt which did the rounding — so a rule that read only
     * `incoming.isBankSender` would apply the zero tolerance to exactly the case the loose one
     * exists for, leave the rounded gateway transaction unmatched, and double-count the payment.
     */
    private fun amountsMatch(
        incoming: DedupInput,
        candidate: DedupCandidate,
        config: DedupConfig
    ): Boolean {
        val gatewayInvolved = !incoming.isBankSender || candidate.isGatewayOrigin
        val tolerance = if (gatewayInvolved) config.gatewayToleranceAbs else config.amountToleranceAbs
        return (incoming.amount - candidate.amount).abs() <= tolerance.abs()
    }

    private fun sameBank(incoming: DedupInput, candidate: DedupCandidate): Boolean {
        val bank = canonicalBank(incoming.bankName) ?: return false
        return bank == canonicalBank(candidate.bankName)
    }

    /**
     * Whether two records may be the same instrument.
     *
     * Being *different* instruments has to be established positively — different banks, or the
     * same bank and two different last-4s. Anything less counts as the same instrument, because
     * the only caller is L5 and the alternative is worse: a credit and a debit of the same amount
     * with the same reference, where one side did not name its bank, would be turned into a
     * transfer and one leg of it deleted, when it was a refund all along. The cost of being
     * strict here is a transfer left as two transactions, which the user can see and merge by
     * hand (§14.6); the cost of being loose is money that silently disappears.
     */
    private fun sameInstrument(incoming: DedupInput, candidate: DedupCandidate): Boolean {
        val bank = canonicalBank(incoming.bankName)
        val candidateBank = canonicalBank(candidate.bankName)
        val last4 = trimmed(incoming.accountLast4)
        val candidateLast4 = trimmed(candidate.accountLast4)

        val differentBanks = bank != null && candidateBank != null && bank != candidateBank
        val differentAccounts = last4 != null && candidateLast4 != null &&
            !last4.equals(candidateLast4, ignoreCase = true)

        return !(differentBanks || differentAccounts)
    }

    /** §14.3 L3: merchants match when they are similar enough, or when either side never knew one. */
    private fun merchantsAgree(incoming: String?, candidate: String?, threshold: Double): Boolean {
        val a = cleanMerchant(incoming) ?: return true
        val b = cleanMerchant(candidate) ?: return true
        return JaroWinkler.similarity(a, b) >= threshold
    }

    private fun isOwnSide(incoming: DedupInput, config: DedupConfig): Boolean =
        isOwnAccount(incoming.bankName, incoming.accountLast4, config) ||
            isOwnVpa(incoming.fromAccount, config) ||
            isOwnVpa(incoming.merchant, config)

    private fun isOwnSide(candidate: DedupCandidate, config: DedupConfig): Boolean =
        isOwnAccount(candidate.bankName, candidate.accountLast4, config) ||
            isOwnVpa(candidate.merchant, config)

    private fun isOwnAccount(bank: String?, last4: String?, config: DedupConfig): Boolean {
        val digits = trimmed(last4) ?: return false
        val canonical = canonicalBank(bank)
        return config.ownAccounts.any { own ->
            own.last4.trim().equals(digits, ignoreCase = true) &&
                // A record that does not name its bank still matches on the last-4 alone: the
                // owner's own list is short, and refusing here would drop real transfers.
                (canonical == null || canonicalBank(own.bank) == null || canonicalBank(own.bank) == canonical)
        }
    }

    private fun isOwnVpa(value: String?, config: DedupConfig): Boolean {
        val needle = alphanumeric(value) ?: return false
        return config.ownAccounts.any { own -> alphanumeric(own.vpa) == needle }
    }

    /** Nearest in time wins inside a layer; the lower id breaks a tie so results never depend on query order. */
    private fun nearestTo(incoming: DedupInput): Comparator<DedupCandidate> =
        compareBy<DedupCandidate> { gap(incoming.occurredAt, it.occurredAt) }
            .thenBy { it.transactionId }

    private fun within(incoming: DedupInput, candidate: DedupCandidate, window: Duration): Boolean =
        gap(incoming.occurredAt, candidate.occurredAt) <= window

    /**
     * Distance between two timestamps, in either direction.
     *
     * Written out rather than `Duration.abs()`, which is a Java 18 method: this module compiles
     * against JVM 11 and runs on desugared `java.time` down to API 26.
     */
    private fun gap(a: LocalDateTime, b: LocalDateTime): Duration {
        val between = Duration.between(a, b)
        return if (between.isNegative) between.negated() else between
    }

    /**
     * §14.2's `cleanMerchant`, borrowed from `:parser-core`.
     *
     * One call site on purpose: spec 02 §2.1 forbids parsing regex in `:app`, and the comparison
     * has to use exactly the canonical form the parsers produce, or dedup would compare a cleaned
     * string against an uncleaned one and quietly stop matching.
     */
    private fun cleanMerchant(raw: String?): String? =
        Cleaners.cleanMerchant(raw)?.takeIf { it.isNotBlank() }

    /** A VPA still looks like one after storage; a real merchant name does not carry an `@`. */
    private fun looksLikeVpa(value: String?): Boolean = value != null && value.contains('@')

    private fun trimmed(value: String?): String? = value.orNullIfBlank()

    /** Order ids are compared case-insensitively and without separators (`pay_ab-12` == `PAY_AB12`). */
    private fun token(value: String?): String? = alphanumeric(value)

    private fun alphanumeric(value: String?): String? =
        value?.lowercase()?.filter { it.isLetterOrDigit() }?.takeIf { it.isNotEmpty() }

    /**
     * §14.2 "bank compared by canonical name". Both channels get their name from the registry, so
     * this only has to absorb spelling: case, spaces and a trailing "bank" ("HDFC" == "HDFC Bank").
     */
    private fun canonicalBank(raw: String?): String? {
        val squashed = alphanumeric(raw) ?: return null
        return squashed.removeSuffix("bank").takeIf { it.isNotEmpty() } ?: squashed
    }

    companion object {

        /**
         * Spec 14 §14.3 guard: L2, L3 and L4 refuse a candidate that already holds a source from
         * the same channel and template. See [DedupCandidate.sameTemplateSourceCount] for why the
         * comparison is channel *and* template rather than the spec's bare "channel".
         */
        fun blocksSameChannelMerge(candidate: DedupCandidate): Boolean =
            candidate.sameTemplateSourceCount > 0

        /**
         * Spec 14 §14.3 guard: two references that both exist and differ are two different charges.
         *
         * Only references long enough to identify a payment are compared — a three- or four-digit
         * "reference" is far more likely to be a truncated token or a card suffix a parser put in
         * the wrong field, and letting that block a merge would cost the user a duplicate. One
         * reference containing the other is not a conflict either: banks print the tail of an RRN
         * as often as the whole thing, and the two sides then agree as far as both can tell.
         */
        fun conflictingReference(incoming: DedupInput, candidate: DedupCandidate): Boolean {
            val a = digitsOnly(incoming.reference) ?: return false
            val b = digitsOnly(candidate.reference) ?: return false
            if (a.length < MIN_REFERENCE_DIGITS || b.length < MIN_REFERENCE_DIGITS) return false
            if (a == b) return false
            return !a.contains(b) && !b.contains(a)
        }

        /** §14.2: "reference = digits only". Null when the record carries no digits at all. */
        private fun digitsOnly(raw: String?): String? =
            raw?.filter { it.isDigit() }?.takeIf { it.isNotEmpty() }

        /**
         * Shortest run of digits that can stand for a payment. UPI RRNs are 12; IMPS and card
         * references run 9–16; anything shorter is not an identifier.
         */
        const val MIN_REFERENCE_DIGITS = 6

        /** `skipReason` for a gateway message that may not create a transaction (spec 11 §11.4). */
        const val GATEWAY_LINK_ONLY = "GATEWAY_LINK_ONLY"
    }
}
