package dev.lockr.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.lockrDataStore by preferencesDataStore(name = "lockr_settings")

data class LockSettings(
    val biometricEnabled: Boolean = false,
    val lockTimeoutMillis: Long = 0L,
    val lockOnScreenOff: Boolean = false
)

data class PinFailureState(val attempts: Int = 0, val lockedUntilEpochMillis: Long = 0L)

class SettingsStore(private val context: Context) {
    private val protectedPackages = stringSetPreferencesKey("protected_packages")
    private val encryptedPin = androidx.datastore.preferences.core.stringPreferencesKey("encrypted_pin")
    private val pinFailures = intPreferencesKey("pin_failures")
    private val pinLockedUntil = longPreferencesKey("pin_locked_until")
    private val biometricEnabled = booleanPreferencesKey("biometric_enabled")
    private val lockTimeoutMillis = longPreferencesKey("lock_timeout_millis")
    private val lockOnScreenOff = booleanPreferencesKey("lock_on_screen_off")
    val protectedApps: Flow<Set<String>> = context.lockrDataStore.data.map { it[protectedPackages] ?: emptySet() }
    val pinConfigured: Flow<Boolean> = context.lockrDataStore.data.map { !it[encryptedPin].isNullOrBlank() }
    val lockSettings: Flow<LockSettings> = context.lockrDataStore.data.map {
        LockSettings(
            biometricEnabled = it[biometricEnabled] ?: false,
            lockTimeoutMillis = it[lockTimeoutMillis] ?: 0L,
            lockOnScreenOff = it[lockOnScreenOff] ?: false
        )
    }

    suspend fun setProtected(packageName: String, locked: Boolean) {
        context.lockrDataStore.edit { prefs ->
            val updated = (prefs[protectedPackages] ?: emptySet()).toMutableSet()
            if (locked) updated.add(packageName) else updated.remove(packageName)
            prefs[protectedPackages] = updated
        }
    }

    suspend fun setAllProtected(packageNames: Set<String>, locked: Boolean) {
        context.lockrDataStore.edit { prefs ->
            val updated = (prefs[protectedPackages] ?: emptySet()).toMutableSet()
            if (locked) updated.addAll(packageNames) else updated.removeAll(packageNames)
            prefs[protectedPackages] = updated
        }
    }

    suspend fun readEncryptedPin(): String? = context.lockrDataStore.data.map { it[encryptedPin] }.first()

    suspend fun savePinSetup(ciphertext: String, biometric: Boolean) {
        context.lockrDataStore.edit { prefs ->
            prefs[encryptedPin] = ciphertext
            prefs[biometricEnabled] = biometric
            prefs[pinFailures] = 0
            prefs[pinLockedUntil] = 0L
        }
    }

    suspend fun replaceEncryptedPin(ciphertext: String) {
        context.lockrDataStore.edit { prefs ->
            prefs[encryptedPin] = ciphertext
            prefs[pinFailures] = 0
            prefs[pinLockedUntil] = 0L
        }
    }

    suspend fun readPinFailureState(): PinFailureState = context.lockrDataStore.data
        .map { PinFailureState(it[pinFailures] ?: 0, it[pinLockedUntil] ?: 0L) }
        .first()

    suspend fun recordPinFailure(state: PinFailureState) {
        context.lockrDataStore.edit { prefs ->
            prefs[pinFailures] = state.attempts
            prefs[pinLockedUntil] = state.lockedUntilEpochMillis
        }
    }

    suspend fun clearPinFailures() {
        context.lockrDataStore.edit { prefs ->
            prefs[pinFailures] = 0
            prefs[pinLockedUntil] = 0L
        }
    }

    suspend fun setBiometricEnabled(enabled: Boolean) {
        context.lockrDataStore.edit { it[biometricEnabled] = enabled }
    }

    suspend fun setLockTimeout(timeoutMillis: Long) {
        require(timeoutMillis in setOf(0L, 15_000L, 30_000L, 60_000L, 300_000L))
        context.lockrDataStore.edit { it[lockTimeoutMillis] = timeoutMillis }
    }

    suspend fun setLockOnScreenOff(enabled: Boolean) {
        context.lockrDataStore.edit { it[lockOnScreenOff] = enabled }
    }
}
