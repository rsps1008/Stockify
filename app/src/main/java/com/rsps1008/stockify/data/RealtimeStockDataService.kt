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

class RealtimeStockDataService(
    private val stockDao: StockDao,
    private val settingsDataStore: SettingsDataStore,
    private val yahooStockInfoFetcher: YahooStockInfoFetcher
) {
    private val _realtimeStockInfo = MutableStateFlow<Map<String, RealtimeStockInfo>>(emptyMap())
    val realtimeStockInfo: StateFlow<Map<String, RealtimeStockInfo>> = _realtimeStockInfo.asStateFlow()

    private var fetchJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    init {
        startFetching()
    }

    private fun startFetching() {
        fetchJob?.cancel()
        fetchJob = scope.launch {
            settingsDataStore.refreshIntervalFlow.collectLatest { interval ->
                while (true) {
                    val stocks = stockDao.getHeldStocks().first()
                    val updatedInfos = _realtimeStockInfo.value.toMutableMap()
                    for (stock in stocks) {
                        yahooStockInfoFetcher.fetchStockInfo(stock.code)?.let { info ->
                            updatedInfos[stock.code] = info
                        }
                    }
                    _realtimeStockInfo.value = updatedInfos
                    delay(interval * 1000L)
                }
            }
        }
    }
}