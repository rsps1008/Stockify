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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
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
internal const val MAX_PARALLEL_STOCK_REQUESTS = 3

internal suspend fun <T, R> mapWithStockRequestLimit(
    items: Iterable<T>,
    transform: suspend (T) -> R
): List<R> = coroutineScope {
    val semaphore = Semaphore(MAX_PARALLEL_STOCK_REQUESTS)
    items.map { item ->
        async(Dispatchers.IO) {
            semaphore.withPermit {
                transform(item)
            }
        }
    }.awaitAll()
}

private fun normalizeRealtimeCache(
    cachedData: Map<String, RealtimeStockInfo>,
    stocks: List<Stock>
): Map<String, RealtimeStockInfo> {
    val stocksByCode = stocks.associateBy { canonicalStockCode(it.code) }
    return cachedData.mapNotNull { (rawKey, info) ->
        val parts = rawKey.split(':', limit = 2)
        val key = if (parts.size == 2) {
            stockCacheKey(parts[0], parts[1])
        } else {
            val code = rawKey.trim()
            val stock = stocksByCode[canonicalStockCode(code)]
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
    private val quoteRefreshMutex = Mutex()

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

    private val client = HttpClient(CIO) {
        engine {
            requestTimeout = 5000
        }
    }

    private val holidayCache = TaiwanHolidayCache(loadYear = { year ->
        val response = client.get("https://cdn.jsdelivr.net/gh/ruyut/TaiwanCalendar/data/$year.json").body<String>()
        Json.decodeFromString<List<TaiwanHolidayItem>>(response).associate {
            LocalDate.parse(it.date, DateTimeFormatter.BASIC_ISO_DATE) to it.isHoliday
        }
    })

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

        settingsDataStore.fetchIntervalFlow.distinctUntilChanged().collectLatest { interval ->
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
        quoteRefreshMutex.withLock {
            try {
                fetchAllStockInfoInternal(
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
    }


    suspend fun fetchAllStockInfo(
        isContinuous: Boolean,
        forceSave: Boolean = false,
        refreshRegardlessOfMarketOpen: Boolean = false
    ) = quoteRefreshMutex.withLock {
        fetchAllStockInfoInternal(
            isContinuous = isContinuous,
            forceSave = forceSave,
            refreshRegardlessOfMarketOpen = refreshRegardlessOfMarketOpen
        )
    }

    private suspend fun fetchAllStockInfoInternal(
        isContinuous: Boolean,
        forceSave: Boolean = false,
        refreshRegardlessOfMarketOpen: Boolean = false
    ) {
        val openKeys = openPositionStockKeys.filterNotNull().first()
        val stocks = stockDao.getHeldStocks().first()
            .filter { it.toStockKey().cacheKey() in openKeys }
        if (stocks.isEmpty()) return

        val eligibleStocks = stocks.groupBy { StockMarket.normalize(it.market) }
            .flatMap { (market, marketStocks) ->
                if (refreshRegardlessOfMarketOpen || shouldRefreshMarket(market)) marketStocks else emptyList()
            }
        val results = fetchAndPublishQuotes(eligibleStocks)
        val fallbackCount = results.count { it.outcome.fallbackUsed }
        val successCount = results.count { it.outcome.info != null }
        val certificateFailureCount = results.count { it.outcome.certificateFailure }

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
            updates = emptyMap(),
            isContinuous = isContinuous,
            forceSave = forceSave
        )
    }

    fun refreshStock(stockCode: String, market: String = StockMarket.inferFromCode(stockCode)) {
        scope.launch {
            quoteRefreshMutex.withLock {
                refreshStockInternal(stockCode, market)
            }
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
        quoteRefreshMutex.withLock {
            refreshStocksInternal(stockKeys)
        }
    }

    private suspend fun refreshStocksInternal(stockKeys: Collection<StockKey>) {
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
        val results = fetchAndPublishQuotes(stocks)
        if (results.any { it.outcome.certificateFailure }) {
            notifyCertificateFailureIfNeeded()
        }
        mergeRealtimeStockInfo(updates = emptyMap(), saveAlways = true)
    }

    private suspend fun fetchAndPublishQuotes(stocks: List<Stock>): List<QuoteFetchResult> =
        refreshQuotesIncrementally(
            stocks = stocks,
            useTwseBatches = preferredStockDataSource.value == "TWSE",
            fetchBatch = ::fetchTwseBatchSafely,
            fetchSingle = { stock ->
                fetchStockInfoForMarket(stock.code, StockMarket.normalize(stock.market),
                    StockExchange.normalize(stock.exchange), stock.stockType)
            },
            fetchTaiwanFallback = { stock -> fetchWithFetcher(yahooFetcher, stock.code, stock.stockType) },
            publish = { updates -> mergeRealtimeStockInfo(updates) }
        )

    private suspend fun fetchTwseBatchSafely(stocks: List<Stock>): QuoteBatchOutcome {
        return try {
            QuoteBatchOutcome(
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
            QuoteBatchOutcome(emptyMap(), certificateFailure = true)
        } catch (e: Exception) {
            Log.e(
                "RealtimeStockDataService",
                "TWSE batch failed; falling back per stock",
                e
            )
            QuoteBatchOutcome(emptyMap(), certificateFailure = false)
        }
    }

    private suspend fun refreshStockInternal(stockCode: String, requestedMarket: String) {
        val normalizedCode = canonicalStockCode(stockCode)
        val market = StockMarket.normalize(requestedMarket)
        val stock = stockDao.getStockByCode(normalizedCode, market)
        val outcome = fetchStockInfoForMarket(
            normalizedCode,
            market,
            StockExchange.normalize(stock?.exchange),
            stock?.stockType.orEmpty()
        )

        if (outcome.certificateFailure) {
            notifyCertificateFailureIfNeeded()
        }

        outcome.info?.let {
            mergeRealtimeStockInfo(
                updates = mapOf(stockCacheKey(market, normalizedCode) to it),
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
            val mergedInfos = if (updates.isEmpty()) _realtimeStockInfo.value
                else mergeRealtimeStockInfoMaps(_realtimeStockInfo.value, updates)
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
        market: String = StockMarket.inferFromCode(stockCode),
        forceRefresh: Boolean = false
    ): RealtimeStockInfo? {
        val key = stockCacheKey(market, stockCode)
        if (!forceRefresh) _realtimeStockInfo.value[key]?.let { return it }
        return quoteRefreshMutex.withLock {
            val normalizedCode = canonicalStockCode(stockCode)
            val normalizedMarket = StockMarket.normalize(market)
            val cached = _realtimeStockInfo.value[stockCacheKey(normalizedMarket, normalizedCode)]
            if (!forceRefresh && cached != null) {
                return@withLock cached
            }

            val stock = stockDao.getStockByCode(normalizedCode, normalizedMarket)
            val resolvedMarket = StockMarket.normalize(stock?.market ?: normalizedMarket)
            fetchStockInfoForMarket(
                normalizedCode,
                resolvedMarket,
                StockExchange.normalize(stock?.exchange),
                stock?.stockType.orEmpty()
            ).info
        }
    }

    private suspend fun fetchStockInfoForMarket(
        stockCode: String,
        market: String,
        exchange: String = StockExchange.UNKNOWN,
        stockType: String = ""
    ): QuoteFetchOutcome {
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
        if (holidayCache.isHoliday(date)) return false

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
    ): QuoteFetchOutcome {
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
            return QuoteFetchOutcome(info = primaryInfo, fallbackUsed = false)
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
            QuoteFetchOutcome(info = secondaryInfo, fallbackUsed = true)
        } else {
            Log.e(
                "RealtimeStockDataService",
                "Fallback also failed for $stockCode → no data"
            )
            QuoteFetchOutcome(
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
    ): QuoteFetchOutcome {
        return try {
            QuoteFetchOutcome(fetcher.fetchStockInfo(stockCode, stockType), fallbackUsed = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: CertificateValidationException) {
            Log.e("RealtimeStockDataService", "Source certificate validation failed for $stockCode", e)
            QuoteFetchOutcome(info = null, fallbackUsed = false, certificateFailure = true)
        } catch (e: Exception) {
            Log.e("RealtimeStockDataService", "Source failed unexpectedly for $stockCode", e)
            QuoteFetchOutcome(info = null, fallbackUsed = false)
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
