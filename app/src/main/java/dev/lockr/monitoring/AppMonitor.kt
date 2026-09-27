package dev.lockr.monitoring

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Replaceable foreground-app observation boundary. This reports observed state only; it does not
 * intercept launches or enforce authentication.
 */
interface AppMonitor {
    val foregroundState: StateFlow<ForegroundAppState?>
    /** Non-conflated package transitions for policy consumers; foregroundState is the latest snapshot. */
    val foregroundTransitions: SharedFlow<ForegroundTransition>
    val status: StateFlow<MonitorStatus>

    /** Recheck special Usage Access after returning from Settings. */
    fun refreshPermission()

    /** Starts monitoring only when the user has already granted Usage Access. */
    fun start()

    fun stop()
}

data class ForegroundAppState(
    val packageName: String,
    val observedAtMillis: Long,
    val isProtected: Boolean
)

data class ForegroundTransition(val packageName: String?, val observedAtMillis: Long)

data class MonitorStatus(
    val permissionGranted: Boolean,
    val running: Boolean,
    val message: String
)
