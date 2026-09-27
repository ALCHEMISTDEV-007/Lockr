package dev.lockr.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.lockr.data.InstalledApp
import dev.lockr.data.ProtectedAppRepository
import dev.lockr.data.LockSettings
import dev.lockr.data.SettingsStore
import dev.lockr.monitoring.AppMonitor
import dev.lockr.security.LockFlowCoordinator
import dev.lockr.security.LockFlowState
import dev.lockr.security.SessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeState(
    val apps: List<InstalledApp> = emptyList(),
    val protected: Set<String> = emptySet(),
    val search: String = "",
    val loading: Boolean = true
)

data class SecurityUiState(
    val pinConfigured: Boolean = false,
    val settings: LockSettings = LockSettings()
)

class HomeViewModel(
    private val repository: ProtectedAppRepository,
    val appMonitor: AppMonitor,
    private val settingsStore: SettingsStore,
    private val sessions: SessionManager,
    lockFlowCoordinator: LockFlowCoordinator
) : ViewModel() {
    private val apps = MutableStateFlow<List<InstalledApp>>(emptyList())
    private val search = MutableStateFlow("")
    private val loading = MutableStateFlow(true)

    val state = combine(apps, repository.protectedPackages, search, loading) { installed, protected, query, isLoading ->
        HomeState(
            apps = installed.filter { it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, true) },
            protected = protected,
            search = query,
            loading = isLoading
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState())
    val securityState = combine(settingsStore.pinConfigured, settingsStore.lockSettings) { pinConfigured, settings ->
        SecurityUiState(pinConfigured, settings)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SecurityUiState())
    val lockFlowState = lockFlowCoordinator.state

    init { refreshApps() }

    fun refreshApps() = viewModelScope.launch {
        loading.value = true
        apps.value = repository.installedLaunchableApps()
        loading.value = false
    }

    fun search(query: String) { search.value = query }
    fun setProtected(packageName: String, locked: Boolean) = viewModelScope.launch {
        repository.setProtected(packageName, locked)
    }
    fun setAll(locked: Boolean) = viewModelScope.launch {
        repository.setAllProtected(apps.value.map { it.packageName }.toSet(), locked)
    }

    fun startMonitoring() = appMonitor.start()
    fun stopMonitoring() = appMonitor.stop()
    fun setBiometricEnabled(enabled: Boolean) = viewModelScope.launch { settingsStore.setBiometricEnabled(enabled) }
    fun setLockTimeout(timeoutMillis: Long) = viewModelScope.launch {
        settingsStore.setLockTimeout(timeoutMillis)
        sessions.clearAll()
    }
    fun setLockOnScreenOff(enabled: Boolean) = viewModelScope.launch { settingsStore.setLockOnScreenOff(enabled) }

    companion object {
        fun factory(
            repository: ProtectedAppRepository,
            appMonitor: AppMonitor,
            settingsStore: SettingsStore,
            sessions: SessionManager,
            coordinator: LockFlowCoordinator
        ) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                HomeViewModel(repository, appMonitor, settingsStore, sessions, coordinator) as T
        }
    }
}
