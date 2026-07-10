package com.rsps1008.stockify

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.rsps1008.stockify.data.TextSizeMode
import com.rsps1008.stockify.ui.navigation.NavGraph
import com.rsps1008.stockify.ui.navigation.Screen
import com.rsps1008.stockify.ui.theme.StockifyTheme
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val dataStore = (application as StockifyApplication).settingsDataStore
            lifecycleScope.launch {
                dataStore.themeFlow.collect { theme ->
                    val mode = when (theme) {
                        "Light" -> AppCompatDelegate.MODE_NIGHT_NO
                        "Dark", "AMOLED" -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
                if (AppCompatDelegate.getDefaultNightMode() != mode) {
                    AppCompatDelegate.setDefaultNightMode(mode)
                }
            }
        }

        enableEdgeToEdge()
        setContent {
            val theme by dataStore.themeFlow.collectAsState(initial = "System")
            val textSizeMode by dataStore.textSizeModeFlow.collectAsState(initial = TextSizeMode.DEFAULT)
            StockifyTheme(
                amoledTheme = theme == "AMOLED",
                textScale = TextSizeMode.scale(textSizeMode)
            ) {
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
                            navigateToTopLevel(navController, Screen.Holdings.route)
                        }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_nav_holdings),
                                contentDescription = "Holdings",
                                modifier = Modifier.size(30.dp),
                                tint = if (currentDestination?.hierarchy?.any { it.route == Screen.Holdings.route } == true) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }
                        IconButton(onClick = {
                            navigateToTopLevel(navController, Screen.Transactions.route)
                        }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_nav_transactions),
                                contentDescription = "Transactions",
                                modifier = Modifier.size(30.dp),
                                tint = if (currentDestination?.hierarchy?.any { it.route == Screen.Transactions.route } == true) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            shadowElevation = 3.dp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .size(50.dp)
                                .clickable {
                                    val currentEntry = navController.currentBackStackEntry
                                    val stockCode = if (currentEntry?.destination?.route == Screen.StockDetail.route) {
                                        currentEntry.arguments?.getString("stockCode")
                                    } else {
                                        null
                                    }
                                    navController.navigate(Screen.AddTransaction.createRoute(stockCode = stockCode)) {
                                        launchSingleTop = true
                                    }
                                }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.Add, contentDescription = "Add")
                            }
                        }
                        IconButton(onClick = {
                            navigateToTopLevel(navController, Screen.DataManagement.route)
                        }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_nav_data_management),
                                contentDescription = "Data Management",
                                modifier = Modifier.size(30.dp),
                                tint = if (currentDestination?.hierarchy?.any { it.route == Screen.DataManagement.route } == true) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }
                        IconButton(onClick = {
                            navigateToTopLevel(navController, Screen.Settings.route)
                        }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_nav_settings),
                                contentDescription = "Settings",
                                modifier = Modifier.size(30.dp),
                                tint = if (currentDestination?.hierarchy?.any { it.route == Screen.Settings.route } == true) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
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

private fun navigateToTopLevel(
    navController: androidx.navigation.NavHostController,
    route: String
) {
    if (navController.currentDestination?.route == Screen.AddTransaction.route) {
        navController.popBackStack()
    }
    navController.navigate(route) {
        popUpTo(navController.graph.findStartDestination().id) {
            saveState = false
        }
        launchSingleTop = true
        restoreState = false
    }
}
