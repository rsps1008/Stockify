package com.rsps1008.stockify.ui.viewmodel

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.rsps1008.stockify.data.RealtimeStockDataService
import com.rsps1008.stockify.data.SettingsDataStore
import com.rsps1008.stockify.data.StockDao
//import com.rsps1008.stockify.data.StockRepository

class ViewModelFactory(
    private val stockDao: StockDao,
    private val application: Application? = null,
    private val realtimeStockDataService: RealtimeStockDataService? = null,
    private val settingsDataStore: SettingsDataStore? = null,
    private val stockId: Int? = null,
    private val transactionId: Int? = null
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
//            modelClass.isAssignableFrom(HoldingsViewModel::class.java) -> {
//                @Suppress("UNCHECKED_CAST")
//                HoldingsViewModel(StockRepository(stockDao, realtimeStockDataService!!)) as T
//            }
            modelClass.isAssignableFrom(AddTransactionViewModel::class.java) -> {
                @Suppress("UNCHECKED_CAST")
                AddTransactionViewModel(stockDao, transactionId) as T
            }
            modelClass.isAssignableFrom(TransactionsViewModel::class.java) -> {
                @Suppress("UNCHECKED_CAST")
                TransactionsViewModel(stockDao) as T
            }
            modelClass.isAssignableFrom(SettingsViewModel::class.java) -> {
                @Suppress("UNCHECKED_CAST")
                SettingsViewModel(stockDao, settingsDataStore!!, application!!) as T
            }
//            modelClass.isAssignableFrom(StockDetailViewModel::class.java) -> {
//                @Suppress("UNCHECKED_CAST")
//                StockDetailViewModel(stockId!!, StockRepository(stockDao, realtimeStockDataService!!)) as T
//            }
            modelClass.isAssignableFrom(TransactionDetailViewModel::class.java) -> {
                @Suppress("UNCHECKED_CAST")
                TransactionDetailViewModel(transactionId!!, stockDao) as T
            }
            else -> throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}