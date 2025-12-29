package com.rsps1008.stockify.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.TimeZone

class RealtimeStockDataService(
    private val stockDao: StockDao,
    private val settingsDataStore: SettingsDataStore,
    private val yahooStockInfoFetcher: YahooStockInfoFetcher
) {
    private val _realtimeStockInfo = MutableStateFlow<Map<String, RealtimeStockInfo>>(emptyMap())
    val realtimeStockInfo: StateFlow<Map<String, RealtimeStockInfo>> = _realtimeStockInfo.asStateFlow()

    private var fetchJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private var fetchCount = 0

    init {
        startFetching()
    }

    fun startFetching() {
        fetchJob?.cancel()
        fetchJob = scope.launch {
            // 1. Load from cache first
            val cachedData = settingsDataStore.realtimeStockInfoCacheFlow.first()
            if (cachedData.isNotEmpty()) {
                _realtimeStockInfo.value = cachedData
            }

            // 2. Check market hours and decide fetching strategy
            val isMarketOpen = isMarketOpen()

            if (isMarketOpen) {
                // Fetch continuously during market hours
                settingsDataStore.yahooFetchIntervalFlow.collectLatest { interval ->
                    while (isMarketOpen()) {
                        fetchAllStockInfo(true)
                        delay(interval * 1000L)
                    }
                    // When market closes, do one final fetch and save
                    fetchAllStockInfo(true, forceSave = true)
                }
            } else {
                // Fetch once after market hours
                fetchAllStockInfo(false)
            }
        }
    }

    suspend fun fetchAllStockInfo(isContinuous: Boolean, forceSave: Boolean = false) {
        val stocks = stockDao.getHeldStocks().first()
        if (stocks.isEmpty()) return

        val stockCodes = stocks.map { it.code }
        val newInfos = yahooStockInfoFetcher.fetchStockInfoList(stockCodes)

        val updatedInfos = _realtimeStockInfo.value.toMutableMap()
        updatedInfos.putAll(newInfos)

        _realtimeStockInfo.value = updatedInfos

        if (isContinuous) {
            fetchCount++
            if (fetchCount >= 10 || forceSave) {
                settingsDataStore.setRealtimeStockInfoCache(updatedInfos)
                fetchCount = 0
            }
        } else {
            settingsDataStore.setRealtimeStockInfoCache(updatedInfos)
        }
    }

    suspend fun refreshStock(stockCode: String) {
        val newInfo = yahooStockInfoFetcher.fetchStockInfoList(listOf(stockCode))
        if (newInfo.isNotEmpty()) {
            val updatedInfos = _realtimeStockInfo.value.toMutableMap()
            updatedInfos.putAll(newInfo)
            _realtimeStockInfo.value = updatedInfos
            settingsDataStore.setRealtimeStockInfoCache(updatedInfos)
        }
    }

    private fun isMarketOpen(): Boolean {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("Asia/Taipei"))
        val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val minute = calendar.get(Calendar.MINUTE)

        // Monday to Friday
        val isWeekday = dayOfWeek >= Calendar.MONDAY && dayOfWeek <= Calendar.FRIDAY
        if (!isWeekday) return false

        // 9:00 AM to 1:30 PM
        val isMarketHour = (hour == 9 && minute >= 0) || (hour > 9 && hour < 13) || (hour == 13 && minute <= 30)

        return isMarketHour
    }
}