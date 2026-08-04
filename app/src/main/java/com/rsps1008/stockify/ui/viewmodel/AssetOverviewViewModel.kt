package com.rsps1008.stockify.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rsps1008.stockify.data.BankDeposit
import com.rsps1008.stockify.data.Loan
import com.rsps1008.stockify.data.SettingsDataStore
import com.rsps1008.stockify.data.StockRepository
import com.rsps1008.stockify.ui.screens.AssetStockValue
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AssetOverviewUiState(
    val taiwanStockValue: Double = 0.0,
    val usStockValue: Double = 0.0,
    val stockValues: List<AssetStockValue> = emptyList(),
    val bankDeposits: List<BankDeposit> = emptyList(),
    val loans: List<Loan> = emptyList()
) {
    val totalBankDeposit: Double
        get() = bankDeposits.sumOf { it.amount }

    val totalLoan: Double
        get() = loans.sumOf { it.amount }

    val grossAssets: Double
        get() = taiwanStockValue + usStockValue + totalBankDeposit

    val netAssets: Double
        get() = grossAssets - totalLoan
}

class AssetOverviewViewModel(
    private val settingsDataStore: SettingsDataStore,
    stockRepository: StockRepository
) : ViewModel() {

    val uiState: StateFlow<AssetOverviewUiState> = combine(
        stockRepository.getHoldings(),
        combine(settingsDataStore.bankDepositsFlow, settingsDataStore.loansFlow) { bankDeposits, loans ->
            bankDeposits to loans
        }
    ) { holdings, bankData ->
        AssetOverviewUiState(
            taiwanStockValue = holdings.taiwanMarketValue,
            usStockValue = holdings.usMarketValue,
            stockValues = holdings.assetStockValues,
            bankDeposits = bankData.first,
            loans = bankData.second
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = AssetOverviewUiState()
    )

    fun saveBankDeposit(id: Long?, name: String, amount: Double) {
        val normalizedName = name.trim()
        if (normalizedName.isBlank() || !amount.isFinite() || amount < 0.0) return

        viewModelScope.launch {
            val current = settingsDataStore.bankDepositsFlow.first()
            val updated = if (id == null) {
                val nextId = (current.maxOfOrNull { it.id } ?: 0L) + 1L
                current + BankDeposit(nextId, normalizedName, amount)
            } else {
                current.map { deposit ->
                    if (deposit.id == id) deposit.copy(name = normalizedName, amount = amount) else deposit
                }
            }
            settingsDataStore.setBankDeposits(updated)
        }
    }

    fun deleteBankDeposit(id: Long) {
        viewModelScope.launch {
            val updated = settingsDataStore.bankDepositsFlow.first()
                .filterNot { it.id == id }
            settingsDataStore.setBankDeposits(updated)
        }
    }

    fun saveLoan(id: Long?, name: String, amount: Double) {
        val normalizedName = name.trim()
        if (normalizedName.isBlank() || !amount.isFinite() || amount < 0.0) return

        viewModelScope.launch {
            val current = settingsDataStore.loansFlow.first()
            val updated = if (id == null) {
                val nextId = (current.maxOfOrNull { it.id } ?: 0L) + 1L
                current + Loan(nextId, normalizedName, amount)
            } else {
                current.map { loan ->
                    if (loan.id == id) loan.copy(name = normalizedName, amount = amount) else loan
                }
            }
            settingsDataStore.setLoans(updated)
        }
    }

    fun deleteLoan(id: Long) {
        viewModelScope.launch {
            val updated = settingsDataStore.loansFlow.first()
                .filterNot { it.id == id }
            settingsDataStore.setLoans(updated)
        }
    }
}
