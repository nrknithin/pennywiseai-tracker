package com.nrknithin.fintrack.shared.data.repository

import com.nrknithin.fintrack.shared.data.local.entity.SharedSubscriptionEntity
import kotlinx.coroutines.flow.Flow

interface SharedSubscriptionRepository {
    fun observeAll(): Flow<List<SharedSubscriptionEntity>>
    suspend fun upsert(subscription: SharedSubscriptionEntity): Long
    suspend fun deleteById(id: Long)
}
