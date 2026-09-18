package com.nrknithin.fintrack.di

import com.nrknithin.fintrack.auth.AuthGateway
import com.nrknithin.fintrack.auth.NoAuthGateway
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt bindings for the F-Droid flavor, which carries no Google Play libraries. The
 * standard/personal flavors have a sibling module of the same FQN under app/src/cloud/
 * wiring the real Credential Manager gateway.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AuthModule {

    @Binds
    @Singleton
    abstract fun bindAuthGateway(impl: NoAuthGateway): AuthGateway
}
