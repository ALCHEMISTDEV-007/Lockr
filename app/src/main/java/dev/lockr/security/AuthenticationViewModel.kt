package dev.lockr.security

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.lockr.LockrApplication
import dev.lockr.data.LockSettings
import dev.lockr.data.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

enum class AuthenticationMode { TARGET, SETUP, OWNER, CHANGE_PIN }
enum class AuthenticationPage { LOADING, PIN, CREATE_PIN, CONFIRM_PIN, BIOMETRIC_SETUP, VERIFY_OLD_PIN, NEW_PIN, CONFIRM_NEW_PIN, FINISHED }

data class AuthenticationUiState(
    val page: AuthenticationPage = AuthenticationPage.LOADING,
    val digits: String = "",
    val pinLength: Int = 6,
    val selectedLength: Int = 6,
    val biometricEnabled: Boolean = false,
    val biometricAvailable: Boolean = false,
    val message: String? = null,
    val complete: Boolean = false,
    val success: Boolean = false
)

class AuthenticationViewModel(
    private val mode: AuthenticationMode,
    private val targetPackage: String?,
    private val pinManager: PinManager,
    private val settings: SettingsStore,
    private val sessions: SessionManager,
    private val protectedApps: dev.lockr.data.ProtectedAppRepository,
    private val coordinator: LockFlowCoordinator,
    biometricAvailable: Boolean
) : ViewModel() {
    private val mutableState = MutableStateFlow(AuthenticationUiState(biometricAvailable = biometricAvailable))
    val state: StateFlow<AuthenticationUiState> = mutableState.asStateFlow()
    private var firstPin: String? = null
    private var oldPin: String? = null
    private var activeSettings = LockSettings()
    private var autoPromptConsumed = false

    init {
        viewModelScope.launch {
            if (targetPackage != null && !protectedApps.isProtectedPackage(targetPackage)) {
                finish(success = false, message = "This app is no longer selected for protection.")
                return@launch
            }
            val configured = pinManager.hasPin()
            activeSettings = settings.lockSettings.first()
            val pinLength = pinManager.pinLength() ?: 6
            val page = when {
                !configured -> AuthenticationPage.CREATE_PIN
                mode == AuthenticationMode.CHANGE_PIN -> AuthenticationPage.VERIFY_OLD_PIN
                else -> AuthenticationPage.PIN
            }
            mutableState.value = mutableState.value.copy(
                page = page,
                pinLength = pinLength,
                selectedLength = pinLength,
                biometricEnabled = activeSettings.biometricEnabled,
                message = if (!configured && mode != AuthenticationMode.SETUP && mode != AuthenticationMode.TARGET) {
                    "Create a Lockr PIN before continuing."
                } else null
            )
        }
    }

    fun shouldAutoPromptBiometric(): Boolean =
        !autoPromptConsumed && state.value.page == AuthenticationPage.PIN &&
            state.value.biometricAvailable && state.value.biometricEnabled

    fun consumeAutoPrompt() { autoPromptConsumed = true }

    fun usePin() {
        autoPromptConsumed = true
        mutableState.value = mutableState.value.copy(message = null)
    }

    fun onBiometricSuccess() {
        viewModelScope.launch { authenticated() }
    }

    fun onBiometricFailure() {
        mutableState.value = mutableState.value.copy(message = "Not recognized. Try again or use your PIN.")
    }

    fun onBiometricError(errorCode: Int, message: CharSequence) {
        if (errorCode == androidx.biometric.BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
            usePin()
        } else if (errorCode == androidx.biometric.BiometricPrompt.ERROR_USER_CANCELED ||
            errorCode == androidx.biometric.BiometricPrompt.ERROR_CANCELED
        ) {
            finish(success = false, message = "Authentication was not completed.")
        } else {
            mutableState.value = mutableState.value.copy(
                message = "Biometric authentication is unavailable. Use your PIN."
            )
        }
    }

    fun selectPinLength(length: Int) {
        if (length in 4..6) mutableState.value = mutableState.value.copy(selectedLength = length, digits = "", message = null)
    }

    fun appendDigit(digit: Int) {
        if (digit !in 0..9 || state.value.page == AuthenticationPage.LOADING) return
        val current = mutableState.value
        val expected = when (current.page) {
            AuthenticationPage.CREATE_PIN, AuthenticationPage.CONFIRM_PIN,
            AuthenticationPage.NEW_PIN, AuthenticationPage.CONFIRM_NEW_PIN -> current.selectedLength
            else -> current.pinLength
        }
        if (current.digits.length >= expected) return
        val updated = current.digits + digit.toString()
        mutableState.value = current.copy(digits = updated, message = null)
        if (updated.length == expected) viewModelScope.launch { acceptDigits(updated, current.page) }
    }

    fun deleteDigit() {
        val current = mutableState.value
        if (current.digits.isNotEmpty()) mutableState.value = current.copy(digits = current.digits.dropLast(1), message = null)
    }

    fun setBiometricEnabled(enabled: Boolean) {
        if (enabled && !state.value.biometricAvailable) return
        mutableState.value = mutableState.value.copy(biometricEnabled = enabled)
    }

    fun finishSetup() {
        val pin = firstPin ?: return
        viewModelScope.launch {
            val result = pinManager.setPin(pin, state.value.biometricEnabled)
            if (result == PinResult.Success) authenticated() else {
                mutableState.value = mutableState.value.copy(message = "Lockr could not securely save the PIN. Try again.")
            }
        }
    }

    fun cancel() {
        finish(success = false, message = "Authentication was not completed.")
    }

    private suspend fun acceptDigits(digits: String, page: AuthenticationPage) {
        when (page) {
            AuthenticationPage.CREATE_PIN -> {
                firstPin = digits
                mutableState.value = state.value.copy(page = AuthenticationPage.CONFIRM_PIN, digits = "", message = "Enter the PIN again to confirm.")
            }
            AuthenticationPage.CONFIRM_PIN -> {
                if (digits != firstPin) {
                    firstPin = null
                    mutableState.value = state.value.copy(page = AuthenticationPage.CREATE_PIN, digits = "", message = "PINs did not match. Create the PIN again.")
                } else {
                    mutableState.value = state.value.copy(page = AuthenticationPage.BIOMETRIC_SETUP, digits = "", message = null)
                }
            }
            AuthenticationPage.PIN, AuthenticationPage.VERIFY_OLD_PIN -> {
                val result = pinManager.verifyPin(digits)
                when (result) {
                    PinResult.Success -> if (page == AuthenticationPage.VERIFY_OLD_PIN) {
                        oldPin = digits
                        mutableState.value = state.value.copy(page = AuthenticationPage.NEW_PIN, digits = "", message = "Choose a new PIN.")
                    } else authenticated()
                    PinResult.InvalidPin -> mutableState.value = state.value.copy(digits = "", message = "Incorrect PIN. Try again.")
                    is PinResult.LockedOut -> mutableState.value = state.value.copy(digits = "", message = "Too many attempts. Try again in ${((result.remainingMillis + 999) / 1000)} seconds.")
                    PinResult.StorageError -> mutableState.value = state.value.copy(digits = "", message = "The saved PIN could not be verified on this device.")
                    PinResult.NotConfigured -> mutableState.value = state.value.copy(page = AuthenticationPage.CREATE_PIN, digits = "", message = "Create a Lockr PIN to continue.")
                    else -> mutableState.value = state.value.copy(digits = "", message = "Enter a 4–6 digit PIN.")
                }
            }
            AuthenticationPage.NEW_PIN -> {
                firstPin = digits
                mutableState.value = state.value.copy(page = AuthenticationPage.CONFIRM_NEW_PIN, digits = "", message = "Enter the new PIN again.")
            }
            AuthenticationPage.CONFIRM_NEW_PIN -> {
                if (digits != firstPin) {
                    firstPin = null
                    mutableState.value = state.value.copy(page = AuthenticationPage.NEW_PIN, digits = "", message = "PINs did not match. Try again.")
                } else {
                    val result = oldPin?.let { pinManager.changePin(it, digits) } ?: PinResult.NotConfigured
                    if (result == PinResult.Success) finish(true) else {
                        mutableState.value = state.value.copy(page = AuthenticationPage.NEW_PIN, digits = "", message = "Lockr could not change the PIN. Try again.")
                    }
                }
            }
            else -> Unit
        }
    }

    private suspend fun authenticated() {
        val packageName = targetPackage
        if (packageName != null) {
            if (!protectedApps.isProtectedPackage(packageName)) {
                finish(success = false, message = "This app is no longer selected for protection.")
                return
            }
            val timeout = settings.lockSettings.first().lockTimeoutMillis
            sessions.createSession(packageName, SystemClock.elapsedRealtime(), timeout)
            coordinator.onAuthenticationSucceeded(packageName)
        } else {
            coordinator.onAuthenticationSucceeded(null)
        }
        finish(success = true)
    }

    private fun finish(success: Boolean, message: String? = null) {
        firstPin = null
        oldPin = null
        mutableState.value = state.value.copy(
            page = AuthenticationPage.FINISHED,
            digits = "",
            complete = true,
            success = success,
            message = message
        )
    }
}

class AuthenticationViewModelFactory(
    private val mode: AuthenticationMode,
    private val targetPackage: String?,
    private val pinManager: PinManager,
    private val settings: SettingsStore,
    private val sessions: SessionManager,
    private val protectedApps: dev.lockr.data.ProtectedAppRepository,
    private val coordinator: LockFlowCoordinator,
    private val biometricAvailable: Boolean
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = AuthenticationViewModel(
        mode, targetPackage, pinManager, settings, sessions, protectedApps, coordinator, biometricAvailable
    ) as T
}
