package com.rsps1008.stockify.data

import java.util.Locale

fun formatMarketAmount(value: Double, market: String?): String {
    return if (StockMarket.isUs(market)) {
        String.format(Locale.US, "%,.2f", value)
    } else {
        String.format(Locale.US, "%,.0f", value)
    }
}

fun formatHomeAmount(value: Double, displayMode: String): String {
    return if (HomeDisplayMode.normalize(displayMode) == HomeDisplayMode.US) {
        String.format(Locale.US, "%,.2f", value)
    } else {
        String.format(Locale.US, "%,.0f", value)
    }
}

fun formatMarketPlainAmount(value: Double, market: String?): String {
    return if (StockMarket.isUs(market)) {
        String.format(Locale.US, "%.2f", value)
    } else {
        String.format(Locale.US, "%.0f", value)
    }
}
