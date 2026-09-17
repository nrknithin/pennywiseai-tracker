package com.pennywiseai.tracker.domain.engine

import com.pennywiseai.parser.core.email.EmailSenderRegistry
import com.pennywiseai.parser.core.email.due.DueEscalation
import com.pennywiseai.parser.core.email.due.ParsedDue
import com.pennywiseai.parser.core.md5Hex
import com.pennywiseai.tracker.data.database.entity.DueEntity
import com.pennywiseai.tracker.data.database.entity.DueKind
import com.pennywiseai.tracker.data.database.entity.DueSource
import com.pennywiseai.tracker.data.database.entity.DueSourceEntity
import com.pennywiseai.tracker.data.database.entity.DueStatus
import com.pennywiseai.tracker.data.database.entity.MergeRule
import com.pennywiseai.tracker.data.database.entity.NoticeKind
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import com.pennywiseai.parser.core.email.due.DueKind as ParserDueKind

/**
 * The `settings.dues.*` figures the merge reads (spec 28 §28.4).
 *
 * Passed per call rather than injected, for the same reason `DedupConfig` is: one sync run
 * judges every message by one copy of the rules, and a test can vary them without a container.
 */
data class DueMergeConfig(
    /** How far apart two readings of one bill may date it and still be that one bill. */
    val mergeDateToleranceDays: Long = 2,

    /**
     * How far a payment confirmation (§20.4 step 7) may sit from the bill it cleared.
     *
     * A separate figure because the two dates mean different things. `mergeDateToleranceDays`
     * compares two senders' idea of *the same due date*, so two days is generous. A payment
     * notice states the day the **money moved**, which is a different quantity entirely: a card
     * bill can be paid three weeks early or three weeks late and still be that bill. So a
     * confirmation is matched over §20.5's settlement span instead, taking its outer bound —
     * `settings.dues.latePayDays` (60) — symmetrically, with the nearest bill winning.
     */
    val paymentNoticeWindowDays: Long = 60
)

/**
 * The source-precedence ladder of spec 20 §20.4 step 4: **bank statement > bank reminder >
 * CRED > Amazon Pay > BillDesk**.
 *
 * A higher [rank] may set `amount`, `minimum_due` and `due_date`; a lower one may only fill
 * fields that are still null. That asymmetry is the whole point of the ladder — CRED's idea of
 * a bill is a copy, the issuer's statement is the bill, and the app must never let the copy
 * quietly replace the original (spec 12 H12 records a real case where two figures disagreed
 * and the wrong one was paid).
 *
 * The spec says "bank"; the fork says "issuer", because GoDaddy, PGSharp and Cloudflare bill
 * the user directly and hold the same authority over their own invoice that a bank holds over
 * its statement.
 */
enum class NoticePrecedence(val rank: Int) {
    /** The biller's own statement or invoice: the bill itself. */
    ISSUER_STATEMENT(4),

    /** The biller's own nudge about a bill it already sent — a reminder, or an overdue notice. */
    ISSUER_REMINDER(3),

    CRED(2),
    AMAZON_PAY(1),
    BILLDESK(0);

    /** True for the three senders that re-publish somebody else's bill (§20.4 step 5). */
    val isAggregator: Boolean
        get() = this == CRED || this == AMAZON_PAY || this == BILLDESK
}

