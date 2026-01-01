package com.rsps1008.stockify.ui.navigation

sealed class Screen(val route: String) {
    object Holdings : Screen("holdings")
    object Transactions : Screen("transactions")
    object Settings : Screen("settings")
    object DataManagement : Screen("data_management")

    object AddTransaction :
        Screen("add_transaction?transactionId={transactionId}&stockCode={stockCode}") {
        fun createRoute(
            transactionId: Int? = null,
            stockCode: String? = null
        ): String {
            val idPart = transactionId?.let { "transactionId=$it" } ?: "transactionId=null"
            val codePart = stockCode?.let { "stockCode=$it" } ?: "stockCode=null"
            return "add_transaction?$idPart&$codePart"
        }
    }

    object StockDetail : Screen("stock_detail/{stockCode}") {
        fun createRoute(stockCode: String) = "stock_detail/$stockCode"
    }

    object TransactionDetail : Screen("transaction_detail/{transactionId}") {
        fun createRoute(transactionId: Int) = "transaction_detail/$transactionId"
    }
}