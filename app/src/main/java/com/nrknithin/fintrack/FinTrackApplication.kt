package com.nrknithin.fintrack

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.nrknithin.fintrack.data.preferences.UserPreferencesRepository
import com.nrknithin.fintrack.data.repository.AppLockRepository
import com.nrknithin.fintrack.ui.icons.CategoryMapping
import com.nrknithin.fintrack.util.RedactingReleaseTree
import com.nrknithin.fintrack.utils.CurrencyFormatter
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber
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

    /** Read once at start-up for the sync interval and the wifi-only constraint (spec 11 §11.2). */
    @Inject
    lateinit var emailAccountConfigStore: com.nrknithin.fintrack.data.email.EmailAccountConfigStore

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

        // Spec 04 §4.6's logging policy, planted before anything else can log. The release
        // tree drops VERBOSE/DEBUG and pushes every surviving line through LogRedactor, so
        // amounts and account last-4s cannot reach logcat (spec 11, AC-11-04). Debug builds
        // keep Timber's unredacted DebugTree: that build only ever runs on a dev machine,
        // and redacting there would make the channel's own logs unreadable while working
        // on it.
        //
        // This does not silence the inherited `android.util.Log` call sites (the SMS path
        // logs merchants, hashes and row ids). Those are removed from the release build by
        // the -assumenosideeffects block in proguard-rules.pro; new code logs via Timber.
        Timber.plant(if (BuildConfig.DEBUG) Timber.DebugTree() else RedactingReleaseTree())

        registerActivityLifecycleCallbacks(AppLockLifecycleObserver())

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

        // Materialise recurring / scheduled manual (cash) transactions (#706):
        // a daily periodic run plus a one-shot catch-up now, so a device that was
        // off still creates anything that came due while it was down.
        com.nrknithin.fintrack.worker.RecurringTransactionWorker.enqueuePeriodic(this)
        com.nrknithin.fintrack.worker.RecurringTransactionWorker.enqueueOneShotCatchUp(this)

        // Schedule the mail sync (spec 11 §11.3, spec 02 §2.5). KEEP, so a process start does
        // not reset an interval already counting down; screen 47 rebuilds it with UPDATE when
        // a setting changes. Enqueued even with no mailbox connected: step 1 of the run reads
        // the config and returns "not configured" before opening a socket or writing a
        // sync_runs row, and scheduling here means a mailbox connected later starts syncing
        // without waiting for the next launch. The interval and the wifi-only constraint come
        // from the stored settings, which is why this needs a coroutine and the line above
        // does not.
        applicationScope.launch {
            try {
                val emailConfig = emailAccountConfigStore.current()
                com.nrknithin.fintrack.worker.EmailSyncWorker.enqueuePeriodic(
                    this@FinTrackApplication,
                    emailConfig
                )
                // The weekly sender-discovery scan (spec 11 §11.6). Own work, not a passenger
                // on the sync above — see SenderDiscoveryWorker's KDoc for the cadence, the
                // watermark and the failure-isolation reasons. Armed here for the same reason
                // the sync is, and cancelled here rather than left running when the owner
                // switches discovery off, so an unscheduled week does not need a launch.
                if (emailConfig.discoveryEnabled) {
                    com.nrknithin.fintrack.worker.SenderDiscoveryWorker.schedule(
                        this@FinTrackApplication,
                        emailConfig
                    )
                } else {
                    com.nrknithin.fintrack.worker.SenderDiscoveryWorker.cancel(
                        this@FinTrackApplication
                    )
                }
            } catch (e: Exception) {
                // A schedule that cannot be written is not worth crashing the app for: the
                // next process start tries again, and "Sync now" still works meanwhile.
                android.util.Log.w("FinTrackApp", "Email sync scheduling failed: ${e.message}", e)
            }
        }

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