package com.nrknithin.fintrack.data.preferences

import android.content.Context
import com.nrknithin.fintrack.domain.model.BudgetCycle
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bank holidays the user has listed so a "last working day" payday cycle
 * ([BudgetCycle.LAST_WORKING_DAY]) steps back past them — a month ending on a holiday
 * pays on the working day before.
 *
 * Kept in SharedPreferences rather than DataStore because it is read synchronously at
 * startup: every budget window is computed from [BudgetCycle.holidays], so the list has
 * to be in place before the first screen asks for one.
 */
@Singleton
class PaydayHolidaysStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _holidays = MutableStateFlow(load())
    val holidays: StateFlow<Set<LocalDate>> = _holidays.asStateFlow()

    init {
        BudgetCycle.holidays = _holidays.value
    }

    fun add(date: LocalDate) = save(_holidays.value + date)

    fun remove(date: LocalDate) = save(_holidays.value - date)

    private fun save(dates: Set<LocalDate>) {
        prefs.edit().putStringSet(KEY, dates.mapTo(HashSet()) { it.toString() }).apply()
        BudgetCycle.holidays = dates
        _holidays.value = dates
    }

    private fun load(): Set<LocalDate> =
        prefs.getStringSet(KEY, emptySet()).orEmpty()
            .mapNotNullTo(HashSet()) { runCatching { LocalDate.parse(it) }.getOrNull() }

    private companion object {
        const val PREFS_NAME = "payday_prefs"
        const val KEY = "holidays"
    }
}
