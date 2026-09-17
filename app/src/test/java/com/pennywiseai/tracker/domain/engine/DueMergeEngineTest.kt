package com.pennywiseai.tracker.domain.engine

import com.pennywiseai.tracker.data.database.entity.DueEntity
import com.pennywiseai.tracker.data.database.entity.DueSource
import com.pennywiseai.tracker.data.database.entity.DueSourceEntity
import com.pennywiseai.tracker.data.database.entity.DueStatus
import com.pennywiseai.tracker.data.database.entity.MergeRule
import com.pennywiseai.tracker.data.database.entity.NoticeKind
import com.pennywiseai.tracker.data.database.entity.SourceChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import com.pennywiseai.parser.core.email.due.DueEscalation
import com.pennywiseai.parser.core.email.due.ParsedDue
import com.pennywiseai.tracker.data.database.entity.DueKind as StoredDueKind
import com.pennywiseai.parser.core.email.due.DueKind as ParserDueKind

/**
 * Spec 20 §20.4, step by step, plus R-20-09.
 *
 * The engine takes rows and notices as plain data, so none of this needs Room, a device or a
 * network (spec 02 §2.3 rule 4). Everything that would otherwise read the wall clock comes
 * from [clock], so a merge's `updated_at` is a fact a test can assert rather than a race.
 *
 * All data is invented. Per spec 06 §6.2 the corpus's shapes are kept and its values are not:
 * last-4s are `1234`/`5678`, references are `123456789012`, and every amount is made up.
 */
class DueMergeEngineTest {

    /** Fixed so a test can never depend on the day it runs (spec 02 §2.8). */
    private val clock: Clock = Clock.fixed(Instant.parse("2026-09-17T19:00:00Z"), ZoneOffset.UTC)

    private val engine = DueMergeEngine(clock)

    private val now = LocalDateTime.of(2026, 9, 17, 19, 0)

    // =========================================================================================
    // Step 1 — the merge key
    // =========================================================================================

    @Test
    fun `the same bill read twice yields the same due id`() {
        assertEquals(engine.dueId(statement()), engine.dueId(statement()))
    }

    @Test
    fun `issuer spelling does not split one bill into two dues`() {
        assertEquals(
            engine.dueId(statement(issuer = "Axis Bank")),
            engine.dueId(statement(issuer = "AXIS  bank"))
        )
    }

    @Test
    fun `a different due date is a different due`() {
        assertNotEquals(
            engine.dueId(statement()),
            engine.dueId(statement(dueDate = LocalDate.of(2026, 10, 2)))
        )
    }

    @Test
    fun `a different currency is a different due`() {
        assertNotEquals(
            engine.dueId(statement()),
            engine.dueId(statement(currency = "USD"))
        )
    }

    @Test
    fun `a different card is a different due`() {
        assertNotEquals(
            engine.dueId(statement(accountLast4 = "1234")),
            engine.dueId(statement(accountLast4 = "5678"))
        )
    }

    @Test
    fun `the reference stands in for the card when no card is named`() {
        // §20.4 step 1's `accountLast4 ?: reference ?: ""`: an invoice with no card still has
        // an identity of its own, so two GoDaddy domains in one mail cannot collapse.
        assertNotEquals(
            engine.dueId(invoice(reference = "123456789012")),
            engine.dueId(invoice(reference = "123456789013"))
        )
    }

    @Test
    fun `an issuer key ignores case punctuation and spacing`() {
        assertEquals(engine.issuerKey("ICICI Bank"), engine.issuerKey("  icici-bank "))
    }

    // =========================================================================================
    // Step 2 — who is a candidate
    // =========================================================================================

    @Test
    fun `a reading merges into a due dated within the tolerance`() {
        val existing = dueRow(dueDate = LocalDate.of(2026, 10, 1))
        assertTrue(engine.isCandidate(existing, statement(dueDate = LocalDate.of(2026, 10, 3))))
    }

    @Test
    fun `a reading past the tolerance is a different due`() {
        val existing = dueRow(dueDate = LocalDate.of(2026, 10, 1))
        assertFalse(engine.isCandidate(existing, statement(dueDate = LocalDate.of(2026, 10, 4))))
    }

    @Test
    fun `the tolerance is a setting`() {
        val existing = dueRow(dueDate = LocalDate.of(2026, 10, 1))
        val fiveDays = DueMergeConfig(mergeDateToleranceDays = 5)
        assertTrue(
            engine.isCandidate(existing, statement(dueDate = LocalDate.of(2026, 10, 4)), fiveDays)
        )
    }

    @Test
    fun `a different card never merges`() {
        val existing = dueRow(accountLast4 = "5678")
        assertFalse(engine.isCandidate(existing, statement(accountLast4 = "1234")))
    }

