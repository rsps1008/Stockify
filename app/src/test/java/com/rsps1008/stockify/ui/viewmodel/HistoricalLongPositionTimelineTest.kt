package com.rsps1008.stockify.ui.viewmodel

import com.rsps1008.stockify.data.HoldingCalculationSupport
import com.rsps1008.stockify.data.LongPositionReplaySummary
import com.rsps1008.stockify.data.StockTransaction
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoricalLongPositionTimelineTest {

    @Test
    fun advanceTo_matchesFullReplayForOrderedCompanyActions() {
        val transactions = listOf(
            StockTransaction(id = 4, stockCode = "2330", date = 4_000, recordTime = 1, type = "賣出", sellPrice = 15.0, sellShares = 4.0, income = 59.0),
            StockTransaction(id = 1, stockCode = "2330", date = 1_000, recordTime = 1, type = "買進", buyShares = 10.0, expense = 100.0),
            StockTransaction(id = 3, stockCode = "2330", date = 3_000, recordTime = 1, type = "分割", stockSplitRatio = 2.0, sharesBeforeSplit = 10.0, sharesAfterSplit = 20.0),
            StockTransaction(id = 5, stockCode = "2330", date = 5_000, recordTime = 1, type = "減資", capitalReductionRatio = 10.0, sharesBeforeReduction = 16.0, sharesAfterReduction = 14.4, cashReturned = 12.0),
            StockTransaction(id = 2, stockCode = "2330", date = 2_000, recordTime = 1, type = "配息", dividendIncome = 5.0)
        )
        val timeline = HistoricalLongPositionTimeline(transactions)

        listOf(500L, 1_000L, 2_000L, 3_000L, 4_000L, 5_000L).forEach { valuationDate ->
            assertSummaryEquals(
                HoldingCalculationSupport.replayLongPosition(transactions, valuationDate),
                timeline.advanceTo(valuationDate)
            )
        }
    }

    @Test
    fun advanceTo_keepsCompanyActionsWithinTheirAccount() {
        val transactions = listOf(
            StockTransaction(id = 1, stockCode = "2330", accountId = 1, date = 1_000, recordTime = 1, type = "買進", buyShares = 10.0, expense = 100.0),
            StockTransaction(id = 2, stockCode = "2330", accountId = 2, date = 1_000, recordTime = 2, type = "買進", buyShares = 10.0, expense = 100.0),
            StockTransaction(id = 3, stockCode = "2330", accountId = 1, date = 2_000, recordTime = 1, type = "分割", stockSplitRatio = 2.0, sharesBeforeSplit = 10.0, sharesAfterSplit = 20.0),
            StockTransaction(id = 4, stockCode = "2330", accountId = 1, date = 3_000, recordTime = 1, type = "減資", capitalReductionRatio = 10.0, sharesBeforeReduction = 20.0, sharesAfterReduction = 18.0, cashReturned = 10.0)
        )
        val timeline = HistoricalLongPositionTimeline(transactions)

        listOf(1_000L, 2_000L, 3_000L).forEach { valuationDate ->
            assertSummaryEquals(
                HoldingCalculationSupport.replayLongPosition(transactions, valuationDate),
                timeline.advanceTo(valuationDate)
            )
        }
    }

    private fun assertSummaryEquals(expected: LongPositionReplaySummary, actual: LongPositionReplaySummary) {
        assertEquals(expected.shares, actual.shares, 0.0)
        assertEquals(expected.totalBuyExpense, actual.totalBuyExpense, 0.0)
        assertEquals(expected.totalSellIncome, actual.totalSellIncome, 0.0)
        assertEquals(expected.totalSellNetIncome, actual.totalSellNetIncome, 0.0)
        assertEquals(expected.sellSharesTotal, actual.sellSharesTotal, 0.0)
        assertEquals(expected.sellAmountBeforeFee, actual.sellAmountBeforeFee, 0.0)
        assertEquals(expected.totalDividendIncome, actual.totalDividendIncome, 0.0)
        assertEquals(expected.buySharesTotal, actual.buySharesTotal, 0.0)
        assertEquals(expected.buyCostTotal, actual.buyCostTotal, 0.0)
    }
}
