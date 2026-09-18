package com.nrknithin.fintrack.data.email

import com.nrknithin.fintrack.data.database.dao.AccountBalanceDao
import com.nrknithin.fintrack.data.database.dao.CardDao
import com.nrknithin.fintrack.data.database.dao.DueSourceDao
import com.nrknithin.fintrack.data.database.dao.EmailMessageDao
import com.nrknithin.fintrack.data.database.dao.TransactionSourceDao
import com.nrknithin.fintrack.data.database.entity.CardType
import com.nrknithin.fintrack.data.database.entity.EmailMessageEntity
import com.nrknithin.fintrack.domain.engine.DedupConfig
import com.nrknithin.fintrack.domain.engine.DedupEngine
import com.nrknithin.fintrack.domain.engine.OwnAccount
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The owner's own accounts and cards - spec 14 §14.5's `settings.accounts.own`.
 *
 * A seam rather than two DAOs on [IngestingEmailBatchProcessor] for one reason: it is the only
 * database read the processor makes for itself, and faking `AccountBalanceDao` (forty-odd
 * methods) to test one list would cost more than the pipeline it is testing.
 *
 * §14.5 says the list is "seeded from `account_balances` + `cards` + the user's edits (42)".
 * The edits are a screen-42 control that arrives with M2; until it does, the seed *is* the list.
 */
interface OwnAccountSource {

    /**
     * Every account and card this device believes belongs to the owner.
     *
     * Read per batch rather than per run, so an account the first batch of a sync discovers is
     * already own-account by the second. Re-reading loses nothing; a stale list would misfile a
     * transfer between the owner's own banks as income, which is precisely what §14.5 exists to
     * prevent.
     */
    suspend fun ownAccounts(): List<OwnAccount>
}

/**
 * The production [OwnAccountSource].
 *
 * **Suspend DAO queries only, on purpose.** This runs inside the Room transaction that
 * [RoomEmailBatchCommitter] opens for the batch, where a suspend DAO call joins that
 * transaction and a `Flow` does not - which is the reason `AccountBalanceDao` already carries
 * a one-shot [AccountBalanceDao.getAllLatestBalancesOnce] beside its flow, and says so in its
 * own KDoc. `CardDao` has no equivalent for "all cards", and adding one would mean editing an
 * upstream file that spec 05 §5.6 asks the fork to leave alone, so the same set is assembled
 * from the three suspend queries it does have: a card is either linked to an account (found
 * through that account) or orphaned (found by type). The one shape this misses is a card
 * linked to a `last4` that `account_balances` has never seen; it appears the moment that
 * account reports a balance.
 *
 * `vpa` is always null. Neither table has a VPA column at schema v63 - spec 14 §14.5 gets it
 * from the user's own edits on screen 42 - and inventing one would be the guess that spec 01
 * constraint C7 forbids.
 */
@Singleton
class RoomOwnAccountSource @Inject constructor(
    private val accountBalances: AccountBalanceDao,
    private val cards: CardDao
) : OwnAccountSource {

    override suspend fun ownAccounts(): List<OwnAccount> {
        val accounts = accountBalances.getAllLatestBalancesOnce()
            .map { OwnAccount(bank = it.bankName, last4 = it.accountLast4) }

        val cardAccounts = buildList {
            addAll(cards.getOrphanedCards(CardType.CREDIT))
            addAll(cards.getOrphanedCards(CardType.DEBIT))
            for (account in accounts) addAll(cards.getCardsForAccount(account.last4))
        }.map { OwnAccount(bank = it.bankName, last4 = it.cardLast4) }

        return (accounts + cardAccounts)
            .filter { it.last4.isNotBlank() }
            // A card reached both through its account and by type is still one card.
            .distinctBy { it.bank.trim().lowercase() to it.last4.trim() }
    }
}

