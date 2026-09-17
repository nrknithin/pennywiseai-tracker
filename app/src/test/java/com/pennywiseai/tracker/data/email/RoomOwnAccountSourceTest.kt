package com.pennywiseai.tracker.data.email

import com.pennywiseai.tracker.data.database.dao.AccountBalanceDao
import com.pennywiseai.tracker.data.database.dao.CardDao
import com.pennywiseai.tracker.data.database.entity.AccountBalanceEntity
import com.pennywiseai.tracker.data.database.entity.CardEntity
import com.pennywiseai.tracker.data.database.entity.CardType
import com.pennywiseai.tracker.domain.engine.OwnAccount
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * [RoomOwnAccountSource] - spec 14 §14.5's own-account registry, as the database really yields it.
 *
 * The registry is the input to the INCOME-vs-TRANSFER decision: an account missing from it turns
 * money the owner moved between two of their own banks into that month's income, and every figure
 * built on income (spec 22's money plan) inherits the error. Nothing downstream can detect the
 * omission, because a short list looks exactly like a small mailbox. So the seam is pinned here,
 * at the only place the union is actually computed.
 *
 * Every `IngestingEmailBatchProcessorTest` stubs [OwnAccountSource] - deliberately, since those
 * tests are about the batch's *rules* - which left the production set-union over
 * `account_balances` and the three suspend `CardDao` queries unexercised. This file is that gap.
 *
 * Plain JUnit: both collaborators are DAO interfaces, so there is no Room, no Android and no
 * Robolectric here. Every value is invented and redacted per spec 06 §6.2 - last-4s are
 * `1234`/`5678`/`9012`, banks are names the sender registry already knows.
 */
class RoomOwnAccountSourceTest {

    // ---------------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------------

    /** Fixed, so nothing here depends on the day the suite runs (spec 02 §2.8). */
    private val someTime: LocalDateTime = LocalDateTime.of(2026, 9, 17, 12, 0)

    private fun account(bank: String, last4: String): AccountBalanceEntity = AccountBalanceEntity(
        bankName = bank,
        accountLast4 = last4,
        // The balance is irrelevant to §14.5 - the registry is a list of identities, not money.
        balance = BigDecimal("1000.00"),
        timestamp = someTime
    )

    private fun card(
        bank: String,
        last4: String,
        type: CardType = CardType.CREDIT,
        accountLast4: String? = null,
        active: Boolean = true
    ): CardEntity = CardEntity(
        cardLast4 = last4,
        cardType = type,
        bankName = bank,
        accountLast4 = accountLast4,
        isActive = active,
        createdAt = someTime,
        updatedAt = someTime
    )

    /** The registry, plus the card DAO that answered - for the tests that read the queries. */
    private fun registryOf(
        accounts: List<AccountBalanceEntity> = emptyList(),
        cards: List<CardEntity> = emptyList()
    ): Pair<List<OwnAccount>, FakeCardDao> {
        val cardDao = FakeCardDao(cards)
        val source = RoomOwnAccountSource(FakeAccountBalanceDao(accounts), cardDao)
        return runBlocking { source.ownAccounts() } to cardDao
    }

    private fun registry(
        accounts: List<AccountBalanceEntity> = emptyList(),
        cards: List<CardEntity> = emptyList()
    ): List<OwnAccount> = registryOf(accounts, cards).first

    /** `bank/last4`, so a failure names the entry that went missing rather than a hash. */
    private fun List<OwnAccount>.ids(): List<String> = map { "${it.bank}/${it.last4}" }

    // ---------------------------------------------------------------------------------------
    // The union
    // ---------------------------------------------------------------------------------------

    @Test
    fun `an empty database yields an empty registry, and still asks about both card types`() {
        val (registry, cards) = registryOf()

        assertEquals(emptyList<OwnAccount>(), registry)
        // No accounts means no per-account card lookup - but the orphan queries are
        // unconditional, because a credit card is an own account with no bank account behind it.
        assertEquals(listOf(CardType.CREDIT, CardType.DEBIT), cards.orphanQueries)
        assertEquals(emptyList<String>(), cards.accountQueries)
    }

    @Test
    fun `every account_balances row becomes an own account, and none of them invents a vpa`() {
        val registry = registry(
            accounts = listOf(account("HDFC Bank", "1234"), account("Axis Bank", "5678"))
        )

        assertEquals(listOf("HDFC Bank/1234", "Axis Bank/5678"), registry.ids())
        // Neither table has a VPA column at schema v63. §14.5 gets `vpa` from screen 42's edits
        // in M2; until then it stays null, because a guessed handle would decide
        // TRANSFER-vs-INCOME on an invention (spec 01 constraint C7).
        assertTrue(registry.all { it.vpa == null })
    }

    @Test
    fun `orphaned credit and debit cards both count`() {
        val registry = registry(
            cards = listOf(
                card("HDFC Bank", "1234", CardType.CREDIT),
                card("Axis Bank", "5678", CardType.DEBIT),
                // Mirrors `is_active = 1` in both orphan queries: a closed card is not an
                // account the owner can still move money through.
                card("HDFC Bank", "9012", CardType.CREDIT, active = false)
            )
        )

        // Dropping either `getOrphanedCards` call - the shape a refactor reaches for, since one
        // query "looks like" it covers cards - loses one of these two.
        assertEquals(listOf("HDFC Bank/1234", "Axis Bank/5678"), registry.ids())
    }

    @Test
    fun `a card linked to a known account counts under its own last4, not its account's`() {
        val (registry, cards) = registryOf(
            accounts = listOf(account("HDFC Bank", "1234")),
            cards = listOf(card("HDFC Bank", "5678", CardType.DEBIT, accountLast4 = "1234"))
        )

        // Both identities are the owner's, and an alert names whichever one it was sent about.
        assertEquals(listOf("HDFC Bank/1234", "HDFC Bank/5678"), registry.ids())
        assertEquals(listOf("1234"), cards.accountQueries)
    }

    @Test
    fun `one card lookup per account, in account order`() {
        val (_, cards) = registryOf(
            accounts = listOf(account("HDFC Bank", "1234"), account("Axis Bank", "5678"))
        )

        assertEquals(listOf("1234", "5678"), cards.accountQueries)
    }

    // ---------------------------------------------------------------------------------------
    // The distinctBy - one identity, however many ways it was reached
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a card reached through two accounts that share a last4 is listed once`() {
        // Two banks numbering an account `1234` is ordinary - last-4s collide across banks. The
        // union then asks `getCardsForAccount("1234")` twice and gets the same card back twice.
        val (registry, cards) = registryOf(
            accounts = listOf(account("HDFC Bank", "1234"), account("Axis Bank", "1234")),
            cards = listOf(card("HDFC Bank", "5678", CardType.DEBIT, accountLast4 = "1234"))
        )

        assertEquals(listOf("1234", "1234"), cards.accountQueries)
        assertEquals(
            listOf("HDFC Bank/1234", "Axis Bank/1234", "HDFC Bank/5678"),
            registry.ids()
        )
    }

    @Test
    fun `two banks sharing a last4 stay two accounts`() {
        // The other half of the same rule: the distinct key is bank *and* last4. Collapsing on
        // last4 alone would delete a real own account, which is the failure §14.5 exists to
        // prevent - a credit from the deleted bank would then read as income.
        val registry = registry(
            accounts = listOf(account("HDFC Bank", "1234"), account("Axis Bank", "1234"))
        )

        assertEquals(listOf("HDFC Bank/1234", "Axis Bank/1234"), registry.ids())
    }

    @Test
    fun `one identity spelled two ways is one entry, spelled as the account has it`() {
        // `account_balances` and `cards` are written by different code paths, so the same bank
        // arrives capitalised two ways and a last4 arrives padded. The key is trimmed and
        // lower-cased for exactly that; the first spelling - the account's - is what survives,
        // so the registry reads the way the accounts screen does.
        val registry = registry(
            accounts = listOf(account("HDFC Bank", "1234")),
            cards = listOf(card("hdfc bank ", " 1234", CardType.DEBIT))
        )

        assertEquals(listOf("HDFC Bank/1234"), registry.ids())
    }

    // ---------------------------------------------------------------------------------------
    // The blank-last4 filter
    // ---------------------------------------------------------------------------------------

    @Test
    fun `an account or card with no last4 never enters the registry`() {
        // A parser that read no account number writes an empty string, not a missing row. Such
        // an entry identifies nothing: `DedupEngine.isOwnAccount` matches on the last4, so a
        // blank one can never match a real record and would only be carried through every dedup
        // pass of every batch.
        val registry = registry(
            accounts = listOf(account("HDFC Bank", ""), account("Axis Bank", "5678")),
            cards = listOf(card("HDFC Bank", "   ", CardType.CREDIT))
        )

        assertEquals(listOf("Axis Bank/5678"), registry.ids())
    }

    @Test
    fun `a wallet account keeps its marker, because the marker is the account`() {
        // Mobile-money wallets have no account number; `WALLET` is the whole account's key
        // (`AccountBalanceEntity.WALLET_ACCOUNT_MARKER`). It is not blank, so it survives the
        // filter - dropping it would leave the owner's wallet off their own-account list.
        val registry = registry(
            accounts = listOf(account("eMola", AccountBalanceEntity.WALLET_ACCOUNT_MARKER))
        )

        assertEquals(listOf("eMola/WALLET"), registry.ids())
    }

    // ---------------------------------------------------------------------------------------
    // The documented gap
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a card linked to an account no balance has ever reported is missed, and reappears with it`() {
        // The one shape the three-query union cannot see, recorded in FORK-CHANGES.md against
        // §14.5: the card is not orphaned (so no `getOrphanedCards` returns it) and its account
        // has never reported a balance (so nothing asks `getCardsForAccount("9012")`).
        val linked = card("HDFC Bank", "5678", CardType.DEBIT, accountLast4 = "9012")

        val (missing, cards) = registryOf(cards = listOf(linked))
        assertEquals(emptyList<OwnAccount>(), missing)
        assertEquals(emptyList<String>(), cards.accountQueries)

        // A delay, not a loss: the account's first balance row brings the card in with it.
        val found = registry(
            accounts = listOf(account("HDFC Bank", "9012")),
            cards = listOf(linked)
        )
        assertEquals(listOf("HDFC Bank/9012", "HDFC Bank/5678"), found.ids())
    }
}

// -------------------------------------------------------------------------------------------
// Fakes
// -------------------------------------------------------------------------------------------

/**
 * A stand-in for [T] that answers nothing.
 *
 * `AccountBalanceDao` declares thirty-eight methods and `CardDao` seventeen;
 * [RoomOwnAccountSource] calls three of them. Hand-writing the other fifty-two bodies would bury
 * the three that matter, so the fakes below delegate to this proxy and override only what the
 * union reads - which also means a *fourth* query a later edit reaches for fails loudly here
 * instead of quietly taking a fake's empty list for an answer.
 *
 * Local to this file on purpose: `EmailIngestFakes` is shared by four suites and these two DAOs
 * belong to none of them.
 */
private inline fun <reified T : Any> answersNothing(): T =
    Proxy.newProxyInstance(
        T::class.java.classLoader,
        arrayOf(T::class.java)
    ) { _, method, _ ->
        throw UnsupportedOperationException(
            "${T::class.java.simpleName}.${method.name} is not part of the own-account read"
        )
    } as T

/** The latest balance row per account, which is all §14.5 reads from `account_balances`. */
private class FakeAccountBalanceDao(
    private val latest: List<AccountBalanceEntity>
) : AccountBalanceDao by answersNothing() {

    override suspend fun getAllLatestBalancesOnce(): List<AccountBalanceEntity> = latest
}

/**
 * The `cards` table, filtered the way the two suspend queries' SQL filters it.
 *
 * `=` on TEXT is byte-exact in SQLite (Room declares no `COLLATE NOCASE` on these columns), so
 * [getCardsForAccount] matches byte-exactly too: the trimming and case-folding under test
 * happens in [RoomOwnAccountSource], not in the database.
 */
private class FakeCardDao(private val rows: List<CardEntity>) : CardDao by answersNothing() {

    /** Every card query the union made, in order - so a test can see the shape of the read. */
    val orphanQueries = mutableListOf<CardType>()
    val accountQueries = mutableListOf<String>()

    override suspend fun getOrphanedCards(cardType: CardType): List<CardEntity> {
        orphanQueries += cardType
        return rows.filter { it.accountLast4 == null && it.cardType == cardType && it.isActive }
    }

    override suspend fun getCardsForAccount(accountLast4: String): List<CardEntity> {
        accountQueries += accountLast4
        return rows.filter { it.accountLast4 == accountLast4 && it.isActive }
    }
}
