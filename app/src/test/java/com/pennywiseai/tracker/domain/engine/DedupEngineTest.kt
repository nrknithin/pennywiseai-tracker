package com.pennywiseai.tracker.domain.engine

import com.pennywiseai.tracker.data.database.entity.MatchRule
import com.pennywiseai.tracker.data.database.entity.SourceChannel
import com.pennywiseai.tracker.data.database.entity.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Spec 14, layer by layer.
 *
 * Every row of §14.1's table of real-world twin cases has a fixture pair here, and so does every
 * false-merge guard §14.3 asks for. The engine takes candidates as plain data, so none of this
 * needs Room, a device, or a network — which is the point of spec 02 §2.3 rule 4.
 *
 * All data is invented. Per spec 06 §6.2 the corpus's shapes are kept and its values are not:
 * last-4s are `1234`/`5678`, references are `123456789012`, VPAs are `someone@upi`, merchants are
 * `acme store`, and every amount is made up.
 */
class DedupEngineTest {

    /** Fixed so a test can never depend on the day it runs (spec 02 §2.8). */
    private val clock: Clock = Clock.fixed(Instant.parse("2026-09-15T12:00:00Z"), ZoneOffset.UTC)

    private val engine = DedupEngine(clock)

    private val defaults = DedupConfig()

    /** The owner's two accounts, as screen 42 would have seeded them (§14.5). */
    private val ownAccounts = DedupConfig(
        ownAccounts = listOf(
            OwnAccount(bank = "HDFC Bank", last4 = "1234"),
            OwnAccount(bank = "Axis Bank", last4 = "5678", vpa = "someone@upi")
        )
    )

    // -------------------------------------------------------------------------------------
    // §14.1 row 1 — HDFC UPI debit: bank SMS + bank email + gateway receipt
    // -------------------------------------------------------------------------------------

    @Test
    fun `the bank sms and the bank email for one upi debit collapse on the reference`() {
        val existing = candidate(
            id = 11,
            reference = REFERENCE,
            occurredAt = at(18, 2),
            sources = listOf(SourceFingerprint(SourceChannel.SMS, "HDFCBankParser"))
        )
        val email = input(
            reference = REFERENCE,
            occurredAt = at(18, 32),
            channel = SourceChannel.EMAIL,
            parserId = "H1"
        )

        assertLink(MatchRule.UPI_REF, 11, engine.evaluate(email, listOf(existing), defaults))
    }

    @Test
    fun `a gateway receipt that carries the upi reference links instead of creating`() {
        val existing = candidate(id = 12, reference = REFERENCE, occurredAt = at(18, 2))
        val receipt = input(
            reference = REFERENCE,
            occurredAt = at(18, 40),
            parserId = "RZ1",
            isBankSender = false,
            merchant = "acme store"
        )

        val decision = engine.evaluate(receipt, listOf(existing), defaults)

        assertLink(MatchRule.UPI_REF, 12, decision)
        assertFalse(engine.mayCreateTransaction(receipt, decision))
    }

    @Test
    fun `a reference outside the twenty four hour window is not a match`() {
        val existing = candidate(id = 13, reference = REFERENCE, occurredAt = at(18, 0))
        // 25 h later: L1's window has closed, L2's has long closed, and L3 needs the same day.
        val late = input(reference = REFERENCE, occurredAt = at(18, 0).plusHours(25))

        assertEquals(DedupDecision.NewTransaction, engine.evaluate(late, listOf(existing), defaults))
    }

    // -------------------------------------------------------------------------------------
    // §14.1 row 2 — Axis card purchase: bank SMS + bank email + merchant receipt
    // -------------------------------------------------------------------------------------

    @Test
    fun `a card purchase reported by sms and by email collapses inside the alert window`() {
        val existing = candidate(
            id = 21,
            bank = "Axis Bank",
            last4 = "5678",
            type = TransactionType.EXPENSE,
            merchant = "acme store",
            occurredAt = at(14, 3),
            sources = listOf(SourceFingerprint(SourceChannel.SMS, "AxisBankParser"))
        )
        // The email parser types the same charge CREDIT because it came off a credit card.
        val email = input(
            bank = "Axis Bank",
            last4 = "5678",
            type = TransactionType.CREDIT,
            merchant = "acme store",
            occurredAt = at(14, 15),
            parserId = "A2"
        )

        assertLink(MatchRule.AMOUNT_WINDOW, 21, engine.evaluate(email, listOf(existing), defaults))
    }

    @Test
    fun `a merchant receipt links to the card charge by amount and merchant`() {
        val existing = candidate(
            id = 22,
            bank = "Axis Bank",
            last4 = "5678",
            merchant = "acme store",
            occurredAt = at(14, 3)
        )
        // Order mail arrives hours later, names no account, and spells the merchant differently.
        val receipt = input(
            bank = null,
            last4 = null,
            merchant = "acme store online",
            occurredAt = at(20, 30),
            parserId = "AMZ1",
            isBankSender = false
        )

        assertLink(MatchRule.GATEWAY_ORDER, 22, engine.evaluate(receipt, listOf(existing), defaults))
    }

    @Test
    fun `a gateway receipt links by order id`() {
        val existing = candidate(id = 23, reference = "ORDER778899", occurredAt = at(9, 0))
        val receipt = input(
            bank = null,
            last4 = null,
            merchant = "some shop",
            occurredAt = at(9, 40),
            parserId = "RZ1",
            isBankSender = false,
            orderId = "order-778899"
        )

        assertLink(MatchRule.GATEWAY_ORDER, 23, engine.evaluate(receipt, listOf(existing), defaults))
    }

