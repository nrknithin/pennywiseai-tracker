package com.nrknithin.fintrack.shared.data.repository

import com.nrknithin.fintrack.shared.data.local.dao.SharedMerchantMappingDao
import com.nrknithin.fintrack.shared.data.local.entity.SharedMerchantMappingEntity
import kotlinx.coroutines.flow.Flow

class RoomSharedMerchantMappingRepository(
    private val dao: SharedMerchantMappingDao
) : SharedMerchantMappingRepository {
    override fun observeAll(): Flow<List<SharedMerchantMappingEntity>> = dao.observeAll()
    override suspend fun getByMerchant(merchantName: String): SharedMerchantMappingEntity? = dao.getByMerchant(merchantName)
    override suspend fun upsert(mapping: SharedMerchantMappingEntity) = dao.upsert(mapping)
}
