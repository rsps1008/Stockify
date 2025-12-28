package com.rsps1008.stockify.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rsps1008.stockify.data.StockRepository
import com.rsps1008.stockify.ui.screens.HoldingsUiState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class HoldingsViewModel(stockRepository: StockRepository) : ViewModel() {

    val uiState: StateFlow<HoldingsUiState> = stockRepository.getHoldings()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = HoldingsUiState()
        )
}