    @Test
    fun `a gateway receipt rounding difference is inside the gateway tolerance`() {
        val existing = candidate(id = 24, amount = "1450.00", merchant = "acme store", occurredAt = at(9, 0))
        val receipt = input(
            amount = "1450.50",
            bank = null,
            last4 = null,
            merchant = "acme store",
            occurredAt = at(9, 30),
            parserId = "CS1",
            isBankSender = false
        )

        assertLink(MatchRule.GATEWAY_ORDER, 24, engine.evaluate(receipt, listOf(existing), defaults))
    }

    @Test
    fun `a bank alert is never treated as a gateway receipt`() {
        // Same amount and merchant a day later, but no account, no bank, no reference: the only
        // layer that could fire is L4, and L4 does not apply to a bank's own alert.
        val existing = candidate(id = 25, merchant = "acme store", occurredAt = at(9, 0))
        val bankAlert = input(
            bank = null,
            last4 = null,
            merchant = "acme store",
            occurredAt = at(9, 0).plusHours(20),
            isBankSender = true
        )

        assertEquals(DedupDecision.NewTransaction, engine.evaluate(bankAlert, listOf(existing), defaults))
    }

    @Test
    fun `a second receipt from the same gateway template does not link to the same charge`() {
        // Two separate charges of the same amount at the same shop, each with its own receipt.
        val existing = candidate(
            id = 26,
            merchant = "acme store",
            occurredAt = at(9, 0),
            sources = listOf(SourceFingerprint(SourceChannel.EMAIL, "RZ1")),
            sameTemplate = 1
        )
        val secondReceipt = input(
            bank = null,
            last4 = null,
            merchant = "acme store",
            occurredAt = at(9, 30),
            parserId = "RZ1",
            isBankSender = false,
            isFromCard = true
        )

        assertEquals(
            DedupDecision.NewTransaction,
            engine.evaluate(secondReceipt, listOf(existing), defaults)
        )
    }

    // -------------------------------------------------------------------------------------
    // §14.1 row 3 — card bill paid by NetBanking: bank debit + issuer mail + CRED confirmation
    // -------------------------------------------------------------------------------------

    @Test
    fun `a card payment confirmation links to the netbanking debit instead of doubling it`() {
        val existing = candidate(
            id = 31,
            amount = "8000.00",
            type = TransactionType.TRANSFER,
            merchant = "credit card payment",
            occurredAt = at(11, 0)
        )
        val confirmation = input(
            amount = "8000.00",
            type = TransactionType.EXPENSE,
            bank = null,
            last4 = null,
            merchant = "credit card payment",
            occurredAt = at(13, 30),
            parserId = "C2",
            isBankSender = false
        )

        val decision = engine.evaluate(confirmation, listOf(existing), defaults)

        assertLink(MatchRule.GATEWAY_ORDER, 31, decision)
        assertFalse(engine.mayCreateTransaction(confirmation, decision))
    }

    @Test
    fun `a payment confirmation that matches nothing and names no instrument is link only`() {
        val confirmation = input(
            bank = null,
            last4 = null,
            merchant = "credit card payment",
            parserId = "C2",
            isBankSender = false
        )

        assertEquals(
            DedupDecision.LinkOnly(null, DedupEngine.GATEWAY_LINK_ONLY),
            engine.evaluate(confirmation, emptyList(), defaults)
        )
    }

    // -------------------------------------------------------------------------------------
    // §14.1 row 4 — own-account transfer: debit in bank A, credit in bank B
    // -------------------------------------------------------------------------------------

    @Test
    fun `a debit in one bank and the credit in the other become one transfer`() {
        val debit = candidate(
            id = 41,
            bank = "HDFC Bank",
            last4 = "1234",
            type = TransactionType.TRANSFER,
            reference = REFERENCE,
            occurredAt = at(16, 10)
        )
        val credit = input(
            bank = "Axis Bank",
            last4 = "5678",
            type = TransactionType.INCOME,
            reference = REFERENCE,
            occurredAt = at(16, 13),
            parserId = "A1"
        )

        assertLink(MatchRule.TRANSFER_LINK, 41, engine.evaluate(credit, listOf(debit), defaults))
    }

    @Test
    fun `the credit leg typed transfer still pairs with its debit instead of merging into it`() {
        // DEFECT 2. §14.5 re-types this credit TRANSFER rather than INCOME so that moving savings
        // between two own banks never counts as income. Read the leg off the type and both sides
        // are "money out": L1 then merges them on their shared reference — recording UPI_REF and
        // absorbing the credit into the debit — and L5, which wants opposite legs, never fires.
        val debit = candidate(
            id = 46,
            bank = "HDFC Bank",
            last4 = "1234",
            type = TransactionType.TRANSFER,
            reference = REFERENCE,
            occurredAt = at(16, 10)
        )
        val credit = input(
            bank = "Axis Bank",
            last4 = "5678",
            type = TransactionType.TRANSFER,
            reference = REFERENCE,
            occurredAt = at(16, 13),
            parserId = "A1",
            direction = MoneyDirection.IN
        )

        assertLink(MatchRule.TRANSFER_LINK, 46, engine.evaluate(credit, listOf(debit), defaults))
    }

    @Test
    fun `two own accounts pair on time when both legs are typed transfer`() {
        // DEFECT 2, without a reference to lean on: the leg is the only thing that can separate
        // these two records, and L5 is the only layer that can join them.
        val debit = candidate(
            id = 47,
            bank = "HDFC Bank",
            last4 = "1234",
            type = TransactionType.TRANSFER,
            occurredAt = at(16, 10),
            direction = MoneyDirection.OUT
        )
        val credit = input(
            bank = "Axis Bank",
            last4 = "5678",
            type = TransactionType.TRANSFER,
            occurredAt = at(16, 16),
            parserId = "A1",
            direction = MoneyDirection.IN
        )

        assertLink(MatchRule.TRANSFER_LINK, 47, engine.evaluate(credit, listOf(debit), ownAccounts))
    }

