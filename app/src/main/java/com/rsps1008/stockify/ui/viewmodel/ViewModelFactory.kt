package com.rsps1008.stockify.ui.viewmodel

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.rsps1008.stockify.data.OfflineStockRepository
import com.rsps1008.stockify.data.RealtimeStockDataService
import com.rsps1008.stockify.data.SettingsDataStore
import com.rsps1008.stockify.data.StockDao

class ViewModelFactory(
    private val stockDao: StockDao,
    private val application: Application? = null,
    private val realtimeStockDataService: RealtimeStockDataService? = null,
    private val settingsDataStore: SettingsDataStore? = null,
    private val stockCode: String? = null,
    private val transactionId: Int? = null
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(HoldingsViewModel::class.java) -> {
                @Suppress("UNCHECKED_CAST")
                HoldingsViewModel(OfflineStockRepository(stockDao, realtimeStockDataService!!, settingsDataStore!!)) as T
            }
            modelClass.isAssignableFrom(AddTransactionViewModel::class.java) -> {
                @Suppress("UNCHECKED_CAST")
                AddTransactionViewModel(stockDao, settingsDataStore!!, transactionId) as T
            }
            modelClass.isAssignableFrom(TransactionsViewModel::class.java) -> {
                @Suppress("UNCHECKED_CAST")
                TransactionsViewModel(stockDao) as T
            }
            modelClass.isAssignableFrom(SettingsViewModel::class.java) -> {
                @Suppress("UNCHECKED_CAST")
                SettingsViewModel(stockDao, settingsDataStore!!, application!!) as T
            }
            modelClass.isAssignableFrom(StockDetailViewModel::class.java) -> {
                @Suppress("UNCHECKED_CAST")
                StockDetailViewModel(stockCode!!, stockDao, OfflineStockRepository(stockDao, realtimeStockDataService!!, settingsDataStore!!)) as T
            }
            modelClass.isAssignableFrom(TransactionDetailViewModel::class.java) -> {
                @Suppress("UNCHECKED_CAST")
                TransactionDetailViewModel(transactionId!!, stockDao) as T
            }
            else -> throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}