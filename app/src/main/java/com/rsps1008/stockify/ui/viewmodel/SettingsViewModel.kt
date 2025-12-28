package com.rsps1008.stockify.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rsps1008.stockify.data.CsvService
import com.rsps1008.stockify.data.SettingsDataStore
import com.rsps1008.stockify.data.Stock
import com.rsps1008.stockify.data.StockDao
import com.rsps1008.stockify.data.StockDataFetcher
import com.rsps1008.stockify.data.StockListRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(
    private val stockDao: StockDao,
    private val settingsDataStore: SettingsDataStore,
    application: Application
) : AndroidViewModel(application) {

    private val stockDataFetcher = StockDataFetcher()
    private val stockListRepository = StockListRepository(application)
    private val csvService = CsvService()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _showImportConfirmDialog = MutableStateFlow(false)
    val showImportConfirmDialog: StateFlow<Boolean> = _showImportConfirmDialog.asStateFlow()

    private var importUri: Uri? = null

    val refreshInterval: StateFlow<Int> = settingsDataStore.refreshIntervalFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), 5)

    val lastStockListUpdateTime: StateFlow<Long?> = settingsDataStore.lastStockListUpdateTimeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), null)

    val feeDiscount: StateFlow<Double> = settingsDataStore.feeDiscountFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), 0.28)

    val minFeeRegular: StateFlow<Int> = settingsDataStore.minFeeRegularFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), 20)

    val minFeeOddLot: StateFlow<Int> = settingsDataStore.minFeeOddLotFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), 1)

    fun exportTransactions(uri: Uri) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val transactions = stockDao.getTransactionsWithStock().first()
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(uri)?.use {
                        csvService.export(transactions, it)
                    }
                }
                _message.value = "匯出成功"
            } catch (e: Exception) {
                _message.value = "匯出失敗: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun onImportRequest(uri: Uri) {
        importUri = uri
        _showImportConfirmDialog.value = true
    }

    fun onImportConfirm(deleteOldData: Boolean) {
        _showImportConfirmDialog.value = false
        importUri?.let {
            performImport(it, deleteOldData)
        }
    }

    fun onImportCancel() {
        _showImportConfirmDialog.value = false
        importUri = null
    }

    private fun performImport(uri: Uri, deleteOldData: Boolean) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                if (deleteOldData) {
                    deleteAllData()
                }

                val csvTransactions = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use {
                        csvService.import(it)
                    }
                } ?: emptyList()

                csvTransactions.forEach { csvTransaction ->
                    var stock = stockDao.getStockByCode(csvTransaction.stockCode)
                    if (stock == null) {
                        val newStock = Stock(name = csvTransaction.stockName, code = csvTransaction.stockCode)
                        stockDao.insertStock(newStock)
                    }
                    stockDao.insertTransaction(csvTransaction.transaction)
                }
                _message.value = "匯入成功，共 ${csvTransactions.size} 筆紀錄"
            } catch (e: Exception) {
                _message.value = "匯入失敗: ${e.message}"
            } finally {
                _isLoading.value = false
                importUri = null
            }
        }
    }

    fun setRefreshInterval(interval: Int) {
        viewModelScope.launch {
            settingsDataStore.setRefreshInterval(interval)
        }
    }

    fun setFeeDiscount(discount: Double) {
        viewModelScope.launch {
            settingsDataStore.setFeeDiscount(discount)
        }
    }

    fun setMinFeeRegular(fee: Int) {
        viewModelScope.launch {
            settingsDataStore.setMinFeeRegular(fee)
        }
    }

    fun setMinFeeOddLot(fee: Int) {
        viewModelScope.launch {
            settingsDataStore.setMinFeeOddLot(fee)
        }
    }

    fun deleteAllData() {
        viewModelScope.launch {
            stockDao.deleteAllTransactions()
            stockDao.deleteAllStocks()
        }
    }

    fun updateStockListFromTwse() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val stocks = stockDataFetcher.fetchStockList()
                // Save to json file
                stockListRepository.saveStocks(stocks)
                // And also save to Room database
                stockDao.insertStocks(stocks)
                settingsDataStore.setLastStockListUpdateTime(System.currentTimeMillis())
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