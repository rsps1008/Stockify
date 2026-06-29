package com.rsps1008.stockify.ui.navigation

import android.util.Log
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.rsps1008.stockify.ui.screens.AddTransactionScreen
import com.rsps1008.stockify.ui.screens.DataManagementScreen
import com.rsps1008.stockify.ui.screens.DividendInfoScreen
import com.rsps1008.stockify.ui.screens.HoldingsScreen
import com.rsps1008.stockify.ui.screens.YahooWebViewScreen
import com.rsps1008.stockify.ui.screens.SettingsScreen
import com.rsps1008.stockify.ui.screens.StockDetailScreen
import com.rsps1008.stockify.ui.screens.TransactionDetailScreen
import com.rsps1008.stockify.ui.screens.TransactionsScreen

private const val TAG = "NavGraph"

@Composable
fun NavGraph(
    navController: NavHostController,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Holdings.route,
        modifier = modifier
    ) {
        composable(
            route = Screen.Holdings.route,
            enterTransition = { fadeIn() },
            exitTransition = { fadeOut() },
            popEnterTransition = { fadeIn() },
            popExitTransition = { fadeOut() }
        ) {
            LogScreenEntry("HoldingsScreen")
            HoldingsScreen(navController = navController)
        }
        composable(
            route = Screen.Transactions.route,
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None }
        ) {
            LogScreenEntry("TransactionsScreen")
            TransactionsScreen(navController = navController)
        }

        composable(Screen.Settings.route) {
            LogScreenEntry("SettingsScreen")
            SettingsScreen()
        }
        composable(Screen.DataManagement.route) {
            LogScreenEntry("DataManagementScreen")
            DataManagementScreen()
        }
        composable(Screen.DividendInfo.route) {
            LogScreenEntry("DividendInfoScreen")
            DividendInfoScreen(navController = navController)
        }
        composable(
            route = Screen.YahooQuote.route,
            arguments = listOf(
                navArgument("stockCode") { type = NavType.StringType },
                navArgument("market") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val stockCode = backStackEntry.arguments?.getString("stockCode") ?: ""
            val market = backStackEntry.arguments?.getString("market") ?: ""
            LogScreenEntry("YahooQuoteScreen", "stockCode=$stockCode, market=$market")
            YahooWebViewScreen(
                stockCode = stockCode,
                market = market,
                navController = navController
            )
        }
        composable(
            route = Screen.StockDetail.route,
            arguments = listOf(navArgument("stockCode") { type = NavType.StringType })
        ) { backStackEntry ->
            val stockCode = backStackEntry.arguments?.getString("stockCode") ?: ""
            LogScreenEntry("StockDetailScreen", "stockCode=$stockCode")
            StockDetailScreen(stockCode = stockCode, navController = navController)
        }
        composable(
            route = Screen.TransactionDetail.route,
            arguments = listOf(navArgument("transactionId") { type = NavType.IntType })
        ) { backStackEntry ->
            val transactionId = backStackEntry.arguments?.getInt("transactionId") ?: 0
            LogScreenEntry("TransactionDetailScreen", "transactionId=$transactionId")
            TransactionDetailScreen(transactionId = transactionId, navController = navController)
        }

        composable(
            route = Screen.AddTransaction.route,
            arguments = listOf(
                navArgument("transactionId") {
                    type = NavType.StringType
                    nullable = true
                },
                navArgument("stockCode") {
                    type = NavType.StringType
                    nullable = true
                }
            )
        ) { backStackEntry ->

            val transactionIdArg = backStackEntry.arguments?.getString("transactionId")
            val transactionId = transactionIdArg?.toIntOrNull()

            val prefillStockCode = backStackEntry.arguments?.getString("stockCode")
            LogScreenEntry(
                "AddTransactionScreen",
                "transactionId=${transactionId ?: "null"}, stockCode=${prefillStockCode ?: "null"}"
            )

            AddTransactionScreen(
                navController = navController,
                transactionId = transactionId,
                prefillStockCode = prefillStockCode
            )
        }
    }
}

@Composable
private fun LogScreenEntry(screenName: String, details: String? = null) {
    LaunchedEffect(screenName, details) {
        val message = if (details.isNullOrBlank()) {
            "Enter $screenName"
        } else {
            "Enter $screenName ($details)"
        }
        Log.d(TAG, message)
    }
}
