package dev.lockr.monitoring

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import dev.lockr.BuildConfig
import dev.lockr.LockrApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * User-started foreground worker for the UsageStats event stream. Polling runs only while the
 * persistent monitoring notification is active.
 */
class UsageMonitoringService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pollingJob: Job? = null
    private var debugJob: Job? = null
    private val screenInteractive = kotlinx.coroutines.flow.MutableStateFlow(true)
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> screenInteractive.value = false
                Intent.ACTION_SCREEN_ON -> screenInteractive.value = true
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val powerManager = getSystemService(PowerManager::class.java)
        screenInteractive.value = powerManager.isInteractive
        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            screenReceiver,
            screenFilter,
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        val monitor = (application as LockrApplication).appMonitor
        if (!monitor.hasUsageAccess()) {
            monitor.permissionRequiredStatus()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        monitor.markRunning(true)
        if (pollingJob == null) {
            debugJob = serviceScope.launch {
                monitor.foregroundState.distinctUntilChangedBy { state ->
                    state?.let { it.packageName to it.isProtected }
                }.collect { state ->
                    if (BuildConfig.DEBUG && state != null) {
                        Log.d(TAG, "Observed ${state.packageName}; protected=${state.isProtected}")
                    }
                }
            }
            pollingJob = serviceScope.launch {
                pollUsageEvents(monitor)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        pollingJob?.cancel()
        debugJob?.cancel()
        (application as? LockrApplication)?.appMonitor?.let { monitor ->
            if (monitor.status.value.running) monitor.markRunning(false)
            monitor.publishForeground(null, 0L)
        }
        runCatching { unregisterReceiver(screenReceiver) }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAsForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private suspend fun pollUsageEvents(monitor: UsageStatsAppMonitor) = withContext(Dispatchers.IO) {
        val usageStats = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
        var cursor = System.currentTimeMillis() - INITIAL_LOOKBACK_MS
        var currentPackage: String? = null
        var nextPollInterval = POLL_INTERVAL_MS
        var initialScan = true
        val handled = HashSet<String>()

        while (serviceScope.coroutineContext[Job]?.isActive == true) {
            if (!screenInteractive.value) {
                currentPackage = null
                withContext(Dispatchers.Main) { monitor.publishForeground(null, System.currentTimeMillis()) }
                screenInteractive.first { it }
                cursor = System.currentTimeMillis() - CURSOR_OVERLAP_MS
            }
            if (!monitor.hasUsageAccess()) {
                withContext(Dispatchers.Main) {
                    monitor.permissionRequiredStatus()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                return@withContext
            }

            try {
                val now = System.currentTimeMillis()
                val events = usageStats.queryEvents(cursor, now)
                if (events == null) {
                    withContext(Dispatchers.Main) {
                        monitor.dataUnavailableStatus()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                    return@withContext
                } else {
                    nextPollInterval = POLL_INTERVAL_MS
                    val reusableEvent = UsageEvents.Event()
                    val transitions = ArrayList<Pair<String?, Long>>()
                    while (events.hasNextEvent()) {
                        events.getNextEvent(reusableEvent)
                        val eventTime = reusableEvent.timeStamp
                        val eventType = reusableEvent.eventType
                        val eventPackage = reusableEvent.packageName
                        val signature = "$eventTime|$eventType|$eventPackage"
                        if (eventTime < cursor || !handled.add(signature)) continue
                        currentPackage = when {
                            isScreenOffOrLocked(eventType) -> {
                                transitions.add(null to eventTime)
                                null
                            }
                            isActivityResumed(eventType) && eventPackage != null && monitor.isUserFacing(eventPackage) ->
                                eventPackage.also { transitions.add(it to eventTime) }
                            else -> currentPackage
                        }
                    }
                    // Retain only a short overlap to avoid missing events that land on the cursor boundary.
                    cursor = now - CURSOR_OVERLAP_MS
                    handled.removeAll { entry ->
                        val timestamp = entry.substringBefore('|').toLongOrNull() ?: 0L
                        timestamp < cursor
                    }
                    withContext(Dispatchers.Main) {
                        monitor.markRunning(true)
                        if (initialScan) {
                            monitor.publishForeground(currentPackage, now)
                            initialScan = false
                        } else {
                            transitions.forEach { (packageName, eventTime) ->
                                monitor.publishForeground(packageName, eventTime)
                            }
                        }
                    }
                }
            } catch (error: SecurityException) {
                withContext(Dispatchers.Main) {
                    monitor.permissionRequiredStatus()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                return@withContext
            } catch (error: RuntimeException) {
                if (BuildConfig.DEBUG) Log.w(TAG, "UsageEvents query failed", error)
                withContext(Dispatchers.Main) {
                    monitor.queryRetryStatus()
                }
                nextPollInterval = ERROR_RETRY_INTERVAL_MS
            }

            try {
                delay(nextPollInterval)
            } catch (cancelled: CancellationException) {
                throw cancelled
            }
        }
    }

    private fun isActivityResumed(eventType: Int): Boolean = if (Build.VERSION.SDK_INT >= 29) {
        eventType == UsageEvents.Event.ACTIVITY_RESUMED
    } else {
        @Suppress("DEPRECATION")
        eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
    }

    private fun isScreenOffOrLocked(eventType: Int): Boolean {
        if (Build.VERSION.SDK_INT >= 28 &&
            (eventType == UsageEvents.Event.SCREEN_NON_INTERACTIVE || eventType == UsageEvents.Event.KEYGUARD_SHOWN)
        ) return true
        return false
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "App monitoring",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Shows while Lockr observes foreground app changes." }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
        .setContentTitle("Lockr monitoring is on")
        .setContentText("Observing foreground app changes")
        .setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

    private companion object {
        const val TAG = "LockrUsageMonitor"
        const val CHANNEL_ID = "lockr_monitoring"
        const val NOTIFICATION_ID = 4101
        const val POLL_INTERVAL_MS = 2_000L
        const val ERROR_RETRY_INTERVAL_MS = 10_000L
        const val INITIAL_LOOKBACK_MS = 5 * 60 * 1000L
        const val CURSOR_OVERLAP_MS = 250L
    }
}
