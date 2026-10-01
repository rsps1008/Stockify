package com.rsps1008.stockify.data

import com.rsps1008.stockify.ui.screens.HoldingInfo

internal data class HoldingCalculationInputs(
    val stock: Stock,
    val transactions: List<StockTransaction>,
    val currentPrice: Double,
    val dailyChange: Double,
    val dailyChangePercentage: Double,
    val limitState: LimitState,
    val preDeductSellFees: Boolean,
    val feeSettingsByAccount: Map<Int, AccountFeeSettings>,
    val sharedFeeSettings: AccountFeeSettings,
    val returnRateMode: ReturnRateMode,
    val currentDateMillis: Long,
    val marginDayCount: Int,
    val includeProfitLossBreakdown: Boolean = false,
    val excludeDividendIncomeFromReturns: Boolean = false
)

/** One entry per stock, owned by a single flow collector. Unchanged stocks need no ledger replay. */
internal class HoldingCalculationCache {
    private val entries = mutableMapOf<String, Pair<HoldingCalculationInputs, HoldingInfo>>()

    fun getOrCalculate(inputs: HoldingCalculationInputs, calculate: (HoldingCalculationInputs) -> HoldingInfo): HoldingInfo {
        val key = inputs.stock.toStockKey().cacheKey()
        entries[key]?.let { (previous, result) -> if (previous == inputs) return result }
        return calculate(inputs).also { entries[key] = inputs to it }
    }

    fun retainStocks(keys: Set<String>) { entries.keys.retainAll(keys) }
}
