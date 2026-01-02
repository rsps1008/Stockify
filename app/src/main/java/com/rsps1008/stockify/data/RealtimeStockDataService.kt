package com.rsps1008.stockify.data

import android.content.Context
import android.util.Log
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class RealtimeStockDataService(
    private val stockDao: StockDao,
    private val settingsDataStore: SettingsDataStore,
    private val applicationContext: Context,
    val lastUpdated: Long = System.currentTimeMillis()
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

    data class FetchResult(
        val code: String,
        val info: RealtimeStockInfo?,
        val fallbackUsed: Boolean
    )

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
            val cachedData = settingsDataStore.realtimeStockInfoCacheFlow.first()
            if (cachedData.isNotEmpty()) {
                _realtimeStockInfo.value = cachedData
            }

            if (isTaiwanMarketOpen()) {
                settingsDataStore.fetchIntervalFlow.collectLatest { interval ->
                    while (isTaiwanMarketOpen()) {
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

        // ★★★ 並發 ★★★
        val results = coroutineScope {
            stockCodes.map { code ->
                async(Dispatchers.IO) {
                    var info = primaryFetcher.fetchStockInfo(code)

                    var usedFallback = false
                    if (info == null) {
                        usedFallback = true
                        info = secondaryFetcher.fetchStockInfo(code)
                    }

                    FetchResult(
                        code = code,
                        info = info,
                        fallbackUsed = usedFallback
                    )
                }
            }.awaitAll()
        }

        // 統計 fallback
        val fallbackCount = results.count { it.fallbackUsed }
        val successCount = results.count { it.info != null }

        // 更新資料
        results.forEach { r ->
            r.info?.let { updatedInfos[r.code] = it }
        }

        // fallback 提示邏輯（保持不變）
        if (fallbackCount > 0) {
            val shouldNotifyRepeatedly = settingsDataStore.notifyFallbackRepeatedlyFlow.first()
            val shouldShowNotification = shouldNotifyRepeatedly || !hasNotifiedAboutFallback

            if (shouldShowNotification) {
                val message = when {
                    successCount == 0 -> "主要與備援來源皆無法取得資料"
                    fallbackCount == stockCodes.size -> "主要來源無法取得所有資料，全部改用備用來源"
                    else -> "部分股票主要來源異常，部分改用備用來源"
                }

                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
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

    private fun isTaiwanMarketOpen(): Boolean {
        val taipeiZone = ZoneId.of("Asia/Taipei")
        val now = ZonedDateTime.now(taipeiZone)
        val day = now.dayOfWeek
        val t = now.toLocalTime()

        val weekday = day in DayOfWeek.MONDAY..DayOfWeek.FRIDAY
        val inTime = t.isAfter(LocalTime.of(8, 45)) && t.isBefore(LocalTime.of(13, 35))

        return weekday && inTime
    }
}