package com.rsps1008.stockify.data

import com.rsps1008.stockify.ui.screens.HoldingInfo
import com.rsps1008.stockify.ui.screens.HoldingsUiState
import com.rsps1008.stockify.ui.screens.TransactionUiState
import kotlinx.coroutines.flow.Flow

interface StockRepository {
    fun getHoldings(): Flow<HoldingsUiState>
    fun getHoldingInfo(
        stockCode: String,
        accountId: Int = 0,
        market: String = StockMarket.inferFromCode(stockCode)
    ): Flow<HoldingInfo?>

    fun getTransactionsForStock(
        stockCode: String,
        accountId: Int = 0,
        market: String = StockMarket.inferFromCode(stockCode)
    ): Flow<List<TransactionUiState>>
}
