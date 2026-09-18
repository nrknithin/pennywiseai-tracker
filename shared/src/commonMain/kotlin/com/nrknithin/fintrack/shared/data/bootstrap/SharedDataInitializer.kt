package com.nrknithin.fintrack.shared.data.bootstrap

import com.nrknithin.fintrack.shared.data.repository.SharedCategoryRepository
import com.nrknithin.fintrack.shared.data.util.currentTimeMillis

class SharedDataInitializer(
    private val categoryRepository: SharedCategoryRepository
) {
    suspend fun seedDefaultCategoriesIfNeeded() {
        if (categoryRepository.countCategories() > 0) return
        categoryRepository.insertCategories(
            DefaultSharedCategories.create(
                nowEpochMillis = currentTimeMillis()
            )
        )
    }
}
