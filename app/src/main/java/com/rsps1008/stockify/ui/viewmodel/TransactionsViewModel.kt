package com.rsps1008.stockify.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rsps1008.stockify.data.Account
import com.rsps1008.stockify.data.SettingsDataStore
import com.rsps1008.stockify.data.StockDao
import com.rsps1008.stockify.data.toStockKey
import com.rsps1008.stockify.data.TransactionListRepository
import com.rsps1008.stockify.data.TransactionListSnapshot
import com.rsps1008.stockify.ui.screens.TransactionUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TransactionDateSection(
    val date: String,
    val transactions: List<TransactionUiState>
)

data class TransactionsUiState(
    val accountId: Int = 0,
    val query: String = "",
    val isLoading: Boolean = true,
    val sections: List<TransactionDateSection> = emptyList(),
    val totalCount: Int = 0,
    val resultCount: Int = 0
)

internal fun buildTransactionDateSections(
    snapshot: TransactionListSnapshot,
    activeAccountId: Int,
    locale: Locale = Locale.getDefault(),
    timeZone: TimeZone = TimeZone.getDefault()
): List<TransactionDateSection> {
    if (snapshot.accountId != activeAccountId) return emptyList()

    val dateFormatter = SimpleDateFormat("yyyy/MM/dd (E)", locale).apply {
        this.timeZone = timeZone
    }
    val stocksByKey = snapshot.stocks.associateBy { it.toStockKey().cacheKey() }
    val dateLabels = mutableMapOf<Long, String>()
    return snapshot.transactions
        .map { transaction ->
            val stock = stocksByKey[transaction.toStockKey().cacheKey()]
            TransactionUiState(
                transaction = transaction,
                stockName = stock?.name ?: "",
                market = stock?.market ?: transaction.market
            )
        }
        .groupBy {
            dateLabels.getOrPut(it.transaction.date) {
                dateFormatter.format(Date(it.transaction.date))
            }
        }
        .map { (date, transactions) -> TransactionDateSection(date, transactions) }
}

internal fun filterTransactionDateSections(
    sections: List<TransactionDateSection>,
    query: String
): List<TransactionDateSection> {
    val terms = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (terms.isEmpty()) return sections

    return sections.mapNotNull { section ->
        val matches = section.transactions.filter { row ->
            terms.all { term ->
                row.transaction.stockCode.contains(term, ignoreCase = true) ||
                    row.stockName.contains(term, ignoreCase = true) ||
                    row.transaction.type.contains(term, ignoreCase = true) ||
                    row.transaction.note.contains(term, ignoreCase = true) ||
                    section.date.contains(term, ignoreCase = true)
            }
        }
        if (matches.isEmpty()) null else section.copy(transactions = matches)
    }
}

internal fun transactionListUiStates(
    snapshots: Flow<TransactionListSnapshot>,
    activeAccountIds: Flow<Int>,
    searchQueries: Flow<String>
): Flow<TransactionsUiState> = combine(snapshots, activeAccountIds) { snapshot, accountId ->
    if (!snapshot.isLoaded || snapshot.accountId != accountId) {
        TransactionsUiState(accountId = accountId)
    } else {
        TransactionsUiState(
            accountId = accountId,
            isLoading = false,
            sections = buildTransactionDateSections(snapshot, accountId),
            totalCount = snapshot.transactions.size
        )
    }
}.combine(searchQueries) { state, query ->
    // Typing only filters the prepared rows; stock lookups and date formatting stay upstream.
    val sections = filterTransactionDateSections(state.sections, query)
    state.copy(query = query, sections = sections, resultCount = sections.sumOf { it.transactions.size })
}.flowOn(Dispatchers.Default)

class TransactionsViewModel(
    private val stockDao: StockDao,
    private val transactionListRepository: TransactionListRepository,
    private val settingsDataStore: SettingsDataStore
) : ViewModel() {

    val activeAccountId: StateFlow<Int> = settingsDataStore.activeAccountIdFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = 0
        )

    val accounts: StateFlow<List<Account>> = stockDao.getAllAccountsFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = emptyList()
        )

    fun selectAccount(accountId: Int) {
        viewModelScope.launch {
            settingsDataStore.setActiveAccountId(accountId)
        }
    }

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    val uiState: StateFlow<TransactionsUiState> = transactionListUiStates(
        transactionListRepository.snapshot,
        settingsDataStore.activeAccountIdFlow,
        searchQuery
    ).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = TransactionsUiState()
    )
}
