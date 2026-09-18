package com.nrknithin.fintrack.ui.viewmodel

import com.nrknithin.fintrack.auth.FakeAuthGateway
import com.nrknithin.fintrack.data.preferences.UserPreferences
import com.nrknithin.fintrack.data.preferences.UserPreferencesRepository
import com.nrknithin.fintrack.navigation.Home
import com.nrknithin.fintrack.navigation.StartDestinationResolver
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The gate formula, `isAuthGateCleared = !signInSupported || authSignedIn`, and the
 * returning-user proof.
 *
 * `ThemeViewModel` is where the launch decision is settled, and it has to be settled in
 * the same DataStore emission that clears `isLoading` — `FinTrackNavHost` pins the start
 * destination with an unkeyed `remember {}`, so a gate that is still undecided on the
 * first non-loading frame is a gate that does not exist. Every test here therefore waits
 * for `first { !it.isLoading }` and reads the answer out of that one emission.
 *
 * `UserPreferencesRepository` is stubbed with MockK for the DataStore-singleton reason
 * spelled out in `AuthRepositoryTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThemeViewModelGateTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        // viewModelScope runs on Dispatchers.Main.immediate, which stateIn() needs.
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `the gate stays shut on a build that can sign in until the flag is on disk`() =
        runTest(testDispatcher) {
            val gateway = FakeAuthGateway(isAvailable = true)

            val state = settledState(gateway, authSignedIn = false)

            assertFalse(state.isAuthGateCleared)
        }

    @Test
    fun `a returning signed-in user is not re-prompted and the gateway is never touched`() =
        runTest(testDispatcher) {
            // THE R5 PROOF. Two halves, and both matter:
            //
            //  1. the gate reports itself cleared from the persisted flag alone, so the
            //     start destination resolves to Home rather than SignIn; and
            //  2. signInCalls is still 0 — the decision was reached without asking the
            //     gateway anything, and therefore without Credential Manager, Play
            //     services or the network. That is what makes an offline cold start work.
            //
            // If someone ever "improves" this by validating the session at launch, (1)
            // may well still pass and (2) will not.
            val gateway = FakeAuthGateway(isAvailable = true)

            val state = settledState(gateway, authSignedIn = true)

            assertTrue(state.isAuthGateCleared)
            assertEquals(0, gateway.signInCalls)
            assertSame(
                Home,
                StartDestinationResolver.resolve(
                    isAuthGateCleared = state.isAuthGateCleared,
                    hasCompletedOnboarding = true
                )
            )
        }

    @Test
    fun `a build with no gateway is transparent`() = runTest(testDispatcher) {
        // fdroid, and any build whose OAuth web client ID is blank. Without this the app
        // would brick on first launch behind a button that cannot work — which is also the
        // escape hatch if the gate ever misbehaves: drop the property, rebuild.
        val gateway = FakeAuthGateway(isAvailable = false)

        val state = settledState(gateway, authSignedIn = false)

        assertTrue(state.isAuthGateCleared)
        assertEquals(0, gateway.signInCalls)
    }

    @Test
    fun `a build with no gateway stays transparent even with the flag set`() =
        runTest(testDispatcher) {
            val gateway = FakeAuthGateway(isAvailable = false)

            val state = settledState(gateway, authSignedIn = true)

            assertTrue(state.isAuthGateCleared)
        }

    @Test
    fun `the google display name rides the same emission`() = runTest(testDispatcher) {
        // Settings reads it from here rather than opening a second flow, so it has to be
        // on ThemeUiState and not only in DataStore.
        val prefs = mockk<UserPreferencesRepository>()
        every { prefs.userPreferences } returns flowOf(
            UserPreferences(authSignedIn = true, authDisplayName = DISPLAY_NAME)
        )

        val state = ThemeViewModel(prefs, FakeAuthGateway(isAvailable = true))
            .themeUiState.first { !it.isLoading }

        assertEquals(DISPLAY_NAME, state.authDisplayName)
    }

    /** The first emission in which the launch decision is actually made. */
    private suspend fun settledState(
        gateway: FakeAuthGateway,
        authSignedIn: Boolean
    ): ThemeUiState {
        val prefs = mockk<UserPreferencesRepository>()
        every { prefs.userPreferences } returns flowOf(UserPreferences(authSignedIn = authSignedIn))
        return ThemeViewModel(prefs, gateway).themeUiState.first { !it.isLoading }
    }

    private companion object {
        const val DISPLAY_NAME = "Sample User"
    }
}
