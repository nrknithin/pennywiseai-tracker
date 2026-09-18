package com.nrknithin.fintrack.di

import com.nrknithin.fintrack.parser.core.email.EmailParserFactory
import com.nrknithin.fintrack.data.database.FinTrackDatabase
import com.nrknithin.fintrack.data.database.dao.DueDao
import com.nrknithin.fintrack.data.database.dao.DueSourceDao
import com.nrknithin.fintrack.data.database.dao.EmailMessageDao
import com.nrknithin.fintrack.data.database.dao.SenderOverrideDao
import com.nrknithin.fintrack.data.database.dao.SyncRunDao
import com.nrknithin.fintrack.data.database.dao.TransactionSourceDao
import com.nrknithin.fintrack.data.email.DataStoreEmailAccountConfigStore
import com.nrknithin.fintrack.data.email.DueParsing
import com.nrknithin.fintrack.data.email.DueSettler
import com.nrknithin.fintrack.data.email.DueWriter
import com.nrknithin.fintrack.data.email.EmailAccountConfigStore
import com.nrknithin.fintrack.data.email.EmailBatchCommitter
import com.nrknithin.fintrack.data.email.EmailBatchProcessor
import com.nrknithin.fintrack.data.email.EmailCredentialStore
import com.nrknithin.fintrack.data.email.EncryptedEmailCredentialStore
import com.nrknithin.fintrack.data.email.FactoryDueParsing
import com.nrknithin.fintrack.data.email.FactoryTransactionParsing
import com.nrknithin.fintrack.data.email.ImapMailboxClient
import com.nrknithin.fintrack.data.email.IngestingEmailBatchProcessor
import com.nrknithin.fintrack.data.email.JavaMailImapClient
import com.nrknithin.fintrack.data.email.OwnAccountSource
import com.nrknithin.fintrack.data.email.ParsedTransactionSaver
import com.nrknithin.fintrack.data.email.PlausibleDueSettler
import com.nrknithin.fintrack.data.email.ProcessorTransactionSaver
import com.nrknithin.fintrack.data.email.RoomEmailBatchCommitter
import com.nrknithin.fintrack.data.email.RoomOwnAccountSource
import com.nrknithin.fintrack.data.email.RoomTransactionStore
import com.nrknithin.fintrack.data.email.SenderOverrides
import com.nrknithin.fintrack.data.email.TransactionParsing
import com.nrknithin.fintrack.data.email.TransactionStore
import com.nrknithin.fintrack.data.repository.DueRepository
import com.nrknithin.fintrack.data.repository.SenderOverrideRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Singleton