    @Test
    fun `a transfer that does not say which leg it is reads as the debit side`() {
        // The fallback, stated so it cannot change by accident: this app stores a transfer on the
        // account the money left, so an unmarked TRANSFER is money out and twins the bank's own
        // debit alert rather than pairing with it.
        val debitAlert = candidate(
            id = 48,
            type = TransactionType.EXPENSE,
            occurredAt = at(16, 10),
            sources = listOf(source(channel = SourceChannel.SMS, parserId = "HDFCBankParser"))
        )
        val unmarked = input(
            type = TransactionType.TRANSFER,
            occurredAt = at(16, 25),
            parserId = "H1"
        )

        assertLink(MatchRule.AMOUNT_WINDOW, 48, engine.evaluate(unmarked, listOf(debitAlert), defaults))
    }

    @Test
    fun `two own accounts link on amount and time when neither message carries a reference`() {
        val debit = candidate(
            id = 42,
            bank = "HDFC Bank",
            last4 = "1234",
            type = TransactionType.TRANSFER,
            occurredAt = at(16, 10)
        )
        val credit = input(
            bank = "Axis Bank",
            last4 = "5678",
            type = TransactionType.INCOME,
            occurredAt = at(16, 16),
            parserId = "A1"
        )

        assertLink(MatchRule.TRANSFER_LINK, 42, engine.evaluate(credit, listOf(debit), ownAccounts))
    }

    @Test
    fun `an unreferenced credit outside the transfer window is income, not a transfer`() {
        val debit = candidate(
            id = 43,
            bank = "HDFC Bank",
            last4 = "1234",
            type = TransactionType.TRANSFER,
            occurredAt = at(16, 10)
        )
        val credit = input(
            bank = "Axis Bank",
            last4 = "5678",
            type = TransactionType.INCOME,
            occurredAt = at(16, 25),
            parserId = "A1"
        )

        assertEquals(
            DedupDecision.NewTransaction,
            engine.evaluate(credit, listOf(debit), ownAccounts)
        )
    }

    @Test
    fun `a credit from an account the user does not own is not a transfer`() {
        val debit = candidate(
            id = 44,
            bank = "HDFC Bank",
            last4 = "1234",
            type = TransactionType.TRANSFER,
            occurredAt = at(16, 10)
        )
        val credit = input(
            bank = "ICICI Bank",
            last4 = "4321",
            type = TransactionType.INCOME,
            occurredAt = at(16, 13),
            parserId = "I1"
        )

        assertEquals(
            DedupDecision.NewTransaction,
            engine.evaluate(credit, listOf(debit), ownAccounts)
        )
    }

    @Test
    fun `a refund back to the same account is a second movement, not a transfer`() {
        // Same reference, same account, opposite direction: the money came back. Absorbing it
        // into the original charge would delete a real credit from the user's history.
        val charge = candidate(
            id = 45,
            bank = "HDFC Bank",
            last4 = "1234",
            reference = REFERENCE,
            occurredAt = at(16, 10)
        )
        val refund = input(
            bank = "HDFC Bank",
            last4 = "1234",
            type = TransactionType.INCOME,
            reference = REFERENCE,
            occurredAt = at(16, 40)
        )

        assertEquals(DedupDecision.NewTransaction, engine.evaluate(refund, listOf(charge), ownAccounts))
    }

    // -------------------------------------------------------------------------------------
    // §14.1 row 5 — mandate charge: two HDFC templates, both by email, plus a store receipt
    // -------------------------------------------------------------------------------------

    @Test
    fun `the two bank emails for one mandate charge collapse although both are email`() {
        // Spec 10 §10.8 item 4: "e-mandate paid" and "charge without OTP" describe one debit.
        val existing = candidate(
            id = 51,
            amount = "649.00",
            occurredAt = at(6, 12),
            sources = listOf(SourceFingerprint(SourceChannel.EMAIL, "H7")),
            sameTemplate = 0
        )
        val twin = input(
            amount = "649.00",
            occurredAt = at(6, 14),
            channel = SourceChannel.EMAIL,
            parserId = "H8"
        )

        assertLink(MatchRule.AMOUNT_WINDOW, 51, engine.evaluate(twin, listOf(existing), defaults))
    }

    @Test
    fun `a second email from the same template is a second charge, not a twin`() {
        val existing = candidate(
            id = 52,
            amount = "649.00",
            occurredAt = at(6, 12),
            sources = listOf(SourceFingerprint(SourceChannel.EMAIL, "H7")),
            sameTemplate = 1
        )
        val another = input(
            amount = "649.00",
            occurredAt = at(6, 20),
            channel = SourceChannel.EMAIL,
            parserId = "H7"
        )

        assertEquals(DedupDecision.NewTransaction, engine.evaluate(another, listOf(existing), defaults))
    }

    @Test
    fun `the same template guard also holds when the caller forgot to count the sources`() {
        // sameTemplateSourceCount is 0 — the ingestor did not fill it in — but the fingerprints
        // still say this transaction already absorbed an H7 email.
        val existing = candidate(
            id = 53,
            amount = "649.00",
            occurredAt = at(6, 12),
            sources = listOf(SourceFingerprint(SourceChannel.EMAIL, "H7")),
            sameTemplate = 0
        )
        val another = input(
            amount = "649.00",
            occurredAt = at(6, 20),
            channel = SourceChannel.EMAIL,
            parserId = "H7"
        )

        assertEquals(DedupDecision.NewTransaction, engine.evaluate(another, listOf(existing), defaults))
    }

