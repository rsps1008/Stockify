package com.rsps1008.stockify.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rsps1008.stockify.data.StockDao
import com.rsps1008.stockify.data.StockRepository
import com.rsps1008.stockify.ui.screens.HoldingInfo
import com.rsps1008.stockify.ui.screens.TransactionUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class StockDetailViewModel(private val stockCode: String, private val stockDao: StockDao, stockRepository: StockRepository) : ViewModel() {

    val holdingInfo: StateFlow<HoldingInfo?> = stockRepository.getHoldingInfo(stockCode)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), null)

    val transactions: StateFlow<List<TransactionUiState>> = stockRepository.getTransactionsForStock(stockCode)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    private val _showDeleteConfirmDialog = MutableStateFlow(false)
    val showDeleteConfirmDialog: StateFlow<Boolean> = _showDeleteConfirmDialog.asStateFlow()

    fun onDeleteTransactionsClicked() {
        _showDeleteConfirmDialog.value = true
    }

    fun onDeleteTransactionsConfirmed() {
        viewModelScope.launch {
            stockDao.deleteTransactionsByStockCode(stockCode)
        }
        _showDeleteConfirmDialog.value = false
    }

    fun onDeleteTransactionsCancelled() {
        _showDeleteConfirmDialog.value = false
    }
}