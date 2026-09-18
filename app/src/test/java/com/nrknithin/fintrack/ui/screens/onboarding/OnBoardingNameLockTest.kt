package com.nrknithin.fintrack.ui.screens.onboarding

import android.content.Context
import com.nrknithin.fintrack.data.preferences.UserPreferences
import com.nrknithin.fintrack.data.preferences.UserPreferencesRepository
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
 * The profile-name lock at the ViewModel seam.
 *
 * The rule is enforced twice on purpose — once in `updateUserName` and once in the
 * composable's `onValueChange`. This file pins the ViewModel half, where it holds for
 * every caller; `ProfileNameFieldSourceGuardTest` pins the composable half, which cannot
 * be executed under `./init.sh`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnBoardingNameLockTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        // The init{} seed runs in viewModelScope, so it needs a Main dispatcher.
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a google name seeds the field and locks it`() = runTest(testDispatcher) {
        val viewModel = viewModel(authDisplayName = DISPLAY_NAME)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(DISPLAY_NAME, state.userName)
        assertTrue(state.isNameLocked)
        // The literal predicate OnBoardingBottomBar's PROFILE branch uses. If the seeding
        // is ever removed while the field stays locked, Save & Continue is permanently
        // disabled and onboarding dead-ends on a field nobody can type into. That is the
        // failure this assertion exists for — it is not a restatement of the one above.
        assertTrue(state.userName.isNotBlank())
    }

    @Test
    fun `a locked name cannot be typed over`() = runTest(testDispatcher) {
        val viewModel = viewModel(authDisplayName = DISPLAY_NAME)
        advanceUntilIdle()

        viewModel.updateUserName("typed over it")

        assertEquals(DISPLAY_NAME, viewModel.uiState.value.userName)
        assertTrue(viewModel.uiState.value.isNameLocked)
    }

    @Test
    fun `no google name leaves the field editable`() = runTest(testDispatcher) {
        // This is what keeps the step completable for an account with no display name.
        val viewModel = viewModel(authDisplayName = null)
        advanceUntilIdle()

        assertEquals("", viewModel.uiState.value.userName)
        assertFalse(viewModel.uiState.value.isNameLocked)

        viewModel.updateUserName("typed in")

        assertEquals("typed in", viewModel.uiState.value.userName)
    }

    @Test
    fun `a blank google name leaves the field editable`() = runTest(testDispatcher) {
        val viewModel = viewModel(authDisplayName = "   ")
        advanceUntilIdle()

        assertEquals("", viewModel.uiState.value.userName)
        assertFalse(viewModel.uiState.value.isNameLocked)

        viewModel.updateUserName("typed in")

        assertEquals("typed in", viewModel.uiState.value.userName)
    }

    @Test
    fun `the avatar and background pickers are untouched by the lock`() =
        runTest(testDispatcher) {
            // The lock reached one TextField and nothing else. A change that quietly froze
            // the rest of the PROFILE step would otherwise pass every assertion above.
            val viewModel = viewModel(authDisplayName = DISPLAY_NAME)
            advanceUntilIdle()

            viewModel.selectAvatar(3)
            assertEquals(3, viewModel.uiState.value.selectedAvatarIndex)
            assertNull(viewModel.uiState.value.profileImageUri)

            viewModel.selectBackgroundColor(5)
            assertEquals(5, viewModel.uiState.value.selectedBackgroundColor)
        }

    private fun viewModel(authDisplayName: String?): OnBoardingViewModel {
        val prefs = mockk<UserPreferencesRepository>(relaxed = true)
        every { prefs.userPreferences } returns flowOf(
            UserPreferences(authDisplayName = authDisplayName)
        )
        return OnBoardingViewModel(
            prefs,
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk<Context>(relaxed = true)
        )
    }

    private companion object {
        const val DISPLAY_NAME = "Sample User"
    }
}
