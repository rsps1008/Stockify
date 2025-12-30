package com.rsps1008.stockify.data

interface StockInfoFetcher {
    fun isMarketOpen(): Boolean
    suspend fun fetchStockInfoList(stockCodes: List<String>): Map<String, RealtimeStockInfo>
    suspend fun fetchStockInfo(stockCode: String): RealtimeStockInfo?
}
