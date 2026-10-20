package com.nrknithin.fintrack.di

import com.nrknithin.fintrack.data.currency.CurrencyConversionService
import com.nrknithin.fintrack.data.currency.ExchangeRateProvider
import com.nrknithin.fintrack.data.currency.ExchangeRateProviderFactory
import com.nrknithin.fintrack.data.database.dao.ExchangeRateDao
import com.nrknithin.fintrack.data.preferences.UserPreferencesRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Qualifier for an application-lifetime [CoroutineScope] — use this for
 * fire-and-forget work that must outlive the calling Activity/ViewModel
 * (e.g. a DB write started from a transient activity that finishes
 * immediately after).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/**
 * Hilt module that provides application-level dependencies.
 */
@Module
@InstallIn(SingletonComponent::class)
object ApplicationModule {

    /**
     * Provides the ExchangeRateProvider implementation.
     *
     * @return ExchangeRateProvider for fetching exchange rates
     */
    @Provides
    @Singleton
    fun provideExchangeRateProvider(): ExchangeRateProvider {
        return ExchangeRateProviderFactory.createProvider()
    }

    /**
     * Provides the CurrencyConversionService.
     *
     * @param exchangeRateDao Database access for exchange rates
     * @param exchangeRateProvider Provider for fetching rates from API
     * @param userPreferencesRepository User preferences for base currency
     * @return CurrencyConversionService for currency conversion operations
     */
    /**
     * Application-lifetime [CoroutineScope] for fire-and-forget work that
     * must outlive its launching component. SupervisorJob so a single failed
     * child doesn't take the scope down.
     */
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Provides
    @Singleton
    fun provideCurrencyConversionService(
        exchangeRateDao: ExchangeRateDao,
        exchangeRateProvider: ExchangeRateProvider,
        userPreferencesRepository: UserPreferencesRepository
    ): CurrencyConversionService {
        return CurrencyConversionService(
            exchangeRateDao = exchangeRateDao,
            exchangeRateProvider = exchangeRateProvider,
            userPreferencesRepository = userPreferencesRepository
        )
    }
}