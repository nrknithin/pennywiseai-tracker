package com.nrknithin.fintrack.shared.data.repository

import com.nrknithin.fintrack.shared.data.local.entity.SharedUnrecognizedSmsEntity
import kotlinx.coroutines.flow.Flow

interface SharedUnrecognizedSmsRepository {
    fun observeVisible(): Flow<List<SharedUnrecognizedSmsEntity>>
    suspend fun insert(entity: SharedUnrecognizedSmsEntity): Long
    suspend fun markReported(id: Long)
    suspend fun softDelete(id: Long)
}
