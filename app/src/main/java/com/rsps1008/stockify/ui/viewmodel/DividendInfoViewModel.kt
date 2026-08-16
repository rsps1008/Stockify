package com.rsps1008.stockify.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rsps1008.stockify.data.SettingsDataStore
import com.rsps1008.stockify.data.StockRepository
import com.rsps1008.stockify.data.StockMarket
import com.rsps1008.stockify.data.dividend.DividendInfoCacheEntry
import com.rsps1008.stockify.data.dividend.YahooDividendRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
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

internal fun getDividendFetchDateString(timeMillis: Long = System.currentTimeMillis()): String {
    val sdf = SimpleDateFormat("yyyy/MM/dd", Locale.US)
    return sdf.format(Date(timeMillis))
}

class DividendInfoViewModel(
    private val stockRepository: StockRepository,
    private val dividendRepository: YahooDividendRepository,
    private val settingsDataStore: SettingsDataStore,
    private val currentDateProvider: () -> String = { getDividendFetchDateString() }
) : ViewModel() {

    private val _dividendList = MutableStateFlow<List<DividendItemUiState>>(emptyList())
    val dividendList: StateFlow<List<DividendItemUiState>> = _dividendList.asStateFlow()

    private var latestTaiwanStocks: List<TaiwanStockRef> = emptyList()
    private var latestAccountId: Int = 0
    private var refreshJob: Job? = null
    private val yahooRequestSemaphore = Semaphore(permits = 3)

    init {
        observeHoldingsAndRefresh()
    }

    private fun observeHoldingsAndRefresh() {
        viewModelScope.launch {
            combine(
                stockRepository.getHoldings().map { holdingsState ->
                    holdingsState.holdings
                        .filter { holding -> StockMarket.isTw(holding.stock.market) }
                        .map { holding -> TaiwanStockRef(holding.stock.code, holding.stock.name) }
                        .distinctBy { it.stockCode }
                },
                settingsDataStore.activeAccountIdFlow
            ) { stocks, accountId ->
                DividendRefreshScope(stocks, accountId)
            }
                .distinctUntilChanged()
                .collect { scope ->
                    val stocks = scope.stocks
                    latestTaiwanStocks = stocks
                    latestAccountId = scope.accountId
                    refreshJob?.cancel()

                    if (stocks.isEmpty()) {
                        _dividendList.value = emptyList()
                        return@collect
                    }

                    showCachedResultsAndAutoRefresh(stocks, scope.accountId)
                }
        }
    }

    private suspend fun showCachedResultsAndAutoRefresh(stocks: List<TaiwanStockRef>, accountId: Int) {
        val today = currentDateProvider()
        val cache = settingsDataStore.dividendInfoCacheFlow.first()
        val items = stocks.map { stock ->
            val cached = cache[stock.stockCode]
            val localCacheMatchesAccount = cached?.lastLocalAccountId == accountId
            val isFreshToday = cached?.lastFetchedDate == today
            DividendItemUiState(
                stockCode = stock.stockCode,
                stockName = stock.stockName,
                cashDividend = cached?.cashDividend,
                cashDividendDate = cached?.cashDividendDate,
                stockDividend = cached?.stockDividend,
                stockDividendDate = cached?.stockDividendDate,
                lastLocalCashDividend = cached?.lastLocalCashDividend.takeIf { localCacheMatchesAccount },
                lastLocalCashDividendDate = cached?.lastLocalCashDividendDate.takeIf { localCacheMatchesAccount },
                lastLocalStockDividend = cached?.lastLocalStockDividend.takeIf { localCacheMatchesAccount },
                lastLocalStockDividendDate = cached?.lastLocalStockDividendDate.takeIf { localCacheMatchesAccount },
                isLoading = !isFreshToday,
                errorMessage = null
            )
        }
        _dividendList.value = sortItems(items)

        // Stocks that haven't been fetched today need network refresh
        val stocksToRefresh = stocks.filter { stock ->
            cache[stock.stockCode]?.lastFetchedDate != today
        }

        // Stocks that are fresh today but account changed might need local Room transactions refreshed
        val stocksNeedLocalOnly = stocks.filter { stock ->
            val cached = cache[stock.stockCode]
            cached?.lastFetchedDate == today && cached.lastLocalAccountId != accountId
        }

        if (stocksNeedLocalOnly.isNotEmpty()) {
            viewModelScope.launch {
                stocksNeedLocalOnly.forEach { stock ->
                    updateLocalDividendsOnly(stock, accountId, cache[stock.stockCode])
                }
            }
        }

        if (stocksToRefresh.isNotEmpty()) {
            startRefresh(stocksToRefresh, accountId)
        }
    }

    private suspend fun updateLocalDividendsOnly(
        stock: TaiwanStockRef,
        accountId: Int,
        cached: DividendInfoCacheEntry?
    ) {
        try {
            val transactions = stockRepository.getTransactionsForStock(stock.stockCode, accountId).first()
            val lastCashTx = transactions
                .filter { it.transaction.type == "配息" }
                .maxByOrNull { it.transaction.date }
            val lastStockTx = transactions
                .filter { it.transaction.type == "配股" }
                .maxByOrNull { it.transaction.date }

            val sdf = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault())
            val localCashDate = lastCashTx?.transaction?.date?.let { sdf.format(Date(it)) }
            val localStockDate = lastStockTx?.transaction?.date?.let { sdf.format(Date(it)) }

            val updatedEntry = (cached ?: DividendInfoCacheEntry()).copy(
                lastLocalCashDividend = lastCashTx?.transaction?.cashDividend,
                lastLocalCashDividendDate = localCashDate,
                lastLocalStockDividend = lastStockTx?.transaction?.stockDividend,
                lastLocalStockDividendDate = localStockDate,
                lastLocalAccountId = accountId
            )
            settingsDataStore.setDividendInfoCacheEntry(stock.stockCode, updatedEntry)

            _dividendList.update { list ->
                sortItems(
                    list.map { item ->
                        if (item.stockCode == stock.stockCode) {
                            item.copy(
                                lastLocalCashDividend = lastCashTx?.transaction?.cashDividend,
                                lastLocalCashDividendDate = localCashDate,
                                lastLocalStockDividend = lastStockTx?.transaction?.stockDividend,
                                lastLocalStockDividendDate = localStockDate
                            )
                        } else {
                            item
                        }
                    }
                )
            }
        } catch (_: Exception) {
        }
    }

    private fun startRefresh(stocks: List<TaiwanStockRef>, accountId: Int) {
        refreshJob = viewModelScope.launch {
            coroutineScope {
                stocks.forEach { stock ->
                    launch {
                        fetchDividendForStock(stock, accountId)
                    }
                }
            }
        }
    }

    private suspend fun fetchDividendForStock(stock: TaiwanStockRef, accountId: Int) {
        try {
            // 1. Fetch Local Dividends
            val transactions = stockRepository.getTransactionsForStock(stock.stockCode, accountId).first()
            val lastCashTx = transactions
                .filter { it.transaction.type == "配息" }
                .maxByOrNull { it.transaction.date }
            val lastStockTx = transactions
                .filter { it.transaction.type == "配股" }
                .maxByOrNull { it.transaction.date }

            val sdf = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault())
            val localCashDate = lastCashTx?.transaction?.date?.let { sdf.format(Date(it)) }
            val localStockDate = lastStockTx?.transaction?.date?.let { sdf.format(Date(it)) }

            // 2. Fetch Yahoo Dividends
            val yahooDividends = yahooRequestSemaphore.withPermit {
                dividendRepository.fetchLatestDividends(stock.stockCode)
            }
            val cashResult = yahooDividends.cashDividend
            val stockResult = yahooDividends.stockDividend

            val today = currentDateProvider()

            settingsDataStore.setDividendInfoCacheEntry(
                stockCode = stock.stockCode,
                entry = DividendInfoCacheEntry(
                    cashDividend = cashResult?.amount,
                    cashDividendDate = cashResult?.date,
                    stockDividend = stockResult?.amount,
                    stockDividendDate = stockResult?.date,
                    lastLocalCashDividend = lastCashTx?.transaction?.cashDividend,
                    lastLocalCashDividendDate = localCashDate,
                    lastLocalStockDividend = lastStockTx?.transaction?.stockDividend,
                    lastLocalStockDividendDate = localStockDate,
                    lastLocalAccountId = accountId,
                    lastFetchedDate = today
                )
            )

            _dividendList.update { list ->
                sortItems(
                    list.map { item ->
                        if (item.stockCode == stock.stockCode) {
                            item.copy(
                                cashDividend = cashResult?.amount,
                                cashDividendDate = cashResult?.date,
                                stockDividend = stockResult?.amount,
                                stockDividendDate = stockResult?.date,
                                lastLocalCashDividend = lastCashTx?.transaction?.cashDividend,
                                lastLocalCashDividendDate = localCashDate,
                                lastLocalStockDividend = lastStockTx?.transaction?.stockDividend,
                                lastLocalStockDividendDate = localStockDate,
                                isLoading = false,
                                errorMessage = null
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
            _dividendList.update { list ->
                list.map { item ->
                    if (item.stockCode == stock.stockCode) {
                        item.copy(
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
    
    fun refresh() {
        val stocks = latestTaiwanStocks
        if (stocks.isEmpty()) return

        _dividendList.update { items ->
            items.map { it.copy(isLoading = true, errorMessage = null) }
        }
        refreshJob?.cancel()
        startRefresh(stocks, latestAccountId)
    }

    private data class TaiwanStockRef(
        val stockCode: String,
        val stockName: String
    )

    private data class DividendRefreshScope(
        val stocks: List<TaiwanStockRef>,
        val accountId: Int
    )
}