/**
 * The M1d binding of [EmailBatchProcessor]: spec 11 §11.4 actually runs.
 *
 * It replaces [DeferredEmailBatchProcessor], which logged mail (R-11-05) and understood none of
 * it. The only thing this class decides is *which rules this run is judged by*: it turns the
 * run's [EmailAccountConfig] into an [IngestConfig], builds an [EmailTransactionIngestor] around
 * it and hands the batch over. Every decision about a message belongs to the ingestor.
 *
 * The ingestor is constructed per call rather than injected because [IngestConfig] is a per-run
 * value (see the note on [EmailTransactionIngestor]). It holds no state, so building one costs a
 * dozen field writes and buys the guarantee that every message in a batch is judged by one copy
 * of the rules - including any setting the user changed since the previous batch.
 */
@Singleton
class IngestingEmailBatchProcessor @Inject constructor(
    private val emailMessageDao: EmailMessageDao,
    private val transactionSourceDao: TransactionSourceDao,
    private val dueSourceDao: DueSourceDao,
    private val transactions: TransactionStore,
    private val saver: ParsedTransactionSaver,
    private val dues: DueWriter,
    private val settler: DueSettler,
    private val dedup: DedupEngine,
    private val clock: Clock,
    private val dueParsers: DueParsing,
    private val transactionParsers: TransactionParsing,
    private val ownAccounts: OwnAccountSource,
    private val senderOverrides: SenderOverrides
) : EmailBatchProcessor {

    override suspend fun process(
        rows: List<EmailMessageEntity>,
        config: EmailAccountConfig
    ): List<IngestResult> {
        // Nothing to judge, so nothing to read: an empty batch must not cost three queries.
        if (rows.isEmpty()) return emptyList()
        return ingestorFor(ingestConfigFor(config)).ingestAll(rows)
    }

    /**
     * Spec 11 §11.2's settings, as the rules of spec 14 and spec 12.
     *
     * Only the three a user can actually set are read. Every other [DedupConfig] figure keeps
     * the documented default of spec 28 §28.9, because `settings.dedup.*` has no editor and no
     * store before M2 - reading a value nothing can write would be inventing it.
     *
     * [config] arrives [EmailAccountConfig.coerced] (the store coerces on every read, not just
     * on write), so the alert window is already inside spec 11 §11.2's range by the time it
     * gets here; re-clamping would only hide it if it ever stopped being true.
     */
    internal suspend fun ingestConfigFor(config: EmailAccountConfig): IngestConfig = IngestConfig(
        dedup = DedupConfig(
            // `settings.email.alertWindowMinutes` - the SMS twin window of spec 14 §14.3 L2.
            alertWindowMinutes = config.alertWindowMinutes,
            // §14.3 L3's same-day fallback, which the user may switch off.
            sameDayFallback = config.sameDayFallback,
            ownAccounts = ownAccounts.ownAccounts()
        ),
        ignoredSenders = config.ignoredSenders,
        // Spec 11 §11.6's overrides, consulted before the compiled registry. `sender_overrides`
        // came forward to v63 with sender discovery, so this is no longer the empty map it was:
        // a sender the owner accepted on screen 47 is resolved through their row, which is what
        // gives a discovered merchant a name and — where they supplied one — an SMS alias.
        //
        // Read per batch, like [ownAccounts] and for the same reason: a sender accepted while a
        // sync is in flight is honoured by the next batch rather than the next run.
        senderOverrides = senderOverrides.mappings()
    )

    private fun ingestorFor(config: IngestConfig): EmailTransactionIngestor =
        EmailTransactionIngestor(
            emailMessageDao = emailMessageDao,
            transactionSourceDao = transactionSourceDao,
            dueSourceDao = dueSourceDao,
            transactions = transactions,
            saver = saver,
            dues = dues,
            settler = settler,
            dedup = dedup,
            clock = clock,
            dueParsers = dueParsers,
            transactionParsers = transactionParsers,
            // Screen 49's `unrecognized_sms` has no channel column at v63, so an email filed
            // there would claim to be an SMS (recorded in FORK-CHANGES). Nothing is lost: step
            // e has already written `NO_PARSER`, which is what "Re-process unmatched" replays.
            unrecognised = UnrecognisedRecorder.NONE,
            config = config
        )
}
