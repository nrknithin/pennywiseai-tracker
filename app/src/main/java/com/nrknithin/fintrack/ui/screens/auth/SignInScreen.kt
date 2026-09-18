package com.nrknithin.fintrack.ui.screens.auth

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nrknithin.fintrack.ui.components.FinTrackScaffold
import com.nrknithin.fintrack.ui.theme.Dimensions
import com.nrknithin.fintrack.ui.theme.Spacing

/**
 * The sign-in gate. The app's start destination whenever the locally-persisted
 * `auth_signed_in` flag is false and this build actually has a gateway.
 *
 * There is deliberately no way back and no way past it. Back is consumed, and there is no
 * skip affordance — the owner's decision, twice stated. What keeps that from becoming a
 * lockout is upstream of this screen: a build with no OAuth web client ID reports
 * `AuthGateway.isAvailable = false`, and `ThemeViewModel` then clears the gate rather than
 * routing here, so this screen is only ever reached by a build that can actually sign in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignInScreen(
    onSignedIn: (hasCompletedOnboarding: Boolean) -> Unit,
    viewModel: SignInViewModel = hiltViewModel(),
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val activity = LocalContext.current.findActivity()

    // The gate is not skippable
    BackHandler(enabled = true) {
        // Do nothing - prevent back navigation
    }

    // Navigate away only once the gate has actually been cleared
    LaunchedEffect(uiState.phase) {
        if (uiState.phase == SignInPhase.Completed) {
            onSignedIn(uiState.hasCompletedOnboarding)
        }
    }

    FinTrackScaffold(
        modifier = modifier,
        transparentTopBar = true
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(Spacing.lg),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Filled.AccountCircle,
                    contentDescription = null,
                    modifier = Modifier.size(Dimensions.Icon.extraLarge),
                    tint = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(Spacing.xl))

                // Title
                Text(
                    text = "Welcome to FinTrack",
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(Spacing.md))

                // Honest copy: this is a privacy-first on-device app suddenly asking for a
                // Google account, and the screen has to say why in one sentence.
                Text(
                    text = "Sign in once with Google to name your profile. Everything after " +
                        "that stays on this device — and works offline.",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(Spacing.xl))

                // A dismissed chooser is not an error, so nothing is shown for it
                if (uiState.failure != null) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = SignInViewModel.messageFor(uiState.failure!!, uiState.diagnostic),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(Spacing.md),
                            textAlign = TextAlign.Center
                        )
                    }
                    Spacer(modifier = Modifier.height(Spacing.md))
                }

                if (uiState.phase == SignInPhase.InProgress) {
                    CircularProgressIndicator()
                } else {
                    Button(
                        onClick = { activity?.let { viewModel.signIn(it) } },
                        // Credential Manager cannot run without a real Activity; without
                        // one the button would silently do nothing.
                        enabled = activity != null,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (uiState.phase == SignInPhase.Failed) "Try again"
                            else "Continue with Google"
                        )
                    }
                }

                // No "continue without signing in". The owner's call: the gate is
                // absolute, and a way past it made the gate decorative. The safety this
                // gave up is real — if sign-in fails on a device, there is no route into
                // the app from this screen. What replaces it is that the gate only ever
                // appears when sign-in is actually configured: a build with no OAuth web
                // client ID reports isAvailable = false, and ThemeViewModel clears the
                // gate outright rather than showing a screen with no way forward.
                Spacer(modifier = Modifier.height(Spacing.md))

                // Privacy note. It says "keeps", not "reads", on purpose: Google's own
                // sheet appears seconds later and tells the user the app receives their
                // name, email and picture. A first screen making a claim the next screen
                // disproves costs exactly the trust the sentence exists to build.
                Text(
                    text = "Google gives FinTrack your name, email and profile picture. " +
                        "Only the name is kept, on this device — nothing is sent anywhere.",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * `LocalContext.current as? Activity` is NOT safe here: a ContextWrapper anywhere in the
 * chain (themes, edge-to-edge helpers) makes the cast return null and the button silently
 * do nothing. MainActivity is a FragmentActivity, which is an Activity.
 */
internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
