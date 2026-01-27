package com.rsps1008.stockify.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.rsps1008.stockify.ui.screens.AddTransactionScreen
import com.rsps1008.stockify.ui.screens.DataManagementScreen
import com.rsps1008.stockify.ui.screens.HoldingsScreen
import com.rsps1008.stockify.ui.screens.SettingsScreen
import com.rsps1008.stockify.ui.screens.StockDetailScreen
import com.rsps1008.stockify.ui.screens.TransactionDetailScreen
import com.rsps1008.stockify.ui.screens.TransactionsScreen

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
        composable(Screen.Holdings.route) {
            HoldingsScreen(navController = navController)
        }
        composable(Screen.Transactions.route) {
            TransactionsScreen(navController = navController)
        }

        composable(Screen.Settings.route) {
            SettingsScreen()
        }
        composable(Screen.DataManagement.route) {
            DataManagementScreen()
        }
        composable(
            route = Screen.StockDetail.route,
            arguments = listOf(navArgument("stockCode") { type = NavType.StringType })
        ) { backStackEntry ->
            val stockCode = backStackEntry.arguments?.getString("stockCode") ?: ""
            StockDetailScreen(stockCode = stockCode, navController = navController)
        }
        composable(
            route = Screen.TransactionDetail.route,
            arguments = listOf(navArgument("transactionId") { type = NavType.IntType })
        ) { backStackEntry ->
            val transactionId = backStackEntry.arguments?.getInt("transactionId") ?: 0
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

            AddTransactionScreen(
                navController = navController,
                transactionId = transactionId,
                prefillStockCode = prefillStockCode
            )
        }
    }
}