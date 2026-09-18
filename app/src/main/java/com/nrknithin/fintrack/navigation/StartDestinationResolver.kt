package com.nrknithin.fintrack.navigation

/**
 * The launch decision, as a pure function, so `./init.sh app` can execute it rather than
 * a comment claiming it is right.
 *
 * Branch ORDER is load-bearing: the gate is checked before onboarding, so a stale
 * `has_completed_onboarding` (an update over an existing install, a restored backup)
 * cannot walk past sign-in. StartDestinationResolverTest pins that cell.
 */
object StartDestinationResolver {
    fun resolve(
        isAuthGateCleared: Boolean,
        hasCompletedOnboarding: Boolean
    ): Any = when {
        !isAuthGateCleared -> SignIn
        hasCompletedOnboarding -> Home
        else -> OnBoarding
    }
}