    @Test
    fun `a shared reference merges even when neither names a card`() {
        val existing = dueRow(
            kind = StoredDueKind.INVOICE,
            issuer = "GoDaddy",
            accountLast4 = null,
            reference = "123456789012"
        )
        assertTrue(engine.isCandidate(existing, invoice(reference = "123456789012")))
    }

    @Test
    fun `two currencies never merge`() {
        val existing = dueRow(currency = "USD")
        assertFalse(engine.isCandidate(existing, statement(currency = "INR")))
    }

    @Test
    fun `a dismissed due is not a candidate for a different bill`() {
        // §20.4 step 2's status set. The dismissed row is a *different* key — same card, a date
        // two days off — so nothing about it should attract this reading.
        val dismissed = dueRow(
            dueId = "another-key",
            status = DueStatus.DISMISSED,
            dueDate = LocalDate.of(2026, 10, 2)
        )
        assertFalse(engine.isCandidate(dismissed, statement()))
    }

    @Test
    fun `a settled due still absorbs a later notice about the same bill`() {
        val paid = dueRow(status = DueStatus.PAID_ON_TIME)
        assertTrue(engine.isCandidate(paid, statement()))
    }

    @Test
    fun `a row carrying this reading's key is a candidate whatever its status`() {
        // R-20-09 dismissed it; the same key arriving again must reach that row, because
        // `dues.due_id` is unique and an insert would abort.
        val notice = statement()
        val dismissed = dueRow(
            dueId = engine.dueId(notice),
            status = DueStatus.DISMISSED,
            autoDismissed = true,
            note = DueMergeEngine.NOTHING_DUE_NOTE
        )
        assertTrue(engine.isCandidate(dismissed, notice))
    }

    // =========================================================================================
    // Step 4 — which candidate wins
    // =========================================================================================

    @Test
    fun `the candidate with the most sources wins`() {
        val thin = DueMergeEngine.Candidate(dueRow(id = 1), listOf(source(1, CRED_SENDER)))
        val fat = DueMergeEngine.Candidate(
            dueRow(id = 2),
            listOf(source(2, CRED_SENDER), source(2, AXIS_SENDER))
        )
        assertEquals(2L, engine.chooseTarget(listOf(thin, fat), statement())?.due?.id)
    }

    @Test
    fun `ties go to the earliest created`() {
        val later = DueMergeEngine.Candidate(dueRow(id = 1, createdAt = now.minusDays(1)))
        val earlier = DueMergeEngine.Candidate(dueRow(id = 2, createdAt = now.minusDays(3)))
        assertEquals(2L, engine.chooseTarget(listOf(later, earlier), statement())?.due?.id)
    }

    @Test
    fun `the row with this reading's own key wins over a better sourced one`() {
        val notice = statement()
        val fat = DueMergeEngine.Candidate(
            dueRow(id = 1, dueId = "another-key"),
            listOf(source(1, CRED_SENDER), source(1, AXIS_SENDER))
        )
        val exact = DueMergeEngine.Candidate(dueRow(id = 2, dueId = engine.dueId(notice)))
        assertEquals(2L, engine.chooseTarget(listOf(fat, exact), notice)?.due?.id)
    }

    @Test
    fun `no candidate means a new due`() {
        assertNull(engine.chooseTarget(emptyList(), statement()))
    }

    // =========================================================================================
    // Step 3 — the new row
    // =========================================================================================

    @Test
    fun `a new due carries every field the notice stated`() {
        val notice = statement(
            amount = BigDecimal("4321.50"),
            minimumDue = BigDecimal("950.00"),
            periodLabel = "generated Sep 10, 2026",
            autoPay = true,
            autoPayInstrument = "Visa-5678"
        )

        val due = engine.newDue(notice, emailMessageId = 7)

        assertEquals(engine.dueId(notice), due.dueId)
        assertEquals("Axis Bank", due.issuer)
        assertEquals(StoredDueKind.CARD_STATEMENT, due.kind)
        assertEquals(BigDecimal("4321.50"), due.amount)
        assertEquals(BigDecimal("950.00"), due.minimumDue)
        assertEquals(LocalDate.of(2026, 10, 1), due.dueDate)
        assertEquals("1234", due.accountLast4)
        assertEquals("generated Sep 10, 2026", due.periodLabel)
        assertTrue(due.autoPay)
        assertEquals("Visa-5678", due.autoPayInstrument)
        assertEquals(DueStatus.OPEN, due.status)
        assertEquals(DueSource.EMAIL, due.source)
        assertEquals(7L, due.emailMessageId)
        assertEquals(now, due.createdAt)
        assertEquals(now, due.updatedAt)
    }