    // -------------------------------------------------------------------------------------
    // §14.1 row 6 — loan EMI: bank email + bank SMS, no reference on either side
    // -------------------------------------------------------------------------------------

    @Test
    fun `an emi debit reported by email and by sms collapses`() {
        val existing = candidate(
            id = 61,
            amount = "18746.00",
            merchant = null,
            occurredAt = at(7, 30),
            sources = listOf(SourceFingerprint(SourceChannel.SMS, "HDFCBankParser"))
        )
        val email = input(
            amount = "18746.00",
            merchant = "loan emi",
            occurredAt = at(7, 34),
            parserId = "H5"
        )

        assertLink(MatchRule.AMOUNT_WINDOW, 61, engine.evaluate(email, listOf(existing), defaults))
    }

    // -------------------------------------------------------------------------------------
    // L0 — exact re-delivery, and only that (§14.3 L0)
    // -------------------------------------------------------------------------------------

    @Test
    fun `the same mail delivered twice links however far apart the two readings are dated`() {
        // Gmail hands the mail back after a UIDVALIDITY reset: same Message-ID, same body. The
        // two readings are dated 40 days apart only because the re-parse took a different date
        // out of the body — L0 has no window, so that cannot matter.
        val existing = candidate(
            id = 1,
            occurredAt = at(9, 0).minusDays(40),
            sources = listOf(
                source(bodyHash = "other-hash", messageId = "<other-mail@example.com>"),
                source(bodyHash = BODY_HASH, messageId = MESSAGE_ID)
            ),
            sameTemplate = 3
        )
        val redelivery = input(
            bodyHash = BODY_HASH,
            occurredAt = at(9, 0),
            parserId = "H1",
            identity = identity(messageId = MESSAGE_ID)
        )

        // L0 outranks both guards on purpose: a re-delivery is the same message, not a twin.
        assertLink(MatchRule.HASH, 1, engine.evaluate(redelivery, listOf(existing), defaults))
    }

    @Test
    fun `an sms restore replays a message and links on its sender and timestamp`() {
        // An SMS has no Message-ID. The restore keeps the sender and the `date` column, which is
        // what identifies the message when the stronger key is missing.
        val existing = candidate(
            id = 4,
            occurredAt = at(9, 0),
            sources = listOf(
                source(
                    channel = SourceChannel.SMS,
                    parserId = "HDFCBankParser",
                    bodyHash = BODY_HASH,
                    sender = SMS_SENDER,
                    receivedAt = at(9, 0)
                )
            )
        )
        val restored = input(
            bodyHash = BODY_HASH,
            occurredAt = at(9, 0),
            channel = SourceChannel.SMS,
            parserId = "HDFCBankParser",
            identity = identity(sender = SMS_SENDER, receivedAt = at(9, 0))
        )

        assertLink(MatchRule.HASH, 4, engine.evaluate(restored, listOf(existing), defaults))
    }

    @Test
    fun `a second charge whose template reads identically is not a re-delivery`() {
        // DEFECT 1. A fixed subscription debit prints the same words and the same amount every
        // month, so `body_hash` alone is equal for two genuinely separate charges. With L0's
        // infinite window and no other guard, the second charge would be swallowed by the first
        // and a month of money would disappear.
        val firstCharge = candidate(
            id = 5,
            amount = "649.00",
            occurredAt = at(9, 0),
            sources = listOf(source(bodyHash = BODY_HASH, receivedAt = at(9, 0)))
        )
        val nextMonth = input(
            amount = "649.00",
            bodyHash = BODY_HASH,
            occurredAt = at(9, 0).plusDays(30),
            identity = identity(receivedAt = at(9, 0).plusDays(30))
        )

        assertEquals(
            DedupDecision.NewTransaction,
            engine.evaluate(nextMonth, listOf(firstCharge), defaults)
        )
    }

    @Test
    fun `an identical body from a different sender is not a re-delivery`() {
        // DEFECT 1. Two senders can template the same words; one of them is not the other's copy.
        val existing = candidate(
            id = 6,
            occurredAt = at(9, 0),
            sources = listOf(source(bodyHash = BODY_HASH, receivedAt = at(9, 0)))
        )
        val lookalike = input(
            bodyHash = BODY_HASH,
            occurredAt = at(9, 0).plusDays(30),
            identity = identity(sender = OTHER_SENDER, receivedAt = at(9, 0))
        )

        assertNull(engine.explain(lookalike, existing, defaults))
    }

    @Test
    fun `a body hash with no source identity cannot fire L0`() {
        // DEFECT 1. A hash the caller cannot attribute to a message is not evidence of anything,
        // so the record is judged on its content by the layers below instead.
        val existing = candidate(
            id = 7,
            occurredAt = at(9, 0),
            sources = listOf(source(bodyHash = BODY_HASH, identity = null))
        )
        val incoming = input(
            bodyHash = BODY_HASH,
            occurredAt = at(9, 0).plusDays(30),
            identity = null
        )

        assertNull(engine.explain(incoming, existing, defaults))
    }

    @Test
    fun `an identical body on another channel is not a re-delivery`() {
        // DEFECT 1. The bank's SMS and its email say the same thing; they are the twin pair L2
        // exists for, not one message arriving twice.
        val existing = candidate(
            id = 8,
            occurredAt = at(9, 0),
            sources = listOf(
                source(channel = SourceChannel.SMS, bodyHash = BODY_HASH, receivedAt = at(9, 0))
            )
        )
        val email = input(
            bodyHash = BODY_HASH,
            occurredAt = at(9, 0).plusDays(30),
            channel = SourceChannel.EMAIL,
            identity = identity(receivedAt = at(9, 0))
        )

        assertNull(engine.explain(email, existing, defaults))
    }

