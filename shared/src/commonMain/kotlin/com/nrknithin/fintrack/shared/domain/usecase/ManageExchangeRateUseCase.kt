package com.nrknithin.fintrack.shared.domain.usecase

import com.nrknithin.fintrack.shared.data.local.entity.SharedExchangeRateEntity
import com.nrknithin.fintrack.shared.data.repository.SharedExchangeRateRepository
import com.nrknithin.fintrack.shared.core.SharedTimeConstants
import com.nrknithin.fintrack.shared.data.util.currentTimeMillis

class ManageExchangeRateUseCase(
    private val repository: SharedExchangeRateRepository
) {
    suspend fun upsertRate(
        fromCurrency: String,
        toCurrency: String,
        rateMicros: Long,
        ttlMillis: Long = SharedTimeConstants.MILLIS_PER_DAY
    ) {
        val now = currentTimeMillis()
        repository.upsert(
            SharedExchangeRateEntity(
                fromCurrency = fromCurrency.uppercase(),
                toCurrency = toCurrency.uppercase(),
                rateMicros = rateMicros,
                provider = "manual",
                updatedAtEpochMillis = now,
                expiresAtEpochMillis = now + ttlMillis
            )
        )
    }
}
