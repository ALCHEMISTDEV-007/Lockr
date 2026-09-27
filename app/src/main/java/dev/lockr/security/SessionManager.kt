package dev.lockr.security

data class SessionState(
    val packageName: String,
    val authenticatedAt: Long,
    val expiresAt: Long
)

interface SessionManager {
    fun createSession(packageName: String, now: Long, timeoutMillis: Long): SessionState
    fun activeSession(packageName: String, now: Long): SessionState?
    fun onForegroundPackageChanged(packageName: String, now: Long, timeoutMillis: Long)
    fun clearSession(packageName: String)
    fun clearAll()
    fun onScreenOff(lockOnScreenOff: Boolean)
}

/** In-memory package-specific sessions. Nothing in this class survives process death or reboot. */
class InMemorySessionManager : SessionManager {
    private val sessions = mutableMapOf<String, SessionState>()

    @Synchronized
    override fun createSession(packageName: String, now: Long, timeoutMillis: Long): SessionState {
        require(packageName.isNotBlank())
        require(timeoutMillis >= 0L)
        val expiry = if (timeoutMillis == IMMEDIATE_LOCK) Long.MAX_VALUE else safeAdd(now, timeoutMillis)
        return SessionState(packageName, now, expiry).also { sessions[packageName] = it }
    }

    @Synchronized
    override fun activeSession(packageName: String, now: Long): SessionState? {
        val session = sessions[packageName] ?: return null
        if (now >= session.expiresAt) {
            sessions.remove(packageName)
            return null
        }
        return session
    }

    @Synchronized
    override fun onForegroundPackageChanged(packageName: String, now: Long, timeoutMillis: Long) {
        sessions.entries.removeAll { (_, session) -> now >= session.expiresAt }
        if (timeoutMillis == IMMEDIATE_LOCK) {
            sessions.keys.removeAll { it != packageName }
        }
    }

    @Synchronized
    override fun clearSession(packageName: String) {
        sessions.remove(packageName)
    }

    @Synchronized
    override fun clearAll() {
        sessions.clear()
    }

    @Synchronized
    override fun onScreenOff(lockOnScreenOff: Boolean) {
        if (lockOnScreenOff) sessions.clear()
    }

    private fun safeAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right

    private companion object {
        const val IMMEDIATE_LOCK = 0L
    }
}
