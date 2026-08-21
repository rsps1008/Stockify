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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TransactionDateSection(
    val date: String,
    val transactions: List<TransactionUiState>
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
    return snapshot.transactions
        .map { transaction ->
            val stock = stocksByKey[transaction.toStockKey().cacheKey()]
            TransactionUiState(
                transaction = transaction,
                stockName = stock?.name ?: "",
                market = stock?.market ?: ""
            )
        }
        .groupBy { dateFormatter.format(Date(it.transaction.date)) }
        .map { (date, transactions) -> TransactionDateSection(date, transactions) }
}

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

    val transactionSections: StateFlow<List<TransactionDateSection>> = combine(
        transactionListRepository.snapshot,
        activeAccountId
    ) { snapshot, activeAccountId ->
        buildTransactionDateSections(snapshot, activeAccountId)
    }.flowOn(Dispatchers.Default).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = emptyList()
    )
}
