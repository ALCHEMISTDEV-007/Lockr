package dev.lockr.security

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.lockr.ui.auth.AuthenticationActivity

/** A user-tapped notification is the supported handoff from a background observer to auth UI. */
class AuthenticationAlertNotifier(context: Context) {
    private val appContext = context.applicationContext

    fun notifyAuthenticationRequired(packageName: String): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false

        return runCatching {
            createChannel()
            val intent = Intent(appContext, AuthenticationActivity::class.java)
                .putExtra(AuthenticationActivity.EXTRA_TARGET_PACKAGE, packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val pending = PendingIntent.getActivity(
                appContext,
                packageName.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setContentTitle("Authentication required")
                .setContentText("Tap to authenticate and continue.")
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pending)
                .setAutoCancel(false)
                .build()
            NotificationManagerCompat.from(appContext).notify(NOTIFICATION_ID, notification)
            true
        }.getOrDefault(false)
    }

    fun cancelAuthenticationRequired() {
        NotificationManagerCompat.from(appContext).cancel(NOTIFICATION_ID)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Authentication requests",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Tap to authenticate after Lockr observes a protected app."
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
            }
            appContext.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private companion object {
        const val CHANNEL_ID = "lockr_authentication"
        const val NOTIFICATION_ID = 4201
    }
}
