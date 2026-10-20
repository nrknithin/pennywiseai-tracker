package com.nrknithin.fintrack.di

import com.nrknithin.fintrack.billing.EntitlementSource
import com.nrknithin.fintrack.billing.PlayBillingGateway
import com.nrknithin.fintrack.billing.PurchaseGateway
import com.nrknithin.fintrack.billing.PurchaseLauncher
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt bindings for the standard (Play Store) flavor. [PurchaseGateway] is
 * implemented by Google Play Billing 9; the narrow interfaces ([EntitlementSource]
 * for readers, [PurchaseLauncher] for the paywall) bind to the same singleton
 * so consumers can depend on only what they need (ISP).
 *
 * F-Droid has a sibling module under `app/src/fdroid/` binding everything to
 * the always-Pro stub. Shared `main` sources only ever import the interfaces.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class BillingModule {

    @Binds
    @Singleton
    abstract fun bindPurchaseGateway(impl: PlayBillingGateway): PurchaseGateway

    @Binds
    @Singleton
    abstract fun bindEntitlementSource(impl: PlayBillingGateway): EntitlementSource

    @Binds
    @Singleton
    abstract fun bindPurchaseLauncher(impl: PlayBillingGateway): PurchaseLauncher
}
