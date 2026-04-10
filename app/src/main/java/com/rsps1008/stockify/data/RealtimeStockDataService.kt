package com.rsps1008.stockify.data

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.os.Handler
import android.os.Looper
import io.ktor.client.HttpClient
import android.widget.Toast
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class RealtimeStockDataService(
    private val stockDao: StockDao,
    private val settingsDataStore: SettingsDataStore,
    private val applicationContext: Context,
) {
    private val _realtimeStockInfo = MutableStateFlow<Map<String, RealtimeStockInfo>>(emptyMap())
    val realtimeStockInfo: StateFlow<Map<String, RealtimeStockInfo>> = _realtimeStockInfo.asStateFlow()

    private var fetchJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private var fetchCount = 0
    private var hasNotifiedAboutFallback = false

    private val twseFetcher = TwseStockInfoFetcher()
    private val yahooFetcher = YahooStockInfoFetcher()
    private val preferredStockDataSource = MutableStateFlow("TWSE")

    init {
        scope.launch {
            settingsDataStore.stockDataSourceFlow
                .distinctUntilChanged()
                .collect { source ->
                    preferredStockDataSource.value = normalizeStockDataSource(source)
                    Log.d(
                        "RealtimeStockDataService",
                        "Preferred realtime data source updated to ${preferredStockDataSource.value}"
                    )
                }
        }
        startFetching()
    }

    private val client = HttpClient(CIO) {
        engine {
            requestTimeout = 5000
        }
    }

    data class FetchResult(
        val code: String,
        val info: RealtimeStockInfo?,
        val fallbackUsed: Boolean
    )

    private data class FetchOutcome(
        val info: RealtimeStockInfo?,
        val fallbackUsed: Boolean
    )

    private fun getFetchers(): Pair<StockInfoFetcher, StockInfoFetcher> {
        val preferredSource = preferredStockDataSource.value
        return if (preferredSource == "TWSE") {
            Pair(twseFetcher, yahooFetcher)
        } else {
            Pair(yahooFetcher, twseFetcher)
        }
    }

    fun startFetching() {
        fetchJob?.cancel()
        fetchJob = scope.launch {

            // 1️⃣ 先載入快取
            val cachedData = settingsDataStore.realtimeStockInfoCacheFlow.first()
            if (cachedData.isNotEmpty()) {
                _realtimeStockInfo.value = cachedData
            }

            // 2️⃣ 盤前只抓一次
            if (!isTaiwanMarketOpen()) {
                fetchAllStockInfo(isContinuous = false)
            }

            // 3️⃣ 永遠 loop，等待開盤
            settingsDataStore.fetchIntervalFlow.collectLatest { interval ->
                while (true) {
                    if (!isTaiwanMarketOpen()) {
                        delay(30_000L)   // 每分鐘檢查一次是否開盤
                        continue
                    }

                    // === 盤中 ===
                    fetchAllStockInfo(isContinuous = true)
                    delay(delayUntilNextAlignedFetch(interval))
                }
            }
        }
    }


    suspend fun fetchAllStockInfo(isContinuous: Boolean, forceSave: Boolean = false) {
        val stocks = stockDao.getHeldStocks().first()
        if (stocks.isEmpty()) return

        val stockCodes = stocks.map { it.code }
        val (primaryFetcher, secondaryFetcher) = getFetchers()
        Log.d(
            "RealtimeStockDataService",
            "Fetching realtime stock info using primary=${primaryFetcher.javaClass.simpleName}, secondary=${secondaryFetcher.javaClass.simpleName}"
        )

        val updatedInfos = _realtimeStockInfo.value.toMutableMap()

        // ★★★ 並發 ★★★
        val results = coroutineScope {
            stockCodes.map { code ->
                async(Dispatchers.IO) {
                    val outcome = fetchWithSingleFallback(
                        stockCode = code,
                        primaryFetcher = primaryFetcher,
                        secondaryFetcher = secondaryFetcher
                    )

                    FetchResult(
                        code = code,
                        info = outcome.info,
                        fallbackUsed = outcome.fallbackUsed
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
        Log.d(
            "RealtimeStockDataService",
            "Refreshing $stockCode using primary=${primaryFetcher.javaClass.simpleName}, secondary=${secondaryFetcher.javaClass.simpleName}"
        )

        val outcome = fetchWithSingleFallback(
            stockCode = stockCode,
            primaryFetcher = primaryFetcher,
            secondaryFetcher = secondaryFetcher
        )

        // 更新 & 快取
        outcome.info?.let {
            val updatedInfos = _realtimeStockInfo.value.toMutableMap()
            updatedInfos[stockCode] = it
            _realtimeStockInfo.value = updatedInfos
            settingsDataStore.setRealtimeStockInfoCache(updatedInfos)
        }
    }

    suspend fun fetchCurrentStockInfo(stockCode: String): RealtimeStockInfo? {
        val cached = _realtimeStockInfo.value[stockCode]
        if (cached != null) {
            return cached
        }

        val (primaryFetcher, secondaryFetcher) = getFetchers()
        return fetchWithSingleFallback(
            stockCode = stockCode,
            primaryFetcher = primaryFetcher,
            secondaryFetcher = secondaryFetcher
        ).info
    }

    private suspend fun isTaiwanMarketOpen(): Boolean {
        val taipeiZone = ZoneId.of("Asia/Taipei")
        val now = ZonedDateTime.now(taipeiZone)
        val date = now.toLocalDate()
        val time = now.toLocalTime()

        // 1. 非交易時間
        val inTime = time.isAfter(LocalTime.of(9, 0)) && time.isBefore(LocalTime.of(17, 30))
        if (!inTime) return false

        // 2. 檢查是否是假日（讀取 20XX.json）
        if (isTaiwanHoliday(date)) return false

        return true
    }

    @SuppressLint("UnsafeOptInUsageError")
    @kotlinx.serialization.Serializable
    data class TaiwanHolidayItem(
        val date: String,
        val week: String,
        val isHoliday: Boolean,
        val description: String
    )

    private suspend fun isTaiwanHoliday(date: LocalDate): Boolean {
        val year = date.year

        val url = "https://cdn.jsdelivr.net/gh/ruyut/TaiwanCalendar/data/${year}.json"

        return try {
            val json = client.get(url).body<String>()
            val list = Json.decodeFromString<List<TaiwanHolidayItem>>(json)
            val today = date.format(DateTimeFormatter.ofPattern("yyyyMMdd"))
            val item = list.find { it.date == today }
            /*Log.d(
                "RealtimeStockDataService",
                "json假日資料 → ${item?.isHoliday} 假日"
            )*/
            item?.isHoliday == true
        } catch (e: Exception) {
            Log.e(
                "RealtimeStockDataService",
                "若抓不到假日資料 → 視為非假日"
            )
            false   // 若抓不到資料 → 視為非假日（保守作法）
        }
    }

    private fun delayUntilNextAlignedFetch(intervalSeconds: Int): Long {
        if (intervalSeconds <= 0) return 0L

        val now = ZonedDateTime.now(ZoneId.of("Asia/Taipei"))
        val currentNano = now.nano
        val epochSecond = now.toEpochSecond()
        val secondsPastBoundary = Math.floorMod(epochSecond, intervalSeconds.toLong()).toInt()

        // 對齊到整數秒邊界，例如 10 秒 => 0/10/20/30/40/50
        val secondsUntilNextBoundary = if (secondsPastBoundary == 0) intervalSeconds else intervalSeconds - secondsPastBoundary

        return (secondsUntilNextBoundary * 1000L) - (currentNano / 1_000_000L)
    }

    private fun normalizeStockDataSource(source: String): String {
        return when (source.trim().uppercase()) {
            "TWSE" -> "TWSE"
            "YAHOO" -> "Yahoo"
            else -> "TWSE"
        }
    }

    private suspend fun fetchWithSingleFallback(
        stockCode: String,
        primaryFetcher: StockInfoFetcher,
        secondaryFetcher: StockInfoFetcher
    ): FetchOutcome {
        val primaryInfo = primaryFetcher.fetchStockInfo(stockCode)
        if (primaryInfo != null) {
            return FetchOutcome(info = primaryInfo, fallbackUsed = false)
        }

        Log.e(
            "RealtimeStockDataService",
            "Primary source failed for $stockCode → fallback to secondary"
        )

        val secondaryInfo = secondaryFetcher.fetchStockInfo(stockCode)
        return if (secondaryInfo != null) {
            Log.d(
                "RealtimeStockDataService",
                "Fallback succeeded for $stockCode using ${secondaryFetcher.javaClass.simpleName}"
            )
            FetchOutcome(info = secondaryInfo, fallbackUsed = true)
        } else {
            Log.e(
                "RealtimeStockDataService",
                "Fallback also failed for $stockCode → no data"
            )
            FetchOutcome(info = null, fallbackUsed = true)
        }
    }

}
