package com.nrknithin.fintrack.auth

import android.app.Activity

/**
 * A scripted [AuthGateway] — a fixture, not a test.
 *
 * Follows the hand-written-fake house style of `FakeMailboxClient` / `EmailIngestFakes`:
 * no mocking framework, no behaviour beyond handing back the next scripted answer, and
 * enough bookkeeping that a test can assert on what was *not* called.
 *
 * The [activity] argument is ignored entirely. That is the whole point: every test can
 * pass a `mockk<Activity>(relaxed = true)` and no test ever touches Credential Manager,
 * Play services or the network.
 *
 * [signInCalls] is what makes the "a returning user is never re-prompted" proof possible
 * — see `ThemeViewModelGateTest`. A gate decision that reaches the right answer while
 * having called this gateway is not the same thing as one that reached it from disk.
 */
class FakeAuthGateway(
    override var isAvailable: Boolean = true
) : AuthGateway {

    /** Answers handed back in order; an empty queue falls through to a generic failure. */
    val scripted = ArrayDeque<AuthResult>()

    var signInCalls = 0
        private set

    override suspend fun signIn(activity: Activity): AuthResult {
        signInCalls++
        return scripted.removeFirstOrNull() ?: AuthResult.Failure(AuthFailure.UNKNOWN)
    }
}