    @Test
    fun `an identical transaction hash links`() {
        val existing = candidate(id = 2, transactionHash = "txn-hash-1", occurredAt = at(9, 0))
        val again = input(
            transactionHash = "txn-hash-1",
            bodyHash = "a-different-body",
            occurredAt = at(9, 0).plusDays(3)
        )

        assertLink(MatchRule.HASH, 2, engine.evaluate(again, listOf(existing), defaults))
    }

    @Test
    fun `an empty body hash matches nothing`() {
        // Spec 11 §11.5 as this fork resolved it: a bodyless mail stores `body_hash = ""`, and
        // every such mail would otherwise share it.
        val existing = candidate(
            id = 3,
            occurredAt = at(3, 0),
            sources = listOf(source(bodyHash = "", receivedAt = at(3, 0)))
        )
        val blank = input(
            bodyHash = "",
            occurredAt = at(20, 0),
            bank = "Axis Bank",
            identity = identity(receivedAt = at(3, 0))
        )

        assertNull(engine.explain(blank, existing, defaults))
    }

    // -------------------------------------------------------------------------------------
    // The false-merge guards (§14.3)
    // -------------------------------------------------------------------------------------

    @Test
    fun `two genuine charges of the same amount on the same day with different references never merge`() {
        val first = candidate(id = 71, reference = REFERENCE, occurredAt = at(13, 0))
        val second = input(reference = OTHER_REFERENCE, occurredAt = at(13, 5))

        assertEquals(DedupDecision.NewTransaction, engine.evaluate(second, listOf(first), defaults))
        assertTrue(DedupEngine.conflictingReference(second, first))
    }

    @Test
    fun `a truncated reference is not a conflicting one`() {
        // Some templates print only the tail of the RRN; that is agreement, not disagreement.
        val existing = candidate(id = 72, reference = REFERENCE, occurredAt = at(13, 0))
        val tail = input(reference = REFERENCE.takeLast(6), occurredAt = at(13, 5))

        assertFalse(DedupEngine.conflictingReference(tail, existing))
        assertLink(MatchRule.AMOUNT_WINDOW, 72, engine.evaluate(tail, listOf(existing), defaults))
    }

    @Test
    fun `a reference too short to identify a payment is not evidence of a conflict`() {
        val existing = candidate(id = 73, reference = "12", occurredAt = at(13, 0))
        val incoming = input(reference = REFERENCE, occurredAt = at(13, 5))

        assertFalse(DedupEngine.conflictingReference(incoming, existing))
        assertLink(MatchRule.AMOUNT_WINDOW, 73, engine.evaluate(incoming, listOf(existing), defaults))
    }

    @Test
    fun `a transaction that already absorbed the same template cannot absorb another`() {
        val existing = candidate(
            id = 74,
            occurredAt = at(13, 0),
            sources = listOf(SourceFingerprint(SourceChannel.SMS, "HDFCBankParser")),
            sameTemplate = 1
        )
        val incoming = input(occurredAt = at(13, 5), channel = SourceChannel.SMS, parserId = "HDFCBankParser")

        assertTrue(DedupEngine.blocksSameChannelMerge(existing))
        assertEquals(DedupDecision.NewTransaction, engine.evaluate(incoming, listOf(existing), defaults))
    }

    @Test
    fun `amounts in different currencies never merge`() {
        val existing = candidate(id = 75, amount = "5.00", currency = "USD", occurredAt = at(13, 0))
        val incoming = input(amount = "5.00", currency = "INR", occurredAt = at(13, 5))

        assertEquals(DedupDecision.NewTransaction, engine.evaluate(incoming, listOf(existing), defaults))
    }

    @Test
    fun `a record whose currency is unknown never merges into one that names a currency`() {
        // DEFECT 3. Treating blank as "matches anything" does not merely merge — it *assigns* the
        // transaction's currency to a record that never named one, which is inventing the value
        // that decides whether 5.00 is five rupees or five dollars (spec 01 C7).
        val inRupees = candidate(id = 79, amount = "5.00", currency = "INR", occurredAt = at(13, 0))
        val unknown = input(amount = "5.00", currency = "  ", occurredAt = at(13, 5))

        assertEquals(DedupDecision.NewTransaction, engine.evaluate(unknown, listOf(inRupees), defaults))

        // And the same in the other direction: a stored row with no currency is not an INR row.
        val unknownRow = candidate(id = 80, amount = "5.00", currency = "", occurredAt = at(13, 0))
        val inRupeesIncoming = input(amount = "5.00", currency = "INR", occurredAt = at(13, 5))

        assertEquals(
            DedupDecision.NewTransaction,
            engine.evaluate(inRupeesIncoming, listOf(unknownRow), defaults)
        )
    }

    @Test
    fun `two records that both name no currency still merge`() {
        // The deliberate other half of DEFECT 3: neither side claims a currency, so merging them
        // assigns nothing to either. This is the legacy row the old wildcard was written for.
        val existing = candidate(id = 89, amount = "5.00", currency = "", occurredAt = at(13, 0))
        val incoming = input(amount = "5.00", currency = "", occurredAt = at(13, 5))

        assertLink(MatchRule.AMOUNT_WINDOW, 89, engine.evaluate(incoming, listOf(existing), defaults))
    }

    @Test
    fun `a credit never merges with a debit at the amount layers`() {
        val existing = candidate(id = 76, occurredAt = at(13, 0))
        val credit = input(type = TransactionType.INCOME, occurredAt = at(13, 5))

        // L5 cannot save it either: without own accounts or a shared reference there is no pair.
        assertEquals(DedupDecision.NewTransaction, engine.evaluate(credit, listOf(existing), defaults))
    }

