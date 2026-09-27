package dev.lockr

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.lockr.ui.LockrApp
import dev.lockr.ui.theme.LockrTheme
import dev.lockr.ui.home.HomeViewModel

class MainActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()
        (application as LockrApplication).appMonitor.refreshPermission()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = (application as LockrApplication).repository
        setContent {
            LockrTheme {
                val model: HomeViewModel = viewModel(
                    factory = HomeViewModel.factory(
                        repository,
                        (application as LockrApplication).appMonitor,
                        (application as LockrApplication).settingsStore,
                        (application as LockrApplication).sessionManager,
                        (application as LockrApplication).lockFlowCoordinator
                    )
                )
                LockrApp(model)
            }
        }
    }
}
