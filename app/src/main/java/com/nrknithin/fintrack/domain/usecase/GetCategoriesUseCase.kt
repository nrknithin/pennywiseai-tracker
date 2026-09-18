package com.nrknithin.fintrack.domain.usecase

import com.nrknithin.fintrack.data.database.entity.CategoryEntity
import com.nrknithin.fintrack.data.repository.CategoryRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetCategoriesUseCase @Inject constructor(
    private val categoryRepository: CategoryRepository
) {
    fun execute(): Flow<List<CategoryEntity>> {
        return categoryRepository.getAllCategories()
    }
}