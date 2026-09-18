package com.nrknithin.fintrack.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.nrknithin.fintrack.data.database.FinTrackDatabase
import com.nrknithin.fintrack.data.database.dao.AccountBalanceDao
import com.nrknithin.fintrack.data.database.dao.ProfileDao
import com.nrknithin.fintrack.data.database.dao.BankNotificationDao
import com.nrknithin.fintrack.data.database.dao.BudgetDao
import com.nrknithin.fintrack.data.database.dao.BudgetSnapshotDao
import com.nrknithin.fintrack.data.database.dao.CardDao
import com.nrknithin.fintrack.shared.data.bootstrap.DefaultCategoryData
import com.nrknithin.fintrack.data.database.dao.CategoryDao
import com.nrknithin.fintrack.data.database.dao.ChatDao
import com.nrknithin.fintrack.data.database.dao.ExchangeRateDao
import com.nrknithin.fintrack.data.database.dao.LoanDao
import com.nrknithin.fintrack.data.database.dao.TransactionGroupDao
import com.nrknithin.fintrack.data.database.dao.MerchantMappingDao
import com.nrknithin.fintrack.data.database.dao.MerchantAliasDao
import com.nrknithin.fintrack.data.database.dao.RuleApplicationDao
import com.nrknithin.fintrack.data.database.dao.RecurringTransactionDao
import com.nrknithin.fintrack.data.database.dao.RuleDao
import com.nrknithin.fintrack.data.database.dao.SubscriptionDao
import com.nrknithin.fintrack.data.database.dao.TagDao
import com.nrknithin.fintrack.data.database.dao.TransactionDao
import com.nrknithin.fintrack.data.database.dao.TransactionSplitDao
import com.nrknithin.fintrack.data.database.dao.UnrecognizedSmsDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Singleton

