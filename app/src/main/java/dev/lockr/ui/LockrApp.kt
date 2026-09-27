package dev.lockr.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.lockr.ui.home.HomeScreen
import dev.lockr.ui.home.HomeViewModel

@Composable
fun LockrApp(model: HomeViewModel) {
    val nav = rememberNavController()
    Scaffold { padding ->
        NavHost(navController = nav, startDestination = "home", modifier = Modifier.padding(padding)) {
            composable("home") { HomeScreen(model) }
        }
    }
}