/**
 * Spec 20 §20.4 — which `dues` row a freshly parsed notice belongs to, and what it is allowed
 * to change about it.
 *
 * Pure Kotlin, in the house style of [DedupEngine]: no Room, no Android, and no `now()` except
 * through the injected [clock] (spec 02 §2.3 rule 4). It is handed the rows a repository
 * fetched together with the `due_sources` already recorded against them, and returns either a
 * row to insert or an edited copy of one to update. Every query behind those rows belongs to
 * `DueRepository`, which owns no rule of its own.
 *
 * Five decisions are stated once here, each as a named member, because each is a place where
 * the app could otherwise lose or invent money:
 *
 *  - [dueId] — step 1's identity. Two readings of one bill must land on one row, and a row's
 *    identity never changes afterwards (see [merge]).
 *  - [chooseTarget] — steps 2 and 4: the candidate with the most sources wins, ties to the
 *    earliest created, and a row carrying the *same* [dueId] wins outright.
 *  - [merge] — step 4's precedence ladder, step 5's aggregator upgrade, and the rule that a
 *    settled due is immutable to notices (§20.7).
 *  - [escalationOf] — step 4's `escalation = max(current, notice)`, which at schema v63 has no
 *    column of its own; see the note on that member.
 *  - [Companion.noticeKindOf] — how the sender framed one notice, including step 7's payment
 *    confirmation, decided here rather than twice. A confirmation is inert against every figure
 *    on the row; what it adds is the `due_sources` entry spec 26's alert is computed from.
 */