    @Test
    fun `an aggregator's notice creates the due in the card issuer's name`() {
        // §20.4 step 5: the due belongs to the issuer CRED named, never to CRED.
        val due = engine.newDue(credNotice(), emailMessageId = 7)

        assertEquals("Axis Bank", due.issuer)
        assertEquals(DueSource.EMAIL, due.source)
        assertEquals(MergeRule.AGGREGATOR_KEY, engine.mergeRuleFor(credNotice(), target = null))
    }

    @Test
    fun `R-20-09 dismisses a statement with nothing due`() {
        val due = engine.newDue(statement(amount = BigDecimal.ZERO), emailMessageId = 7)

        assertEquals(DueStatus.DISMISSED, due.status)
        // The flag is the app saying "this dismissal was mine"; the note only says it on screen.
        assertTrue(due.autoDismissed)
        assertEquals("Nothing due (statement)", due.note)
        // Zero was stated, so it is a figure — this is the one dismissal that is not an unknown.
        assertTrue(due.amountStated)
    }

    @Test
    fun `a due nobody dismissed is not flagged as auto-dismissed`() {
        val due = engine.newDue(statement(), emailMessageId = 7)

        assertEquals(DueStatus.OPEN, due.status)
        assertFalse(due.autoDismissed)
        assertTrue(due.amountStated)
    }

    @Test
    fun `R-20-09 dismisses a statement in credit`() {
        // A "CR" balance reaches the app as a negative figure: the issuer owes the user.
        val due = engine.newDue(statement(amount = BigDecimal("-120.00")), emailMessageId = 7)

        assertEquals(DueStatus.DISMISSED, due.status)
        assertTrue(due.autoDismissed)
        assertEquals(DueMergeEngine.NOTHING_DUE_NOTE, due.note)
    }

    @Test
    fun `a zero invoice is not a statement and stays open`() {
        // R-20-09 is scoped to card statements. Nothing else in spec 20 says a zero elsewhere
        // means "nothing owed", and inventing that reading would hide a real bill.
        val due = engine.newDue(invoice(amount = BigDecimal.ZERO), emailMessageId = 7)

        assertEquals(DueStatus.OPEN, due.status)
    }

    @Test
    fun `a notice that states no amount is kept open and marked unknown`() {
        // Constraint C7: unknown is not zero. `dues.amount` is NOT NULL at v63, so
        // `amount_stated` carries the difference until the column can.
        val due = engine.newDue(statement(amount = null), emailMessageId = 7)

        assertEquals(DueStatus.OPEN, due.status)
        assertFalse(due.amountStated)
        assertEquals(DueMergeEngine.AMOUNT_NOT_STATED_NOTE, due.note)
        assertEquals(0, due.amount.signum())
    }

    // =========================================================================================
    // Step 4 — the precedence ladder
    // =========================================================================================

    @Test
    fun `an aggregator never overwrites the issuer's amount`() {
        val existing = DueMergeEngine.Candidate(
            dueRow(amount = BigDecimal("4321.50"), minimumDue = BigDecimal("950.00")),
            listOf(source(1, AXIS_SENDER, NoticeKind.STATEMENT))
        )

        val outcome = engine.merge(
            existing,
            credNotice(amount = BigDecimal("4100.00"), minimumDue = BigDecimal("900.00"))
        )

        assertEquals(BigDecimal("4321.50"), outcome.due.amount)
        assertEquals(BigDecimal("950.00"), outcome.due.minimumDue)
    }

    @Test
    fun `an aggregator may still fill what the issuer left null`() {
        val existing = DueMergeEngine.Candidate(
            dueRow(minimumDue = null, periodLabel = null),
            listOf(source(1, AXIS_SENDER, NoticeKind.STATEMENT))
        )

        val outcome = engine.merge(
            existing,
            credNotice(minimumDue = BigDecimal("900.00"), periodLabel = "generated Sep 10, 2026")
        )

        assertTrue(outcome.changed)
        assertEquals(BigDecimal("900.00"), outcome.due.minimumDue)
        assertEquals("generated Sep 10, 2026", outcome.due.periodLabel)
    }

    @Test
    fun `the bank statement upgrades a due an aggregator created, in place`() {
        // §20.4 step 5. One row, the issuer's figure, and the join recorded as AGGREGATOR_KEY.
        val fromCred = DueMergeEngine.Candidate(
            dueRow(id = 4, amount = BigDecimal("4100.00"), accountLast4 = "1234"),
            listOf(source(4, CRED_SENDER, NoticeKind.STATEMENT))
        )

        val outcome = engine.merge(fromCred, statement(amount = BigDecimal("4321.50")))

        assertEquals(4L, outcome.due.id)
        assertTrue(outcome.changed)
        assertEquals(BigDecimal("4321.50"), outcome.due.amount)
        assertEquals(MergeRule.AGGREGATOR_KEY, outcome.mergeRule)
        assertEquals(now, outcome.due.updatedAt)
    }