    @Test
    fun `different banks never merge at the amount layers`() {
        val existing = candidate(id = 77, bank = "Axis Bank", occurredAt = at(13, 0))
        val incoming = input(bank = "HDFC Bank", occurredAt = at(13, 5))

        assertEquals(DedupDecision.NewTransaction, engine.evaluate(incoming, listOf(existing), defaults))
    }

    @Test
    fun `different account last four never merge`() {
        val existing = candidate(id = 78, last4 = "5678", occurredAt = at(13, 0))
        val incoming = input(last4 = "1234", occurredAt = at(13, 5))

        assertEquals(DedupDecision.NewTransaction, engine.evaluate(incoming, listOf(existing), defaults))
    }

    // -------------------------------------------------------------------------------------
    // Windows and the L3 fallback
    // -------------------------------------------------------------------------------------

    @Test
    fun `the alert window is inclusive at its edge`() {
        val existing = candidate(id = 81, occurredAt = at(10, 0))
        val onTheEdge = input(occurredAt = at(10, 30))

        assertLink(MatchRule.AMOUNT_WINDOW, 81, engine.evaluate(onTheEdge, listOf(existing), defaults))
    }

    @Test
    fun `past the alert window the same day fallback takes over`() {
        val existing = candidate(id = 82, merchant = "acme store", occurredAt = at(10, 0))
        val late = input(merchant = "acme stores", occurredAt = at(15, 20))

        assertLink(MatchRule.SAME_DAY, 82, engine.evaluate(late, listOf(existing), defaults))
    }

    @Test
    fun `the same day fallback can be switched off`() {
        val existing = candidate(id = 83, merchant = "acme store", occurredAt = at(10, 0))
        val late = input(merchant = "acme stores", occurredAt = at(15, 20))

        assertEquals(
            DedupDecision.NewTransaction,
            engine.evaluate(late, listOf(existing), defaults.copy(sameDayFallback = false))
        )
    }

    @Test
    fun `the same day fallback accepts an unknown merchant on either side`() {
        val existing = candidate(id = 84, merchant = null, last4 = null, occurredAt = at(10, 0))
        val late = input(merchant = "acme store", occurredAt = at(15, 20))

        assertLink(MatchRule.SAME_DAY, 84, engine.evaluate(late, listOf(existing), defaults))
    }

    @Test
    fun `the same day fallback rejects a different merchant`() {
        val existing = candidate(id = 85, merchant = "zenith travels", occurredAt = at(10, 0))
        val late = input(merchant = "acme store", occurredAt = at(15, 20))

        assertEquals(DedupDecision.NewTransaction, engine.evaluate(late, listOf(existing), defaults))
    }

    @Test
    fun `the same day fallback does not cross midnight`() {
        val existing = candidate(id = 86, merchant = "acme store", occurredAt = at(23, 50))
        val nextDay = input(merchant = "acme store", occurredAt = at(23, 50).plusHours(2))

        assertEquals(DedupDecision.NewTransaction, engine.evaluate(nextDay, listOf(existing), defaults))
    }

    @Test
    fun `the same day fallback still refuses two different accounts`() {
        val existing = candidate(id = 87, last4 = "5678", merchant = "acme store", occurredAt = at(10, 0))
        val late = input(last4 = "1234", merchant = "acme store", occurredAt = at(15, 20))

        assertEquals(DedupDecision.NewTransaction, engine.evaluate(late, listOf(existing), defaults))
    }

    @Test
    fun `a merchant similarity threshold can be tightened by settings`() {
        val existing = candidate(id = 88, merchant = "acme store", occurredAt = at(10, 0))
        val late = input(merchant = "acme stores", occurredAt = at(15, 20))

        assertEquals(
            DedupDecision.NewTransaction,
            engine.evaluate(late, listOf(existing), defaults.copy(merchantSimilarity = 1.0))
        )
    }

    // -------------------------------------------------------------------------------------
    // Layer order, candidate choice, explanations
    // -------------------------------------------------------------------------------------

    @Test
    fun `the nearest candidate in a layer wins`() {
        // Both are inside the 30-minute alert window, so the layer has to choose.
        val far = candidate(id = 91, occurredAt = at(12, 0))
        val near = candidate(id = 92, occurredAt = at(12, 20))
        val incoming = input(occurredAt = at(12, 25))

        assertLink(MatchRule.AMOUNT_WINDOW, 92, engine.evaluate(incoming, listOf(far, near), defaults))
    }

    @Test
    fun `layers are tried in order, so a reference match outranks an amount match`() {
        val byAmount = candidate(id = 93, occurredAt = at(12, 20))
        val byReference = candidate(
            id = 94,
            reference = REFERENCE,
            last4 = null,
            bank = null,
            occurredAt = at(4, 0)
        )
        val incoming = input(reference = REFERENCE, occurredAt = at(12, 25))

        assertLink(MatchRule.UPI_REF, 94, engine.evaluate(incoming, listOf(byAmount, byReference), defaults))
    }

    @Test
    fun `explain names the same rule that evaluate used`() {
        val existing = candidate(id = 95, reference = REFERENCE, occurredAt = at(12, 0))
        val incoming = input(reference = REFERENCE, occurredAt = at(12, 20))

        val decision = engine.evaluate(incoming, listOf(existing), defaults) as DedupDecision.Link
        assertEquals(decision.rule, engine.explain(incoming, existing, defaults))
    }

    @Test
    fun `explain returns null when no layer fires`() {
        val existing = candidate(id = 96, bank = "Axis Bank", occurredAt = at(3, 0))
        val incoming = input(bank = "HDFC Bank", occurredAt = at(20, 0))

        assertNull(engine.explain(incoming, existing, defaults))
    }

