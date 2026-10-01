package com.rsps1008.stockify

import com.rsps1008.stockify.data.*
import com.rsps1008.stockify.ui.screens.HoldingInfo
import org.junit.Assert.*
import org.junit.Test

class HoldingCalculationCacheTest {
    private fun inputs(code: String = "2330", market: String = "TW") = HoldingCalculationInputs(
        Stock(code = code, name = "Stock", market = market),
        listOf(StockTransaction(id = 1, stockCode = code, market = market, type = "買進", date = 1L, recordTime = 1L, accountId = 1)),
        100.0, 1.0, 1.0, LimitState.NONE, false, emptyMap(), AccountFeeSettings(0.6, 20, 1),
        ReturnRateMode.REMAINING_POSITION, 1000L, 365
    )

    @Test
    fun quoteBatchOnlyRecalculatesChangedStocksAndRemovedStocksAreEvicted() {
        val cache = HoldingCalculationCache()
        var calculations = 0
        fun calculate(input: HoldingCalculationInputs): HoldingInfo {
            calculations++
            return HoldingInfo(input.stock, currentPrice = input.currentPrice)
        }
        val taiwan = inputs()
        val us = inputs("AAPL", "US")
        val original = cache.getOrCalculate(taiwan, ::calculate)
        cache.getOrCalculate(us, ::calculate)
        assertSame(original, cache.getOrCalculate(taiwan.copy(), ::calculate))
        cache.getOrCalculate(us.copy(currentPrice = 150.0), ::calculate)
        assertEquals(3, calculations)
        cache.retainStocks(setOf("US:AAPL"))
        cache.getOrCalculate(taiwan, ::calculate)
        assertEquals(4, calculations)
    }

    @Test
    fun everyCalculationInputInvalidatesCacheIncludingAccountFeesDateAndDividendModes() {
        val base = inputs()
        val changed = listOf(
            base.copy(stock = base.stock.copy(name = "New name")),
            base.copy(transactions = base.transactions.map { it.copy(accountId = 2) }),
            base.copy(currentPrice = 101.0), base.copy(dailyChange = 2.0), base.copy(dailyChangePercentage = 2.0),
            base.copy(limitState = LimitState.LIMIT_UP), base.copy(preDeductSellFees = true),
            base.copy(feeSettingsByAccount = mapOf(1 to AccountFeeSettings(0.3, 20, 1))),
            base.copy(sharedFeeSettings = AccountFeeSettings(0.3, 20, 1)),
            base.copy(returnRateMode = ReturnRateMode.XIRR), base.copy(currentDateMillis = 2000L),
            base.copy(marginDayCount = 360), base.copy(includeProfitLossBreakdown = true),
            base.copy(excludeDividendIncomeFromReturns = true)
        )
        changed.forEach { input ->
            val cache = HoldingCalculationCache()
            var calls = 0
            val calculate: (HoldingCalculationInputs) -> HoldingInfo = { calls++; HoldingInfo(it.stock) }
            cache.getOrCalculate(base, calculate)
            cache.getOrCalculate(input, calculate)
            assertEquals(input.toString(), 2, calls)
        }
    }
}