    @Test
    fun `two issuer notices are joined on the merge key, not through an aggregator`() {
        val existing = DueMergeEngine.Candidate(
            dueRow(),
            listOf(source(1, AXIS_SENDER, NoticeKind.STATEMENT))
        )

        val outcome = engine.merge(existing, statement(escalation = DueEscalation.DUE_SOON))

        assertEquals(MergeRule.MERGE_KEY, outcome.mergeRule)
    }

    @Test
    fun `a corrected statement from the same issuer wins`() {
        // Equal rank still overwrites: refusing the correction would pin the due to a figure
        // the biller has already withdrawn.
        val existing = DueMergeEngine.Candidate(
            dueRow(amount = BigDecimal("4321.50")),
            listOf(source(1, AXIS_SENDER, NoticeKind.STATEMENT))
        )

        val outcome = engine.merge(existing, statement(amount = BigDecimal("4480.00")))

        assertEquals(BigDecimal("4480.00"), outcome.due.amount)
    }

    @Test
    fun `an issuer reminder does not move the amount its own statement set`() {
        val existing = DueMergeEngine.Candidate(
            dueRow(amount = BigDecimal("4321.50")),
            listOf(source(1, AXIS_SENDER, NoticeKind.STATEMENT))
        )

        val outcome = engine.merge(
            existing,
            statement(amount = BigDecimal("4100.00"), escalation = DueEscalation.OVERDUE)
        )

        assertEquals(BigDecimal("4321.50"), outcome.due.amount)
    }

    @Test
    fun `a due nobody has described yet takes the first notice's figures`() {
        // §20.6: a due generated from a commitment has no sources, so the bill that finally
        // arrives is the authority — this is how a committed due gains the real amount.
        val committed = DueMergeEngine.Candidate(
            dueRow(amount = BigDecimal("5000.00"), source = DueSource.COMMITMENT)
        )

        val outcome = engine.merge(committed, credNotice(amount = BigDecimal("5060.00")))

        assertEquals(BigDecimal("5060.00"), outcome.due.amount)
        assertEquals(DueSource.COMMITMENT, outcome.due.source)
    }

    @Test
    fun `auto-pay is positive evidence only`() {
        val existing = DueMergeEngine.Candidate(
            dueRow(autoPay = true, autoPayInstrument = "Visa-5678"),
            listOf(source(1, AXIS_SENDER, NoticeKind.STATEMENT))
        )

        val outcome = engine.merge(existing, credNotice(autoPay = false))

        assertTrue(outcome.due.autoPay)
        assertEquals("Visa-5678", outcome.due.autoPayInstrument)
    }

    @Test
    fun `a notice that says nothing new writes nothing`() {
        val existing = DueMergeEngine.Candidate(
            dueRow(),
            listOf(source(1, AXIS_SENDER, NoticeKind.STATEMENT))
        )

        val outcome = engine.merge(existing, statement())

        assertFalse(outcome.changed)
        assertEquals(existing.due.updatedAt, outcome.due.updatedAt)
    }

    // =========================================================================================
    // §20.7 — a settled due is immutable to notices
    // =========================================================================================

    @Test
    fun `a paid due keeps its figures when a later notice disagrees`() {
        val paid = DueMergeEngine.Candidate(
            dueRow(amount = BigDecimal("4321.50"), status = DueStatus.PAID_ON_TIME),
            listOf(source(1, AXIS_SENDER, NoticeKind.STATEMENT))
        )

        val outcome = engine.merge(paid, statement(amount = BigDecimal("4480.00")))

        assertEquals(BigDecimal("4321.50"), outcome.due.amount)
        assertEquals(DueStatus.PAID_ON_TIME, outcome.due.status)
    }

    @Test
    fun `a due the user dismissed is never re-opened by a notice`() {
        val dismissed = DueMergeEngine.Candidate(
            dueRow(status = DueStatus.DISMISSED, note = "Paid from another account"),
            listOf(source(1, AXIS_SENDER, NoticeKind.STATEMENT))
        )

        val outcome = engine.merge(dismissed, statement(amount = BigDecimal("4480.00")))

        assertEquals(DueStatus.DISMISSED, outcome.due.status)
        assertFalse(outcome.changed)
    }

