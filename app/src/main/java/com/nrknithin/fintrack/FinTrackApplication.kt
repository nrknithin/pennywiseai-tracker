package com.nrknithin.fintrack

import android.app.Activity
import android.app.Application
import android.content.res.Configuration as ResConfiguration
import android.os.Bundle
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.nrknithin.fintrack.data.preferences.UserPreferencesRepository
import com.nrknithin.fintrack.data.repository.AppLockRepository
import com.nrknithin.fintrack.ui.icons.CategoryMapping
import com.nrknithin.fintrack.utils.CurrencyFormatter
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class FinTrackApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var appLockRepository: AppLockRepository

    @Inject
    lateinit var purchaseGateway: com.nrknithin.fintrack.billing.PurchaseGateway

    @Inject
    lateinit var userPreferencesRepository: UserPreferencesRepository

    @Inject
    lateinit var categoryRepository: com.nrknithin.fintrack.data.repository.CategoryRepository

    @Inject
    lateinit var scheduledFolderBackupScheduler: com.nrknithin.fintrack.backup.folder.ScheduledFolderBackupScheduler

    // Injected so its list of payday holidays reaches BudgetCycle before any screen
    // computes a budget window.
    @Inject
    lateinit var paydayHolidaysStore: com.nrknithin.fintrack.data.preferences.PaydayHolidaysStore

    @Inject
    lateinit var bankLoanRepository: com.nrknithin.fintrack.data.repository.BankLoanRepository

    @Inject
    lateinit var currentDate: com.nrknithin.fintrack.utils.CurrentDate

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var activityReferences = 0
    private var isInForeground = false

    /**
     * Publicly accessible flag to check if the app is in the foreground.
     * Used by SmsBroadcastReceiver to determine whether to show notifications.
     */
    @Volatile
    var isAppInForeground: Boolean = false
        private set

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(AppLockLifecycleObserver())
        lastLocales = resources.configuration.locales

        // Initial billing-entitlement sync. No-op on F-Droid (the stub
        // gateway returns immediately). Failure here is non-fatal — the
        // cached entitlement (DataStore) keeps the UI consistent until
        // the next attempt.
        applicationScope.launch {
            try {
                purchaseGateway.refresh()
            } catch (e: Exception) {
                android.util.Log.w("FinTrackApp", "Initial billing refresh failed: ${e.message}", e)
            }
        }

        // Re-arm the daily folder backup on process start when the user has it enabled,
        // so scheduled work survives reboots / app updates that clear WorkManager state.
        applicationScope.launch {
            if (userPreferencesRepository.isScheduledFolderBackupEnabled()) {
                scheduledFolderBackupScheduler.schedule()
            }
        }

        // Roll "today" over at each midnight while the app stays open, so cycle-based
        // figures (Home's spend this cycle) switch on payday without a restart. The
        // foreground hook below covers the device having slept through midnight.
        applicationScope.launch {
            while (true) {
                val now = java.time.LocalDateTime.now()
                val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay()
                kotlinx.coroutines.delay(java.time.Duration.between(now, nextMidnight).toMillis() + 1_000)
                currentDate.refresh()
            }
        }

        // Bank loans: roll each EMI reminder to its next instalment and close loans
        // whose last EMI is past.
        applicationScope.launch {
            try {
                bankLoanRepository.syncAllReminders()
            } catch (e: Exception) {
                android.util.Log.w("FinTrackApp", "Bank loan reminder sync failed: ${e.message}", e)
            }
        }

        // Materialise recurring / scheduled manual (cash) transactions (#706):
        // a daily periodic run plus a one-shot catch-up now, so a device that was
        // off still creates anything that came due while it was down.
        com.nrknithin.fintrack.worker.RecurringTransactionWorker.enqueuePeriodic(this)
        com.nrknithin.fintrack.worker.RecurringTransactionWorker.enqueueOneShotCatchUp(this)

        // Keep CurrencyFormatter's number-format style in sync with the user's
        // preference. CurrencyFormatter is a stateless object used from non-Compose
        // contexts (widgets, workers) too, so we push the value into its @Volatile
        // field rather than relying on a Compose-collected state.
        applicationScope.launch {
            userPreferencesRepository.numberFormatStyle.collectLatest { style ->
                CurrencyFormatter.numberFormatStyle = style
            }
        }

        // Mirror user category styles (color + emoji, #760) into CategoryMapping so
        // icon call sites outside ViewModels can render custom categories.
        applicationScope.launch {
            categoryRepository.getAllCategories().collectLatest { categories ->
                val styles = categories.associate { it.name to CategoryMapping.UserStyle(it.color, it.icon) }
                CategoryMapping.userStyles.keys.retainAll(styles.keys)
                CategoryMapping.userStyles.putAll(styles)
            }
        }
    }

    private var lastLocales: android.os.LocaleList? = null

    // Widgets bake translated text into their stored snapshots, so rebuild them
    // when the device or per-app language changes.
    override fun onConfigurationChanged(newConfig: ResConfiguration) {
        super.onConfigurationChanged(newConfig)
        val locales = newConfig.locales
        if (lastLocales != null && locales != lastLocales) {
            com.nrknithin.fintrack.widget.WidgetRefresher.refreshTransactionWidgets(this)
        }
        lastLocales = locales
    }

    /**
     * Lifecycle observer to track app foreground/background state
     * This is used to trigger app lock when app returns from background
     */
    private inner class AppLockLifecycleObserver : ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}

        override fun onActivityStarted(activity: Activity) {
            activityReferences++
            if (!isInForeground) {
                // App came to foreground
                isInForeground = true
                isAppInForeground = true
                // The date may have moved on while the app was in the background.
                currentDate.refresh()
                // Check if app should be locked when returning from background
                checkAndLockApp()
            }
        }

        override fun onActivityResumed(activity: Activity) {}

        override fun onActivityPaused(activity: Activity) {}

        override fun onActivityStopped(activity: Activity) {
            activityReferences--
            if (activityReferences == 0) {
                // App went to background
                isInForeground = false
                isAppInForeground = false
                // Note: We don't need to do anything here
                // The lock state will be checked when app returns to foreground
            }
        }

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

        override fun onActivityDestroyed(activity: Activity) {}

        private fun checkAndLockApp() {
            applicationScope.launch {
                // The AppLockRepository will determine if app should be locked
                // based on timeout settings
                // The lock state will be observed by the AppLockViewModel
            }
        }
    }
}