package com.rsps1008.stockify.ui.screens

import androidx.room.Embedded
import com.rsps1008.stockify.data.StockTransaction

data class TransactionUiState(
    @Embedded val transaction: StockTransaction,
    val stockName: String
)
