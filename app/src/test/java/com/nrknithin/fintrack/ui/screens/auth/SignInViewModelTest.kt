package com.nrknithin.fintrack.ui.screens.auth

import android.app.Activity
import com.nrknithin.fintrack.auth.AuthFailure
import com.nrknithin.fintrack.auth.AuthRepository
import com.nrknithin.fintrack.auth.AuthResult
import com.nrknithin.fintrack.auth.FakeAuthGateway
import com.nrknithin.fintrack.auth.GoogleIdentity
import com.nrknithin.fintrack.data.preferences.UserPreferences
import com.nrknithin.fintrack.data.preferences.UserPreferencesRepository
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The sign-in phase machine.
 *
 * A `StandardTestDispatcher` is what makes the intermediate `InProgress` observable:
 * `signIn()` flips the phase synchronously and only then launches, so the state can be
 * read before `advanceUntilIdle()` lets the gateway answer. That ordering is the
 * double-tap guard, so it is worth pinning rather than glossing over.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SignInViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val gateway = FakeAuthGateway()
    private val prefs = mockk<UserPreferencesRepository>(relaxed = true)

    /** Ignored by [FakeAuthGateway]; it exists only to satisfy the signature. */
    private val activity = mockk<Activity>(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a successful sign-in walks Idle to InProgress to Completed`() = runTest(testDispatcher) {
        val viewModel = viewModel()
        assertEquals(SignInPhase.Idle, viewModel.uiState.value.phase)

        gateway.scripted.addLast(AuthResult.Success(GoogleIdentity(DISPLAY_NAME, ID_TOKEN)))
        viewModel.signIn(activity)

        assertEquals(SignInPhase.InProgress, viewModel.uiState.value.phase)

        advanceUntilIdle()

        assertEquals(SignInPhase.Completed, viewModel.uiState.value.phase)
        assertNull(viewModel.uiState.value.failure)
    }

    @Test
    fun `a dismissed chooser returns to Idle and shows no error`() = runTest(testDispatcher) {
        // Dismissing the account picker is a decision, not a fault. A red banner here
        // reads as a bug in the app.
        val viewModel = viewModel()
        gateway.scripted.addLast(AuthResult.Cancelled)

        viewModel.signIn(activity)
        advanceUntilIdle()

        assertEquals(SignInPhase.Idle, viewModel.uiState.value.phase)
        assertNull(viewModel.uiState.value.failure)
        assertNull(viewModel.uiState.value.diagnostic)
    }

    @Test
    fun `every failure reason reaches the screen unchanged`() = runTest(testDispatcher) {
        val viewModel = viewModel()

        for (reason in AuthFailure.entries) {
            gateway.scripted.addLast(AuthResult.Failure(reason))
            viewModel.signIn(activity)
            advanceUntilIdle()

            assertEquals(SignInPhase.Failed, viewModel.uiState.value.phase)
            assertEquals(reason, viewModel.uiState.value.failure)
        }
    }

    @Test
    fun `an unregistered build carries the package name and nothing else`() =
        runTest(testDispatcher) {
            val viewModel = viewModel()
            gateway.scripted.addLast(
                AuthResult.Failure(AuthFailure.APP_NOT_REGISTERED, PACKAGE_NAME)
            )

            viewModel.signIn(activity)
            advanceUntilIdle()

            assertEquals(AuthFailure.APP_NOT_REGISTERED, viewModel.uiState.value.failure)
            assertEquals(PACKAGE_NAME, viewModel.uiState.value.diagnostic)
        }

    @Test
    fun `a second tap while the chooser is open does not open a second chooser`() =
        runTest(testDispatcher) {
            val viewModel = viewModel()
            gateway.scripted.addLast(AuthResult.Success(GoogleIdentity(DISPLAY_NAME, ID_TOKEN)))

            viewModel.signIn(activity)
            viewModel.signIn(activity)
            advanceUntilIdle()

            assertEquals(1, gateway.signInCalls)
            assertEquals(SignInPhase.Completed, viewModel.uiState.value.phase)
        }

    @Test
    fun `Failed is always recoverable`() = runTest(testDispatcher) {
        val viewModel = viewModel()
        gateway.scripted.addLast(AuthResult.Failure(AuthFailure.UNKNOWN))
        viewModel.signIn(activity)
        advanceUntilIdle()
        assertEquals(SignInPhase.Failed, viewModel.uiState.value.phase)

        gateway.scripted.addLast(AuthResult.Success(GoogleIdentity(DISPLAY_NAME, ID_TOKEN)))
        viewModel.retry(activity)

        // The stale failure is cleared on the way in, not on the way out — otherwise the
        // old message sits under the spinner for the whole second attempt.
        assertEquals(SignInPhase.InProgress, viewModel.uiState.value.phase)
        assertNull(viewModel.uiState.value.failure)

        advanceUntilIdle()

        assertEquals(SignInPhase.Completed, viewModel.uiState.value.phase)
        assertEquals(2, gateway.signInCalls)
    }

    @Test
    fun `the escape hatch is offered only to an install with data behind the gate`() =
        runTest(testDispatcher) {
            val viewModel = viewModel(hasCompletedOnboarding = true)
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.canContinueWithoutSigningIn) // Idle

            gateway.scripted.addLast(AuthResult.Failure(AuthFailure.APP_NOT_REGISTERED))
            viewModel.signIn(activity)
            assertFalse(viewModel.uiState.value.canContinueWithoutSigningIn) // InProgress

            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.canContinueWithoutSigningIn) // Failed
        }

    @Test
    fun `a cancelled attempt also opens the escape hatch`() = runTest(testDispatcher) {
        // The reason this is not merely thorough: Play services reports DEVELOPER_ERROR —
        // an unregistered package or SHA-1 — by closing its activity with RESULT_CANCELED,
        // so the single most likely real-world failure arrives as Cancelled, not Failure.
        // Gating the escape on SignInPhase.Failed made it unreachable in exactly the case
        // it exists for: an owner with a ledger already behind the gate, facing a picker
        // that closes itself every time, and a BackHandler that eats the back gesture.
        val viewModel = viewModel(hasCompletedOnboarding = true)
        advanceUntilIdle()

        gateway.scripted.addLast(AuthResult.Cancelled)
        viewModel.signIn(activity)
        advanceUntilIdle()

        // No banner — a dismissed picker is still not an error.
        assertEquals(SignInPhase.Idle, viewModel.uiState.value.phase)
        assertNull(viewModel.uiState.value.failure)
        // But the way out is now reachable.
        assertTrue(viewModel.uiState.value.canContinueWithoutSigningIn)
    }

    @Test
    fun `a fresh install is never offered the escape hatch`() = runTest(testDispatcher) {
        // Nothing is behind the gate on a fresh install, so there is no lockout to escape
        // — and a skip button on a first run would make the gate decorative.
        val viewModel = viewModel(hasCompletedOnboarding = false)
        advanceUntilIdle()

        gateway.scripted.addLast(AuthResult.Failure(AuthFailure.APP_NOT_REGISTERED))
        viewModel.signIn(activity)
        advanceUntilIdle()

        assertEquals(SignInPhase.Failed, viewModel.uiState.value.phase)
        assertFalse(viewModel.uiState.value.canContinueWithoutSigningIn)

        viewModel.continueWithoutSigningIn()

        assertEquals(SignInPhase.Failed, viewModel.uiState.value.phase)
    }

    @Test
    fun `continuing without signing in persists nothing`() = runTest(testDispatcher) {
        // It must never become a permanent opt-out: nothing is written, so the gate is
        // back on the next cold start. A misconfigured console costs one tap per launch
        // instead of the database.
        val viewModel = viewModel(hasCompletedOnboarding = true)
        advanceUntilIdle()
        gateway.scripted.addLast(AuthResult.Failure(AuthFailure.APP_NOT_REGISTERED))
        viewModel.signIn(activity)
        advanceUntilIdle()

        val callsBefore = gateway.signInCalls
        viewModel.continueWithoutSigningIn()
        advanceUntilIdle()

        assertEquals(SignInPhase.Completed, viewModel.uiState.value.phase)
        assertEquals(callsBefore, gateway.signInCalls)
        coVerify(exactly = 0) { prefs.applyGoogleIdentity(any()) }
    }

    @Test
    fun `the unregistered-build message names the package to register`() {
        // The one failure a developer can actually fix, and the message is the only place
        // the package name appears — so it has to be in there.
        val message = SignInViewModel.messageFor(AuthFailure.APP_NOT_REGISTERED, PACKAGE_NAME)

        assertTrue(message, message.contains(PACKAGE_NAME))
    }

    @Test
    fun `every failure reason has a message of its own`() {
        val messages = AuthFailure.entries.map { SignInViewModel.messageFor(it, null) }

        assertTrue(messages.none { it.isBlank() })
        assertEquals(AuthFailure.entries.size, messages.toSet().size)
    }

    private fun viewModel(hasCompletedOnboarding: Boolean = false): SignInViewModel {
        // Stubbed explicitly: a relaxed mock would hand back a Flow that never emits, and
        // hasCompletedOnboarding would silently stay false.
        every { prefs.userPreferences } returns flowOf(
            UserPreferences(hasCompletedOnboarding = hasCompletedOnboarding)
        )
        return SignInViewModel(AuthRepository(gateway, prefs), prefs)
    }

    private companion object {
        const val DISPLAY_NAME = "Sample User"
        const val ID_TOKEN = "fake-id-token"

        /** Shaped like the debug applicationId; not read from anywhere. */
        const val PACKAGE_NAME = "com.example.app.debug"
    }
}
