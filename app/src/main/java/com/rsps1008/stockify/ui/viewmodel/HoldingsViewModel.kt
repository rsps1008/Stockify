package com.rsps1008.stockify.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rsps1008.stockify.data.RealtimeStockDataService
import com.rsps1008.stockify.data.SettingsDataStore
import com.rsps1008.stockify.data.StockRepository
import com.rsps1008.stockify.data.TaiwanWeightedIndexService
import com.rsps1008.stockify.ui.screens.HoldingsUiState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HoldingsViewModel(
    private val settingsDataStore: SettingsDataStore,
    private val realtimeStockDataService: RealtimeStockDataService,
    private val taiwanWeightedIndexService: TaiwanWeightedIndexService,
    stockRepository: StockRepository
) : ViewModel() {

    val uiState: StateFlow<HoldingsUiState> = stockRepository.getHoldings()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = HoldingsUiState()
        )

    val homeDisplayMode: StateFlow<String> = settingsDataStore.homeDisplayModeFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = com.rsps1008.stockify.data.HomeDisplayMode.COMBINED
        )

    val holdingsOrder: StateFlow<List<String>> = settingsDataStore.holdingsOrderFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = emptyList()
        )

    val realizedHoldingsOrder: StateFlow<List<String>> = settingsDataStore.realizedHoldingsOrderFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = emptyList()
        )

    val holdingsReorderHintShown: StateFlow<Boolean> = settingsDataStore.holdingsReorderHintShownFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = false
        )

    val homeHoldingsSortMode: StateFlow<String> = settingsDataStore.homeHoldingsSortModeFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = "MANUAL"
        )

    val homeHoldingsSortColumn: StateFlow<String> = settingsDataStore.homeHoldingsSortColumnFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = "NONE"
        )

    val homeHoldingsSortAscending: StateFlow<Boolean> = settingsDataStore.homeHoldingsSortAscendingFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = true
        )

    val showTaiwanWeightedIndex: StateFlow<Boolean> = settingsDataStore.showTaiwanWeightedIndexFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = true
        )

    val taiwanWeightedIndexInfo: StateFlow<com.rsps1008.stockify.data.TaiwanWeightedIndexInfo?> =
        taiwanWeightedIndexService.indexInfo

    fun setHomeDisplayMode(mode: String) {
        viewModelScope.launch {
            settingsDataStore.setHomeDisplayMode(mode)
        }
    }

    fun refreshAllHoldingsQuotes() {
        viewModelScope.launch {
            realtimeStockDataService.refreshAllHeldStockInfo()
        }
    }

    fun setHoldingsOrder(order: List<String>) {
        viewModelScope.launch {
            settingsDataStore.setHoldingsOrder(order)
        }
    }

    fun setRealizedHoldingsOrder(order: List<String>) {
        viewModelScope.launch {
            settingsDataStore.setRealizedHoldingsOrder(order)
        }
    }

    fun markHoldingsReorderHintShown() {
        viewModelScope.launch {
            settingsDataStore.setHoldingsReorderHintShown(true)
        }
    }

    fun setHomeHoldingsManualSort() {
        viewModelScope.launch {
            settingsDataStore.setHomeHoldingsSortPreference(
                mode = "MANUAL",
                column = "NONE",
                ascending = true
            )
        }
    }

    fun setHomeHoldingsFixedSort(column: String, ascending: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setHomeHoldingsSortPreference(
                mode = "COLUMN",
                column = column,
                ascending = ascending
            )
        }
    }
}
