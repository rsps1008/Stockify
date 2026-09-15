package com.rsps1008.stockify.data

private const val TAIWAN_STOCK_PAR_VALUE = 10.0

/**
 * Converts the announced per-share stock-dividend amount into issued shares.
 * Taiwan announcements are denominated using the NT$10 par value, unlike US
 * stock dividends which already represent shares per share.
 */
fun calculateStockDividendShares(
    stockDividend: Double,
    exRightsShares: Double,
    market: String
): Double {
    val sharesPerShare = if (StockMarket.isTw(market)) {
        stockDividend / TAIWAN_STOCK_PAR_VALUE
    } else {
        stockDividend
    }
    return exRightsShares * sharesPerShare
}
