package com.rsps1008.stockify.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rsps1008.stockify.data.SettingsDataStore
import com.rsps1008.stockify.data.StockRepository
import com.rsps1008.stockify.data.StockMarket
import com.rsps1008.stockify.data.Stock
import com.rsps1008.stockify.data.StockTransaction
import com.rsps1008.stockify.data.TransactionListSnapshot
import com.rsps1008.stockify.data.TransactionListRepository
import com.rsps1008.stockify.data.stockCacheKey
import com.rsps1008.stockify.data.toStockKey
import com.rsps1008.stockify.data.dividend.DividendInfoCacheEntry
import com.rsps1008.stockify.data.dividend.YahooDividendRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DividendItemUiState(
    val stockCode: String,
    val stockName: String,
    val cashDividend: Double? = null,
    val cashDividendDate: String? = null,
    val stockDividend: Double? = null,
    val stockDividendDate: String? = null,
    val lastLocalCashDividend: Double? = null,
    val lastLocalCashDividendDate: String? = null,
    val lastLocalStockDividend: Double? = null,
    val lastLocalStockDividendDate: String? = null,
    val isLoading: Boolean = true,
    val errorMessage: String? = null
)

internal data class LocalDividendSummary(
    val lastCashDividend: Double? = null,
    val lastCashDividendDate: String? = null,
    val lastStockDividend: Double? = null,
    val lastStockDividendDate: String? = null
)

internal data class TransactionRevisionKey(
    val id: Int,
    val stockCode: String,
    val date: Long,
    val recordTime: Long,
    val type: String,
    val cashDividend: Double?,
    val stockDividend: Double?
)

internal fun buildTransactionRevisionSignature(
    transactions: List<StockTransaction>,
    taiwanStockKeys: Set<String>
): List<TransactionRevisionKey> = transactions
    .filter { it.toStockKey().cacheKey() in taiwanStockKeys }
    .map {
        TransactionRevisionKey(
            id = it.id,
            stockCode = it.stockCode,
            date = it.date,
            recordTime = it.recordTime,
            type = it.type,
            cashDividend = it.cashDividend,
            stockDividend = it.stockDividend
        )
    }

internal fun getDividendFetchDateString(timeMillis: Long = System.currentTimeMillis()): String {
    val sdf = SimpleDateFormat("yyyy/MM/dd", Locale.US)
    return sdf.format(Date(timeMillis))
}

internal data class TaiwanStockRef(
    val stockCode: String,
    val stockName: String
)

internal fun buildTaiwanStockRefs(
    snapshot: TransactionListSnapshot,
    accountId: Int,
    valuationDateMillis: Long
): List<TaiwanStockRef> {
    val activeTransactions = if (accountId == 0) {
        snapshot.transactions
    } else {
        snapshot.transactions.filter { it.accountId == accountId }
    }
    val activeStockKeys = activeTransactions
        .asSequence()
        .filter { it.date <= valuationDateMillis }
        .map { it.toStockKey().cacheKey() }
        .toSet()

    return snapshot.stocks
        .asSequence()
        .filter { stock ->
            StockMarket.isTw(stock.market) && stock.toStockKey().cacheKey() in activeStockKeys
        }
        .map { stock -> TaiwanStockRef(stock.code, stock.name) }
        .distinctBy { it.stockCode }
        .toList()
}

