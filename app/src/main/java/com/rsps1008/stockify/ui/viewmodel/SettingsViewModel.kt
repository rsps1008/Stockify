package com.rsps1008.stockify.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rsps1008.stockify.data.SettingsDataStore
import com.rsps1008.stockify.data.StockDao
import com.rsps1008.stockify.data.StockDataFetcher
import com.rsps1008.stockify.data.StockListRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val stockDao: StockDao,
    private val settingsDataStore: SettingsDataStore,
    application: Application
) : AndroidViewModel(application) {

    private val stockDataFetcher = StockDataFetcher()
    private val stockListRepository = StockListRepository(application)

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val refreshInterval: StateFlow<Int> = settingsDataStore.refreshIntervalFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), 5)

    fun setRefreshInterval(interval: Int) {
        viewModelScope.launch {
            settingsDataStore.setRefreshInterval(interval)
        }
    }

    fun deleteAllData() {
        viewModelScope.launch {
            stockDao.deleteAllTransactions()
            stockDao.deleteAllStocks()
        }
    }

    fun updateStockList() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val stocks = stockDataFetcher.fetchStocks()
                stockListRepository.saveStocks(stocks)
                _message.value = "股票列表更新成功！共 ${stocks.size} 筆"
            } catch (e: Exception) {
                _message.value = "更新失敗: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun onMessageShown() {
        _message.value = null
    }
}