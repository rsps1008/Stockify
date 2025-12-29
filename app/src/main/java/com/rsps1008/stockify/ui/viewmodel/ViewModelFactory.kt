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
                requireNotNull(realtimeStockDataService) { "realtimeStockDataService is required for HoldingsViewModel" }
                requireNotNull(settingsDataStore) { "settingsDataStore is required for HoldingsViewModel" }
                @Suppress("UNCHECKED_CAST")
                HoldingsViewModel(OfflineStockRepository(stockDao, realtimeStockDataService, settingsDataStore)) as T
            }
            modelClass.isAssignableFrom(AddTransactionViewModel::class.java) -> {
                requireNotNull(settingsDataStore) { "settingsDataStore is required for AddTransactionViewModel" }
                requireNotNull(realtimeStockDataService) { "realtimeStockDataService is required for AddTransactionViewModel" }
                @Suppress("UNCHECKED_CAST")
                AddTransactionViewModel(stockDao, settingsDataStore, transactionId, realtimeStockDataService) as T
            }
            modelClass.isAssignableFrom(TransactionsViewModel::class.java) -> {
                @Suppress("UNCHECKED_CAST")
                TransactionsViewModel(stockDao) as T
            }
            modelClass.isAssignableFrom(SettingsViewModel::class.java) -> {
                requireNotNull(settingsDataStore) { "settingsDataStore is required for SettingsViewModel" }
                requireNotNull(application) { "application is required for SettingsViewModel" }
                requireNotNull(realtimeStockDataService) { "realtimeStockDataService is required for SettingsViewModel" }
                @Suppress("UNCHECKED_CAST")
                SettingsViewModel(stockDao, settingsDataStore, application, realtimeStockDataService) as T
            }
            modelClass.isAssignableFrom(StockDetailViewModel::class.java) -> {
                requireNotNull(stockCode) { "stockCode is required for StockDetailViewModel" }
                requireNotNull(realtimeStockDataService) { "realtimeStockDataService is required for StockDetailViewModel" }
                requireNotNull(settingsDataStore) { "settingsDataStore is required for StockDetailViewModel" }
                @Suppress("UNCHECKED_CAST")
                StockDetailViewModel(stockCode, stockDao, OfflineStockRepository(stockDao, realtimeStockDataService, settingsDataStore)) as T
            }
            modelClass.isAssignableFrom(TransactionDetailViewModel::class.java) -> {
                requireNotNull(transactionId) { "transactionId is required for TransactionDetailViewModel" }
                @Suppress("UNCHECKED_CAST")
                TransactionDetailViewModel(transactionId, stockDao) as T
            }
            else -> throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}