package com.rsps1008.stockify.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rsps1008.stockify.data.StockRepository
import com.rsps1008.stockify.ui.screens.HoldingInfo
import com.rsps1008.stockify.ui.screens.TransactionUiState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class StockDetailViewModel(stockId: Int, stockRepository: StockRepository) : ViewModel() {

    val holdingInfo: StateFlow<HoldingInfo?> = stockRepository.getHoldingInfo(stockId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), null)

    val transactions: StateFlow<List<TransactionUiState>> = stockRepository.getTransactionsForStock(stockId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())
}