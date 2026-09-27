package dev.lockr.security

import dev.lockr.data.LockSettings
import dev.lockr.data.ProtectedAppLookup

enum class LockDecision {
    REQUIRE_AUTHENTICATION,
    ALLOW_ACTIVE_SESSION,
    IGNORE_UNPROTECTED_APP
}

/** Pure policy boundary: no Activity, service, notification, or Android UI dependencies. */
class LockDecisionEngine(
    private val protectedApps: ProtectedAppLookup,
    private val sessions: SessionManager
) {
    suspend fun shouldAuthenticate(packageName: String, currentTime: Long): LockDecision {
        if (!protectedApps.isProtectedPackage(packageName)) return LockDecision.IGNORE_UNPROTECTED_APP
        return if (sessions.activeSession(packageName, currentTime) != null) {
            LockDecision.ALLOW_ACTIVE_SESSION
        } else {
            LockDecision.REQUIRE_AUTHENTICATION
        }
    }

    suspend fun onForegroundTransition(
        packageName: String,
        currentTime: Long,
        settings: LockSettings
    ): LockDecision {
        sessions.onForegroundPackageChanged(packageName, currentTime, settings.lockTimeoutMillis)
        return shouldAuthenticate(packageName, currentTime)
    }
}
