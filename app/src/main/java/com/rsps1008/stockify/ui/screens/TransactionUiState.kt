package com.rsps1008.stockify.ui.screens

import com.rsps1008.stockify.data.StockTransaction

data class TransactionUiState(
    val transaction: StockTransaction,
    val stockName: String
)
