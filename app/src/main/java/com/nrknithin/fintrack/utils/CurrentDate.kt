package com.nrknithin.fintrack.utils

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Today's date as a flow, so anything bucketed by "the current cycle" moves on when the
 * day does — not only when the app process restarts. Without it a Home screen left in
 * memory overnight kept last cycle's spend on payday.
 *
 * Refreshed when the app comes to the foreground and at each midnight while it is open
 * (both driven by FinTrackApplication). Emits only when the date actually changes.
 */
@Singleton
class CurrentDate @Inject constructor() {
    private val _today = MutableStateFlow(LocalDate.now())
    val today: StateFlow<LocalDate> = _today.asStateFlow()

    fun refresh() {
        _today.value = LocalDate.now()
    }
}
