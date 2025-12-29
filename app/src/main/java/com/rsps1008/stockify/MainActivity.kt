package com.rsps1008.stockify

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.rsps1008.stockify.ui.navigation.NavGraph
import com.rsps1008.stockify.ui.navigation.Screen
import com.rsps1008.stockify.ui.theme.StockifyTheme
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val dataStore = (application as StockifyApplication).settingsDataStore
        lifecycleScope.launch {
            dataStore.themeFlow.collect { theme ->
                val mode = when (theme) {
                    "Light" -> AppCompatDelegate.MODE_NIGHT_NO
                    "Dark" -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
                if (AppCompatDelegate.getDefaultNightMode() != mode) {
                    AppCompatDelegate.setDefaultNightMode(mode)
                }
            }
        }

        enableEdgeToEdge()
        setContent {
            StockifyTheme {
                MainScreen()
            }
        }
    }
}

@Composable
fun MainScreen() {
    val navController = rememberNavController()
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            BottomAppBar(
                actions = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        val navBackStackEntry by navController.currentBackStackEntryAsState()
                        val currentDestination = navBackStackEntry?.destination
                        IconButton(onClick = {
                            if (navController.currentDestination?.route == Screen.AddTransaction.route) {
                                navController.popBackStack()
                            }
                            navController.navigate(Screen.Holdings.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }) {
                            Icon(
                                imageVector = Icons.Filled.Assessment,
                                contentDescription = "Holdings",
                                tint = if (currentDestination?.hierarchy?.any { it.route == Screen.Holdings.route } == true) {
                                    androidx.compose.material3.MaterialTheme.colorScheme.primary
                                } else {
                                    androidx.compose.material3.MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }
                        IconButton(onClick = {
                            if (navController.currentDestination?.route == Screen.AddTransaction.route) {
                                navController.popBackStack()
                            }
                            navController.navigate(Screen.Transactions.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }) {
                            Icon(
                                imageVector = Icons.Filled.History,
                                contentDescription = "Transactions",
                                tint = if (currentDestination?.hierarchy?.any { it.route == Screen.Transactions.route } == true) {
                                    androidx.compose.material3.MaterialTheme.colorScheme.primary
                                } else {
                                    androidx.compose.material3.MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }
                        IconButton(onClick = {
                            if (navController.currentDestination?.route == Screen.AddTransaction.route) {
                                navController.popBackStack()
                            }
                            navController.navigate(Screen.Settings.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }) {
                            Icon(
                                imageVector = Icons.Filled.Settings,
                                contentDescription = "Settings",
                                tint = if (currentDestination?.hierarchy?.any { it.route == Screen.Settings.route } == true) {
                                    androidx.compose.material3.MaterialTheme.colorScheme.primary
                                } else {
                                    androidx.compose.material3.MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }
                        FloatingActionButton(
                            onClick = {
                                navController.navigate(Screen.AddTransaction.route) {
                                    launchSingleTop = true
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = "Add Transaction"
                            )
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        NavGraph(
            navController = navController,
            modifier = Modifier.padding(innerPadding)
        )
    }
}