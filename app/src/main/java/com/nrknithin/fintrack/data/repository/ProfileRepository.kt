package com.nrknithin.fintrack.data.repository

import com.nrknithin.fintrack.data.database.dao.ProfileDao
import com.nrknithin.fintrack.data.database.entity.ProfileEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProfileRepository @Inject constructor(
    private val profileDao: ProfileDao
) {
    fun observeAllProfiles(): Flow<List<ProfileEntity>> = profileDao.observeAllProfiles()

    suspend fun getAllProfiles(): List<ProfileEntity> = profileDao.getAllProfiles()

    suspend fun getById(id: Long): ProfileEntity? = profileDao.getById(id)

    suspend fun insert(profile: ProfileEntity) = profileDao.insert(profile)

    suspend fun update(profile: ProfileEntity) = profileDao.update(profile)

    suspend fun deleteById(id: Long) = profileDao.deleteById(id)
}
