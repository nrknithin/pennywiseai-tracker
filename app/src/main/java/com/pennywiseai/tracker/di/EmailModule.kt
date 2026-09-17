package com.pennywiseai.tracker.di

import com.pennywiseai.tracker.data.database.PennyWiseDatabase
import com.pennywiseai.tracker.data.database.dao.EmailMessageDao
import com.pennywiseai.tracker.data.database.dao.SyncRunDao
import com.pennywiseai.tracker.data.email.DataStoreEmailAccountConfigStore
import com.pennywiseai.tracker.data.email.DeferredEmailBatchProcessor
import com.pennywiseai.tracker.data.email.EmailAccountConfigStore
import com.pennywiseai.tracker.data.email.EmailBatchCommitter
import com.pennywiseai.tracker.data.email.EmailBatchProcessor
import com.pennywiseai.tracker.data.email.EmailCredentialStore
import com.pennywiseai.tracker.data.email.EncryptedEmailCredentialStore
import com.pennywiseai.tracker.data.email.ImapMailboxClient
import com.pennywiseai.tracker.data.email.JavaMailImapClient
import com.pennywiseai.tracker.data.email.RoomEmailBatchCommitter
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
     * M1c binds the deferred processor: messages are logged (R-11-05) and left with
     * `processed = false` for `EmailMessageDao.getUnmatched` to replay. **M1d changes this one
     * line** to an implementation that builds an `EmailTransactionIngestor` from the run's
     * settings and returns `ingestAll(rows)` - it cannot be bound yet because the pipeline's
     * `DueWriter` has no production implementation at M1c. Nothing else in the worker or this
     * module moves when it lands.
     */
    @Binds
    @Singleton
    abstract fun bindEmailBatchProcessor(
        processor: DeferredEmailBatchProcessor
    ): EmailBatchProcessor

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
         * untouched (spec 05 section 5.6); the remaining fork DAOs - dues and the two source
         * tables - join this module with the milestone that first writes them.
         */
        @Provides
        @Singleton
        fun provideEmailMessageDao(database: PennyWiseDatabase): EmailMessageDao =
            database.emailMessageDao()

        @Provides
        @Singleton
        fun provideSyncRunDao(database: PennyWiseDatabase): SyncRunDao = database.syncRunDao()
    }
}
