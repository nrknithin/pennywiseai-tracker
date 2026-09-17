package com.pennywiseai.tracker.di

import com.pennywiseai.parser.core.email.EmailParserFactory
import com.pennywiseai.tracker.data.database.PennyWiseDatabase
import com.pennywiseai.tracker.data.database.dao.DueDao
import com.pennywiseai.tracker.data.database.dao.DueSourceDao
import com.pennywiseai.tracker.data.database.dao.EmailMessageDao
import com.pennywiseai.tracker.data.database.dao.SyncRunDao
import com.pennywiseai.tracker.data.database.dao.TransactionSourceDao
import com.pennywiseai.tracker.data.email.DataStoreEmailAccountConfigStore
import com.pennywiseai.tracker.data.email.DueParsing
import com.pennywiseai.tracker.data.email.DueSettler
import com.pennywiseai.tracker.data.email.DueWriter
import com.pennywiseai.tracker.data.email.EmailAccountConfigStore
import com.pennywiseai.tracker.data.email.EmailBatchCommitter
import com.pennywiseai.tracker.data.email.EmailBatchProcessor
import com.pennywiseai.tracker.data.email.EmailCredentialStore
import com.pennywiseai.tracker.data.email.EncryptedEmailCredentialStore
import com.pennywiseai.tracker.data.email.FactoryDueParsing
import com.pennywiseai.tracker.data.email.FactoryTransactionParsing
import com.pennywiseai.tracker.data.email.ImapMailboxClient
import com.pennywiseai.tracker.data.email.IngestingEmailBatchProcessor
import com.pennywiseai.tracker.data.email.JavaMailImapClient
import com.pennywiseai.tracker.data.email.OwnAccountSource
import com.pennywiseai.tracker.data.email.ParsedTransactionSaver
import com.pennywiseai.tracker.data.email.PlausibleDueSettler
import com.pennywiseai.tracker.data.email.ProcessorTransactionSaver
import com.pennywiseai.tracker.data.email.RoomEmailBatchCommitter
import com.pennywiseai.tracker.data.email.RoomOwnAccountSource
import com.pennywiseai.tracker.data.email.RoomTransactionStore
import com.pennywiseai.tracker.data.email.TransactionParsing
import com.pennywiseai.tracker.data.email.TransactionStore
import com.pennywiseai.tracker.data.repository.DueRepository
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
        fun provideEmailMessageDao(database: PennyWiseDatabase): EmailMessageDao =
            database.emailMessageDao()

        @Provides
        @Singleton
        fun provideSyncRunDao(database: PennyWiseDatabase): SyncRunDao = database.syncRunDao()

        @Provides
        @Singleton
        fun provideDueDao(database: PennyWiseDatabase): DueDao = database.dueDao()

        @Provides
        @Singleton
        fun provideDueSourceDao(database: PennyWiseDatabase): DueSourceDao =
            database.dueSourceDao()

        @Provides
        @Singleton
        fun provideTransactionSourceDao(database: PennyWiseDatabase): TransactionSourceDao =
            database.transactionSourceDao()

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
