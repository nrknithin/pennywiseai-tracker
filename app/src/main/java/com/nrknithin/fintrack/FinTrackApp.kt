package com.nrknithin.fintrack

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import com.nrknithin.fintrack.navigation.AppLock
import com.nrknithin.fintrack.navigation.SignIn
import com.nrknithin.fintrack.navigation.StartDestinationResolver
import com.nrknithin.fintrack.navigation.FinTrackNavHost
import com.nrknithin.fintrack.ui.theme.FinTrackTheme
import com.nrknithin.fintrack.ui.viewmodel.AppLockViewModel
import com.nrknithin.fintrack.ui.viewmodel.ThemeViewModel
import com.nrknithin.fintrack.widget.WidgetRefresher

@Composable
fun FinTrackApp(
    themeViewModel: ThemeViewModel? = null,
    appLockViewModel: AppLockViewModel? = null,
    editTransactionId: Long? = null,
    openAddTransaction: Boolean = false,
    onEditComplete: (() -> Unit)? = null,
    onAddTransactionShortcutHandled: (() -> Unit)? = null
) {
    val owner = androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner.current ?: error("No ViewModelStoreOwner")
    val actualThemeViewModel: ThemeViewModel = themeViewModel ?: hiltViewModel(viewModelStoreOwner = owner, key = null)
    val actualAppLockViewModel: AppLockViewModel = appLockViewModel ?: hiltViewModel(viewModelStoreOwner = owner, key = null)

    val themeUiState by actualThemeViewModel.themeUiState.collectAsStateWithLifecycle()
    val appLockUiState by actualAppLockViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val darkTheme = themeUiState.isDarkTheme ?: isSystemInDarkTheme()

    val navController = rememberNavController()
    val lifecycleOwner = LocalLifecycleOwner.current

    // Observe lifecycle events and refresh lock state when app resumes from background
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // App came to foreground - check if it should be locked
                actualAppLockViewModel.refreshLockState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Wait for preferences to load before deciding start destination
    if (themeUiState.isLoading) {
        return
    }

    // REMOVED: the upstream "migrate existing users" effect, which auto-completed
    // onboarding whenever READ_SMS was already granted.
    //
    // It existed so a PennyWise user updating the app was not dragged back through a
    // first-run flow. In this fork it does active harm: SMS permission is granted on the
    // owner's phone, so the moment the gate cleared it marked onboarding complete and a
    // genuinely new account was dropped straight onto Home — never seeing the PROFILE
    // step that adopts the Google name, which is the entire point of the sign-in. A new
    // account signing in now resets has_completed_onboarding (see
    // UserPreferencesRepository.applyGoogleIdentity) and this effect would have undone
    // that within a frame.

    // Set correct start destination based on loaded preferences.
    // The sign-in gate is decided HERE and never later: FinTrackNavHost pins this value
    // with an UNKEYED remember{}, so the decision must be complete on the first
    // non-loading frame. It is — isAuthGateCleared rides the same DataStore emission that
    // the isLoading early-return above already waits for.
    val startDestination: Any = remember(
        themeUiState.isAuthGateCleared,
        themeUiState.hasCompletedOnboarding
    ) {
        StartDestinationResolver.resolve(
            isAuthGateCleared = themeUiState.isAuthGateCleared,
            hasCompletedOnboarding = themeUiState.hasCompletedOnboarding
        )
    }

    // Observe lock state changes and navigate to lock screen if needed
    // But don't navigate when user is actively in Settings configuring app lock
    LaunchedEffect(appLockUiState.isLocked, appLockUiState.isLockEnabled, themeUiState.isAuthGateCleared) {
        if (appLockUiState.isLocked && appLockUiState.isLockEnabled) {
            val currentRoute = navController.currentDestination?.route
            // Don't navigate if already on lock screen, on the sign-in gate, or in
            // Settings (user is configuring).
            //
            // The route exemption — not an isAuthGateCleared guard — is what keeps the lock
            // from landing past the gate: AppLockScreen's onUnlocked navigates to Home with
            // popUpTo(AppLock){inclusive}, so it must never fire while SignIn is showing.
            // A gate guard here would also disable the lock for the rest of the session
            // after Settings -> Sign out, leaving the whole ledger visible on resume.
            // currentRoute is null before the first destination settles, which is also
            // before the gate has been decided, so null is treated as "not yet safe".
            if (currentRoute != null &&
                currentRoute != AppLock::class.qualifiedName &&
                currentRoute != SignIn::class.qualifiedName &&
                currentRoute != com.nrknithin.fintrack.navigation.Settings::class.qualifiedName) {
                navController.navigate(AppLock) {
                    // Don't add to back stack, force lock screen
                    popUpTo(navController.graph.startDestinationId) { inclusive = false }
                    launchSingleTop = true
                }
            }
        }
    }
    
    // Navigate to transaction detail when editTransactionId changes.
    //
    // Gated, like everything else that navigates: these two effects run on the first
    // non-loading frame, which is the frame that shows SignIn. Ungated they walk straight
    // past the gate into app content, and there are four live ways in — the launcher
    // long-press shortcut MainActivity registers in onCreate, the quick-add tile, the
    // recent-transactions widget, and an edit-transaction notification — plus the
    // fintrack://add deep link, which any other app can fire. Keying on the gate also
    // re-fires the intent once sign-in completes, so the shortcut is honoured, not lost.
    LaunchedEffect(editTransactionId, themeUiState.isAuthGateCleared) {
        if (!themeUiState.isAuthGateCleared) return@LaunchedEffect
        editTransactionId?.let { transactionId ->
            navController.navigate(com.nrknithin.fintrack.navigation.TransactionDetail(transactionId)) { launchSingleTop = true }
        }
    }

    // Navigate directly to Add Transaction when requested (e.g., from widget shortcut)
    LaunchedEffect(openAddTransaction, themeUiState.isAuthGateCleared) {
        if (themeUiState.isAuthGateCleared && openAddTransaction) {
            navController.navigate(com.nrknithin.fintrack.navigation.AddTransaction()) {
                launchSingleTop = true
            }
            onAddTransactionShortcutHandled?.invoke()
        }
    }

    // Keep widgets current when app launches (covers upgrades/installs with existing widgets)
    LaunchedEffect(Unit) {
        WidgetRefresher.refreshTransactionWidgets(context.applicationContext)
    }

    FinTrackTheme(
        darkTheme = darkTheme,
        dynamicColor = themeUiState.isDynamicColorEnabled,
        themeStyle = themeUiState.themeStyle,
        accentColor = themeUiState.accentColor,
        isAmoledMode = themeUiState.isAmoledMode,
        appFont = themeUiState.appFont,
        blurEffects = themeUiState.blurEffectsEnabled
    ) {
        FinTrackNavHost(
            navController = navController,
            themeViewModel = actualThemeViewModel,
            startDestination = startDestination,
            onEditComplete = onEditComplete ?: {}
        )
    }
}