    @Test
    fun `a paid due whose amount was never stated does not take a later figure`() {
        // The C7 case the null-fill branch used to let through: the row was settled while
        // nobody had said what it was for, so the "Amount not stated" note is the truth about
        // it. Taking CRED's figure here would write money into a row that screen 33 still
        // renders as "—" — a due that holds a number and shows an em-dash.
        val paidUnknown = DueMergeEngine.Candidate(
            dueRow(
                amount = BigDecimal.ZERO,
                amountStated = false,
                note = DueMergeEngine.AMOUNT_NOT_STATED_NOTE,
                status = DueStatus.PAID_ON_TIME
            ),
            listOf(source(1, AXIS_SENDER, NoticeKind.REMINDER))
        )

        val outcome = engine.merge(paidUnknown, credNotice(amount = BigDecimal("4321.50")))

        assertEquals(0, outcome.due.amount.signum())
        assertFalse(outcome.due.amountStated)
        assertEquals(DueMergeEngine.AMOUNT_NOT_STATED_NOTE, outcome.due.note)
        assertEquals(DueStatus.PAID_ON_TIME, outcome.due.status)
        assertFalse(outcome.changed)
    }

    @Test
    fun `not even the issuer's own statement re-states a settled due's unknown amount`() {
        // The ladder is not the reason the figure is refused — §20.7 is. The top of the ladder
        // is turned away exactly like the bottom of it.
        val paidUnknown = DueMergeEngine.Candidate(
            dueRow(
                amount = BigDecimal.ZERO,
                amountStated = false,
                note = DueMergeEngine.AMOUNT_NOT_STATED_NOTE,
                status = DueStatus.MARKED_PAID
            ),
            listOf(source(1, AXIS_SENDER, NoticeKind.STATEMENT))
        )

        val outcome = engine.merge(paidUnknown, statement(amount = BigDecimal("4321.50")))

        assertEquals(0, outcome.due.amount.signum())
        assertEquals(DueMergeEngine.AMOUNT_NOT_STATED_NOTE, outcome.due.note)
        assertFalse(outcome.changed)
    }

    @Test
    fun `a settled due with no minimum on file does not take a later minimum`() {
        // The minimumDue twin of the case above. A minimum owed is a figure like any other, and
        // `minimum_due` being null is the same kind of gap as an unstated amount.
        val paid = DueMergeEngine.Candidate(
            dueRow(minimumDue = null, status = DueStatus.PAID_ON_TIME),
            listOf(source(1, AXIS_SENDER, NoticeKind.STATEMENT))
        )

        val outcome = engine.merge(paid, credNotice(minimumDue = BigDecimal("900.00")))

        assertNull(outcome.due.minimumDue)
        assertFalse(outcome.changed)
    }

    @Test
    fun `a due the user dismissed takes neither a figure nor a minimum`() {
        // `frozen` is wider than `isSettled`: a due the user closed by hand is just as final,
        // and an unstated amount is not an invitation to fill it afterwards.
        val dismissed = DueMergeEngine.Candidate(
            dueRow(
                amount = BigDecimal.ZERO,
                amountStated = false,
                minimumDue = null,
                note = DueMergeEngine.AMOUNT_NOT_STATED_NOTE,
                // The user's dismissal, not the app's — `auto_dismissed` is what says so now.
                autoDismissed = false,
                status = DueStatus.DISMISSED
            ),
            listOf(source(1, AXIS_SENDER, NoticeKind.REMINDER))
        )

        val outcome = engine.merge(
            dismissed,
            credNotice(amount = BigDecimal("4321.50"), minimumDue = BigDecimal("900.00"))
        )

        assertEquals(0, outcome.due.amount.signum())
        assertNull(outcome.due.minimumDue)
        assertEquals(DueStatus.DISMISSED, outcome.due.status)
        assertFalse(outcome.changed)
    }

    // =========================================================================================
    // R-20-09 through a merge
    // =========================================================================================

    @Test
    fun `a corrected statement that says nothing is due closes the row`() {
        val existing = DueMergeEngine.Candidate(
            dueRow(amount = BigDecimal("4321.50")),
            listOf(source(1, AXIS_SENDER, NoticeKind.STATEMENT))
        )

        val outcome = engine.merge(existing, statement(amount = BigDecimal.ZERO))

        assertEquals(DueStatus.DISMISSED, outcome.due.status)
        assertTrue(outcome.due.autoDismissed)
        assertEquals(DueMergeEngine.NOTHING_DUE_NOTE, outcome.due.note)
    }

