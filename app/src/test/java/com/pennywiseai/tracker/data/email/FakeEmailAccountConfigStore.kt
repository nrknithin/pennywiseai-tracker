package com.pennywiseai.tracker.data.email

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant

/**
 * Settings in a variable. Test-only; the worker cannot tell it from the DataStore one.
 *
 * It enforces the same invariant the real store does — every value in and out goes through
 * [EmailAccountConfig.coerced] — so a test cannot accidentally hand the worker a
 * configuration production could never produce.
 *
 * The recorders exist for the resume tests of spec 11 R-11-07 / AC-11-02: what matters there
 * is not only the final watermark but that it advanced once per committed batch, and that a
 * batch commit did not rewrite the user's settings along the way.
 */
class FakeEmailAccountConfigStore(
    initial: EmailAccountConfig = EmailAccountConfig()
) : EmailAccountConfigStore {

    private val flow = MutableStateFlow(initial.coerced())

    override val config: Flow<EmailAccountConfig> = flow.asStateFlow()

    private val watermarks = mutableListOf<Pair<Long, Long>>()
    private val states = mutableListOf<EmailAccountState>()

    /** Watermarks written, oldest first, as `(uidValidity, lastSyncedUid)`. */
    val watermarkWrites: List<Pair<Long, Long>> get() = watermarks.toList()

    /** States written, oldest first — the banner sequence of spec 11 §11.7. */
    val stateWrites: List<EmailAccountState> get() = states.toList()

    /** How many times [update] ran. A batch commit must not increment this. */
    var updateCount: Int = 0
        private set

    /** The value a reader would see right now. */
    val value: EmailAccountConfig get() = flow.value

    override suspend fun current(): EmailAccountConfig = flow.value

    override suspend fun update(
        transform: (EmailAccountConfig) -> EmailAccountConfig
    ): EmailAccountConfig {
        updateCount++
        val next = transform(flow.value).coerced()
        flow.value = next
        return next
    }

    override suspend fun setWatermark(uidValidity: Long, lastSyncedUid: Long) {
        watermarks += uidValidity to lastSyncedUid
        flow.value = flow.value.copy(
            uidValidity = uidValidity,
            lastSyncedUid = lastSyncedUid
        ).coerced()
    }

    override suspend fun setState(state: EmailAccountState) {
        states += state
        flow.value = flow.value.copy(state = state)
    }

    override suspend fun markSynced(at: Instant) {
        flow.value = flow.value.copy(lastSyncAt = at)
    }

    override suspend fun resetWatermark() {
        setWatermark(0L, 0L)
    }
}
