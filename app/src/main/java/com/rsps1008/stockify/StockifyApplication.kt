package com.rsps1008.stockify

import android.app.Application
import android.util.Log
import com.rsps1008.stockify.data.AppDatabase
import com.rsps1008.stockify.data.RealtimeStockDataService
import com.rsps1008.stockify.data.SettingsDataStore
import com.rsps1008.stockify.data.StockDataFetcher
import com.rsps1008.stockify.data.StockListRepository
import com.rsps1008.stockify.data.StockMarket
import com.rsps1008.stockify.data.TaiwanWeightedIndexService
import com.rsps1008.stockify.data.TransactionListRepository
import com.rsps1008.stockify.data.UsdTwdExchangeRateService
import com.rsps1008.stockify.data.TwseStockHistoryService
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first

private const val STOCK_LIST_UPDATE_INTERVAL_MILLIS = 7 * 24 * 60 * 60 * 1000L


class StockifyApplication : Application() {
    lateinit var database: AppDatabase
    lateinit var settingsDataStore: SettingsDataStore
    lateinit var exchangeRateService: UsdTwdExchangeRateService
    lateinit var realtimeStockDataService: RealtimeStockDataService
    lateinit var taiwanWeightedIndexService: TaiwanWeightedIndexService
    lateinit var twseStockHistoryService: TwseStockHistoryService
    lateinit var transactionListRepository: TransactionListRepository
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
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

    /**
     * Checks the Taiwan stock list once when the app opens. This deliberately does not
     * schedule background work or surface a user-facing message.
     */
    suspend fun updateTaiwanStockListIfDue() {
        val lastUpdatedAt = settingsDataStore.lastStockListUpdateTimeFlow.first()
        if (lastUpdatedAt != null && System.currentTimeMillis() - lastUpdatedAt < STOCK_LIST_UPDATE_INTERVAL_MILLIS) {
            return
        }

        try {
            val stocks = StockDataFetcher().fetchStockList()
            if (stocks.isEmpty()) {
                Log.w(TAG, "Taiwan stock list update returned no stocks")
                return
            }

            StockListRepository(this).saveStocks(stocks)
            database.stockDao().deleteStocksByMarket(StockMarket.TW)
            database.stockDao().insertStocks(stocks)
            settingsDataStore.setLastStockListUpdateTime(System.currentTimeMillis())
            Log.i(TAG, "Updated Taiwan stock list on app open: ${stocks.size} stocks")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update Taiwan stock list on app open", e)
        }
    }

    /**
     * Checks the U.S. stock list once when the app opens. This deliberately does not
     * schedule background work or surface a user-facing message.
     */
    suspend fun updateUsStockListIfDue() {
        val lastUpdatedAt = settingsDataStore.lastUsStockListUpdateTimeFlow.first()
        if (lastUpdatedAt != null && System.currentTimeMillis() - lastUpdatedAt < STOCK_LIST_UPDATE_INTERVAL_MILLIS) {
            return
        }

        try {
            val stocks = StockDataFetcher().fetchUsStockList()
            if (stocks.isEmpty()) {
                Log.w(TAG, "U.S. stock list update returned no stocks")
                return
            }

            database.stockDao().deleteStocksByMarket(StockMarket.US)
            database.stockDao().insertStocks(stocks)
            settingsDataStore.setLastUsStockListUpdateTime(System.currentTimeMillis())
            Log.i(TAG, "Updated U.S. stock list on app open: ${stocks.size} stocks")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update U.S. stock list on app open", e)
        }
    }

    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(this)
        database = AppDatabase.getDatabase(this)
        settingsDataStore = SettingsDataStore(this)
        exchangeRateService = UsdTwdExchangeRateService(settingsDataStore)
        taiwanWeightedIndexService = TaiwanWeightedIndexService(httpClient, settingsDataStore)
        realtimeStockDataService = RealtimeStockDataService(
            stockDao = database.stockDao(),
            settingsDataStore = settingsDataStore,
            taiwanWeightedIndexService = taiwanWeightedIndexService,
            applicationContext = this
        )
        twseStockHistoryService = TwseStockHistoryService(httpClient, database.stockDao())
        transactionListRepository = TransactionListRepository(database.stockDao(), applicationScope)
    }

    private companion object {
        const val TAG = "StockifyApplication"
    }
}
