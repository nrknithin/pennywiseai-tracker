package com.nrknithin.fintrack.presentation.accounts

import com.nrknithin.fintrack.data.database.entity.AccountBalanceEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * [AccountMergeGuard] — the refusal rules in front of the account merge (#368).
 *
 * **Why this suite exists.** Merge is the most destructive operation in the app: inside one
 * `database.withTransaction` it bulk-UPDATEs every transaction from the source to the target,
 * re-targets transfer legs, re-links cards, and DELETEs the source's balance snapshots outright.
 * There is no undo and no automatic backup. Until this file, none of that had any test at all —
 * `mergeAccounts` appeared nowhere under `app/src/test` or `app/src/androidTest`, so the three
 * conditions standing between a mis-tap and an irreversible data loss were unpinned, and a
 * fourth (the two accounts being at unrelated banks) was simply missing.
 *
 * **What it covers and what it cannot.** Everything here is the decision — may this merge run,
 * and what does the user get told when it may not. It is pure Kotlin over two entities, so no
 * Room, no Android and no Robolectric. What it does **not** cover is the transactional body
 * itself: that needs a real `FinTrackDatabase` (`database.withTransaction` cannot run against a
 * stubbed database), and belongs in a Robolectric suite built on `RoomTestDb` or an instrumented
 * test. `mergeAccounts` holds no copy of these rules — it asks [AccountMergeGuard] and returns
 * on any non-null answer — so the refusal paths are genuinely what is pinned below.
 *
 * All values are invented: banks are institutions, last-4s are the repo's redacted
 * `1234`/`5678`/`9012`, and no balance is anyone's real figure.
 */
class AccountMergeGuardTest {

    /** Fixed, so nothing here depends on the day the suite runs. */
    private val someTime: LocalDateTime = LocalDateTime.of(2026, 1, 15, 9, 0)

    private fun account(
        bank: String,
        last4: String,
        currency: String = "INR",
        isCreditCard: Boolean = false
    ): AccountBalanceEntity = AccountBalanceEntity(
        bankName = bank,
        accountLast4 = last4,
        // Irrelevant to the guard — it compares identities, never money.
        balance = BigDecimal("1000.00"),
        timestamp = someTime,
        currency = currency,
        isCreditCard = isCreditCard
    )

    // -------------------------------------------------------------------------------------
    // Refusal: the same account
    // -------------------------------------------------------------------------------------

    @Test
    fun `refuses merging an account into itself`() {
        val a = account("HDFC Bank", "1234")

        assertEquals(
            "Source and target are the same account",
            AccountMergeGuard.refusalReason(a, a)
        )
    }

    @Test
    fun `refuses a self-merge when the bank name differs only in case`() {
        // Case alone is genuinely the same row: the old guard compared with
        // ignoreCase = true, and nothing in the app can produce two rows that differ
        // only in case, because the DAO groups on the raw name and the parsers emit one
        // compile-time constant per bank.
        assertEquals(
            "Source and target are the same account",
            AccountMergeGuard.refusalReason(account("HDFC Bank", "1234"), account("hdfc bank", "1234"))
        )
    }

    @Test
    fun `allows merging two rows whose bank name differs only by whitespace`() {
        // The regression this pins. Trailing space makes a DISTINCT row — the DAO groups
        // on the raw `bank_name` and SQLite `=` is whitespace-sensitive, while
        // addAccount() stores whatever was typed and validateForm only checks
        // isNotBlank(). Two rows, identical on screen, same last-4: exactly the duplicate
        // merge exists to repair. An earlier version of this guard trimmed before the
        // identity test, called them "the same account", and — because the picker filters
        // through canMerge — hid the target, leaving no way to fix them at all.
        assertNull(
            AccountMergeGuard.refusalReason(account("HDFC Bank", "1234"), account("HDFC Bank ", "1234"))
        )
    }

    // -------------------------------------------------------------------------------------
    // Refusal: unrelated accounts — the guard this suite was written for
    // -------------------------------------------------------------------------------------

    @Test
    fun `refuses accounts that share neither bank nor number`() {
        val source = account("HDFC Bank", "1234")
        val target = account("ICICI Bank", "5678")

        val reason = AccountMergeGuard.refusalReason(source, target)

        assertNotNull("a merge across two unrelated banks must be refused", reason)
        assertTrue(
            "the message must name both accounts so the user can see the mis-tap: $reason",
            reason!!.contains("HDFC Bank ••1234") && reason.contains("ICICI Bank ••5678")
        )
    }

    @Test
    fun `refuses unrelated accounts in either direction`() {
        val a = account("HDFC Bank", "1234")
        val b = account("ICICI Bank", "5678")

        assertFalse(AccountMergeGuard.canMerge(a, b))
        assertFalse(AccountMergeGuard.canMerge(b, a))
    }

    @Test
    fun `refuses two mobile-money wallets from different services`() {
        // Every wallet carries the same WALLET sentinel instead of an account number, so
        // "same last-4" says nothing here — these are two different services.
        val source = account("Wallet Service One", AccountBalanceEntity.WALLET_ACCOUNT_MARKER)
        val target = account("Wallet Service Two", AccountBalanceEntity.WALLET_ACCOUNT_MARKER)

        assertFalse(AccountMergeGuard.canMerge(source, target))
    }

    @Test
    fun `refuses unrelated accounts before complaining about currency`() {
        // The most fundamental objection wins: telling the user the currencies differ would
        // imply the merge is fine once they match, which it is not.
        val source = account("HDFC Bank", "1234", currency = "INR")
        val target = account("ICICI Bank", "5678", currency = "USD")

        val reason = AccountMergeGuard.refusalReason(source, target)

        assertTrue(
            "expected the unrelated-accounts refusal, got: $reason",
            reason!!.contains("share neither bank nor account number")
        )
    }

    // -------------------------------------------------------------------------------------
    // Refusal: currency and account kind
    // -------------------------------------------------------------------------------------

    @Test
    fun `refuses a currency mismatch and names both currencies`() {
        val source = account("HDFC Bank", "1234", currency = "INR")
        val target = account("HDFC Bank", "5678", currency = "USD")

        assertEquals(
            "Currencies don't match (INR vs USD)",
            AccountMergeGuard.refusalReason(source, target)
        )
    }

    @Test
    fun `treats currency codes case-insensitively`() {
        val source = account("HDFC Bank", "1234", currency = "inr")
        val target = account("HDFC Bank", "5678", currency = "INR")

        assertNull(AccountMergeGuard.refusalReason(source, target))
    }

    @Test
    fun `refuses merging a credit card with a regular account`() {
        val card = account("HDFC Bank", "1234", isCreditCard = true)
        val savings = account("HDFC Bank", "5678", isCreditCard = false)

        assertEquals(
            "Can't merge a credit card with a regular account",
            AccountMergeGuard.refusalReason(card, savings)
        )
        assertEquals(
            "Can't merge a credit card with a regular account",
            AccountMergeGuard.refusalReason(savings, card)
        )
    }

    // -------------------------------------------------------------------------------------
    // Allowed: the merges the feature exists for
    // -------------------------------------------------------------------------------------

    @Test
    fun `allows two accounts at the same bank`() {
        val source = account("HDFC Bank", "1234")
        val target = account("HDFC Bank", "5678")

        assertNull(AccountMergeGuard.refusalReason(source, target))
        assertTrue(AccountMergeGuard.canMerge(source, target))
    }

    @Test
    fun `allows one account recorded twice under different bank-name spellings`() {
        // The commonest real duplicate: a manually-added account and the parser-created one
        // for the same real account, differing only in how the bank is written. A strict
        // same-bank rule would block exactly the case merge exists to fix.
        val manual = account("HDFC", "1234")
        val parsed = account("HDFC Bank", "1234")

        assertNull(AccountMergeGuard.refusalReason(manual, parsed))
    }

    @Test
    fun `allows two credit cards at the same bank`() {
        val source = account("HDFC Bank", "1234", isCreditCard = true)
        val target = account("HDFC Bank", "9012", isCreditCard = true)

        assertNull(AccountMergeGuard.refusalReason(source, target))
    }

    @Test
    fun `allows two manual cash accounts`() {
        // Cash accounts all carry the "CASH" sentinel and are all hand-made by the user;
        // folding two of them together is a reasonable cleanup, not a mis-tap.
        val source = account("Cash", "CASH")
        val target = account("Pocket money", "CASH")

        assertNull(AccountMergeGuard.refusalReason(source, target))
    }
}