    @Test
    fun `a bill arriving after an auto-dismissal re-opens the row`() {
        // The app dismissed it, so the app may take it back. Losing the bill would be worse
        // than either outcome.
        val dismissed = DueMergeEngine.Candidate(
            dueRow(
                amount = BigDecimal.ZERO,
                status = DueStatus.DISMISSED,
                autoDismissed = true,
                note = DueMergeEngine.NOTHING_DUE_NOTE
            ),
            listOf(source(1, AXIS_SENDER, NoticeKind.STATEMENT))
        )

        val outcome = engine.merge(dismissed, statement(amount = BigDecimal("4321.50")))

        assertEquals(DueStatus.OPEN, outcome.due.status)
        assertEquals(BigDecimal("4321.50"), outcome.due.amount)
        // The row is open again, so the app's claim on the dismissal is spent: the next one,
        // which may be the user's, has to be final.
        assertFalse(outcome.due.autoDismissed)
        assertNull(outcome.due.note)
    }

    @Test
    fun `a figure fills an amount nobody had stated`() {
        val unknown = DueMergeEngine.Candidate(
            dueRow(
                amount = BigDecimal.ZERO,
                amountStated = false,
                note = DueMergeEngine.AMOUNT_NOT_STATED_NOTE
            ),
            listOf(source(1, AXIS_SENDER, NoticeKind.REMINDER))
        )

        val outcome = engine.merge(unknown, credNotice(amount = BigDecimal("4321.50")))

        assertEquals(BigDecimal("4321.50"), outcome.due.amount)
        assertTrue(outcome.due.amountStated)
        assertNull(outcome.due.note)
    }

    // =========================================================================================
    // The two machine flags — `amount_stated` and `auto_dismissed`
    //
    // Both decisions used to be read off `dues.note`, by exact equality against the two
    // constants below. The note belongs to the user (screen 33 lets them type in it), so the
    // tests here are the ones that could not pass while it did: a note the user rewrote, and a
    // note the user happened to write *as* one of the constants.
    // =========================================================================================

    @Test
    fun `a user rewriting the note does not turn an unknown amount into a stated zero`() {
        // The C7 case: the row holds zero because nobody has said what the bill is, and the
        // user has typed their own caption over the app's. The figure must still be takeable.
        val unknown = DueMergeEngine.Candidate(
            dueRow(
                amount = BigDecimal.ZERO,
                amountStated = false,
                note = "chasing this one up"
            ),
            listOf(source(1, AXIS_SENDER, NoticeKind.REMINDER))
        )

        val outcome = engine.merge(unknown, credNotice(amount = BigDecimal("4321.50")))

        assertEquals(BigDecimal("4321.50"), outcome.due.amount)
        assertTrue(outcome.due.amountStated)
        // Their words, not the app's: only the app's own captions are replaced.
        assertEquals("chasing this one up", outcome.due.note)
    }

    @Test
    fun `a user rewriting the note does not freeze an auto-dismissed due`() {
        val dismissed = DueMergeEngine.Candidate(
            dueRow(
                amount = BigDecimal.ZERO,
                status = DueStatus.DISMISSED,
                autoDismissed = true,
                note = "nil statement, nothing to do"
            ),
            listOf(source(1, AXIS_SENDER, NoticeKind.STATEMENT))
        )

        val outcome = engine.merge(dismissed, statement(amount = BigDecimal("4321.50")))

        assertEquals(DueStatus.OPEN, outcome.due.status)
        assertEquals(BigDecimal("4321.50"), outcome.due.amount)
    }

    @Test
    fun `a user's own dismissal stays final even worded like the app's`() {
        // The mirror image, and the reason equality against a string was never a decision: a
        // user who dismisses a due and writes "Nothing due (statement)" over it has not given
        // the app permission to re-open the row.
        val dismissed = DueMergeEngine.Candidate(
            dueRow(
                status = DueStatus.DISMISSED,
                autoDismissed = false,
                note = DueMergeEngine.NOTHING_DUE_NOTE
            ),
            listOf(source(1, AXIS_SENDER, NoticeKind.STATEMENT))
        )

        val outcome = engine.merge(dismissed, statement(amount = BigDecimal("4480.00")))

        assertEquals(DueStatus.DISMISSED, outcome.due.status)
        assertEquals(BigDecimal("4321.50"), outcome.due.amount)
        assertFalse(outcome.changed)
    }

    @Test
    fun `a stated amount stays stated when a notice states nothing`() {
        // `amount_stated` is a one-way latch: a reminder that names no figure says nothing
        // about a figure the issuer already stated, and must not un-state it.
        val known = DueMergeEngine.Candidate(
            dueRow(amount = BigDecimal("4321.50")),
            listOf(source(1, AXIS_SENDER, NoticeKind.STATEMENT))
        )

        val outcome = engine.merge(known, statement(amount = null))

        assertEquals(BigDecimal("4321.50"), outcome.due.amount)
        assertTrue(outcome.due.amountStated)
    }

