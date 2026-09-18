package com.nrknithin.fintrack.auth

import android.app.Activity
import com.nrknithin.fintrack.data.preferences.UserPreferencesRepository
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate-opening rules, which live in [AuthRepository] and nowhere else.
 *
 * `UserPreferencesRepository` is stubbed with MockK rather than constructed for real: its
 * `userPreferences` property is initialised eagerly from the `preferencesDataStore`
 * delegate, which is a per-JVM singleton keyed on the file name. Two tests opening it in
 * one Gradle worker leak state into each other and throw "There are multiple DataStores
 * active for the same file". So the *atomicity* of `applyGoogleIdentity` is structural
 * (one `dataStore.edit {}` block) and deliberately not asserted here — what is asserted is
 * that the repository calls it, exactly once, and only when it should.
 *
 * The display name below is an invented placeholder, per the redaction rule the parser
 * fixtures follow.
 */
class AuthRepositoryTest {

    private val gateway = FakeAuthGateway()
    private val prefs = mockk<UserPreferencesRepository>(relaxed = true)
    private val repository = AuthRepository(gateway, prefs)

    /** Ignored by [FakeAuthGateway]; it exists only to satisfy the signature. */
    private val activity = mockk<Activity>(relaxed = true)

    @Test
    fun `isAvailable is the gateway's answer and nothing else`() {
        gateway.isAvailable = true
        assertTrue(repository.isAvailable)

        gateway.isAvailable = false
        assertFalse(repository.isAvailable)
    }

    @Test
    fun `a successful sign-in persists the identity before it reports success`() = runTest {
        gateway.scripted.addLast(AuthResult.Success(GoogleIdentity(DISPLAY_NAME, ID_TOKEN)))

        val result = repository.signIn(activity)

        // Verified BEFORE the result is asserted, on purpose: this pins persist-then-report.
        // A caller that sees Success must be able to rely on the write having landed, so
        // that a process death mid-flow leaves the gate shut rather than half-open.
        coVerify(exactly = 1) { prefs.applyGoogleIdentity(DISPLAY_NAME) }
        assertTrue(result is AuthResult.Success)
    }

    @Test
    fun `a success with no usable name still opens the gate`() = runTest {
        // The writer is the one that decides not to write the name half — the repository
        // must not start second-guessing it, or the gate never opens for an account whose
        // profile carries no display name.
        gateway.scripted.addLast(AuthResult.Success(GoogleIdentity(null, ID_TOKEN)))

        val result = repository.signIn(activity)

        coVerify(exactly = 1) { prefs.applyGoogleIdentity(null) }
        assertTrue(result is AuthResult.Success)
    }

    @Test
    fun `a success with a blank name still opens the gate`() = runTest {
        gateway.scripted.addLast(AuthResult.Success(GoogleIdentity(BLANK_NAME, ID_TOKEN)))

        val result = repository.signIn(activity)

        coVerify(exactly = 1) { prefs.applyGoogleIdentity(BLANK_NAME) }
        assertTrue(result is AuthResult.Success)
    }

    @Test
    fun `a dismissed chooser leaves the gate shut`() = runTest {
        gateway.scripted.addLast(AuthResult.Cancelled)

        val result = repository.signIn(activity)

        assertSame(AuthResult.Cancelled, result)
        coVerify(exactly = 0) { prefs.applyGoogleIdentity(any()) }
    }

    @Test
    fun `no failure reason opens the gate`() = runTest {
        for (reason in AuthFailure.entries) {
            gateway.scripted.addLast(AuthResult.Failure(reason))

            val result = repository.signIn(activity)

            assertTrue("$reason should surface as a Failure", result is AuthResult.Failure)
            assertEquals(reason, (result as AuthResult.Failure).reason)
        }
        coVerify(exactly = 0) { prefs.applyGoogleIdentity(any()) }
    }

    @Test
    fun `signing out closes the gate without blanking the profile`() = runTest {
        repository.signOut()

        coVerify(exactly = 1) { prefs.forgetGoogleIdentity() }
        // Signing out is not a factory reset: the profile name, the avatar, the background
        // colour and the whole transaction database stay exactly where they are.
        coVerify(exactly = 0) { prefs.updateUserName(any()) }
    }

    private companion object {
        const val DISPLAY_NAME = "Sample User"
        const val BLANK_NAME = "   "
        const val ID_TOKEN = "fake-id-token"
    }
}