/**
 * The email channel's dependency graph (spec 02 section 2.8).
 *
 * Every one of these is an interface with exactly one production implementation and one
 * in-memory test double. That is not ceremony: `EmailSyncWorker` runs the whole of spec 11
 * section 11.3 against the doubles as plain JVM unit tests - no device, no network, and no
 * Gmail app password anywhere near the test source set (spec 04 section 4.1).
 *
 * A new file rather than an edit to `ApplicationModule` or `DatabaseModule`, per spec 05
 * section 5.6: the fork adds files so the quarterly rebase against upstream stays cheap.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class EmailModule {

    /** Settings and the two watermarks, in the fork's own `fin_email_settings` DataStore. */
    @Binds
    @Singleton
    abstract fun bindEmailAccountConfigStore(
        store: DataStoreEmailAccountConfigStore
    ): EmailAccountConfigStore

    /**
     * The app password, in `EncryptedSharedPreferences` and nowhere else (constraint C1).
     * Bound as the interface so nothing outside the store can reach an implementation detail
     * that might hand the credential back.
     */
    @Binds
    @Singleton
    abstract fun bindEmailCredentialStore(
        store: EncryptedEmailCredentialStore
    ): EmailCredentialStore

    /** IMAPS, folder opened READ_ONLY (R-11-02). The only class that touches `javax.mail`. */
    @Binds
    @Singleton
    abstract fun bindImapMailboxClient(client: JavaMailImapClient): ImapMailboxClient

    /**
     * Steps 11 to 18 of the sync: log a batch and process it, in one committed transaction.
     * The worker advances the watermark the moment this returns, so the binding must be one
     * that really has committed by then (R-11-07).
     */
    @Binds
    @Singleton
    abstract fun bindEmailBatchCommitter(committer: RoomEmailBatchCommitter): EmailBatchCommitter

    /**
     * Section 11.4 over the rows a batch just logged.
     *
     * M1c bound `DeferredEmailBatchProcessor`, which logged mail and understood none of it.
     * M1d binds the real one: it builds an `EmailTransactionIngestor` from the run's settings
     * and returns `ingestAll(rows)`. That was the whole of the change - nothing in the worker
     * or in `RoomEmailBatchCommitter` moved. The deferred processor stays in the tree as the
     * documented no-op (and as the contrast the processor's own test asserts against).
     */
    @Binds
    @Singleton
    abstract fun bindEmailBatchProcessor(
        processor: IngestingEmailBatchProcessor
    ): EmailBatchProcessor

    // --- Section 11.4's collaborators (spec 02 section 2.8) ---------------------------------
    //
    // Every one of these is an interface declared beside the pipeline in
    // `EmailTransactionIngestor.kt`, so that the pipeline can be driven on fakes with no Room
    // and no Android. Here is where the real ones are named, and it is the only place they are.

    /** Spec 14's candidate lookup, over the fifty-method upstream `TransactionDao`. */
    @Binds
    @Singleton
    abstract fun bindTransactionStore(store: RoomTransactionStore): TransactionStore

    /**
     * Step g. Email goes through the same door as SMS - `SmsTransactionProcessor` - so an
     * email transaction gets the categories, rules, merchant mappings, subscription matching
     * and balance updates the SMS channel already does.
     */
    @Binds
    @Singleton
    abstract fun bindParsedTransactionSaver(
        saver: ProcessorTransactionSaver
    ): ParsedTransactionSaver

    /** Step b: spec 20 section 20.4's merge, decided by `DueMergeEngine`. */
    @Binds
    @Singleton
    abstract fun bindDueWriter(repository: DueRepository): DueWriter

    /** Where spec 14 section 14.5's own-account list is read from. */
    @Binds
    @Singleton
    abstract fun bindOwnAccountSource(source: RoomOwnAccountSource): OwnAccountSource

    /**
     * Spec 11 section 11.6's accepted overrides, as the two questions the sync path asks of
     * them. Bound as the narrow interface rather than injecting `SenderOverrideRepository`
     * directly, so `EmailSyncRunner` and `IngestingEmailBatchProcessor` can still be built in
     * a JVM unit test with `SenderOverrides.NONE` and no Room.
     */
    @Binds
    @Singleton
    abstract fun bindSenderOverrides(repository: SenderOverrideRepository): SenderOverrides

    /**
     * Steps b and d/e's parsers. `FactoryDueParsing` needs nothing and says so with an
     * `@Inject` constructor, so it binds; `FactoryTransactionParsing` carries an
     * `EmailParserFactory` Hilt cannot construct and is provided below.
     */
    @Binds
    @Singleton
    abstract fun bindDueParsing(parsing: FactoryDueParsing): DueParsing

    companion object {

        /**
         * The one clock the fork's engines read (spec 02 section 2.8, and the house rule that
         * no engine calls `now()` for itself). Tests inject a fixed clock instead, which is
         * what makes "due today" and the sync run timings deterministic.
         *
         * Spec 02 section 2.8 files this under `EngineModule`; there is no such module yet
         * and `DedupEngine` already needs a `Clock`, so it lives here until one exists.
         */
        @Provides
        @Singleton
        fun provideClock(): Clock = Clock.systemDefaultZone()

        /**
         * The v63 DAOs the email channel writes. Upstream's `DatabaseModule` is left
         * untouched (spec 05 section 5.6): all five live here, where the rest of the channel
         * is, so the rebase diff against upstream stays a file addition rather than an edit.
         */
        @Provides
        @Singleton
        fun provideEmailMessageDao(database: FinTrackDatabase): EmailMessageDao =
            database.emailMessageDao()

        @Provides
        @Singleton
        fun provideSyncRunDao(database: FinTrackDatabase): SyncRunDao = database.syncRunDao()

        @Provides
        @Singleton
        fun provideDueDao(database: FinTrackDatabase): DueDao = database.dueDao()

        @Provides
        @Singleton
        fun provideDueSourceDao(database: FinTrackDatabase): DueSourceDao =
            database.dueSourceDao()

        @Provides
        @Singleton
        fun provideTransactionSourceDao(database: FinTrackDatabase): TransactionSourceDao =
            database.transactionSourceDao()

        /**
         * Spec 03 §3.3's `sender_overrides`, brought forward to v63 for sender discovery.
         *
         * Everything above this line in the discovery graph needs no binding of its own:
         * `SenderDiscovery`, `SenderOverrideRepository` and `DiscoverSendersUseCase` all
         * carry `@Inject` constructors over types Hilt already knows, and
         * `SenderDiscoveryWorker` is `@HiltWorker`, so `HiltWorkerFactory` constructs it.
         * This `@Provides` is the one edge Dagger cannot infer, because a DAO comes from an
         * abstract method on the database rather than from a constructor.
         */
        @Provides
        @Singleton
        fun provideSenderOverrideDao(database: FinTrackDatabase): SenderOverrideDao =
            database.senderOverrideDao()

        /**
         * Step h's settlement (spec 11 section 11.4, spec 20 section 20.5).
         *
         * `@Provides` rather than `@Binds` because `PlausibleDueSettler` takes a
         * `SettlementConfig` - the `settings.dues.*` figures of spec 28 section 28.4, which
         * have no store until M2. The documented defaults stand until that store exists; a
         * settings-backed value replaces this one line and nothing else.
         */
        @Provides
        @Singleton
        fun provideDueSettler(dueDao: DueDao, clock: Clock): DueSettler =
            PlausibleDueSettler(dueDao = dueDao, clock = clock)

        /**
         * Steps d and e's transaction parsers.
         *
         * `EmailParserFactory.DEFAULT` carries no own VPAs and no account-holder names, so the
         * own-transfer rules inside the HDFC and Axis parsers are inert. They are not wrong,
         * only quiet: screen 42's own-account editor (spec 14 section 14.5) is what will fill
         * them, and until it does there is nothing truthful to pass.
         */
        @Provides
        @Singleton
        fun provideTransactionParsing(): TransactionParsing =
            FactoryTransactionParsing(EmailParserFactory.DEFAULT)
    }
}
