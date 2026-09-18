package com.nrknithin.fintrack.navigation

import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The launch decision as a truth table — all four cells, because the interesting one is
 * not the happy path.
 *
 * `FinTrackNavHost` pins the start destination with an unkeyed `remember {}`, so this
 * function's answer is the only chance the app gets. There is no Compose test here and
 * cannot be under `./init.sh`; this is the part of the decision that can be executed, and
 * it is the part that can be got wrong.
 */
class StartDestinationResolverTest {

    @Test
    fun `a fresh install starts at the sign-in gate`() {
        assertSame(
            SignIn,
            StartDestinationResolver.resolve(
                isAuthGateCleared = false,
                hasCompletedOnboarding = false
            )
        )
    }

    @Test
    fun `a stale onboarding flag cannot walk past the gate`() {
        // The anti-bypass cell, and the reason the `when` checks the gate FIRST. An update
        // over an existing install, or a restored backup, arrives with
        // has_completed_onboarding already true and auth_signed_in false. If anyone
        // reorders those two branches, this is the assertion that goes red.
        assertSame(
            SignIn,
            StartDestinationResolver.resolve(
                isAuthGateCleared = false,
                hasCompletedOnboarding = true
            )
        )
    }

    @Test
    fun `clearing the gate on a fresh install lands on onboarding`() {
        assertSame(
            OnBoarding,
            StartDestinationResolver.resolve(
                isAuthGateCleared = true,
                hasCompletedOnboarding = false
            )
        )
    }

    @Test
    fun `a returning user goes straight home`() {
        // R5 expressed as routing: a cold start with the flag already on disk never shows
        // the gate, so it never needs the network. See ThemeViewModelGateTest for the
        // other half of that proof — that the gateway is not consulted to get here.
        assertSame(
            Home,
            StartDestinationResolver.resolve(
                isAuthGateCleared = true,
                hasCompletedOnboarding = true
            )
        )
    }
}
