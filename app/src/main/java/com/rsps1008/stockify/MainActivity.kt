package com.rsps1008.stockify

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.rsps1008.stockify.data.TextSizeMode
import com.rsps1008.stockify.ui.navigation.NavGraph
import com.rsps1008.stockify.ui.navigation.Screen
import com.rsps1008.stockify.ui.screens.AppLockScreen
import com.rsps1008.stockify.ui.theme.StockifyTheme
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private var appLockEnabled: Boolean? by mutableStateOf(null)
    private val appLockSession: AppLockSessionViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val stockifyApplication = application as StockifyApplication
        val dataStore = stockifyApplication.settingsDataStore
        lifecycleScope.launch {
            launch { stockifyApplication.updateTaiwanStockListIfDue() }
            launch { stockifyApplication.updateUsStockListIfDue() }
        }
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
        lifecycleScope.launch {
            dataStore.appLockEnabledFlow.collect { enabled ->
                val previous = appLockEnabled
                appLockEnabled = enabled
                when {
                    !enabled -> appLockSession.unlock()
                    previous == false -> appLockSession.unlock()
                }
            }
        }

        enableEdgeToEdge()
        setContent {
            val theme by dataStore.themeFlow.collectAsState(initial = "System")
            val textSizeMode by dataStore.textSizeModeFlow.collectAsState(initial = TextSizeMode.DEFAULT)
            val navController = rememberNavController()
            StockifyTheme(
                amoledTheme = theme == "AMOLED",
                textScale = TextSizeMode.scale(textSizeMode)
            ) {
                when (appLockEnabled) {
                    null -> Surface(modifier = Modifier.fillMaxSize()) {}
                    false -> MainScreen(navController)
                    true -> if (appLockSession.unlocked) {
                        MainScreen(navController)
                    } else {
                        AppLockScreen(
                            activity = this@MainActivity,
                            settingsDataStore = dataStore,
                            onUnlocked = appLockSession::unlock
                        )
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && appLockEnabled == true) {
            appLockSession.lock()
        }
    }
}

class AppLockSessionViewModel : ViewModel() {
    var unlocked: Boolean by mutableStateOf(false)
        private set

    fun unlock() {
        unlocked = true
    }

    fun lock() {
        unlocked = false
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(navController: NavHostController) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            BottomAppBar(
                actions = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        val navBackStackEntry by navController.currentBackStackEntryAsState()
                        val currentDestination = navBackStackEntry?.destination
                        NavigationTooltip("查看持股總覽") {
                            IconButton(onClick = {
                                navigateToTopLevel(navController, Screen.Holdings.route)
                            }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_nav_holdings),
                                    contentDescription = "持股總覽",
                                    modifier = Modifier.size(30.dp),
                                    tint = if (currentDestination?.hierarchy?.any { it.route == Screen.Holdings.route } == true) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    }
                                )
                            }
                        }
                        NavigationTooltip("查看交易紀錄") {
                            IconButton(onClick = {
                                navigateToTopLevel(navController, Screen.Transactions.route)
                            }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_nav_transactions),
                                    contentDescription = "交易紀錄",
                                    modifier = Modifier.size(30.dp),
                                    tint = if (currentDestination?.hierarchy?.any { it.route == Screen.Transactions.route } == true) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    }
                                )
                            }
                        }
                        NavigationTooltip("新增一筆交易") {
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
                                    Icon(Icons.Filled.Add, contentDescription = "新增交易")
                                }
                            }
                        }
                        NavigationTooltip("匯入、匯出與備份資料") {
                            IconButton(onClick = {
                                navigateToTopLevel(navController, Screen.DataManagement.route)
                            }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_nav_data_management),
                                    contentDescription = "資料管理",
                                    modifier = Modifier.size(30.dp),
                                    tint = if (currentDestination?.hierarchy?.any { it.route == Screen.DataManagement.route } == true) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    }
                                )
                            }
                        }
                        NavigationTooltip("調整 App 設定") {
                            IconButton(onClick = {
                                navigateToTopLevel(navController, Screen.Settings.route)
                            }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_nav_settings),
                                    contentDescription = "設定",
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NavigationTooltip(
    message: String,
    content: @Composable () -> Unit
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(message) } },
        state = rememberTooltipState(),
        hasAction = false,
        content = content
    )
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
