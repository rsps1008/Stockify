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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import com.rsps1008.stockify.data.StockMarket

internal fun mergeRealtimeStockInfoMaps(
    current: Map<String, RealtimeStockInfo>,
    updates: Map<String, RealtimeStockInfo>
): Map<String, RealtimeStockInfo> {
    return current.toMutableMap().apply { putAll(updates) }.toMap()
}

private const val STOCK_LOOKUP_CHUNK_SIZE = 500

private fun normalizeRealtimeCache(
    cachedData: Map<String, RealtimeStockInfo>,
    stocks: List<Stock>
): Map<String, RealtimeStockInfo> {
    val stocksByCode = stocks.associateBy { it.code.trim().uppercase() }
    return cachedData.mapNotNull { (rawKey, info) ->
        val parts = rawKey.split(':', limit = 2)
        val key = if (parts.size == 2) {
            stockCacheKey(parts[0], parts[1])
        } else {
            val code = rawKey.trim()
            val stock = stocksByCode[code.uppercase()]
            stock?.toStockKey()?.cacheKey() ?: stockCacheKey(StockMarket.inferFromCode(code), code)
        }
        key to info
    }.toMap()
}

class RealtimeStockDataService(
    private val stockDao: StockDao,
    private val settingsDataStore: SettingsDataStore,
    private val taiwanWeightedIndexService: TaiwanWeightedIndexService,
    private val applicationContext: Context,
) {
    private val _realtimeStockInfo = MutableStateFlow<Map<String, RealtimeStockInfo>>(emptyMap())
    val realtimeStockInfo: StateFlow<Map<String, RealtimeStockInfo>> = _realtimeStockInfo.asStateFlow()
    private val realtimeInfoMutex = Mutex()

    private var fetchJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private val valuationDateFlow = flow {
        while (currentCoroutineContext().isActive) {
            emit(LocalDate.now(ZoneId.systemDefault()))
            delay(60_000L)
        }
    }.distinctUntilChanged()
    private val openPositionStockKeys: StateFlow<Set<String>?> = combine(
        stockDao.getAllTransactions(),
        valuationDateFlow
    ) { transactions, _ ->
        openStockKeysAt(transactions, System.currentTimeMillis())
    }
        .stateIn(scope, SharingStarted.Eagerly, null)
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
        val key: String,
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

    private data class TwseBatchFetchOutcome(
        val infos: Map<String, RealtimeStockInfo>,
        val certificateFailure: Boolean
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
            while (isActive) {
                try {
                    startFetchingLoop()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(
                        "RealtimeStockDataService",
                        "Background quote loop failed; retrying later",
                        e
                    )
                    delay(30_000L)
                }
            }
        }
    }

    private suspend fun startFetchingLoop() {
        val cachedData = settingsDataStore.realtimeStockInfoCacheFlow.first()
        if (cachedData.isNotEmpty()) {
            realtimeInfoMutex.withLock {
                if (_realtimeStockInfo.value.isEmpty()) {
                    val heldStocks = stockDao.getHeldStocks().first()
                    _realtimeStockInfo.value = normalizeRealtimeCache(cachedData, heldStocks)
                }
            }
        }

        // App 啟動時先強制抓一次最新資料，避免只看到過期快取。
        refreshQuotesSafely(
            isContinuous = false,
            refreshRegardlessOfMarketOpen = true
        )

        settingsDataStore.fetchIntervalFlow.collectLatest { interval ->
            while (currentCoroutineContext().isActive) {
                if (!isAnyMarketOpen()) {
                    delay(30_000L)
                    continue
                }
                refreshQuotesSafely(isContinuous = true)
                delay(delayUntilNextAlignedFetch(interval))
            }
        }
    }

    private suspend fun refreshQuotesSafely(
        isContinuous: Boolean,
        forceSave: Boolean = false,
        refreshRegardlessOfMarketOpen: Boolean = false
    ) {
        try {
            fetchAllStockInfo(
                isContinuous = isContinuous,
                forceSave = forceSave,
                refreshRegardlessOfMarketOpen = refreshRegardlessOfMarketOpen
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("RealtimeStockDataService", "Quote refresh failed; keeping previous data", e)
        }

        try {
            refreshTaiwanWeightedIndex(refreshRegardlessOfMarketOpen = refreshRegardlessOfMarketOpen)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("RealtimeStockDataService", "Taiwan weighted index refresh failed", e)
        }
    }


    suspend fun fetchAllStockInfo(
        isContinuous: Boolean,
        forceSave: Boolean = false,
        refreshRegardlessOfMarketOpen: Boolean = false
    ) {
        val openKeys = openPositionStockKeys.filterNotNull().first()
        val stocks = stockDao.getHeldStocks().first()
            .filter { it.toStockKey().cacheKey() in openKeys }
        if (stocks.isEmpty()) return

        val stockGroups = stocks.groupBy {
            StockMarket.normalize(it.market) to StockExchange.normalize(it.exchange)
        }

        val results = coroutineScope {
            stockGroups.flatMap { (group, marketStocks) ->
                val market = group.first
                val exchange = group.second
                if (!refreshRegardlessOfMarketOpen && !shouldRefreshMarket(market)) {
                    Log.d(
                        "RealtimeStockDataService",
                        "Skipping $market realtime stock info because market is closed"
                    )
                    emptyList()
                } else {
                    Log.d(
                        "RealtimeStockDataService",
                        "Fetching $market/$exchange realtime stock info"
                    )

                    if (StockMarket.isTw(market) &&
                        !StockExchange.isEmerging(exchange) &&
                        preferredStockDataSource.value == "TWSE"
                    ) {
                        val batchOutcome = fetchTwseBatchSafely(marketStocks)
                        val twseInfos = batchOutcome.infos
                        marketStocks.map { stock ->
                            async(Dispatchers.IO) {
                                val twseInfo = twseInfos[stock.code]
                                val outcome = if (twseInfo != null) {
                                    FetchOutcome(info = twseInfo, fallbackUsed = false)
                                } else {
                                    val fallback = fetchWithFetcher(yahooFetcher, stock.code, stock.stockType)
                                    fallback.copy(
                                        fallbackUsed = true,
                                        certificateFailure = fallback.certificateFailure || batchOutcome.certificateFailure
                                    )
                                }
                                FetchResult(
                                    key = stock.toStockKey().cacheKey(),
                                    code = stock.code,
                                    info = outcome.info,
                                    fallbackUsed = outcome.fallbackUsed,
                                    certificateFailure = outcome.certificateFailure
                                )
                            }
                        }
                    } else {
                        marketStocks.map { stock ->
                            async(Dispatchers.IO) {
                                val outcome = fetchStockInfoForMarket(
                                    stockCode = stock.code,
                                    market = market,
                                    exchange = exchange,
                                    stockType = stock.stockType
                                )
                                FetchResult(
                                    key = stock.toStockKey().cacheKey(),
                                    code = stock.code,
                                    info = outcome.info,
                                    fallbackUsed = outcome.fallbackUsed,
                                    certificateFailure = outcome.certificateFailure
                                )
                            }
                        }
                    }
                }
            }.awaitAll()
        }

        val fallbackCount = results.count { it.fallbackUsed }
        val successCount = results.count { it.info != null }
        val certificateFailureCount = results.count { it.certificateFailure }

        val fetchedInfos = results.mapNotNull { result ->
            result.info?.let { result.key to it }
        }.toMap()

        if (fallbackCount > 0) {
            val fallbackNoticeEnabled = settingsDataStore.fallbackNoticeEnabledFlow.first()
            val shouldShowNotification = fallbackNoticeEnabled && !hasNotifiedAboutFallback

            if (shouldShowNotification) {
                val message = when {
                    successCount == 0 -> "主要與備援來源皆無法取得資料"
                    fallbackCount == results.size -> "主要來源無法取得所有資料，全部改用備用來源"
                    else -> "部分股票主要來源異常，部分改用備用來源"
                }

                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
                }

                hasNotifiedAboutFallback = true
            }
        }

        if (certificateFailureCount > 0) {
            notifyCertificateFailureIfNeeded()
        }

        mergeRealtimeStockInfo(
            updates = fetchedInfos,
            isContinuous = isContinuous,
            forceSave = forceSave
        )
    }

    fun refreshStock(stockCode: String, market: String = StockMarket.inferFromCode(stockCode)) {
        scope.launch {
            refreshStockInternal(stockCode, market)
        }
    }

    suspend fun refreshAllHeldStockInfo() {
        refreshQuotesSafely(
            isContinuous = false,
            forceSave = true,
            refreshRegardlessOfMarketOpen = true
        )
    }

    suspend fun refreshStocks(stockKeys: Collection<StockKey>) {
        val distinctKeys = stockKeys
            .map { StockKey(StockMarket.normalize(it.market), it.normalizedCode) }
            .filter { it.code.isNotEmpty() }
            .distinctBy { it.cacheKey() }

        if (distinctKeys.isEmpty()) return

        val stocks = distinctKeys
            .groupBy { StockMarket.normalize(it.market) }
            .flatMap { (market, keys) ->
                keys.map { it.code }
                    .chunked(STOCK_LOOKUP_CHUNK_SIZE)
                    .flatMap { codes -> stockDao.getStocksByMarketAndCodes(market, codes) }
            }
        val batchableTaiwanStocks = stocks.filter {
            StockMarket.isTw(it.market) && !StockExchange.isEmerging(it.exchange)
        }
        val fetchedInfos = mutableMapOf<String, RealtimeStockInfo>()
        var certificateFailure = false

        if (batchableTaiwanStocks.isNotEmpty() && preferredStockDataSource.value == "TWSE") {
            val batchOutcome = fetchTwseBatchSafely(batchableTaiwanStocks)
            val twseInfos = batchOutcome.infos
            batchableTaiwanStocks.forEach { stock ->
                twseInfos[stock.code]?.let { fetchedInfos[stock.toStockKey().cacheKey()] = it }
            }
            certificateFailure = batchOutcome.certificateFailure

            val missingStocks = batchableTaiwanStocks.filter { it.code !in twseInfos }
            coroutineScope {
                missingStocks.map { stock ->
                    async(Dispatchers.IO) {
                        stock.toStockKey().cacheKey() to fetchWithFetcher(yahooFetcher, stock.code, stock.stockType)
                    }
                }.awaitAll().forEach { (key, outcome) ->
                    certificateFailure = certificateFailure || outcome.certificateFailure
                    outcome.info?.let { fetchedInfos[key] = it }
                }
            }
        }

        val remainingStocks = stocks.filterNot { it in batchableTaiwanStocks }
        coroutineScope {
            remainingStocks.map { stock ->
                async(Dispatchers.IO) {
                    stock.toStockKey().cacheKey() to fetchStockInfoForMarket(
                        stock.code,
                        StockMarket.normalize(stock.market),
                        StockExchange.normalize(stock.exchange),
                        stock.stockType
                    )
                }
            }.awaitAll().forEach { (key, outcome) ->
                certificateFailure = certificateFailure || outcome.certificateFailure
                outcome.info?.let { fetchedInfos[key] = it }
            }
        }

        if (certificateFailure) {
            notifyCertificateFailureIfNeeded()
        }

        mergeRealtimeStockInfo(updates = fetchedInfos, saveAlways = true)
    }

    private suspend fun fetchTwseBatchSafely(stocks: List<Stock>): TwseBatchFetchOutcome {
        return try {
            TwseBatchFetchOutcome(
                infos = twseFetcher.fetchStockInfoListByExchange(stocks),
                certificateFailure = false
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: CertificateValidationException) {
            Log.e(
                "RealtimeStockDataService",
                "TWSE batch certificate validation failed; falling back per stock",
                e
            )
            TwseBatchFetchOutcome(emptyMap(), certificateFailure = true)
        } catch (e: Exception) {
            Log.e(
                "RealtimeStockDataService",
                "TWSE batch failed; falling back per stock",
                e
            )
            TwseBatchFetchOutcome(emptyMap(), certificateFailure = false)
        }
    }

    private suspend fun refreshStockInternal(stockCode: String, requestedMarket: String) {
        val market = StockMarket.normalize(requestedMarket)
        val stock = stockDao.getStockByCode(stockCode, market)
        val outcome = fetchStockInfoForMarket(
            stockCode,
            market,
            StockExchange.normalize(stock?.exchange),
            stock?.stockType.orEmpty()
        )

        if (outcome.certificateFailure) {
            notifyCertificateFailureIfNeeded()
        }

        outcome.info?.let {
            mergeRealtimeStockInfo(
                updates = mapOf(stockCacheKey(market, stockCode) to it),
                saveAlways = true
            )
        }
    }

    private suspend fun mergeRealtimeStockInfo(
        updates: Map<String, RealtimeStockInfo>,
        isContinuous: Boolean? = null,
        forceSave: Boolean = false,
        saveAlways: Boolean = false
    ) {
        realtimeInfoMutex.withLock {
            val mergedInfos = mergeRealtimeStockInfoMaps(_realtimeStockInfo.value, updates)
            _realtimeStockInfo.value = mergedInfos

            val shouldSave = when {
                saveAlways || isContinuous == false -> true
                isContinuous == true -> {
                    fetchCount++
                    fetchCount >= 10 || forceSave
                }
                else -> false
            }
            if (shouldSave) {
                settingsDataStore.setRealtimeStockInfoCache(mergedInfos)
                if (isContinuous == true) {
                    fetchCount = 0
                }
            }
        }
    }

    suspend fun fetchCurrentStockInfo(
        stockCode: String,
        market: String = StockMarket.inferFromCode(stockCode)
    ): RealtimeStockInfo? {
        val normalizedMarket = StockMarket.normalize(market)
        val cached = _realtimeStockInfo.value[stockCacheKey(normalizedMarket, stockCode)]
        if (cached != null) {
            return cached
        }

        val stock = stockDao.getStockByCode(stockCode, normalizedMarket)
        val resolvedMarket = StockMarket.normalize(stock?.market ?: normalizedMarket)
        return fetchStockInfoForMarket(
            stockCode,
            resolvedMarket,
            StockExchange.normalize(stock?.exchange),
            stock?.stockType.orEmpty()
        ).info
    }

    private suspend fun fetchStockInfoForMarket(
        stockCode: String,
        market: String,
        exchange: String = StockExchange.UNKNOWN,
        stockType: String = ""
    ): FetchOutcome {
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
        } else if (StockExchange.isEmerging(exchange)) {
            Log.d("RealtimeStockDataService", "Refreshing $stockCode as emerging stock using Yahoo only")
            fetchWithFetcher(yahooFetcher, stockCode, stockType)
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: CertificateValidationException) {
            primaryCertificateFailure = true
            Log.e(
                "RealtimeStockDataService",
                "Primary source certificate validation failed for $stockCode",
                e
            )
            null
        } catch (e: Exception) {
            Log.e(
                "RealtimeStockDataService",
                "Primary source failed unexpectedly for $stockCode",
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: CertificateValidationException) {
            secondaryCertificateFailure = true
            Log.e(
                "RealtimeStockDataService",
                "Fallback source certificate validation failed for $stockCode",
                e
            )
            null
        } catch (e: Exception) {
            Log.e(
                "RealtimeStockDataService",
                "Fallback source failed unexpectedly for $stockCode",
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

    private suspend fun fetchWithFetcher(
        fetcher: StockInfoFetcher,
        stockCode: String,
        stockType: String
    ): FetchOutcome {
        return try {
            FetchOutcome(fetcher.fetchStockInfo(stockCode, stockType), fallbackUsed = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: CertificateValidationException) {
            Log.e("RealtimeStockDataService", "Source certificate validation failed for $stockCode", e)
            FetchOutcome(info = null, fallbackUsed = false, certificateFailure = true)
        } catch (e: Exception) {
            Log.e("RealtimeStockDataService", "Source failed unexpectedly for $stockCode", e)
            FetchOutcome(info = null, fallbackUsed = false)
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

    private fun notifyCertificateFailureIfNeeded() {
        if (!hasNotifiedAboutCertificateFailure) {
            postQuoteCertificateFailureToast()
            hasNotifiedAboutCertificateFailure = true
        }
    }

}
