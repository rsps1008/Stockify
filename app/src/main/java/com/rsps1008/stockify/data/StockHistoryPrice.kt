package com.rsps1008.stockify.data

import androidx.room.Entity

@Entity(
    tableName = "stock_history_prices",
    primaryKeys = ["stockCode", "market", "date"]
)
data class StockHistoryPrice(
    val stockCode: String,
    val date: String, // "YYYY-MM-DD"
    val price: Double,
    val market: String = StockMarket.TW
)
