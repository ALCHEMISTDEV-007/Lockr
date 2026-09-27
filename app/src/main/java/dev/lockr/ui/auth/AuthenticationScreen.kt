package dev.lockr.ui.auth

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.lockr.security.AuthenticationPage
import dev.lockr.security.AuthenticationUiState

@Composable
fun AuthenticationScreen(
    state: AuthenticationUiState,
    appLabel: String,
    appIcon: Bitmap?,
    onDigit: (Int) -> Unit,
    onDelete: () -> Unit,
    onSelectLength: (Int) -> Unit,
    onSetBiometric: (Boolean) -> Unit,
    onFinishSetup: () -> Unit,
    onUsePin: () -> Unit,
    onUseBiometric: () -> Unit,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (appIcon != null) {
            Image(appIcon.asImageBitmap(), contentDescription = "$appLabel icon", modifier = Modifier.size(68.dp))
        } else {
            Surface(Modifier.size(68.dp), shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) { }
        }
        Spacer(Modifier.height(16.dp))
        Text(appLabel, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        Text(
            when (state.page) {
                AuthenticationPage.CREATE_PIN -> "Create your Lockr PIN"
                AuthenticationPage.CONFIRM_PIN -> "Confirm your PIN"
                AuthenticationPage.BIOMETRIC_SETUP -> "Enable biometric unlock?"
                AuthenticationPage.VERIFY_OLD_PIN -> "Enter your current PIN"
                AuthenticationPage.NEW_PIN -> "Create a new PIN"
                AuthenticationPage.CONFIRM_NEW_PIN -> "Confirm your new PIN"
                AuthenticationPage.PIN -> if (appLabel == "Lockr") "Unlock Lockr" else "Unlock to continue"
                AuthenticationPage.LOADING -> "Preparing secure authentication…"
                AuthenticationPage.FINISHED -> ""
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(26.dp))

        when (state.page) {
            AuthenticationPage.LOADING -> CircularProgressIndicator()
            AuthenticationPage.BIOMETRIC_SETUP -> {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text("Biometric unlock", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (state.biometricAvailable) "Use strong biometrics supported by this device." else "No strong biometric is enrolled. You can enable this later.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = state.biometricEnabled,
                        enabled = state.biometricAvailable,
                        onCheckedChange = onSetBiometric
                    )
                }
                Spacer(Modifier.height(16.dp))
                Button(onClick = onFinishSetup, modifier = Modifier.fillMaxWidth()) { Text("Finish setup") }
            }
            AuthenticationPage.FINISHED -> Unit
            else -> {
                if (state.page == AuthenticationPage.CREATE_PIN || state.page == AuthenticationPage.NEW_PIN) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        listOf(4, 6).forEach { length ->
                            if (state.selectedLength == length) {
                                Button(onClick = { onSelectLength(length) }) { Text("$length digits") }
                            } else {
                                OutlinedButton(onClick = { onSelectLength(length) }) { Text("$length digits") }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                Text(
                    text = buildString {
                        repeat(state.digits.length) { append("●  ") }
                        repeat((if (state.page in setOf(AuthenticationPage.CREATE_PIN, AuthenticationPage.CONFIRM_PIN, AuthenticationPage.NEW_PIN, AuthenticationPage.CONFIRM_NEW_PIN)) state.selectedLength else state.pinLength) - state.digits.length) { append("○  ") }
                    }.trim(),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(18.dp))
                NumericKeypad(onDigit = onDigit, onDelete = onDelete)
                if (state.page == AuthenticationPage.PIN && state.biometricAvailable) {
                    Spacer(Modifier.height(14.dp))
                    OutlinedButton(onClick = onUseBiometric, modifier = Modifier.fillMaxWidth()) { Text("Use biometrics") }
                }
                Spacer(Modifier.height(4.dp))
                OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
            }
        }

        state.message?.let { message ->
            Spacer(Modifier.height(12.dp))
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun NumericKeypad(onDigit: (Int) -> Unit, onDelete: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        for (row in 0..2) {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                for (column in 1..3) {
                    val digit = row * 3 + column
                    NumberButton(digit.toString()) { onDigit(digit) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.size(64.dp))
            NumberButton("0") { onDigit(0) }
            NumberButton("⌫", onClick = onDelete)
        }
    }
}

@Composable
private fun NumberButton(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.size(64.dp), shape = CircleShape) {
        Text(label, style = MaterialTheme.typography.titleLarge)
    }
}