    @Test
    fun `no candidates means a new transaction for a bank message`() {
        assertEquals(DedupDecision.NewTransaction, engine.evaluate(input(), emptyList(), defaults))
    }

    // -------------------------------------------------------------------------------------
    // L4's create rule (§14.3 L4 as resolved in docs/FORK-CHANGES.md)
    // -------------------------------------------------------------------------------------

    @Test
    fun `a gateway charge on a card no bank reports is still recorded`() {
        val receipt = input(
            amount = "5.00",
            currency = "USD",
            bank = null,
            last4 = null,
            merchant = "some service",
            parserId = "P2",
            isBankSender = false,
            isFromCard = true
        )

        assertEquals(DedupDecision.NewTransaction, engine.evaluate(receipt, emptyList(), defaults))
        assertTrue(engine.mayCreateTransaction(receipt, DedupDecision.NewTransaction))
    }

    @Test
    fun `a gateway message naming a source account may create a transaction`() {
        val receipt = input(
            bank = null,
            last4 = null,
            parserId = "RZ1",
            isBankSender = false,
            fromAccount = "someone@upi"
        )

        assertTrue(engine.mayCreateTransaction(receipt, DedupDecision.NewTransaction))
    }

    @Test
    fun `a gateway message naming no instrument may not create a transaction`() {
        val receipt = input(bank = null, last4 = null, parserId = "RZ1", isBankSender = false)

        assertFalse(engine.mayCreateTransaction(receipt, DedupDecision.NewTransaction))
    }

    @Test
    fun `no link of any kind may create a transaction`() {
        val bankMessage = input()

        assertFalse(
            engine.mayCreateTransaction(bankMessage, DedupDecision.Link(1, MatchRule.AMOUNT_WINDOW))
        )
        assertFalse(
            engine.mayCreateTransaction(bankMessage, DedupDecision.LinkOnly(1, "GATEWAY_ORDER"))
        )
    }

    // -------------------------------------------------------------------------------------
    // Primary-source promotion (spec 10 §10.8 item 5)
    // -------------------------------------------------------------------------------------

    @Test
    fun `the bank alert takes the primary slot from a gateway origin transaction`() {
        val gatewayOrigin = candidate(
            id = 101,
            merchant = "acme store",
            occurredAt = at(12, 0),
            isGatewayOrigin = true
        )
        val bankAlert = input(occurredAt = at(12, 10))

        val decision = engine.evaluate(bankAlert, listOf(gatewayOrigin), defaults) as DedupDecision.Link
        assertTrue(decision.promoteIncomingToPrimary)
    }

    @Test
    fun `the bank alert reaches a gateway origin transaction that rounded the amount`() {
        // DEFECT 4. Spec 10 §10.8 item 5 has the receipt land first and the bank's alert arrive
        // second to take over account and balance — so the side that rounded is the *stored* one.
        // A tolerance chosen from `incoming.isBankSender` alone is the exact tolerance of zero in
        // exactly that case, and the payment is counted twice.
        val gatewayOrigin = candidate(
            id = 103,
            amount = "1450.50",
            merchant = "acme store",
            occurredAt = at(12, 0),
            isGatewayOrigin = true
        )
        val bankAlert = input(amount = "1450.00", occurredAt = at(12, 10))

        val decision = engine.evaluate(bankAlert, listOf(gatewayOrigin), defaults)

        assertLink(MatchRule.AMOUNT_WINDOW, 103, decision)
        assertTrue((decision as DedupDecision.Link).promoteIncomingToPrimary)
    }

    @Test
    fun `two bank alerts that disagree by fifty paise are two charges`() {
        // The other half of DEFECT 4: the loose tolerance is for gateway rounding only. Two bank
        // alerts for one debit agree to the paisa, so amounts that differ are different money.
        val existing = candidate(id = 104, amount = "1450.50", occurredAt = at(12, 0))
        val bankAlert = input(amount = "1450.00", occurredAt = at(12, 10))

        assertEquals(DedupDecision.NewTransaction, engine.evaluate(bankAlert, listOf(existing), defaults))
    }

    @Test
    fun `a receipt arriving after the bank alert does not become primary`() {
        val bankOrigin = candidate(id = 102, merchant = "acme store", occurredAt = at(12, 0))
        val receipt = input(
            bank = null,
            last4 = null,
            merchant = "acme store",
            occurredAt = at(12, 40),
            parserId = "RZ1",
            isBankSender = false
        )

        val decision = engine.evaluate(receipt, listOf(bankOrigin), defaults) as DedupDecision.Link
        assertFalse(decision.promoteIncomingToPrimary)
    }

    // -------------------------------------------------------------------------------------
    // §14.4 enrichment
    // -------------------------------------------------------------------------------------

    @Test
    fun `enrichment fills only the holes`() {
        val existing = candidate(id = 111, reference = null, merchant = null, last4 = null)
        val incoming = input(reference = REFERENCE, merchant = "acme store", last4 = "1234")

        val enrichment = engine.enrich(incoming, existing)

        assertEquals(REFERENCE, enrichment.reference)
        assertEquals("acme store", enrichment.merchant)
        assertEquals("1234", enrichment.accountLast4)
        assertFalse(enrichment.isEmpty)
    }

    @Test
    fun `enrichment never overwrites what the transaction already knows`() {
        val existing = candidate(id = 112, reference = REFERENCE, merchant = "acme store", last4 = "1234")
        val incoming = input(reference = OTHER_REFERENCE, merchant = "other shop", last4 = "5678")

        val enrichment = engine.enrich(incoming, existing)

        assertNull(enrichment.reference)
        assertNull(enrichment.merchant)
        assertNull(enrichment.accountLast4)
        assertTrue(enrichment.isEmpty)
    }

