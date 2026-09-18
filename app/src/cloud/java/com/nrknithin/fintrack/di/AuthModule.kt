package com.nrknithin.fintrack.di

import com.nrknithin.fintrack.auth.AuthGateway
import com.nrknithin.fintrack.auth.GoogleAuthGateway
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Bindings for the flavors that may carry Google Play libraries (`standard`, `personal`).
 * `fdroid` has a sibling module of the same FQN under app/src/nocloud/ — exactly the
 * arrangement di/BillingModule.kt already uses.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AuthModule {

    @Binds
    @Singleton
    abstract fun bindAuthGateway(impl: GoogleAuthGateway): AuthGateway
}
