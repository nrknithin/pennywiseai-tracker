package com.nrknithin.fintrack.shared.domain.usecase

import com.nrknithin.fintrack.shared.data.local.entity.SharedRuleApplicationEntity
import com.nrknithin.fintrack.shared.data.local.entity.SharedRuleEntity
import com.nrknithin.fintrack.shared.data.repository.SharedRuleRepository
import com.nrknithin.fintrack.shared.data.util.currentTimeMillis

class ManageRulesUseCase(
    private val repository: SharedRuleRepository
) {
    suspend fun upsertRule(
        id: String,
        name: String,
        priority: Int,
        conditionsJson: String,
        actionsJson: String
    ) {
        val now = currentTimeMillis()
        repository.upsertRule(
            SharedRuleEntity(
                id = id,
                name = name,
                priority = priority,
                conditionsJson = conditionsJson,
                actionsJson = actionsJson,
                isActive = true,
                isSystemTemplate = false,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now
            )
        )
    }

    suspend fun recordApplication(ruleId: String, ruleName: String, transactionId: Long, fieldsModifiedJson: String) {
        repository.addApplication(
            SharedRuleApplicationEntity(
                id = "${ruleId}_${transactionId}_${currentTimeMillis()}",
                ruleId = ruleId,
                ruleName = ruleName,
                transactionId = transactionId,
                fieldsModifiedJson = fieldsModifiedJson,
                appliedAtEpochMillis = currentTimeMillis()
            )
        )
    }
}