    @Test
    fun `a stored vpa is upgraded to a real merchant name`() {
        val existing = candidate(id = 113, merchant = "someone@upi")
        val incoming = input(merchant = "acme store")

        assertEquals("acme store", engine.enrich(incoming, existing).merchant)
    }

    @Test
    fun `a vpa does not replace a stored vpa`() {
        val existing = candidate(id = 114, merchant = "someone@upi")
        val incoming = input(merchant = "someone@upi")

        assertNull(engine.enrich(incoming, existing).merchant)
    }

    @Test
    fun `fill keeps whatever the transaction already has`() {
        assertEquals(BigDecimal("10.00"), engine.fill(BigDecimal("10.00"), BigDecimal("20.00")))
        assertEquals(BigDecimal("20.00"), engine.fill(null, BigDecimal("20.00")))
        assertNull(engine.fill<String>(null, null))
    }

    // -------------------------------------------------------------------------------------
    // The injected clock
    // -------------------------------------------------------------------------------------

    @Test
    fun `the engine reads time only from the injected clock`() {
        assertEquals(LocalDateTime.of(2026, 9, 15, 12, 0), engine.now())
    }

    // -------------------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------------------

    private fun assertLink(rule: MatchRule, transactionId: Long, decision: DedupDecision) {
        val link = decision as? DedupDecision.Link
            ?: throw AssertionError("expected a Link under $rule, got $decision")
        assertEquals(rule, link.rule)
        assertEquals(transactionId, link.transactionId)
    }

    /** All fixtures sit on one invented day so "same calendar day" is under the test's control. */
    private fun at(hour: Int, minute: Int): LocalDateTime =
        LocalDateTime.of(2026, 9, 15, hour, minute)

    /**
     * One source row as the ingestor records it: channel, template, the body it carried and the
     * identity of the message that carried it.
     */
    private fun source(
        channel: SourceChannel = SourceChannel.EMAIL,
        parserId: String? = "H1",
        bodyHash: String? = BODY_HASH,
        sender: String? = SENDER,
        messageId: String? = null,
        receivedAt: LocalDateTime? = at(9, 0),
        identity: MessageIdentity? = MessageIdentity(sender, messageId, receivedAt)
    ) = SourceFingerprint(
        channel = channel,
        parserId = parserId,
        bodyHash = bodyHash,
        identity = identity
    )

    /** The incoming message's own identity: who sent it, which copy it is, when it was stamped. */
    private fun identity(
        sender: String? = SENDER,
        messageId: String? = null,
        receivedAt: LocalDateTime? = at(9, 0)
    ) = MessageIdentity(sender = sender, messageId = messageId, receivedAt = receivedAt)

    private fun input(
        amount: String = "1450.00",
        currency: String = "INR",
        type: TransactionType = TransactionType.EXPENSE,
        bank: String? = "HDFC Bank",
        last4: String? = "1234",
        reference: String? = null,
        merchant: String? = "acme store",
        occurredAt: LocalDateTime = at(12, 0),
        bodyHash: String = "incoming-body",
        transactionHash: String? = null,
        channel: SourceChannel = SourceChannel.EMAIL,
        parserId: String? = "H1",
        isBankSender: Boolean = true,
        orderId: String? = null,
        isFromCard: Boolean = false,
        fromAccount: String? = null,
        direction: MoneyDirection? = null,
        identity: MessageIdentity? = null
    ) = DedupInput(
        amount = BigDecimal(amount),
        currency = currency,
        type = type,
        bankName = bank,
        accountLast4 = last4,
        reference = reference,
        merchant = merchant,
        occurredAt = occurredAt,
        bodyHash = bodyHash,
        transactionHash = transactionHash,
        channel = channel,
        parserId = parserId,
        isBankSender = isBankSender,
        orderId = orderId,
        isFromCard = isFromCard,
        fromAccount = fromAccount,
        direction = direction,
        identity = identity
    )

    private fun candidate(
        id: Long = 1,
        amount: String = "1450.00",
        currency: String = "INR",
        type: TransactionType = TransactionType.EXPENSE,
        bank: String? = "HDFC Bank",
        last4: String? = "1234",
        reference: String? = null,
        merchant: String? = "acme store",
        occurredAt: LocalDateTime = at(12, 0),
        transactionHash: String? = "txn-$id",
        sources: List<SourceFingerprint> = listOf(
            source(
                channel = SourceChannel.SMS,
                parserId = "HDFCBankParser",
                bodyHash = "existing-body-$id",
                sender = SMS_SENDER
            )
        ),
        sameTemplate: Int = 0,
        isGatewayOrigin: Boolean = false,
        direction: MoneyDirection? = null
    ) = DedupCandidate(
        transactionId = id,
        amount = BigDecimal(amount),
        currency = currency,
        type = type,
        bankName = bank,
        accountLast4 = last4,
        reference = reference,
        merchant = merchant,
        occurredAt = occurredAt,
        transactionHash = transactionHash,
        sources = sources,
        sameTemplateSourceCount = sameTemplate,
        isGatewayOrigin = isGatewayOrigin,
        direction = direction
    )

    private companion object {
        /** A redacted UPI reference: right shape (12 digits), invented value (spec 06 §6.2). */
        const val REFERENCE = "123456789012"
        const val OTHER_REFERENCE = "210987654321"
        const val BODY_HASH = "0123456789abcdef0123456789abcdef"

        /** Invented senders. The shape is a bank's; the address and the header id are not. */
        const val SENDER = "alerts@example.com"
        const val OTHER_SENDER = "noreply@example.org"
        const val SMS_SENDER = "AD-BANKSM"
        const val MESSAGE_ID = "<redelivered-mail@example.com>"
    }
}