    @Test
    fun `an unstated amount a notice restates as the same figure is still recorded as stated`() {
        // The flag cannot be inferred by comparing the figures afterwards: here they agree, and
        // the fact that changed is that somebody finally said so. A renewal reminder that named
        // no price, then the invoice itself at zero — R-20-09 does not reach an invoice, so the
        // row stays open and the zero it now holds is a figure rather than a gap.
        val unknown = DueMergeEngine.Candidate(
            dueRow(
                issuer = "GoDaddy",
                kind = StoredDueKind.INVOICE,
                amount = BigDecimal.ZERO,
                amountStated = false,
                accountLast4 = null,
                reference = "123456789012"
            ),
            listOf(source(1, AXIS_SENDER, NoticeKind.REMINDER))
        )

        val outcome = engine.merge(unknown, invoice(amount = BigDecimal.ZERO))

        assertEquals(0, outcome.due.amount.signum())
        assertTrue(outcome.due.amountStated)
        assertEquals(DueStatus.OPEN, outcome.due.status)
        assertTrue(outcome.changed)
    }

    // =========================================================================================
    // Escalation — REMINDER < DUE_TODAY < OVERDUE_NOTICE
    // =========================================================================================

    @Test
    fun `escalation is the highest any notice claimed`() {
        val existing = DueMergeEngine.Candidate(
            dueRow(),
            listOf(
                source(1, AXIS_SENDER, NoticeKind.STATEMENT),
                source(1, AMAZON_PAY_SENDER, NoticeKind.DUE_TODAY)
            )
        )

        val outcome = engine.merge(existing, statement(escalation = DueEscalation.DUE_SOON))

        assertEquals(NoticeKind.DUE_TODAY, outcome.escalation)
    }

    @Test
    fun `escalation never falls back once a notice has raised it`() {
        val existing = DueMergeEngine.Candidate(
            dueRow(),
            listOf(source(1, AMAZON_PAY_SENDER, NoticeKind.OVERDUE))
        )

        val outcome = engine.merge(existing, statement())

        assertEquals(NoticeKind.OVERDUE, outcome.escalation)
    }

    @Test
    fun `a statement claims no urgency`() {
        val existing = DueMergeEngine.Candidate(
            dueRow(),
            listOf(source(1, AXIS_SENDER, NoticeKind.STATEMENT))
        )

        assertNull(engine.merge(existing, statement()).escalation)
    }

    @Test
    fun `a payment confirmation is not an escalation`() {
        // §20.4 step 7 gives "thank you for paying" its own handling; it is not urgency.
        assertNull(engine.escalationOf(listOf(NoticeKind.PAYMENT_CONFIRMED)))
    }

    @Test
    fun `an auto-pay warning is an announcement, not an escalation`() {
        assertNull(engine.escalationOf(listOf(NoticeKind.AUTOPAY_UPCOMING)))
    }

    // =========================================================================================
    // The ladder itself
    // =========================================================================================

    @Test
    fun `the ladder ranks bank statement above bank reminder above the three aggregators`() {
        assertTrue(NoticePrecedence.ISSUER_STATEMENT.rank > NoticePrecedence.ISSUER_REMINDER.rank)
        assertTrue(NoticePrecedence.ISSUER_REMINDER.rank > NoticePrecedence.CRED.rank)
        assertTrue(NoticePrecedence.CRED.rank > NoticePrecedence.AMAZON_PAY.rank)
        assertTrue(NoticePrecedence.AMAZON_PAY.rank > NoticePrecedence.BILLDESK.rank)
    }

    @Test
    fun `an aggregator is recognised from the parser's source token`() {
        assertEquals(NoticePrecedence.CRED, engine.precedenceOf(credNotice()))
        assertEquals(
            NoticePrecedence.AMAZON_PAY,
            engine.precedenceOf(statement(source = "AMAZON_PAY"))
        )
        assertEquals(
            NoticePrecedence.BILLDESK,
            engine.precedenceOf(statement(source = "BILLDESK"))
        )
    }

    @Test
    fun `an aggregator on file is recognised from the sender registry`() {
        assertEquals(NoticePrecedence.CRED, engine.precedenceOf(source(1, CRED_SENDER)))
        assertEquals(
            NoticePrecedence.AMAZON_PAY,
            engine.precedenceOf(source(1, AMAZON_PAY_SENDER))
        )
        assertEquals(
            NoticePrecedence.ISSUER_STATEMENT,
            engine.precedenceOf(source(1, AXIS_SENDER, NoticeKind.STATEMENT))
        )
        assertEquals(
            NoticePrecedence.ISSUER_REMINDER,
            engine.precedenceOf(source(1, AXIS_SENDER, NoticeKind.OVERDUE))
        )
    }

    // =========================================================================================
    // The two DueKind vocabularies
    // =========================================================================================

