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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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

class DividendInfoViewModel(
    private val stockRepository: StockRepository,
    private val dividendRepository: YahooDividendRepository,
    private val settingsDataStore: SettingsDataStore
) : ViewModel() {

    private val _dividendList = MutableStateFlow<List<DividendItemUiState>>(emptyList())
    val dividendList: StateFlow<List<DividendItemUiState>> = _dividendList.asStateFlow()

    private var latestTaiwanStocks: List<TaiwanStockRef> = emptyList()
    private var refreshJob: Job? = null

    init {
        observeHoldingsAndRefresh()
    }

    private fun observeHoldingsAndRefresh() {
        viewModelScope.launch {
            stockRepository.getHoldings()
                .map { holdingsState ->
                    holdingsState.holdings
                        .filter { holding -> StockMarket.isTw(holding.stock.market) }
                        .map { holding -> TaiwanStockRef(holding.stock.code, holding.stock.name) }
                        .distinctBy { it.stockCode }
                }
                .distinctUntilChanged()
                .collect { stocks ->
                    latestTaiwanStocks = stocks
                    refreshJob?.cancel()

                    if (stocks.isEmpty()) {
                        _dividendList.value = emptyList()
                        return@collect
                    }

                    showCachedResults(stocks)
                    startRefresh(stocks)
                }
        }
    }

    private suspend fun showCachedResults(stocks: List<TaiwanStockRef>) {
        val cache = settingsDataStore.dividendInfoCacheFlow.first()
        _dividendList.value = sortItems(
            stocks.map { stock ->
                val cached = cache[stock.stockCode]
                DividendItemUiState(
                    stockCode = stock.stockCode,
                    stockName = stock.stockName,
                    cashDividend = cached?.cashDividend,
                    cashDividendDate = cached?.cashDividendDate,
                    stockDividend = cached?.stockDividend,
                    stockDividendDate = cached?.stockDividendDate,
                    lastLocalCashDividend = cached?.lastLocalCashDividend,
                    lastLocalCashDividendDate = cached?.lastLocalCashDividendDate,
                    lastLocalStockDividend = cached?.lastLocalStockDividend,
                    lastLocalStockDividendDate = cached?.lastLocalStockDividendDate,
                    isLoading = true
                )
            }
        )
    }

    private fun startRefresh(stocks: List<TaiwanStockRef>) {
        refreshJob = viewModelScope.launch {
            coroutineScope {
                stocks.forEach { stock ->
                    launch {
                        fetchDividendForStock(stock)
                    }
                }
            }
        }
    }

    private suspend fun fetchDividendForStock(stock: TaiwanStockRef) {
        try {
            // 1. Fetch Local Dividends
            val transactions = stockRepository.getTransactionsForStock(stock.stockCode).first()
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
            val cashResult = dividendRepository.fetchLatestCashDividend(stock.stockCode)
            val stockResult = dividendRepository.fetchLatestStockDividend(stock.stockCode)

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
                    lastLocalStockDividendDate = localStockDate
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
                                isLoading = false
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
        startRefresh(stocks)
    }

    private data class TaiwanStockRef(
        val stockCode: String,
        val stockName: String
    )
}
