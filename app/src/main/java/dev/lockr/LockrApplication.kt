package dev.lockr

import android.app.Application
import dev.lockr.data.ProtectedAppRepository
import dev.lockr.data.SettingsStore
import dev.lockr.monitoring.UsageStatsAppMonitor
import dev.lockr.security.InMemorySessionManager
import dev.lockr.security.LockFlowCoordinator
import dev.lockr.security.KeystorePinManager
import dev.lockr.security.ScreenOffSessionObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class LockrApplication : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settingsStore by lazy { SettingsStore(this) }
    val repository by lazy { ProtectedAppRepository(this, settingsStore) }
    val appMonitor by lazy { UsageStatsAppMonitor(this, repository) }
    val pinManager by lazy { KeystorePinManager(settingsStore) }
    val sessionManager = InMemorySessionManager()
    val lockFlowCoordinator by lazy {
        LockFlowCoordinator(this, appMonitor, repository, settingsStore, sessionManager, appScope)
    }
    private val screenOffSessionObserver by lazy {
        ScreenOffSessionObserver(this, settingsStore, sessionManager, appScope)
    }

    override fun onCreate() {
        super.onCreate()
        screenOffSessionObserver.register()
        lockFlowCoordinator.start()
    }
}