    @Test
    fun `every parser due kind has a stored counterpart of the same name`() {
        // `DueEnums.kt` promises the two lists are pinned together; this is that pin. A parser
        // kind with no stored name would silently become OTHER and lose the due's meaning.
        val stored = StoredDueKind.entries.map { it.name }.toSet()
        val missing = ParserDueKind.entries.map { it.name }.filterNot { it in stored }
        assertEquals(emptyList<String>(), missing)
    }

    @Test
    fun `a parser kind maps to the stored kind of the same name`() {
        for (kind in ParserDueKind.entries) {
            assertEquals(kind.name, engine.storedKind(kind).name)
        }
    }

    // =========================================================================================
    // Fixtures
    // =========================================================================================

    private fun statement(
        issuer: String = "Axis Bank",
        kind: ParserDueKind = ParserDueKind.CARD_STATEMENT,
        amount: BigDecimal? = BigDecimal("4321.50"),
        dueDate: LocalDate = LocalDate.of(2026, 10, 1),
        currency: String = "INR",
        minimumDue: BigDecimal? = null,
        accountLast4: String? = "1234",
        reference: String? = null,
        periodLabel: String? = null,
        autoPay: Boolean = false,
        autoPayInstrument: String? = null,
        escalation: DueEscalation? = null,
        source: String = "AXIS",
        description: String? = null
    ): ParsedDue = ParsedDue(
        issuer = issuer,
        kind = kind,
        amount = amount,
        dueDate = dueDate,
        currency = currency,
        minimumDue = minimumDue,
        accountLast4 = accountLast4,
        reference = reference,
        periodLabel = periodLabel,
        autoPay = autoPay,
        autoPayInstrument = autoPayInstrument,
        escalation = escalation,
        source = source,
        description = description
    )

    /** CRED's copy of the same card bill — the issuer it names, never CRED itself. */
    private fun credNotice(
        amount: BigDecimal? = BigDecimal("4321.50"),
        minimumDue: BigDecimal? = null,
        periodLabel: String? = null,
        autoPay: Boolean = false
    ): ParsedDue = statement(
        amount = amount,
        minimumDue = minimumDue,
        periodLabel = periodLabel,
        autoPay = autoPay,
        source = "CRED"
    )

    private fun invoice(
        amount: BigDecimal? = BigDecimal("899.00"),
        reference: String? = "123456789012"
    ): ParsedDue = statement(
        issuer = "GoDaddy",
        kind = ParserDueKind.INVOICE,
        amount = amount,
        accountLast4 = null,
        reference = reference,
        source = "GODADDY"
    )

    private fun dueRow(
        id: Long = 1,
        dueId: String = "stored-key",
        issuer: String = "Axis Bank",
        kind: StoredDueKind = StoredDueKind.CARD_STATEMENT,
        amount: BigDecimal = BigDecimal("4321.50"),
        // The two machine flags, defaulted the way a row written by `newDue` from a notice that
        // named a figure carries them. A test that cares passes its own.
        amountStated: Boolean = true,
        autoDismissed: Boolean = false,
        currency: String = "INR",
        minimumDue: BigDecimal? = null,
        dueDate: LocalDate = LocalDate.of(2026, 10, 1),
        accountLast4: String? = "1234",
        reference: String? = null,
        periodLabel: String? = null,
        autoPay: Boolean = false,
        autoPayInstrument: String? = null,
        status: DueStatus = DueStatus.OPEN,
        source: DueSource = DueSource.EMAIL,
        note: String? = null,
        createdAt: LocalDateTime = now.minusDays(2)
    ): DueEntity = DueEntity(
        id = id,
        dueId = dueId,
        issuer = issuer,
        kind = kind,
        amount = amount,
        amountStated = amountStated,
        currency = currency,
        minimumDue = minimumDue,
        dueDate = dueDate,
        accountLast4 = accountLast4,
        reference = reference,
        periodLabel = periodLabel,
        autoPay = autoPay,
        autoPayInstrument = autoPayInstrument,
        status = status,
        autoDismissed = autoDismissed,
        source = source,
        note = note,
        createdAt = createdAt,
        updatedAt = createdAt
    )

    private fun source(
        dueId: Long,
        sender: String,
        noticeKind: NoticeKind = NoticeKind.STATEMENT
    ): DueSourceEntity = DueSourceEntity(
        dueId = dueId,
        channel = SourceChannel.EMAIL,
        sender = sender,
        noticeKind = noticeKind,
        receivedAt = now.minusDays(1)
    )

    private companion object {
        /** Registry senders, so `EmailSenderRegistry` resolves them as the app would. */
        const val AXIS_SENDER = "cc.statements@axis.bank.in"
        const val CRED_SENDER = "protect@cred.club"
        const val AMAZON_PAY_SENDER = "no-reply@amazonpay.in"
    }
}
