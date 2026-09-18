package com.nrknithin.fintrack.shared.domain.usecase

import com.nrknithin.fintrack.shared.data.local.entity.SharedTransactionSplitEntity
import com.nrknithin.fintrack.shared.data.repository.SharedSplitRepository
import com.nrknithin.fintrack.shared.data.util.currentTimeMillis

class ManageSplitUseCase(
    private val repository: SharedSplitRepository
) {
    suspend fun replaceSplits(
        transactionId: Long,
        categoryAmounts: List<Pair<String, Long>>
    ) {
        val now = currentTimeMillis()
        repository.replaceSplits(
            transactionId = transactionId,
            splits = categoryAmounts.map { (category, amountMinor) ->
                SharedTransactionSplitEntity(
                    transactionId = transactionId,
                    category = category,
                    amountMinor = amountMinor,
                    createdAtEpochMillis = now
                )
            }
        )
    }
}
