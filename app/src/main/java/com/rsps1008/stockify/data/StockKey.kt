package com.rsps1008.stockify.data

import java.util.Locale

fun canonicalStockCode(stockCode: String): String = stockCode.trim().uppercase(Locale.ROOT)

data class StockKey(
    val market: String,
    val code: String
) {
    val normalizedMarket: String
        get() = StockMarket.normalize(market)

    val normalizedCode: String
        get() = canonicalStockCode(code)

    fun cacheKey(): String = stockCacheKey(normalizedMarket, normalizedCode)
}

fun stockCacheKey(market: String, stockCode: String): String =
    "${StockMarket.normalize(market)}:${canonicalStockCode(stockCode)}"

fun Stock.toStockKey(): StockKey = StockKey(market = market, code = code)

fun StockTransaction.toStockKey(): StockKey = StockKey(market = market, code = stockCode)
