package com.rsps1008.stockify

import android.app.Application
import com.rsps1008.stockify.data.AppDatabase
import com.rsps1008.stockify.data.RealtimeStockDataService
import com.rsps1008.stockify.data.SettingsDataStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json


class StockifyApplication : Application() {
    lateinit var database: AppDatabase
    lateinit var settingsDataStore: SettingsDataStore
    lateinit var realtimeStockDataService: RealtimeStockDataService
    // ★ 新增：全域 HttpClient（給 TWSE / 即時股價 / 配息用）

    val httpClient: HttpClient by lazy {
        HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = 10_000
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = 10_000
            }
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        isLenient = true
                    }
                )
            }
        }
    }
    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getDatabase(this)
        settingsDataStore = SettingsDataStore(this)
        realtimeStockDataService = RealtimeStockDataService(
            stockDao = database.stockDao(),
            settingsDataStore = settingsDataStore,
            applicationContext = this
        )
    }
}