package com.rsps1008.stockify.data

data class StockKey(
    val market: String,
    val code: String
) {
    val normalizedMarket: String
        get() = StockMarket.normalize(market)

    val normalizedCode: String
        get() = code.trim().uppercase()

    fun cacheKey(): String = stockCacheKey(normalizedMarket, normalizedCode)
}

fun stockCacheKey(market: String, stockCode: String): String =
    "${StockMarket.normalize(market)}:${stockCode.trim().uppercase()}"

fun Stock.toStockKey(): StockKey = StockKey(market = market, code = code)

fun StockTransaction.toStockKey(): StockKey = StockKey(market = market, code = stockCode)