@Singleton
class DueMergeEngine @Inject constructor(
    private val clock: Clock
) {

    /**
     * One row the repository found, with the notices already recorded against it.
     *
     * The sources are what make the ladder work: `due_sources` is the only record of *who*
     * said what, so the authority standing behind a due's current figures is read back off
     * them rather than cached on the due.
     */
    data class Candidate(
        val due: DueEntity,
        val sources: List<DueSourceEntity> = emptyList()
    ) {
        /** §20.4 step 4's "most sources": how many notices already describe this due. */
        val sourceCount: Int
            get() = sources.size
    }

    /** The date range step 2 looks in, inclusive at both ends. */
    data class DateWindow(val from: LocalDate, val to: LocalDate)

    /** What a merge did to one row. [changed] is false when the notice told us nothing new. */
    data class MergeOutcome(
        val due: DueEntity,
        val changed: Boolean,
        val mergeRule: MergeRule,
        /**
         * `max(current, notice)` over the escalation ladder, including the incoming notice
         * (§20.4 step 4). Null when no notice on this due claims any urgency.
         */
        val escalation: NoticeKind?
    )

    /** The engine's only source of wall-clock time. */
    fun now(): LocalDateTime = LocalDateTime.now(clock)

    // -----------------------------------------------------------------------------------------
    // Step 1 — identity
    // -----------------------------------------------------------------------------------------

    /**
     * `issuerKey(parsed.issuer)` of spec 20 §20.4.
     *
     * The spec calls for "the canonical issuer name via registry". That canonicalisation has
     * already happened by the time a [ParsedDue] exists — `EmailSenderRegistry` and each due
     * parser emit `ICICI Bank`, `GoDaddy`, `OneCard` and so on, and CRED's parser deliberately
     * names the card issuer rather than CRED (§20.4 step 5). What is left for the key is to
     * make the *spelling* irrelevant, so `AXIS BANK` and `Axis Bank` cannot become two dues.
     */
    fun issuerKey(issuer: String?): String =
        issuer?.filter { it.isLetterOrDigit() }?.lowercase().orEmpty()

    /**
     * `md5(issuerKey | kind | accountLast4 ?: reference ?: "" | dueDate | currency)` — spec 20
     * §20.4 step 1, and the `dues.due_id` contract in spec 03 §3.2.
     *
     * md5 rather than a stronger digest for the same reason `body_hash` uses it: this is an
     * identity key inside one device's database, not a security boundary, and `md5Hex` is the
     * one hash the fork already ships.
     */
    fun dueId(parsed: ParsedDue): String = md5Hex(
        listOf(
            issuerKey(parsed.issuer),
            storedKind(parsed.kind).name,
            parsed.accountLast4 ?: parsed.reference ?: "",
            parsed.dueDate.toString(),
            parsed.currency.trim().uppercase()
        ).joinToString("|")
    )

    /** Step 2's date window: the readings must date the bill within the tolerance of each other. */
    fun mergeWindow(parsed: ParsedDue, config: DueMergeConfig = DueMergeConfig()): DateWindow =
        toleranceDays(parsed, config).let { tolerance ->
            DateWindow(
                from = parsed.dueDate.minusDays(tolerance),
                to = parsed.dueDate.plusDays(tolerance)
            )
        }

    /**
     * How far the two dates may lie apart, which depends on what the incoming date *is*.
     *
     * For a bill it is another sender's reading of the same due date (±2). For a payment
     * confirmation it is the day the money moved, which §20.4 step 7 has to reconcile with a
     * due date it never states — see [DueMergeConfig.paymentNoticeWindowDays].
     */
    private fun toleranceDays(parsed: ParsedDue, config: DueMergeConfig): Long =
        if (parsed.paymentConfirmed) config.paymentNoticeWindowDays else config.mergeDateToleranceDays

    // -----------------------------------------------------------------------------------------
    // Steps 2 and 4 — which row this reading belongs to
    // -----------------------------------------------------------------------------------------

    /**
     * Step 2's filter: same issuer, same kind, same card **or** same reference, dated within
     * tolerance, and not already closed.
     *
     * Two readings of a due that names neither a card nor a reference can only meet through
     * [dueId], which is the first branch here and is deliberately exempt from the status
     * filter. Step 3 inserts with `due_id = md5(key)` into a **unique** column, so a row
     * carrying that exact key is this reading's row whatever state it is in; excluding it
     * because it had been auto-dismissed (R-20-09) would not create a second due, it would
     * abort the insert and lose the mail.
     */
    fun isCandidate(
        due: DueEntity,
        parsed: ParsedDue,
        config: DueMergeConfig = DueMergeConfig()
    ): Boolean {
        if (due.dueId.isNotEmpty() && due.dueId == dueId(parsed)) return true
        if (due.status !in MERGEABLE_STATUSES) return false
        if (due.kind != storedKind(parsed.kind)) return false
        if (issuerKey(due.issuer) != issuerKey(parsed.issuer)) return false
        // Currency is part of the key, and merging a $5 invoice into a ₹5 one would produce a
        // figure that means nothing (upstream CLAUDE.md constraint 2).
        if (!due.currency.equals(parsed.currency.trim(), ignoreCase = true)) return false
        if (!sameAccount(due, parsed) && !sameReference(due, parsed)) return false
        return abs(ChronoUnit.DAYS.between(due.dueDate, parsed.dueDate)) <=
            toleranceDays(parsed, config)
    }

    /**
     * Step 4: "the candidate with the most sources (ties to earliest created)".
     *
     * The row whose `due_id` *is* this reading's key skips the contest: it is the same bill by
     * definition, and no count of sources can make another row a better answer.
     *
     * A payment confirmation is ordered differently, and has to be. Step 4's contest assumes
     * every candidate is a reading of *one* bill, which the ±2-day window all but guarantees;
     * over the two-month span a payment is matched across (§20.4 step 7) two consecutive card
     * statements are both candidates, and "most sources" would cheerfully hand the payment to
     * last month's. The bill a payment cleared is the nearest one by date — the same tie-break
     * §20.5 settles a transaction with.
     */
    fun chooseTarget(
        candidates: List<Candidate>,
        parsed: ParsedDue,
        config: DueMergeConfig = DueMergeConfig()
    ): Candidate? {
        val key = dueId(parsed)
        candidates.firstOrNull { it.due.dueId == key }?.let { return it }
        val byAuthority = compareByDescending<Candidate> { it.sourceCount }
            .thenBy { it.due.createdAt }
            // Id last, so the answer never depends on the order rows came back in.
            .thenBy { it.due.id }
        val ordering =
            if (parsed.paymentConfirmed) {
                compareBy<Candidate> {
                    abs(ChronoUnit.DAYS.between(it.due.dueDate, parsed.dueDate))
                }.then(byAuthority)
            } else {
                byAuthority
            }
        return candidates
            .filter { isCandidate(it.due, parsed, config) }
            .sortedWith(ordering)
            .firstOrNull()
    }

    // -----------------------------------------------------------------------------------------
    // Step 3 — a due nothing else describes yet
    // -----------------------------------------------------------------------------------------

    /**
     * Step 3's new row.
     *
     * `source` is [DueSource.EMAIL] because `DueWriter` is the email channel's seam; an
     * aggregator's notice still creates an EMAIL due attributed to the card issuer it named,
     * never to the aggregator (§20.4 step 5).
     *
     * R-20-09 is applied here: a card statement that states zero — or a credit balance, which
     * reaches the app as a non-positive amount — is born [DueStatus.DISMISSED] and flagged
     * `auto_dismissed`, with the note the requirement dictates as its caption, rather than
     * sitting in Pending waiting for a payment that will never come.
     */
    fun newDue(parsed: ParsedDue, emailMessageId: Long?): DueEntity {
        val timestamp = now()
        val nothingDue = statesNothingDue(parsed)
        return DueEntity(
            dueId = dueId(parsed),
            issuer = parsed.issuer.trim(),
            kind = storedKind(parsed.kind),
            // `dues.amount` is NOT NULL at v63 while `ParsedDue.amount` is nullable by design.
            // Zero is the only value the column can take, so the difference between "nothing is
            // owed" and "the sender did not say" is carried by `amount_stated`. Dropping the
            // due instead would lose its date as well.
            amount = parsed.amount ?: BigDecimal.ZERO,
            amountStated = parsed.amount != null,
            currency = parsed.currency,
            minimumDue = parsed.minimumDue,
            dueDate = parsed.dueDate,
            accountLast4 = parsed.accountLast4,
            reference = parsed.reference,
            periodLabel = parsed.periodLabel,
            autoPay = parsed.autoPay,
            autoPayInstrument = parsed.autoPayInstrument,
            status = if (nothingDue) DueStatus.DISMISSED else DueStatus.OPEN,
            autoDismissed = nothingDue,
            source = DueSource.EMAIL,
            emailMessageId = emailMessageId,
            // Display text. The two decisions it used to carry are the two flags above.
            note = noteFor(parsed, nothingDue),
            createdAt = timestamp,
            updatedAt = timestamp
        )
    }

    // -----------------------------------------------------------------------------------------
    // Steps 4 and 5 — folding a second reading into a row that exists
    // -----------------------------------------------------------------------------------------

    /**
     * Step 4's field-by-field precedence, step 5's in-place aggregator upgrade, and R-20-09.
     *
     * The authority behind a row's current figures is the highest-ranked notice already
     * recorded against it. A notice at least that authoritative may correct `amount`,
     * `minimum_due` and `due_date`; any notice at all may fill a field that is still null.
     * A row with no sources yet — one generated from a commitment (§20.6), or entered by hand —
     * has no authority standing behind it, so the first notice to describe it wins, which is
     * exactly how a committed due gains the billed amount.
     *
     * `due_id` is never recomputed. It is the row's identity, and a second notice that moved
     * the date by a day would otherwise either orphan the first notice's key or collide with
     * another row's — and `dues.due_id` is unique, so a collision is a crash, not a merge.
     */
    fun merge(
        target: Candidate,
        parsed: ParsedDue,
        config: DueMergeConfig = DueMergeConfig()
    ): MergeOutcome {
        val due = target.due
        val incoming = precedenceOf(parsed)
        val standing = target.sources.maxOfOrNull { precedenceOf(it).rank }
        // §20.4 step 7. A payment confirmation is never authoritative over anything, whatever
        // rung its sender sits on, because it is not talking about the bill: "amount paid" and
        // "amount due" are different numbers the moment somebody pays the minimum, and the date
        // it states is the day money moved, not a due date. Letting one overwrite either would
        // put a figure on screen 33 that nobody ever billed (spec 01, constraint C7). Nothing is
        // lost — every figure it did state is kept on its `due_sources` row, which is where
        // spec 26's UNMATCHED_PAYMENT_NOTICE reads it from.
        val confirmation = parsed.paymentConfirmed
        // Equal rank still wins: a second statement from the issuer is a correction, and
        // refusing it would pin the due to a figure the biller has already withdrawn.
        val authoritative = !confirmation && (standing == null || incoming.rank >= standing)
        // §20.7: a due whose money question is closed never changes status or figures because
        // a notice arrived. The notice is still kept in `due_sources`, so nothing is lost —
        // only the settled row is protected. The one exception is a due R-20-09 dismissed by
        // itself, which a later bill may legitimately re-open.
        // `auto_dismissed`, not the note: the note is the user's to edit, and a decision keyed
        // off free text is a decision the user can flip by typing (docs/FORK-CHANGES.md).
        val frozen = due.status.isSettled ||
            due.status == DueStatus.SKIPPED ||
            (due.status == DueStatus.DISMISSED && !due.autoDismissed)
        val mayOverwrite = authoritative && !frozen

        // Bound to locals because `ParsedDue` lives in another module, so Kotlin will not smart
        // cast its properties however they have been checked.
        val incomingAmount = parsed.amount
        val incomingMinimum = parsed.minimumDue

        val stated = statedAmount(due)
        // Whether this notice's figure becomes the row's, as one boolean rather than as a
        // `when` over the amount: `amount_stated` has to move in lock-step with `amount`, and a
        // flag derived from comparing the two figures afterwards would miss a sender restating
        // the number the row already held — which is the flag drifting from the fact, the very
        // failure the note marker had.
        val takesAmount = when {
            incomingAmount == null -> false
            // §20.4 step 7, and the null-fill is no exception to it: an amount nobody has
            // stated, filled from a payment notice, prints the paid figure as the bill.
            confirmation -> false
            // §20.7 is tested before the null-fill, not after it. A settled due takes no figure
            // at all — not even into a gap — because the alternative is the row silently
            // acquiring an aggregator's number while it is still flagged amount-not-stated,
            // which leaves screen 33 printing "—" over a due that now holds money (constraint
            // C7). The notice is not lost: it is still recorded in `due_sources`, so the day the
            // user re-opens the due the figure is there to be read back.
            frozen -> false
            stated == null -> true
            else -> mayOverwrite
        }
        val amount = incomingAmount.takeIf { takesAmount } ?: due.amount
        val amountStated = due.amountStated || takesAmount
        val minimumDue = when {
            incomingMinimum == null -> due.minimumDue
            // Same reason as the amount above: a confirmation contributes no figure at all.
            confirmation -> due.minimumDue
            // Same guard, same reason: a minimum owed is a figure, and a settled due's figures
            // are closed. Spec 20 §20.4's "it may fill nulls" is a licence for a *lower-ranked*
            // notice, not for a notice arriving after the money question was answered.
            frozen -> due.minimumDue
            due.minimumDue == null -> incomingMinimum
            mayOverwrite -> incomingMinimum
            else -> due.minimumDue
        }
        val dueDate = if (mayOverwrite) parsed.dueDate else due.dueDate

        // R-20-09 in both directions. A statement that says nothing is owed closes the due; a
        // later one that names a figure re-opens it. Only the app's own auto-dismissal is
        // reversible here — `frozen` has already excluded the user's.
        val nothingDue = statesNothingDue(parsed)
        val status = when {
            frozen -> due.status
            nothingDue && authoritative && due.status == DueStatus.OPEN -> DueStatus.DISMISSED
            due.status == DueStatus.DISMISSED && amount.signum() > 0 && authoritative -> DueStatus.OPEN
            else -> due.status
        }
        // Only the app's own dismissal is reversible, so only the app's own dismissal is
        // recorded. A row that reaches here dismissed and not frozen was auto-dismissed
        // already; re-opening it clears the flag, which is what makes the *next* dismissal —
        // possibly the user's — final.
        val autoDismissed = when {
            frozen -> due.autoDismissed
            status == DueStatus.DISMISSED -> due.autoDismissed || nothingDue
            else -> false
        }

        // Display text from here down. These branches replace the app's own stand-in captions
        // once they stop describing the row; a note the user has typed over matches neither
        // constant, so it survives, and nothing below decides anything.
        val note = when {
            frozen -> due.note
            // A confirmation's caption describes the *payment*, not the bill, and the chain
            // below can only replace a note, never clear one — so "Payment confirmed by CRED"
            // would become this due's caption for the rest of its life. Screen 34 shows the
            // notice from `due_sources` instead, where it reads as what it is.
            confirmation -> due.note
            status == DueStatus.DISMISSED && nothingDue -> NOTHING_DUE_NOTE
            // The figure arrived, so the caption that stood in for it must go.
            due.note == AMOUNT_NOT_STATED_NOTE && takesAmount -> parsed.description
            due.note == NOTHING_DUE_NOTE && status == DueStatus.OPEN -> parsed.description
            else -> due.note ?: parsed.description
        }

        val merged = due.copy(
            amount = amount,
            amountStated = amountStated,
            minimumDue = minimumDue,
            dueDate = dueDate,
            // Filling a null costs nothing and is what lets a bank statement teach a
            // CRED-created due which card it is about (§20.4 step 4, "it may fill nulls").
            accountLast4 = due.accountLast4 ?: parsed.accountLast4,
            reference = due.reference ?: parsed.reference,
            periodLabel = due.periodLabel ?: parsed.periodLabel,
            // No notice ever says "this is not auto-paid", so auto-pay is positive evidence only.
            autoPay = due.autoPay || parsed.autoPay,
            autoPayInstrument = due.autoPayInstrument ?: parsed.autoPayInstrument,
            status = status,
            autoDismissed = autoDismissed,
            note = note
        )

        val changed = merged != due
        return MergeOutcome(
            due = if (changed) merged.copy(updatedAt = now()) else due,
            changed = changed,
            mergeRule = mergeRuleFor(parsed, target),
            escalation = escalationOf(target.sources.map { it.noticeKind } + noticeKind(parsed))
        )
    }

    /**
     * How this reading reached its row — the value that goes on its `due_sources` row.
     *
     * §20.4 step 5. An aggregator on either side means the reading met the due through
     * somebody's *copy* of the bill rather than through the biller's own key: CRED arriving at
     * the issuer's statement, the issuer's statement upgrading a due CRED created, or CRED
     * creating the due in the first place. [target] is null for a due being created.
     */
    fun mergeRuleFor(parsed: ParsedDue, target: Candidate?): MergeRule =
        if (precedenceOf(parsed).isAggregator ||
            target?.sources.orEmpty().any { precedenceOf(it).isAggregator }
        ) {
            MergeRule.AGGREGATOR_KEY
        } else {
            MergeRule.MERGE_KEY
        }

    // -----------------------------------------------------------------------------------------
    // Escalation
    // -----------------------------------------------------------------------------------------

    /**
     * How the *sender* framed this notice (spec 12 C3) — the same reading
     * `EmailTransactionIngestor` writes to `due_sources.notice_kind`, so the engine can rank a
     * notice that is not stored yet. Both call sites share [Companion.noticeKindOf].
     */
    fun noticeKind(parsed: ParsedDue): NoticeKind = noticeKindOf(parsed)

    /**
     * `escalation = max(current, notice.escalation)`, `REMINDER < DUE_TODAY < OVERDUE_NOTICE`
     * (spec 20 §20.4 step 4).
     *
     * **There is no `dues.escalation` column at schema v63.** Spec 03 §3.7 puts it in the
     * 63 to 64 migration, so the only place the fork can hold the answer today is
     * `due_sources.notice_kind` — every notice keeps its own wording, and the due's escalation
     * is the maximum over them. That loses nothing: the column M2 adds is a cache of this
     * function, and because the rows are kept it can be backfilled rather than re-parsed. See
     * docs/FORK-CHANGES.md.
     *
     * A statement and an auto-pay warning are announcements, not urgency, and a payment
     * confirmation is not on the ladder at all (§20.4 step 7 gives it its own handling), so
     * none of the three can raise an escalation. Null means no notice claimed any.
     */
    fun escalationOf(kinds: List<NoticeKind>): NoticeKind? =
        kinds.filter { ESCALATION_RANK.containsKey(it) }
            .maxByOrNull { ESCALATION_RANK.getValue(it) }
            ?.takeIf { ESCALATION_RANK.getValue(it) > 0 }

    // -----------------------------------------------------------------------------------------
    // The ladder
    // -----------------------------------------------------------------------------------------

    /** Where an incoming reading sits on the ladder, from the sender its parser recorded. */
    fun precedenceOf(parsed: ParsedDue): NoticePrecedence =
        aggregator(parsed.source) ?: issuerRank(noticeKind(parsed))

    /**
     * Where a notice already on file sits.
     *
     * `due_sources` keeps the sender's address rather than the parser's source token, so the
     * aggregators are recognised through `EmailSenderRegistry` — the same table that told the
     * pipeline the mail was worth fetching in the first place.
     */
    fun precedenceOf(source: DueSourceEntity): NoticePrecedence =
        aggregator(EmailSenderRegistry.resolve(source.sender)?.bankName)
            ?: aggregator(source.sender)
            ?: issuerRank(source.noticeKind)

    /**
     * Anything the app cannot name as one of the three aggregators is treated as the biller's
     * own mail, which is what every other parser in `:parser-core` reads. A fourth aggregator
     * needs a row here as well as a parser.
     */
    private fun aggregator(name: String?): NoticePrecedence? =
        when (name?.filter { it.isLetterOrDigit() }?.lowercase()) {
            "cred" -> NoticePrecedence.CRED
            "amazonpay" -> NoticePrecedence.AMAZON_PAY
            "billdesk" -> NoticePrecedence.BILLDESK
            else -> null
        }

    private fun issuerRank(kind: NoticeKind): NoticePrecedence = when (kind) {
        NoticeKind.STATEMENT -> NoticePrecedence.ISSUER_STATEMENT
        // A payment confirmation is the issuer speaking about its own bill, and §20.4 step 7
        // keeps it from settling anything. It ranks below a statement, which matters only for
        // what it does to *later* notices: [merge] refuses it every figure outright, so the
        // rung it holds is the authority it lends the row, not a licence of its own.
        else -> NoticePrecedence.ISSUER_REMINDER
    }

    // -----------------------------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------------------------

    /**
     * R-20-09's test. The sender must have *stated* the figure: a notice that carries no amount
     * at all (`ParsedDue.amount == null`, e.g. a plan-expiry reminder) says nothing about
     * whether money is owed, and dismissing it would throw the due away.
     */
    private fun statesNothingDue(parsed: ParsedDue): Boolean {
        val amount = parsed.amount ?: return false
        return storedKind(parsed.kind) == DueKind.CARD_STATEMENT && amount.signum() <= 0
    }

    /**
     * The due's amount as a figure somebody actually stated, or null while none has been.
     *
     * `amount_stated` is the record of that, and nothing else is: the column cannot hold null
     * at v63, and zero is a figure a card statement really does state (R-20-09). Reading the
     * answer off `note` — as this did until the marker was retired — let a user edit turn an
     * unknown amount into a stated zero, which screen 33 would then print as `₹0` for a bill
     * nobody has sized (spec 01 constraint C7).
     */
    private fun statedAmount(due: DueEntity): BigDecimal? =
        due.amount.takeIf { due.amountStated }

    private fun noteFor(parsed: ParsedDue, nothingDue: Boolean): String? = when {
        nothingDue -> NOTHING_DUE_NOTE
        parsed.amount == null -> AMOUNT_NOT_STATED_NOTE
        else -> parsed.description
    }

    private fun sameAccount(due: DueEntity, parsed: ParsedDue): Boolean {
        val stored = digits(due.accountLast4) ?: return false
        val incoming = digits(parsed.accountLast4) ?: return false
        return stored == incoming
    }

    private fun sameReference(due: DueEntity, parsed: ParsedDue): Boolean {
        val stored = due.reference?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return false
        val incoming = parsed.reference?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            ?: return false
        return stored == incoming
    }

    private fun digits(raw: String?): String? =
        raw?.filter { it.isDigit() }?.takeIf { it.isNotEmpty() }

    /**
     * `:parser-core`'s `DueKind` to the stored one.
     *
     * The two enums are deliberately separate — the stored vocabulary is a schema contract and
     * `:parser-core` carries no serialization plugin (see `DueEnums.kt`) — and they are kept
     * name-for-name identical, which `DueMergeEngineTest` pins. An unknown name falls back to
     * `OTHER` rather than throwing: a due filed under the wrong kind is recoverable, a lost
     * bill is not.
     *
     * Public because `DueRepository` needs it to ask `DueDao` for candidates — the mapping is
     * a rule, and rules live here.
     */
    fun storedKind(kind: ParserDueKind): DueKind = DueKind.fromStored(kind.name)

    companion object {

        /**
         * How the *sender* framed one notice (spec 12 C3), decided in exactly one place.
         *
         * `EmailTransactionIngestor` writes the answer to `due_sources.notice_kind` and the
         * engine reads it back to rank a notice; both used to carry their own verbatim copy of
         * this `when`, with nothing pinning them together, so a value added to [NoticeKind]
         * could be — and was — wired into neither.
         *
         * A payment confirmation is tested first because it is a different question from
         * urgency (spec 20 §20.4 step 7): a bank confirming payment of a bill it had already
         * called overdue is still confirming a payment, and [escalationOf] ignores
         * `PAYMENT_CONFIRMED` precisely so that the ladder is not disturbed by it.
         */
        fun noticeKindOf(parsed: ParsedDue): NoticeKind = when {
            parsed.paymentConfirmed -> NoticeKind.PAYMENT_CONFIRMED
            parsed.escalation == DueEscalation.OVERDUE -> NoticeKind.OVERDUE
            parsed.escalation == DueEscalation.DUE_TODAY -> NoticeKind.DUE_TODAY
            parsed.escalation == DueEscalation.DUE_SOON -> NoticeKind.REMINDER
            parsed.autoPay -> NoticeKind.AUTOPAY_UPCOMING
            else -> NoticeKind.STATEMENT
        }

        /**
         * R-20-09's wording, verbatim — the **caption** on an auto-dismissed due.
         *
         * `dues.auto_dismissed` is what says the app dismissed the row; this only says so to
         * the reader. A user who rewrites it changes what screen 33 prints and nothing else.
         */
        const val NOTHING_DUE_NOTE = "Nothing due (statement)"

        /**
         * The caption on a due whose amount nobody has stated.
         *
         * `dues.amount_stated` is the fact — `amount` is NOT NULL at v63, so a due with no
         * figure holds zero and is flagged unstated, and screen 33 prints "—" for it (spec 01
         * constraint C7). This string is how that reads on screen; the merge stopped deciding
         * anything from it when the flag column arrived, and the day `amount` becomes nullable
         * both can go.
         */
        const val AMOUNT_NOT_STATED_NOTE = "Amount not stated"

        /** §20.4 step 2's status set: a due whose money question is still open. */
        private val MERGEABLE_STATUSES = setOf(
            DueStatus.OPEN,
            DueStatus.PAID_ON_TIME,
            DueStatus.PAID_LATE,
            DueStatus.MARKED_PAID
        )

        /** `REMINDER < DUE_TODAY < OVERDUE_NOTICE`; 0 means "on the ladder, but not urgent". */
        private val ESCALATION_RANK = mapOf(
            NoticeKind.STATEMENT to 0,
            NoticeKind.AUTOPAY_UPCOMING to 0,
            NoticeKind.REMINDER to 1,
            NoticeKind.DUE_TODAY to 2,
            NoticeKind.OVERDUE to 3
        )
    }
}
