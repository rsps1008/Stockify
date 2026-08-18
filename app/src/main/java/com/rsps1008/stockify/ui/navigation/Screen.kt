package com.rsps1008.stockify.ui.navigation

import android.net.Uri

sealed class Screen(val route: String) {
    object Holdings : Screen("holdings")
    object AssetOverview : Screen("asset_overview")
    object Transactions : Screen("transactions")
    object Settings : Screen("settings")
    object DataManagement : Screen("data_management")
    object DividendInfo : Screen("dividend_info")

    object YahooQuote : Screen("yahoo_quote/{stockCode}/{market}") {
        fun createRoute(stockCode: String, market: String) =
            "yahoo_quote/${Uri.encode(stockCode)}/${Uri.encode(market)}"
    }

    object AddTransaction :
        Screen("add_transaction?transactionId={transactionId}&stockCode={stockCode}&market={market}&date={date}") {
        fun createRoute(
            transactionId: Int? = null,
            stockCode: String? = null,
            market: String? = null,
            date: Long? = null
        ): String {
            val params = mutableListOf<String>()
            transactionId?.let { params.add("transactionId=$it") }
            stockCode?.let { params.add("stockCode=${Uri.encode(it)}") }
            market?.let { params.add("market=${Uri.encode(it)}") }
            date?.let { params.add("date=$it") }
            
            return if (params.isEmpty()) {
                "add_transaction"
            } else {
                "add_transaction?${params.joinToString("&")}"
            }
        }
    }

    object StockDetail : Screen("stock_detail/{stockCode}?market={market}") {
        fun createRoute(stockCode: String, market: String? = null): String {
            val encodedCode = Uri.encode(stockCode)
            return market?.takeIf { it.isNotBlank() }?.let {
                "stock_detail/$encodedCode?market=${Uri.encode(it)}"
            } ?: "stock_detail/$encodedCode"
        }
    }

    object TransactionDetail : Screen("transaction_detail/{transactionId}") {
        fun createRoute(transactionId: Int) = "transaction_detail/$transactionId"
    }
}
