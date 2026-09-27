package dev.lockr.monitoring

import android.app.AppOpsManager
import android.content.Context
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import dev.lockr.BuildConfig
import dev.lockr.data.ProtectedAppRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** UsageEvents-based observer. The service owns polling; this class publishes immutable state. */
class UsageStatsAppMonitor(
    context: Context,
    protectedApps: ProtectedAppRepository
) : AppMonitor {
    private val appContext = context.applicationContext
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val observedPackage = MutableStateFlow<ObservedPackage?>(null)
    private val mutableTransitions = MutableSharedFlow<ForegroundTransition>(extraBufferCapacity = 64)
    private val mutableStatus = MutableStateFlow(
        MonitorStatus(permissionGranted = false, running = false, message = "Usage access is required.")
    )

    override val status: StateFlow<MonitorStatus> = mutableStatus
    override val foregroundTransitions: SharedFlow<ForegroundTransition> = mutableTransitions
    override val foregroundState: StateFlow<ForegroundAppState?> =
        combine(observedPackage, protectedApps.protectedPackages) { observed, protected ->
            observed?.let {
                ForegroundAppState(
                    packageName = it.packageName,
                    observedAtMillis = it.observedAtMillis,
                    isProtected = it.packageName in protected
                )
            }
        }.stateIn(appScope, SharingStarted.Eagerly, null)

    override fun refreshPermission() {
        val granted = hasUsageAccess()
        val wasRunning = mutableStatus.value.running
        mutableStatus.value = when {
            !granted -> MonitorStatus(false, false, "Usage access is off. Foreground monitoring is unavailable.")
            wasRunning -> MonitorStatus(true, true, "Monitoring is running with best-effort foreground detection.")
            else -> MonitorStatus(true, false, "Usage access is enabled. Start monitoring to observe app changes.")
        }
        if (!granted) observedPackage.value = null
    }

    override fun start() {
        refreshPermission()
        if (!mutableStatus.value.permissionGranted) return
        try {
            ContextCompat.startForegroundService(
                appContext,
                Intent(appContext, UsageMonitoringService::class.java)
            )
        } catch (error: RuntimeException) {
            mutableStatus.value = MonitorStatus(
                permissionGranted = true,
                running = false,
                message = "Android could not start monitoring. Open Lockr and try again."
            )
            if (BuildConfig.DEBUG) Log.w(TAG, "Unable to start usage monitoring service", error)
        }
    }

    override fun stop() {
        appContext.stopService(Intent(appContext, UsageMonitoringService::class.java))
        refreshPermission()
        if (mutableStatus.value.permissionGranted) {
            mutableStatus.value = mutableStatus.value.copy(running = false, message = "Monitoring is stopped.")
        }
        observedPackage.value = null
    }

    fun hasUsageAccess(): Boolean = try {
        val appOps = appContext.getSystemService(AppOpsManager::class.java)
        @Suppress("DEPRECATION")
        appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            appContext.packageName
        ) == AppOpsManager.MODE_ALLOWED
    } catch (_: RuntimeException) {
        false
    }

    fun permissionRequiredStatus() {
        observedPackage.value = null
        mutableStatus.value = MonitorStatus(
            permissionGranted = false,
            running = false,
            message = "Usage access was removed. Monitoring is unavailable."
        )
    }

    fun dataUnavailableStatus() {
        observedPackage.value = null
        mutableStatus.value = MonitorStatus(
            permissionGranted = true,
            running = false,
            message = "Android did not provide current usage events. Monitoring is paused; start it again to retry."
        )
    }

    fun queryRetryStatus() {
        mutableStatus.value = MonitorStatus(
            permissionGranted = hasUsageAccess(),
            running = true,
            message = "Usage event query failed temporarily. Lockr is retrying."
        )
    }

    fun markRunning(running: Boolean) {
        val granted = hasUsageAccess()
        mutableStatus.value = when {
            !granted -> MonitorStatus(false, false, "Usage access is off. Foreground monitoring is unavailable.")
            running -> MonitorStatus(true, true, "Monitoring is running with best-effort foreground detection.")
            else -> MonitorStatus(true, false, "Monitoring is stopped.")
        }
    }

    fun publishForeground(packageName: String?, timestamp: Long) {
        val previous = observedPackage.value
        if (packageName == null) {
            if (previous != null) mutableTransitions.tryEmit(ForegroundTransition(null, timestamp))
            observedPackage.value = null
            return
        }
        if (previous?.packageName != packageName) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Foreground package: $packageName")
            observedPackage.value = ObservedPackage(packageName, timestamp)
            mutableTransitions.tryEmit(ForegroundTransition(packageName, timestamp))
        }
    }

    fun isUserFacing(packageName: String): Boolean = try {
        appContext.packageManager.getLaunchIntentForPackage(packageName) != null
    } catch (_: RuntimeException) {
        false
    }

    private data class ObservedPackage(val packageName: String, val observedAtMillis: Long)

    private companion object {
        const val TAG = "LockrAppMonitor"
    }
}
