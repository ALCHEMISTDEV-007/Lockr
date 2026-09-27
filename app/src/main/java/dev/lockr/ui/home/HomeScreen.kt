package dev.lockr.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.asImageBitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.content.Intent
import android.provider.Settings
import android.Manifest
import android.os.Build
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.biometric.BiometricManager
import dev.lockr.ui.auth.AuthenticationActivity
import dev.lockr.security.LockDecision

@Composable
fun HomeScreen(model: HomeViewModel) {
    val state by model.state.collectAsState()
    val context = LocalContext.current
    val monitorStatus by model.appMonitor.status.collectAsState()
    val foreground by model.appMonitor.foregroundState.collectAsState()
    val security by model.securityState.collectAsState()
    val lockFlow by model.lockFlowState.collectAsState()
    var pendingSecurityAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var notificationPermissionMessage by remember { mutableStateOf<String?>(null) }
    val securityAuthLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) pendingSecurityAction?.invoke()
        pendingSecurityAction = null
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            notificationPermissionMessage = null
            model.startMonitoring()
        } else {
            notificationPermissionMessage = "Notification permission is needed to show an authentication request when Android blocks opening Lockr over another app."
        }
    }
    fun requireOwnerAuthentication(action: () -> Unit) {
        pendingSecurityAction = action
        val intent = Intent(context, AuthenticationActivity::class.java).apply {
            if (security.pinConfigured) putExtra(AuthenticationActivity.EXTRA_OWNER_AUTH, true)
            else putExtra(AuthenticationActivity.EXTRA_SETUP, true)
        }
        securityAuthLauncher.launch(intent)
    }
    fun startMonitoring() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            notificationPermissionMessage = null
            model.startMonitoring()
        }
    }
    val hasStrongBiometric = remember(context) {
        BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp)) {
        Spacer(Modifier.height(28.dp))
        Text("Lockr", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
        Text("Choose apps for Lockr", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("App monitoring", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Monitoring: ${when { !monitorStatus.permissionGranted -> "Unavailable"; monitorStatus.running -> "Active"; else -> "Paused" }}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(monitorStatus.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "Usage Access reads app-switch events. Lockr does not read screen content. Detection can happen after an app opens.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (foreground != null) {
                    val appLabel = runCatching {
                        val info = context.packageManager.getApplicationInfo(foreground!!.packageName, 0)
                        context.packageManager.getApplicationLabel(info).toString()
                    }.getOrDefault(foreground!!.packageName)
                    Spacer(Modifier.height(8.dp))
                    Text("Current observed application: $appLabel", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Protection: ${if (foreground!!.isProtected) "Protected" else "Not protected"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text("Current observed application: None", style = MaterialTheme.typography.bodyMedium)
                    Text("Protection: Not currently observed", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    "Authentication: ${if (security.pinConfigured) "Configured" else "Not configured"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (lockFlow.message.isNotBlank()) {
                    Text(lockFlow.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                notificationPermissionMessage?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                        }
                    }) { Text("Open notification settings") }
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    when {
                        !monitorStatus.permissionGranted -> runCatching {
                            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                        }
                        monitorStatus.running -> requireOwnerAuthentication { model.stopMonitoring() }
                        else -> startMonitoring()
                    }
                }) {
                    Text(
                        when {
                            !monitorStatus.permissionGranted -> "Open Usage Access settings"
                            monitorStatus.running -> "Stop monitoring"
                            else -> "Start monitoring"
                        }
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("Security", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (security.pinConfigured) "Change PIN" else "Set up PIN")
                        Text(if (security.pinConfigured) "PIN is configured" else "A PIN is required for authentication", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = {
                        val intent = Intent(context, AuthenticationActivity::class.java).apply {
                            if (security.pinConfigured) putExtra(AuthenticationActivity.EXTRA_CHANGE_PIN, true)
                            else putExtra(AuthenticationActivity.EXTRA_SETUP, true)
                        }
                        securityAuthLauncher.launch(intent)
                    }) { Text(if (security.pinConfigured) "Change" else "Set up") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Biometric unlock")
                        Text(if (hasStrongBiometric) "Strong biometric authentication" else "No strong biometric is enrolled", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = security.settings.biometricEnabled,
                        enabled = security.pinConfigured && hasStrongBiometric,
                        onCheckedChange = { enabled -> requireOwnerAuthentication { model.setBiometricEnabled(enabled) } }
                    )
                }
                Text("Lock timeout", style = MaterialTheme.typography.bodyMedium)
                val timeoutOptions = listOf(0L to "Immediate", 15_000L to "15s", 30_000L to "30s", 60_000L to "1m", 300_000L to "5m")
                timeoutOptions.chunked(3).forEach { rowOptions ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        rowOptions.forEach { (millis, label) ->
                            FilterChip(
                                selected = security.settings.lockTimeoutMillis == millis,
                                onClick = { requireOwnerAuthentication { model.setLockTimeout(millis) } },
                                label = { Text(label) }
                            )
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Lock when screen turns off", modifier = Modifier.weight(1f))
                    Switch(
                        checked = security.settings.lockOnScreenOff,
                        enabled = security.pinConfigured,
                        onCheckedChange = { enabled -> requireOwnerAuthentication { model.setLockOnScreenOff(enabled) } }
                    )
                }
            }
        }
        Spacer(Modifier.height(22.dp))
        OutlinedTextField(
            value = state.search,
            onValueChange = model::search,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(18.dp),
            label = { Text("Search apps") }
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { requireOwnerAuthentication { model.setAll(true) } }) { Text("Select all") }
            TextButton(onClick = { requireOwnerAuthentication { model.setAll(false) } }) { Text("Clear selection") }
        }
        when {
            state.loading -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).padding(24.dp))
            state.apps.isEmpty() -> Text("No launchable apps found", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(state.apps, key = { it.packageName }) { app ->
                    val locked = app.packageName in state.protected
                    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            AppIcon(app.packageName)
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(app.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                Text(if (locked) "Selected" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = locked, onCheckedChange = { selected ->
                                requireOwnerAuthentication { model.setProtected(app.packageName, selected) }
                            })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppIcon(packageName: String) {
    val context = LocalContext.current
    val drawable = runCatching { context.packageManager.getApplicationIcon(packageName) }.getOrNull()
    if (drawable != null) {
        Image(drawable.toBitmap().asImageBitmap(), contentDescription = null, modifier = Modifier.size(42.dp))
    } else {
        Surface(Modifier.size(42.dp), shape = RoundedCornerShape(13.dp), color = MaterialTheme.colorScheme.secondaryContainer) { }
    }
}

private fun Drawable.toBitmap(): Bitmap {
    if (this is BitmapDrawable) return bitmap
    val width = intrinsicWidth.coerceAtLeast(1)
    val height = intrinsicHeight.coerceAtLeast(1)
    return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
        val canvas = Canvas(bitmap)
        setBounds(0, 0, canvas.width, canvas.height)
        draw(canvas)
    }
}
