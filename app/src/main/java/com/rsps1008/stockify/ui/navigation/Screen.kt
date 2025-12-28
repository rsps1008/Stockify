package com.rsps1008.stockify.ui.navigation

sealed class Screen(val route: String) {
    object Holdings : Screen("holdings")
    object Transactions : Screen("transactions")
    object Settings : Screen("settings")

    object AddTransaction : Screen("add_transaction?transactionId={transactionId}") {
        fun createRoute(transactionId: Int? = null): String {
            return transactionId?.let { "add_transaction?transactionId=$it" } ?: "add_transaction"
        }
    }

    object StockDetail : Screen("stock_detail/{stockCode}") {
        fun createRoute(stockCode: String) = "stock_detail/$stockCode"
    }

    object TransactionDetail : Screen("transaction_detail/{transactionId}") {
        fun createRoute(transactionId: Int) = "transaction_detail/$transactionId"
    }
}