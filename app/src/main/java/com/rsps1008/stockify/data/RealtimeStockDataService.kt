package com.rsps1008.stockify.data

import android.content.Context
import android.util.Log
import android.widget.Toast
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
    private val applicationContext: Context
) {
    private val _realtimeStockInfo = MutableStateFlow<Map<String, RealtimeStockInfo>>(emptyMap())
    val realtimeStockInfo: StateFlow<Map<String, RealtimeStockInfo>> = _realtimeStockInfo.asStateFlow()

    private var fetchJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private var fetchCount = 0
    private var hasNotifiedAboutFallback = false

    private val twseFetcher = TwseStockInfoFetcher()
    private val yahooFetcher = YahooStockInfoFetcher()

    init {
        startFetching()
    }

    private suspend fun getFetchers(): Pair<StockInfoFetcher, StockInfoFetcher> {
        val preferredSource = settingsDataStore.stockDataSourceFlow.first()
        return if (preferredSource == "TWSE") {
            Pair(twseFetcher, yahooFetcher)
        } else {
            Pair(yahooFetcher, twseFetcher)
        }
    }

    fun startFetching() {
        fetchJob?.cancel()
        fetchJob = scope.launch {
            val (primaryFetcher, _) = getFetchers()

            val cachedData = settingsDataStore.realtimeStockInfoCacheFlow.first()
            if (cachedData.isNotEmpty()) {
                _realtimeStockInfo.value = cachedData
            }

            val isMarketOpen = primaryFetcher.isMarketOpen()

            if (isMarketOpen) {
                settingsDataStore.yahooFetchIntervalFlow.collectLatest { interval ->
                    while (getFetchers().first.isMarketOpen()) {
                        fetchAllStockInfo(true)
                        delay(interval * 1000L)
                    }
                    fetchAllStockInfo(true, forceSave = true)
                }
            } else {
                fetchAllStockInfo(false)
            }
        }
    }

    suspend fun fetchAllStockInfo(isContinuous: Boolean, forceSave: Boolean = false) {
        val stocks = stockDao.getHeldStocks().first()
        if (stocks.isEmpty()) return

        val stockCodes = stocks.map { it.code }
        val (primaryFetcher, secondaryFetcher) = getFetchers()

        val updatedInfos = _realtimeStockInfo.value.toMutableMap()

        var needToast = false

        for (code in stockCodes) {
            // 先抓主要來源
            var info = primaryFetcher.fetchStockInfo(code)

            // 單一檔 TWSE 失敗 → fallback Yahoo
            if (info == null) {
                needToast = true
                Log.e("RealtimeStockDataService", "Primary failed for $code → fallback")
                info = secondaryFetcher.fetchStockInfo(code)

                if (info != null) {
                    Log.d(
                        "RealtimeStockDataService",
                        "Fallback succeeded for $code using ${secondaryFetcher.javaClass.simpleName}"
                    )
                } else {
                    Log.e("RealtimeStockDataService", "Fallback also failed for $code")
                }
            }

            // 更新到 map
            info?.let { updatedInfos[code] = it }
        }

        if (needToast) {
            val shouldNotifyRepeatedly = settingsDataStore.notifyFallbackRepeatedlyFlow.first()

            val shouldShowNotification = if (shouldNotifyRepeatedly) {
                true
            } else {
                !hasNotifiedAboutFallback
            }

            if (shouldShowNotification) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    Toast.makeText(
                        applicationContext,
                        "部分股票主要來源無法取得資料，已自動使用備援來源",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                if (!shouldNotifyRepeatedly) {
                    hasNotifiedAboutFallback = true
                }
            }
        }

        // 更新 StateFlow 與快取
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
        val (primaryFetcher, secondaryFetcher) = getFetchers()

        // 先抓主要來源
        var newInfo = primaryFetcher.fetchStockInfo(stockCode)

        // 主來源抓不到 → 單項 fallback，但不動設定
        if (newInfo == null) {
            Log.e(
                "RealtimeStockDataService",
                "Primary source failed for $stockCode → fallback to secondary"
            )
            newInfo = secondaryFetcher.fetchStockInfo(stockCode)

            if (newInfo != null) {
                Log.d(
                    "RealtimeStockDataService",
                    "Fallback succeeded for $stockCode using ${secondaryFetcher.javaClass.simpleName}"
                )
            } else {
                Log.e(
                    "RealtimeStockDataService",
                    "Fallback also failed for $stockCode → no data"
                )
            }
        }

        // 更新 & 快取
        newInfo?.let {
            val updatedInfos = _realtimeStockInfo.value.toMutableMap()
            updatedInfos[stockCode] = it
            _realtimeStockInfo.value = updatedInfos
            settingsDataStore.setRealtimeStockInfoCache(updatedInfos)
        }
    }


}