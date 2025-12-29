package com.rsps1008.stockify

import android.app.Application
import com.rsps1008.stockify.data.AppDatabase
import com.rsps1008.stockify.data.RealtimeStockDataService
import com.rsps1008.stockify.data.SettingsDataStore
import com.rsps1008.stockify.data.YahooStockInfoFetcher

class StockifyApplication : Application() {
    lateinit var database: AppDatabase
    lateinit var settingsDataStore: SettingsDataStore
    lateinit var realtimeStockDataService: RealtimeStockDataService

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getDatabase(this)
        settingsDataStore = SettingsDataStore(this)
        realtimeStockDataService = RealtimeStockDataService(
            stockDao = database.stockDao(),
            settingsDataStore = settingsDataStore,
            yahooStockInfoFetcher = YahooStockInfoFetcher()
        )
    }
}