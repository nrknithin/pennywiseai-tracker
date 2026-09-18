package com.nrknithin.fintrack.di

import com.nrknithin.fintrack.data.service.LlmServiceImpl
import com.nrknithin.fintrack.domain.service.LlmService
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class LlmModule {
    
    @Binds
    @Singleton
    abstract fun bindLlmService(
        llmServiceImpl: LlmServiceImpl
    ): LlmService
}