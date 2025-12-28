package com.rsps1008.stockify

import android.app.Application
import com.rsps1008.stockify.data.AppDatabase
import com.rsps1008.stockify.data.RealtimeStockDataService
import com.rsps1008.stockify.data.SettingsDataStore
import com.rsps1008.stockify.data.YahooStockInfoFetcher

class StockifyApplication : Application() {
    val database: AppDatabase by lazy { AppDatabase.getDatabase(this) }
    val settingsDataStore: SettingsDataStore by lazy { SettingsDataStore(this) }
    val realtimeStockDataService: RealtimeStockDataService by lazy {
        RealtimeStockDataService(
            stockDao = database.stockDao(),
            settingsDataStore = settingsDataStore,
            yahooStockInfoFetcher = YahooStockInfoFetcher()
        )
    }
}