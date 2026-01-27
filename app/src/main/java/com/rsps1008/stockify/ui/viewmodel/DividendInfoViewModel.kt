package com.rsps1008.stockify.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rsps1008.stockify.data.StockRepository
import com.rsps1008.stockify.data.dividend.YahooDividendRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
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
    private val dividendRepository: YahooDividendRepository
) : ViewModel() {

    private val _dividendList = MutableStateFlow<List<DividendItemUiState>>(emptyList())
    val dividendList: StateFlow<List<DividendItemUiState>> = _dividendList.asStateFlow()

    init {
        loadHoldingsAndFetchDividends()
    }

    private fun loadHoldingsAndFetchDividends() {
        viewModelScope.launch {
            stockRepository.getHoldings().collect { holdingsState ->
                val currentItems = _dividendList.value
                
                if (currentItems.isEmpty() && holdingsState.holdings.isNotEmpty()) {
                    val newItems = holdingsState.holdings.map { holding ->
                        DividendItemUiState(
                            stockCode = holding.stock.code,
                            stockName = holding.stock.name
                        )
                    }
                    _dividendList.value = newItems
                    
                    // Fetch data for each item
                    newItems.forEach { item ->
                        fetchDividendForStock(item.stockCode)
                    }
                }
            }
        }
    }

    private fun fetchDividendForStock(stockCode: String) {
        viewModelScope.launch {
            try {
                // 1. Fetch Local Dividends
                val transactions = stockRepository.getTransactionsForStock(stockCode).first()
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
                val cashResult = dividendRepository.fetchLatestCashDividend(stockCode)
                val stockResult = dividendRepository.fetchLatestStockDividend(stockCode)

                _dividendList.update { list ->
                    list.map { item ->
                        if (item.stockCode == stockCode) {
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
                    }.sortedWith { a, b ->
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
            } catch (e: Exception) {
                _dividendList.update { list ->
                    list.map { item ->
                        if (item.stockCode == stockCode) {
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
    
    fun refresh() {
        _dividendList.value = emptyList()
        loadHoldingsAndFetchDividends()
    }
}
