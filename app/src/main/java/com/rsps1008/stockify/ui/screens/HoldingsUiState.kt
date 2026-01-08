package com.rsps1008.stockify.ui.screens

import com.rsps1008.stockify.data.LimitState
import com.rsps1008.stockify.data.Stock

data class HoldingsUiState(
    val cumulativePL: Double = 0.0,
    val cumulativePLPercentage: Double = 0.0,
    val dailyPL: Double = 0.0,
    val marketValue: Double = 0.0,
    val totalCost: Double = 0.0,
    val dividendIncome: Double = 0.0,
    val holdings: List<HoldingInfo> = emptyList(),
    val sellAverage: Double = 0.0
)

data class HoldingInfo(
    val stock: Stock,
    val shares: Double = 0.0,
    val currentPrice: Double = 0.0,
    val averageCost: Double = 0.0,
    val buyAverage: Double = 0.0,
    val sellAverage: Double = 0.0,
    val totalPL: Double = 0.0,
    val totalPLPercentage: Double = 0.0,
    val dailyChange: Double = 0.0,
    val dailyChangePercentage: Double = 0.0,
    val dividendIncome: Double = 0.0,
    val marketValue: Double = 0.0,
    val limitState: LimitState = LimitState.NONE
)