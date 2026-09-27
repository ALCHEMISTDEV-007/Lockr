package dev.lockr.security

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import dev.lockr.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Device-event adapter; screen-off policy remains in SessionManager and settings. */
class ScreenOffSessionObserver(
    context: Context,
    private val settings: SettingsStore,
    private val sessions: SessionManager,
    private val scope: CoroutineScope
) {
    private val appContext = context.applicationContext
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                scope.launch {
                    sessions.onScreenOff(settings.lockSettings.first().lockOnScreenOff)
                }
            }
        }
    }

    fun register() {
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    fun unregister() {
        runCatching { appContext.unregisterReceiver(receiver) }
    }
}
