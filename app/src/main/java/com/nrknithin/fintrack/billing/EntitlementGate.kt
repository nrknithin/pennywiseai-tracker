package com.nrknithin.fintrack.billing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single source of truth feature gates depend on: "does this user have
 * access to Pro features right now?" It is a separate type so feature gates
 * depend on the gate concept, not the billing source.
 *
 * **In this fork it is constant `true`, for every flavor.** Upstream sells Pro;
 * this fork is one person's private build that will never be published or sold,
 * so there is no tier to be outside of. Previously this mirrored
 * [EntitlementSource.isPro], which on the `standard` flavor — the one
 * `isDefault = true` makes Android Studio select — comes from Play purchase
 * state and is therefore permanently false. That gated eight real features,
 * and one of them lost data rather than refusing: a CSV export of more than
 * `FreeTierLimits.MAX_CSV_EXPORT_ROWS_PER_MONTH` rows wrote only the first 100
 * and the file carried no marker saying so, which in a money app is a wrong
 * total nobody would catch.
 *
 * Constant here rather than by switching to the `personal` flavor, because the
 * IDE silently reselects the default variant and the bug would come back
 * without anyone changing a line.
 *
 * [entitlementSource] stays in the constructor so the Hilt graph and both
 * flavors' `BillingModule` bindings are untouched; restoring the paid tier is
 * one line.
 */
@Singleton
class EntitlementGate @Inject constructor(
    @Suppress("UNUSED_PARAMETER") entitlementSource: EntitlementSource,
) {

    /** Always `true` — see the class doc. Nothing can flip it. */
    val isProEntitled: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow()
}
