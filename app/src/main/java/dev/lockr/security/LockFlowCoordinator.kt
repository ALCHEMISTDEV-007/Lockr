package dev.lockr.security

import android.content.Context
import android.os.SystemClock
import android.util.Log
import dev.lockr.BuildConfig
import dev.lockr.data.ProtectedAppRepository
import dev.lockr.data.SettingsStore
import dev.lockr.monitoring.AppMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class LockFlowState(
    val packageName: String? = null,
    val decision: LockDecision? = null,
    val message: String = "Waiting for an observed app transition."
)

/** Connects observed package transitions to policy and a user-initiated authentication handoff. */
class LockFlowCoordinator(
    context: Context,
    private val monitor: AppMonitor,
    repository: ProtectedAppRepository,
    private val settings: SettingsStore,
    private val sessions: SessionManager,
    private val scope: CoroutineScope
) {
    private val appContext = context.applicationContext
    private val decisions = LockDecisionEngine(repository, sessions)
    private val notifier = AuthenticationAlertNotifier(appContext)
    private val mutableState = MutableStateFlow(LockFlowState())
    private var lastPackage: String? = null
    private var pendingPackage: String? = null
    private var observeJob: Job? = null

    @Volatile
    var authenticationUiVisible: Boolean = false
        private set

    val state: StateFlow<LockFlowState> = mutableState.asStateFlow()

    fun start() {
        if (observeJob != null) return
        observeJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            monitor.foregroundTransitions.collect { transition ->
                val packageName = transition.packageName
                if (packageName == null) {
                    notifier.cancelAuthenticationRequired()
                    lastPackage = null
                    pendingPackage = null
                    mutableState.value = LockFlowState()
                    return@collect
                }
                if (packageName == appContext.packageName && authenticationUiVisible) return@collect
                if (packageName == lastPackage) return@collect

                val previousPackage = lastPackage
                lastPackage = packageName
                if (packageName != previousPackage && packageName != pendingPackage) {
                    if (pendingPackage != null) notifier.cancelAuthenticationRequired()
                    pendingPackage = null
                }

                val currentTime = SystemClock.elapsedRealtime()
                val lockSettings = settings.lockSettings.first()
                val decision = decisions.onForegroundTransition(packageName, currentTime, lockSettings)
                val message = when (decision) {
                    LockDecision.IGNORE_UNPROTECTED_APP -> {
                        if (pendingPackage == packageName) notifier.cancelAuthenticationRequired()
                        pendingPackage = null
                        "Not protected"
                    }
                    LockDecision.ALLOW_ACTIVE_SESSION -> {
                        if (pendingPackage == packageName) notifier.cancelAuthenticationRequired()
                        pendingPackage = null
                        "Session active"
                    }
                    LockDecision.REQUIRE_AUTHENTICATION -> {
                        if (pendingPackage != packageName) {
                            pendingPackage = packageName
                            val alerted = notifier.notifyAuthenticationRequired(packageName)
                            if (!alerted) {
                                "Authentication required; notification permission is unavailable."
                            } else {
                                "Authentication required; tap the Lockr notification to continue."
                            }
                        } else {
                            "Authentication required"
                        }
                    }
                }
                mutableState.value = LockFlowState(packageName, decision, message)
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Observed transition $packageName: $decision")
                }
            }
        }
    }

    fun onAuthenticationSucceeded(packageName: String?) {
        if (packageName != null) {
            notifier.cancelAuthenticationRequired()
            mutableState.value = LockFlowState(packageName, LockDecision.ALLOW_ACTIVE_SESSION, "Session active")
        }
    }

    fun onAuthenticationCancelled(packageName: String?) {
        if (packageName != null) {
            mutableState.value = LockFlowState(packageName, LockDecision.REQUIRE_AUTHENTICATION, "Authentication not completed")
        }
    }

    fun setAuthenticationUiVisible(visible: Boolean) {
        authenticationUiVisible = visible
    }

    private companion object {
        const val TAG = "LockrLockFlow"
    }
}
