package com.nrknithin.fintrack.data.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.nrknithin.fintrack.data.database.entity.BankLoanEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BankLoanDao {

    @Insert
    suspend fun insert(loan: BankLoanEntity): Long

    @Update
    suspend fun update(loan: BankLoanEntity)

    @Delete
    suspend fun delete(loan: BankLoanEntity)

    /** Active loans first, then by next EMI day; closed loans after. */
    @Query("SELECT * FROM bank_loans ORDER BY status = 'CLOSED', first_emi_date, name")
    fun observeAll(): Flow<List<BankLoanEntity>>

    @Query("SELECT * FROM bank_loans ORDER BY status = 'CLOSED', first_emi_date, name")
    suspend fun getAll(): List<BankLoanEntity>

    @Query("SELECT * FROM bank_loans WHERE status = 'ACTIVE'")
    suspend fun getActive(): List<BankLoanEntity>

    @Query("SELECT * FROM bank_loans WHERE id = :id")
    fun observeById(id: Long): Flow<BankLoanEntity?>

    @Query("SELECT * FROM bank_loans WHERE id = :id")
    suspend fun getById(id: Long): BankLoanEntity?

    @Query("SELECT * FROM bank_loans WHERE uid = :uid LIMIT 1")
    suspend fun getByUid(uid: String): BankLoanEntity?

    @Query("DELETE FROM bank_loans")
    suspend fun deleteAll()
}
