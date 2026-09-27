package dev.lockr.ui.auth

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lockr.LockrApplication
import dev.lockr.security.AuthenticationMode
import dev.lockr.security.AuthenticationViewModel
import dev.lockr.security.AuthenticationViewModelFactory
import dev.lockr.ui.theme.LockrTheme

class AuthenticationActivity : FragmentActivity() {
    private lateinit var model: AuthenticationViewModel
    private lateinit var biometricPrompt: BiometricPrompt
    private var targetPackage: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val app = application as LockrApplication
        targetPackage = intent.getStringExtra(EXTRA_TARGET_PACKAGE)
        val mode = when {
            targetPackage != null -> AuthenticationMode.TARGET
            intent.getBooleanExtra(EXTRA_CHANGE_PIN, false) -> AuthenticationMode.CHANGE_PIN
            intent.getBooleanExtra(EXTRA_OWNER_AUTH, false) -> AuthenticationMode.OWNER
            else -> AuthenticationMode.SETUP
        }
        val biometricAvailable = BiometricManager.from(this)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
        model = ViewModelProvider(
            this,
            AuthenticationViewModelFactory(
                mode = mode,
                targetPackage = targetPackage,
                pinManager = app.pinManager,
                settings = app.settingsStore,
                sessions = app.sessionManager,
                protectedApps = app.repository,
                coordinator = app.lockFlowCoordinator,
                biometricAvailable = biometricAvailable
            )
        )[AuthenticationViewModel::class.java]
        app.lockFlowCoordinator.setAuthenticationUiVisible(true)
        val (appLabel, appIcon) = targetDetails()

        biometricPrompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    model.onBiometricSuccess()
                }

                override fun onAuthenticationFailed() {
                    model.onBiometricFailure()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    model.onBiometricError(errorCode, errString)
                }
            }
        )

        setContent {
            LockrTheme {
                val state = model.state.collectAsStateWithLifecycle().value
                androidx.compose.runtime.LaunchedEffect(model.shouldAutoPromptBiometric(), state.page) {
                    if (model.shouldAutoPromptBiometric()) {
                        model.consumeAutoPrompt()
                        showBiometricPrompt(appLabel)
                    }
                }
                androidx.compose.runtime.LaunchedEffect(state.complete) {
                    if (state.complete) {
                        setResult(if (state.success) RESULT_OK else RESULT_CANCELED)
                        finish()
                    }
                }
                AuthenticationScreen(
                    state = state,
                    appLabel = appLabel,
                    appIcon = appIcon,
                    onDigit = model::appendDigit,
                    onDelete = model::deleteDigit,
                    onSelectLength = model::selectPinLength,
                    onSetBiometric = model::setBiometricEnabled,
                    onFinishSetup = model::finishSetup,
                    onUsePin = model::usePin,
                    onUseBiometric = { showBiometricPrompt(appLabel) },
                    onCancel = { model.cancel() }
                )
            }
        }
    }

    override fun onDestroy() {
        if (!isChangingConfigurations) {
            val app = application as LockrApplication
            app.lockFlowCoordinator.setAuthenticationUiVisible(false)
            if (!::model.isInitialized || !model.state.value.success) {
                app.lockFlowCoordinator.onAuthenticationCancelled(targetPackage)
            }
        }
        super.onDestroy()
    }

    private fun showBiometricPrompt(appLabel: String) {
        if (!::biometricPrompt.isInitialized) return
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock $appLabel")
            .setSubtitle("Confirm it’s you to continue")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText("Use PIN")
            .build()
        biometricPrompt.authenticate(info)
    }

    private fun targetDetails(): Pair<String, android.graphics.Bitmap?> {
        val packageName = targetPackage ?: this@AuthenticationActivity.packageName
        return runCatching {
            val info = packageManager.getApplicationInfo(packageName, 0)
            val drawable = packageManager.getApplicationIcon(info)
            val bitmap = if (drawable is android.graphics.drawable.BitmapDrawable) drawable.bitmap else {
                val width = drawable.intrinsicWidth.coerceAtLeast(1)
                val height = drawable.intrinsicHeight.coerceAtLeast(1)
                android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888).also {
                    val canvas = android.graphics.Canvas(it)
                    drawable.setBounds(0, 0, canvas.width, canvas.height)
                    drawable.draw(canvas)
                }
            }
            packageManager.getApplicationLabel(info).toString() to bitmap
        }.getOrElse { "Lockr" to null }
    }

    companion object {
        const val EXTRA_TARGET_PACKAGE = "dev.lockr.extra.TARGET_PACKAGE"
        const val EXTRA_SETUP = "dev.lockr.extra.SETUP"
        const val EXTRA_CHANGE_PIN = "dev.lockr.extra.CHANGE_PIN"
        const val EXTRA_OWNER_AUTH = "dev.lockr.extra.OWNER_AUTH"
    }
}