/**
 * Hilt module that provides database-related dependencies.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    
    /**
     * Provides the singleton instance of FinTrackDatabase.
     * 
     * @param context Application context
     * @return Configured Room database instance
     */
    @Provides
    @Singleton
    fun provideFinTrackDatabase(
        @ApplicationContext context: Context
    ): FinTrackDatabase {
        FinTrackDatabase.migrateLegacyDatabaseFile(context)
        val database = Room.databaseBuilder(
            context,
            FinTrackDatabase::class.java,
            FinTrackDatabase.DATABASE_NAME
        )
            // Single source of truth lives in FinTrackDatabase.ALL_MIGRATIONS
            // so the BroadcastReceiver fallback builder stays in sync.
            .addMigrations(*FinTrackDatabase.ALL_MIGRATIONS)

            // Enable auto-migrations
            // Room will automatically detect schema changes between versions

            // Add callback to seed default data on first creation
            .addCallback(DatabaseCallback())

            .build()

        // Set the singleton instance so BroadcastReceivers can access it
        FinTrackDatabase.setInstance(database)

        return database
    }
    
    /**
     * Provides the TransactionDao from the database.
     * 
     * @param database The FinTrackDatabase instance
     * @return TransactionDao for accessing transaction data
     */
    @Provides
    @Singleton
    fun provideTransactionDao(database: FinTrackDatabase): TransactionDao {
        return database.transactionDao()
    }
    
    /**
     * Provides the SubscriptionDao from the database.
     * 
     * @param database The FinTrackDatabase instance
     * @return SubscriptionDao for accessing subscription data
     */
    @Provides
    @Singleton
    fun provideSubscriptionDao(database: FinTrackDatabase): SubscriptionDao {
        return database.subscriptionDao()
    }
    
    /**
     * Provides the ChatDao from the database.
     * 
     * @param database The FinTrackDatabase instance
     * @return ChatDao for accessing chat message data
     */
    @Provides
    @Singleton
    fun provideChatDao(database: FinTrackDatabase): ChatDao {
        return database.chatDao()
    }
    
    /**
     * Provides the MerchantMappingDao from the database.
     * 
     * @param database The FinTrackDatabase instance
     * @return MerchantMappingDao for accessing merchant mapping data
     */
    @Provides
    @Singleton
    fun provideMerchantMappingDao(database: FinTrackDatabase): MerchantMappingDao {
        return database.merchantMappingDao()
    }

    /**
     * Provides the MerchantAliasDao from the database.
     *
     * @param database The FinTrackDatabase instance
     * @return MerchantAliasDao for accessing merchant alias data
     */
    @Provides
    @Singleton
    fun provideMerchantAliasDao(database: FinTrackDatabase): MerchantAliasDao {
        return database.merchantAliasDao()
    }

    /**
     * Provides the CategoryDao from the database.
     * 
     * @param database The FinTrackDatabase instance
     * @return CategoryDao for accessing category data
     */
    @Provides
    @Singleton
    fun provideCategoryDao(database: FinTrackDatabase): CategoryDao {
        return database.categoryDao()
    }
    
    /**
     * Provides the AccountBalanceDao from the database.
     * 
     * @param database The FinTrackDatabase instance
     * @return AccountBalanceDao for accessing account balance data
     */
    @Provides
    @Singleton
    fun provideAccountBalanceDao(database: FinTrackDatabase): AccountBalanceDao {
        return database.accountBalanceDao()
    }
    
    /**
     * Provides the UnrecognizedSmsDao from the database.
     * 
     * @param database The FinTrackDatabase instance
     * @return UnrecognizedSmsDao for accessing unrecognized SMS data
     */
    @Provides
    @Singleton
    fun provideUnrecognizedSmsDao(database: FinTrackDatabase): UnrecognizedSmsDao {
        return database.unrecognizedSmsDao()
    }
    
    /**
     * Provides the CardDao from the database.
     *
     * @param database The FinTrackDatabase instance
     * @return CardDao for accessing card data
     */
    @Provides
    @Singleton
    fun provideCardDao(database: FinTrackDatabase): CardDao {
        return database.cardDao()
    }

    /**
     * Provides the RuleDao from the database.
     *
     * @param database The FinTrackDatabase instance
     * @return RuleDao for accessing rule data
     */
    @Provides
    @Singleton
    fun provideRuleDao(database: FinTrackDatabase): RuleDao {
        return database.ruleDao()
    }

    /**
     * Provides the RuleApplicationDao from the database.
     *
     * @param database The FinTrackDatabase instance
     * @return RuleApplicationDao for accessing rule application data
     */
    @Provides
    @Singleton
    fun provideRuleApplicationDao(database: FinTrackDatabase): RuleApplicationDao {
        return database.ruleApplicationDao()
    }

    /**
     * Provides the ExchangeRateDao from the database.
     *
     * @param database The FinTrackDatabase instance
     * @return ExchangeRateDao for accessing exchange rate data
     */
    @Provides
    @Singleton
    fun provideExchangeRateDao(database: FinTrackDatabase): ExchangeRateDao {
        return database.exchangeRateDao()
    }

    /**
     * Provides the BudgetDao from the database.
     *
     * @param database The FinTrackDatabase instance
     * @return BudgetDao for accessing budget data
     */
    @Provides
    @Singleton
    fun provideBudgetDao(database: FinTrackDatabase): BudgetDao {
        return database.budgetDao()
    }

    /**
     * Provides the TransactionSplitDao from the database.
     *
     * @param database The FinTrackDatabase instance
     * @return TransactionSplitDao for accessing transaction split data
     */
    @Provides
    @Singleton
    fun provideTransactionSplitDao(database: FinTrackDatabase): TransactionSplitDao {
        return database.transactionSplitDao()
    }

    @Provides
    @Singleton
    fun provideBankNotificationDao(database: FinTrackDatabase): BankNotificationDao {
        return database.bankNotificationDao()
    }

    @Provides
    @Singleton
    fun provideLoanDao(database: FinTrackDatabase): LoanDao {
        return database.loanDao()
    }

    @Provides
    @Singleton
    fun provideTransactionGroupDao(database: FinTrackDatabase): TransactionGroupDao {
        return database.transactionGroupDao()
    }

    @Provides
    @Singleton
    fun provideBudgetSnapshotDao(database: FinTrackDatabase): BudgetSnapshotDao {
        return database.budgetSnapshotDao()
    }

    @Provides
    @Singleton
    fun provideProfileDao(database: FinTrackDatabase): ProfileDao {
        return database.profileDao()
    }

    @Provides
    @Singleton
    fun provideTagDao(database: FinTrackDatabase): TagDao {
        return database.tagDao()
    }

    @Provides
    @Singleton
    fun provideRecurringTransactionDao(database: FinTrackDatabase): RecurringTransactionDao {
        return database.recurringTransactionDao()
    }
}

/**
 * Database callback to seed initial data when database is first created
 */
class DatabaseCallback : RoomDatabase.Callback() {
    override fun onCreate(db: SupportSQLiteDatabase) {
        super.onCreate(db)
        
        // Seed default categories for new installations
        CoroutineScope(Dispatchers.IO).launch {
            seedCategories(db)
            seedProfiles(db)
        }
    }
    
    private fun seedCategories(db: SupportSQLiteDatabase) {
        val categories = DefaultCategoryData.ALL

        categories.forEachIndexed { index, seed ->
            db.execSQL("""
                INSERT OR IGNORE INTO categories (name, color, is_system, is_income, display_order, created_at, updated_at)
                VALUES (?, ?, 1, ?, ?, datetime('now'), datetime('now'))
            """.trimIndent(), arrayOf<Any>(seed.name, seed.colorHex, if (seed.isIncome) 1 else 0, index + 1))
        }
    }

    private fun seedProfiles(db: SupportSQLiteDatabase) {
        db.execSQL("INSERT OR IGNORE INTO profiles (id, name, color_hex, sort_order) VALUES (1, 'Personal', '#4CAF50', 0)")
        db.execSQL("INSERT OR IGNORE INTO profiles (id, name, color_hex, sort_order) VALUES (2, 'Business', '#2196F3', 1)")
    }
}
