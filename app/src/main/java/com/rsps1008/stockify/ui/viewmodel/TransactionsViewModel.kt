package com.rsps1008.stockify.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rsps1008.stockify.data.Account
import com.rsps1008.stockify.data.SettingsDataStore
import com.rsps1008.stockify.data.StockDao
import com.rsps1008.stockify.data.TransactionListRepository
import com.rsps1008.stockify.ui.screens.TransactionUiState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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

    val transactions: StateFlow<List<TransactionUiState>> = combine(
        transactionListRepository.snapshot,
        activeAccountId
    ) { snapshot, activeAccountId ->
        val filteredTx = if (activeAccountId == 0) {
            snapshot.transactions
        } else {
            snapshot.transactions.filter { it.accountId == activeAccountId }
        }
        val stocksByCode = snapshot.stocks.associateBy { it.code }
        filteredTx.map { transaction ->
            val stock = stocksByCode[transaction.stockCode]
            TransactionUiState(
                transaction = transaction,
                stockName = stock?.name ?: "",
                market = stock?.market ?: ""
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = emptyList()
    )
}
