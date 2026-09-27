package dev.lockr.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

interface ProtectedAppLookup {
    suspend fun isProtectedPackage(packageName: String): Boolean
}

class ProtectedAppRepository(context: Context, private val settings: SettingsStore) : ProtectedAppLookup {
    private val appContext = context.applicationContext
    val protectedPackages: Flow<Set<String>> = settings.protectedApps

    override suspend fun isProtectedPackage(packageName: String): Boolean = packageName in protectedPackages.first()

    suspend fun installedLaunchableApps(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val pm = appContext.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
        resolved.asSequence()
            .filter { it.activityInfo.packageName != appContext.packageName }
            .distinctBy { it.activityInfo.packageName }
            .map { InstalledApp(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    suspend fun setProtected(packageName: String, locked: Boolean) = settings.setProtected(packageName, locked)

    suspend fun setAllProtected(packageNames: Set<String>, locked: Boolean) =
        settings.setAllProtected(packageNames, locked)
}
