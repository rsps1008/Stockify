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
import com.rsps1008.stockify.data.StockMarket

class RealtimeStockDataService(
    private val stockDao: StockDao,
    private val settingsDataStore: SettingsDataStore,
    private val taiwanWeightedIndexService: TaiwanWeightedIndexService,
    private val applicationContext: Context,
) {
    private val _realtimeStockInfo = MutableStateFlow<Map<String, RealtimeStockInfo>>(emptyMap())
    val realtimeStockInfo: StateFlow<Map<String, RealtimeStockInfo>> = _realtimeStockInfo.asStateFlow()

    private var fetchJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private var fetchCount = 0
    private var hasNotifiedAboutFallback = false
    private var hasNotifiedAboutCertificateFailure = false

    private val twseFetcher = TwseStockInfoFetcher()
    private val yahooFetcher = YahooStockInfoFetcher()
    private val usYahooFetcher = UsYahooStockInfoFetcher()
    private val usNasdaqFetcher = NasdaqStockInfoFetcher()
    private val preferredStockDataSource = MutableStateFlow("TWSE")
    private val preferredUsStockDataSource = MutableStateFlow("Nasdaq")

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
        scope.launch {
            settingsDataStore.usStockDataSourceFlow
                .distinctUntilChanged()
                .collect { source ->
                    preferredUsStockDataSource.value = normalizeUsStockDataSource(source)
                    Log.d(
                        "RealtimeStockDataService",
                        "Preferred US realtime data source updated to ${preferredUsStockDataSource.value}"
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
        val fallbackUsed: Boolean,
        val certificateFailure: Boolean
    )

    private data class FetchOutcome(
        val info: RealtimeStockInfo?,
        val fallbackUsed: Boolean,
        val certificateFailure: Boolean = false
    )

    private fun getTwFetchers(): Pair<StockInfoFetcher, StockInfoFetcher> {
        val preferredSource = preferredStockDataSource.value
        return if (preferredSource == "TWSE") {
            Pair(twseFetcher, yahooFetcher)
        } else {
            Pair(yahooFetcher, twseFetcher)
        }
    }

    private fun getFetcherForMarket(market: String): StockInfoFetcher {
        return if (StockMarket.isUs(market)) getUsPrimaryFetcher() else twseFetcher
    }

    private fun isAnyMarketOpen(): Boolean {
        return twseFetcher.isMarketOpen() || usNasdaqFetcher.isMarketOpen()
    }

    fun startFetching() {
        fetchJob?.cancel()
        fetchJob = scope.launch {

            val cachedData = settingsDataStore.realtimeStockInfoCacheFlow.first()
            if (cachedData.isNotEmpty()) {
                _realtimeStockInfo.value = cachedData
            }

            // App 啟動時先強制抓一次最新資料，避免只看到過期快取。
            fetchAllStockInfo(
                isContinuous = false,
                refreshRegardlessOfMarketOpen = true
            )
            refreshTaiwanWeightedIndex(refreshRegardlessOfMarketOpen = true)

            settingsDataStore.fetchIntervalFlow.collectLatest { interval ->
                while (true) {
                    if (!isAnyMarketOpen()) {
                        delay(30_000L)
                        continue
                    }
                    fetchAllStockInfo(isContinuous = true)
                    refreshTaiwanWeightedIndex()
                    delay(delayUntilNextAlignedFetch(interval))
                }
            }
        }
    }


    suspend fun fetchAllStockInfo(
        isContinuous: Boolean,
        forceSave: Boolean = false,
        refreshRegardlessOfMarketOpen: Boolean = false
    ) {
        val stocks = stockDao.getHeldStocks().first()
        if (stocks.isEmpty()) return

        val updatedInfos = _realtimeStockInfo.value.toMutableMap()
        val stockGroups = stocks.groupBy { StockMarket.normalize(it.market) }

        val results = coroutineScope {
            stockGroups.flatMap { (market, marketStocks) ->
                if (!refreshRegardlessOfMarketOpen && !shouldRefreshMarket(market)) {
                    Log.d(
                        "RealtimeStockDataService",
                        "Skipping $market realtime stock info because market is closed"
                    )
                    emptyList()
                } else {
                    val fetcher = getFetcherForMarket(market)
                    Log.d(
                        "RealtimeStockDataService",
                        "Fetching $market realtime stock info using ${fetcher.javaClass.simpleName}"
                    )

                    marketStocks.map { stock ->
                        async(Dispatchers.IO) {
                            val outcome = fetchStockInfoForMarket(
                                stockCode = stock.code,
                                market = market,
                                stockType = stock.stockType
                            )

                            FetchResult(
                                code = stock.code,
                                info = outcome.info,
                                fallbackUsed = outcome.fallbackUsed,
                                certificateFailure = outcome.certificateFailure
                            )
                        }
                    }
                }
            }.awaitAll()
        }

        val fallbackCount = results.count { it.fallbackUsed }
        val successCount = results.count { it.info != null }
        val certificateFailureCount = results.count { it.certificateFailure }

        results.forEach { r ->
            r.info?.let { updatedInfos[r.code] = it }
        }

        if (fallbackCount > 0) {
            val shouldNotifyRepeatedly = settingsDataStore.notifyFallbackRepeatedlyFlow.first()
            val shouldShowNotification = shouldNotifyRepeatedly || !hasNotifiedAboutFallback

            if (shouldShowNotification) {
                val message = when {
                    successCount == 0 -> "主要與備援來源皆無法取得資料"
                    fallbackCount == results.size -> "主要來源無法取得所有資料，全部改用備用來源"
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

        if (certificateFailureCount > 0 && !hasNotifiedAboutCertificateFailure) {
            postQuoteCertificateFailureToast()
            hasNotifiedAboutCertificateFailure = true
        }

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

    fun refreshStock(stockCode: String) {
        scope.launch {
            refreshStockInternal(stockCode)
        }
    }

    suspend fun refreshAllHeldStockInfo() {
        fetchAllStockInfo(
            isContinuous = false,
            forceSave = true,
            refreshRegardlessOfMarketOpen = true
        )
        refreshTaiwanWeightedIndex(refreshRegardlessOfMarketOpen = true)
    }

    suspend fun refreshStocks(stockCodes: Collection<String>) {
        val distinctCodes = stockCodes
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        if (distinctCodes.isEmpty()) return

        coroutineScope {
            distinctCodes.map { stockCode ->
                async(Dispatchers.IO) {
                    refreshStockInternal(stockCode)
                }
            }.awaitAll()
        }
    }

    private suspend fun refreshStockInternal(stockCode: String) {
        val stock = stockDao.getStockByCode(stockCode)
        val market = StockMarket.normalize(stock?.market ?: StockMarket.inferFromCode(stockCode))
        val outcome = fetchStockInfoForMarket(stockCode, market, stock?.stockType.orEmpty())

        if (outcome.certificateFailure && !hasNotifiedAboutCertificateFailure) {
            postQuoteCertificateFailureToast()
            hasNotifiedAboutCertificateFailure = true
        }

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

        val stock = stockDao.getStockByCode(stockCode)
        val market = StockMarket.normalize(stock?.market ?: StockMarket.inferFromCode(stockCode))
        return fetchStockInfoForMarket(stockCode, market, stock?.stockType.orEmpty()).info
    }

    private suspend fun fetchStockInfoForMarket(stockCode: String, market: String, stockType: String = ""): FetchOutcome {
        return if (StockMarket.isUs(market)) {
            val (primaryFetcher, secondaryFetcher) = getUsFetchers()
            Log.d(
                "RealtimeStockDataService",
                "Refreshing $stockCode using primary=${primaryFetcher.javaClass.simpleName}, secondary=${secondaryFetcher.javaClass.simpleName}"
            )
            fetchWithSingleFallback(
                stockCode = stockCode,
                primaryFetcher = primaryFetcher,
                secondaryFetcher = secondaryFetcher,
                stockType = stockType
            )
        } else {
            val (primaryFetcher, secondaryFetcher) = getTwFetchers()
            Log.d(
                "RealtimeStockDataService",
                "Refreshing $stockCode using primary=${primaryFetcher.javaClass.simpleName}, secondary=${secondaryFetcher.javaClass.simpleName}"
            )
            fetchWithSingleFallback(
                stockCode = stockCode,
                primaryFetcher = primaryFetcher,
                secondaryFetcher = secondaryFetcher,
                stockType = stockType
            )
        }
    }

    private fun getUsFetchers(): Pair<StockInfoFetcher, StockInfoFetcher> {
        return if (preferredUsStockDataSource.value == "Yahoo") {
            Pair(usYahooFetcher, usNasdaqFetcher)
        } else {
            Pair(usNasdaqFetcher, usYahooFetcher)
        }
    }

    private fun getUsPrimaryFetcher(): StockInfoFetcher {
        return if (preferredUsStockDataSource.value == "Yahoo") {
            usYahooFetcher
        } else {
            usNasdaqFetcher
        }
    }

    private suspend fun shouldRefreshMarket(market: String): Boolean {
        return when (StockMarket.normalize(market)) {
            StockMarket.US -> usNasdaqFetcher.isMarketOpen()
            else -> isTaiwanMarketOpen()
        }
    }

    private suspend fun isTaiwanMarketOpen(): Boolean {
        val taipeiZone = ZoneId.of("Asia/Taipei")
        val now = ZonedDateTime.now(taipeiZone)
        val date = now.toLocalDate()
        val time = now.toLocalTime()

        // 1. 非交易時間
        val inTime = time.isAfter(LocalTime.of(9, 0)) && time.isBefore(LocalTime.of(13, 30))
        if (!inTime) return false

        // 2. 檢查是否是假日（讀取 20XX.json）
        if (isTaiwanHoliday(date)) return false

        return true
    }

    private suspend fun refreshTaiwanWeightedIndex(
        refreshRegardlessOfMarketOpen: Boolean = false
    ) {
        if (!refreshRegardlessOfMarketOpen && !isTaiwanMarketOpen()) {
            Log.d(
                "RealtimeStockDataService",
                "Skipping Taiwan weighted index refresh because TW market is closed"
            )
            return
        }

        taiwanWeightedIndexService.refreshOnce(preferredStockDataSource.value)
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

    private fun normalizeUsStockDataSource(source: String): String {
        return when (source.trim().uppercase()) {
            "NASDAQ" -> "Nasdaq"
            "YAHOO" -> "Yahoo"
            else -> "Nasdaq"
        }
    }

    private suspend fun fetchWithSingleFallback(
        stockCode: String,
        primaryFetcher: StockInfoFetcher,
        secondaryFetcher: StockInfoFetcher,
        stockType: String = ""
    ): FetchOutcome {
        var primaryCertificateFailure = false
        val primaryInfo = try {
            primaryFetcher.fetchStockInfo(stockCode, stockType)
        } catch (e: CertificateValidationException) {
            primaryCertificateFailure = true
            Log.e(
                "RealtimeStockDataService",
                "Primary source certificate validation failed for $stockCode",
                e
            )
            null
        }
        if (primaryInfo != null) {
            return FetchOutcome(info = primaryInfo, fallbackUsed = false)
        }

        Log.e(
            "RealtimeStockDataService",
            "Primary source failed for $stockCode → fallback to secondary"
        )

        var secondaryCertificateFailure = false
        val secondaryInfo = try {
            secondaryFetcher.fetchStockInfo(stockCode, stockType)
        } catch (e: CertificateValidationException) {
            secondaryCertificateFailure = true
            Log.e(
                "RealtimeStockDataService",
                "Fallback source certificate validation failed for $stockCode",
                e
            )
            null
        }
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
            FetchOutcome(
                info = null,
                fallbackUsed = true,
                certificateFailure = primaryCertificateFailure || secondaryCertificateFailure
            )
        }
    }

    private fun postQuoteCertificateFailureToast() {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(
                applicationContext,
                "抓取報價憑證失效",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

}