class DividendInfoViewModel(
    private val stockRepository: StockRepository,
    private val transactionListRepository: TransactionListRepository,
    private val dividendRepository: YahooDividendRepository,
    private val settingsDataStore: SettingsDataStore,
    private val currentDateProvider: () -> String = { getDividendFetchDateString() }
) : ViewModel() {

    private val _dividendList = MutableStateFlow<List<DividendItemUiState>>(emptyList())
    val dividendList: StateFlow<List<DividendItemUiState>> = _dividendList.asStateFlow()

    private var latestTaiwanStocks: List<TaiwanStockRef> = emptyList()
    private var latestAccountId: Int = 0
    private var currentGeneration: Long = 0L
    private var refreshJob: Job? = null
    private val yahooRequestSemaphore = Semaphore(permits = 3)
    private val cacheCommitMutex = Mutex()
    private val latestCommittedGenerations = mutableMapOf<String, Long>()

    init {
        observeHoldingsAndRefresh()
    }

    private fun observeHoldingsAndRefresh() {
        viewModelScope.launch {
            combine(
                transactionListRepository.snapshot,
                settingsDataStore.activeAccountIdFlow.distinctUntilChanged(),
            ) { snapshot, accountId ->
                val stocks = buildTaiwanStockRefs(
                    snapshot = snapshot,
                    accountId = accountId,
                    valuationDateMillis = System.currentTimeMillis()
                )
                val activeTransactions = if (accountId == 0) {
                    snapshot.transactions
                } else {
                    snapshot.transactions.filter { it.accountId == accountId }
                }
                val taiwanStockKeys = stocks
                    .map { stockCacheKey(StockMarket.TW, it.stockCode) }
                    .toSet()
                val revisionSignature = buildTransactionRevisionSignature(
                    activeTransactions,
                    taiwanStockKeys
                )
                DividendRefreshScope(stocks, accountId, revisionSignature)
            }
                .distinctUntilChanged()
                .collect { scope ->
                    latestTaiwanStocks = scope.stocks
                    latestAccountId = scope.accountId
                    refreshJob?.cancel()

                    // Invalidate the previous account/transaction snapshot immediately;
                    // the next load will repopulate it from the current Room and cache data.
                    _dividendList.value = scope.stocks.map { stock ->
                        DividendItemUiState(
                            stockCode = stock.stockCode,
                            stockName = stock.stockName
                        )
                    }

                    if (scope.stocks.isEmpty()) {
                        ++currentGeneration
                        return@collect
                    }

                    val generation = ++currentGeneration
                    refreshJob = viewModelScope.launch {
                        loadAndRefreshScope(scope.stocks, scope.accountId, generation)
                    }
                }
        }
    }

    private suspend fun fetchLocalDividends(stockCode: String, accountId: Int): Result<LocalDividendSummary> {
        return try {
            val transactions = stockRepository.getTransactionsForStock(stockCode, accountId).first()
            val lastCashTx = transactions
                .filter { it.transaction.type == "配息" }
                .maxByOrNull { it.transaction.date }
            val lastStockTx = transactions
                .filter { it.transaction.type == "配股" }
                .maxByOrNull { it.transaction.date }

            val sdf = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault())
            val localCashDate = lastCashTx?.transaction?.date?.let { sdf.format(Date(it)) }
            val localStockDate = lastStockTx?.transaction?.date?.let { sdf.format(Date(it)) }

            Result.success(
                LocalDividendSummary(
                    lastCashDividend = lastCashTx?.transaction?.cashDividend,
                    lastCashDividendDate = localCashDate,
                    lastStockDividend = lastStockTx?.transaction?.stockDividend,
                    lastStockDividendDate = localStockDate
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun loadAndRefreshScope(
        stocks: List<TaiwanStockRef>,
        accountId: Int,
        generation: Long
    ) {
        try {
            val today = currentDateProvider()
            val cache = settingsDataStore.dividendInfoCacheFlow.first()

            if (generation != currentGeneration || accountId != latestAccountId) return

            // 1. Query latest local Room dividend transactions for each stock in parallel with error isolation
            val localDividends = coroutineScope {
                stocks.map { stock ->
                    async { stock.stockCode to fetchLocalDividends(stock.stockCode, accountId) }
                }.awaitAll().toMap()
            }

            if (generation != currentGeneration || accountId != latestAccountId) return

            // 2. Build initial UI items with fresh local Room data + cached Yahoo market data
            val items = stocks.map { stock ->
                val cached = cache[stock.stockCode]
                val localResult = localDividends[stock.stockCode]
                val local = localResult?.getOrNull()
                val localError = localResult?.exceptionOrNull()?.message
                val isYahooFreshToday = cached?.lastFetchedDate == today
                DividendItemUiState(
                    stockCode = stock.stockCode,
                    stockName = stock.stockName,
                    cashDividend = cached?.cashDividend,
                    cashDividendDate = cached?.cashDividendDate,
                    stockDividend = cached?.stockDividend,
                    stockDividendDate = cached?.stockDividendDate,
                    lastLocalCashDividend = local?.lastCashDividend,
                    lastLocalCashDividendDate = local?.lastCashDividendDate,
                    lastLocalStockDividend = local?.lastStockDividend,
                    lastLocalStockDividendDate = local?.lastStockDividendDate,
                    isLoading = !isYahooFreshToday,
                    errorMessage = localError
                )
            }
            _dividendList.value = sortItems(items)

            // 3. Only trigger Yahoo network fetch for stocks that haven't been fetched today
            val stocksToRefresh = stocks.filter { stock ->
                cache[stock.stockCode]?.lastFetchedDate != today
            }

            if (stocksToRefresh.isNotEmpty()) {
                coroutineScope {
                    stocksToRefresh.forEach { stock ->
                        launch {
                            fetchDividendForStock(stock, accountId, generation)
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (generation != currentGeneration || accountId != latestAccountId) return
            _dividendList.update { list ->
                if (generation != currentGeneration || accountId != latestAccountId) return@update list
                list.map { it.copy(isLoading = false, errorMessage = it.errorMessage ?: e.message) }
            }
        }
    }

    private suspend fun fetchDividendForStock(
        stock: TaiwanStockRef,
        accountId: Int,
        generation: Long
    ) {
        val localResult = fetchLocalDividends(stock.stockCode, accountId)
        val local = localResult.getOrNull()
        try {
            if (generation != currentGeneration || accountId != latestAccountId) return

            // Record strictly monotonic request sequence BEFORE issuing network request
            val requestSequence = SettingsDataStore.nextSequence()
            val requestStartTime = System.currentTimeMillis()

            // Yahoo Dividends (Network)
            val yahooDividends = yahooRequestSemaphore.withPermit {
                dividendRepository.fetchLatestDividends(stock.stockCode)
            }
            val cashResult = yahooDividends.cashDividend
            val stockResult = yahooDividends.stockDividend
            val today = currentDateProvider()

            // Atomically guard and serialize cache commit
            cacheCommitMutex.withLock {
                val lastCommitted = latestCommittedGenerations[stock.stockCode] ?: 0L
                if (generation >= lastCommitted && generation == currentGeneration) {
                    latestCommittedGenerations[stock.stockCode] = generation
                    settingsDataStore.setDividendInfoCacheEntry(
                        stockCode = stock.stockCode,
                        entry = DividendInfoCacheEntry(
                            cashDividend = cashResult?.amount,
                            cashDividendDate = cashResult?.date,
                            stockDividend = stockResult?.amount,
                            stockDividendDate = stockResult?.date,
                            lastFetchedDate = today,
                            lastFetchedTimeMillis = requestStartTime,
                            requestSequence = requestSequence
                        )
                    )
                }
            }

            if (generation != currentGeneration || accountId != latestAccountId) return

            _dividendList.update { list ->
                if (generation != currentGeneration || accountId != latestAccountId) return@update list
                sortItems(
                    list.map { item ->
                        if (item.stockCode == stock.stockCode) {
                            item.copy(
                                cashDividend = cashResult?.amount,
                                cashDividendDate = cashResult?.date,
                                stockDividend = stockResult?.amount,
                                stockDividendDate = stockResult?.date,
                                lastLocalCashDividend = local?.lastCashDividend,
                                lastLocalCashDividendDate = local?.lastCashDividendDate,
                                lastLocalStockDividend = local?.lastStockDividend,
                                lastLocalStockDividendDate = local?.lastStockDividendDate,
                                isLoading = false,
                                errorMessage = localResult.exceptionOrNull()?.message
                            )
                        } else {
                            item
                        }
                    }
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (generation != currentGeneration || accountId != latestAccountId) return
            _dividendList.update { list ->
                if (generation != currentGeneration || accountId != latestAccountId) return@update list
                list.map { item ->
                    if (item.stockCode == stock.stockCode) {
                        item.copy(
                            lastLocalCashDividend = local?.lastCashDividend,
                            lastLocalCashDividendDate = local?.lastCashDividendDate,
                            lastLocalStockDividend = local?.lastStockDividend,
                            lastLocalStockDividendDate = local?.lastStockDividendDate,
                            isLoading = false,
                            errorMessage = e.message
                        )
                    } else {
                        item
                    }
                }
            }
        }
    }

    fun refresh() {
        val stocks = latestTaiwanStocks
        val accountId = latestAccountId
        if (stocks.isEmpty()) return

        refreshJob?.cancel()
        val generation = ++currentGeneration

        _dividendList.update { items ->
            items.map { it.copy(isLoading = true, errorMessage = null) }
        }

        refreshJob = viewModelScope.launch {
            // Manual refresh bypasses loadAndRefreshScope(), so explicitly read the
            // persisted cache first to advance the request sequence after restart
            // or a system-clock rollback.
            try {
                settingsDataStore.dividendInfoCacheFlow.first()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Preserve the existing manual-refresh behavior if cache reading fails;
                // the individual network/cache operation will report its own failure.
            }

            if (generation != currentGeneration || accountId != latestAccountId) return@launch

            coroutineScope {
                stocks.forEach { stock ->
                    launch {
                        fetchDividendForStock(stock, accountId, generation)
                    }
                }
            }
        }
    }

    private fun getLatestDate(item: DividendItemUiState): String? {
        val dates = listOfNotNull(
            item.cashDividendDate,
            item.stockDividendDate,
            item.lastLocalCashDividendDate,
            item.lastLocalStockDividendDate
        ).filter { it.isNotBlank() && it != "-" }
        return dates.maxOrNull()
    }

    private fun sortItems(items: List<DividendItemUiState>): List<DividendItemUiState> {
        return items.sortedWith { a, b ->
            val dateA = getLatestDate(a)
            val dateB = getLatestDate(b)
            when {
                dateA == null && dateB == null -> 0
                dateA == null -> 1
                dateB == null -> -1
                else -> dateB.compareTo(dateA)
            }
        }
    }

    private data class DividendRefreshScope(
        val stocks: List<TaiwanStockRef>,
        val accountId: Int,
        val revisionSignature: List<TransactionRevisionKey>
    )
}
