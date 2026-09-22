package com.rsps1008.stockify

import com.rsps1008.stockify.data.MarginSummary
import com.rsps1008.stockify.data.ProfitLossBreakdownSupport
import com.rsps1008.stockify.data.ProfitLossBreakdown
import com.rsps1008.stockify.data.ShortSellingCalculationSupport
import com.rsps1008.stockify.data.Stock
import com.rsps1008.stockify.data.StockTransaction
import com.rsps1008.stockify.ui.screens.HoldingInfo
import com.rsps1008.stockify.ui.screens.buildHoldingSections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfitLossBreakdownSupportTest {
    @Test
    fun partialSaleUsesMovingAverageAndPreservesLegacyTotal() {
        val transactions = listOf(
            buy(date = 1L, price = 100.0, shares = 100.0, expense = 10_000.0),
            buy(date = 2L, price = 200.0, shares = 100.0, expense = 20_000.0),
            sell(date = 3L, price = 250.0, shares = 100.0, income = 25_000.0)
        )

        val result = calculate(transactions, valuationDate = 3L, legacyTotal = 15_000.0)

        assertEquals(10_000.0, result.realizedProfitLoss, 0.001)
        assertEquals(5_000.0, result.unrealizedProfitLoss, 0.001)
        assertEquals(15_000.0, result.realizedCost, 0.001)
        assertEquals(15_000.0, result.unrealizedCost, 0.001)
        assertEquals(100.0, result.soldShares, 0.0)
        assertEquals(15_000.0, result.realizedProfitLoss + result.unrealizedProfitLoss, 0.001)
    }

    @Test
    fun movingAverageKeepsAccountBoundaries() {
        val transactions = listOf(
            buy(date = 1L, price = 100.0, shares = 100.0, expense = 10_000.0, accountId = 1),
            buy(date = 1L, price = 200.0, shares = 100.0, expense = 20_000.0, accountId = 2),
            sell(date = 2L, price = 150.0, shares = 50.0, income = 7_500.0, accountId = 1)
        )

        val result = calculate(transactions, valuationDate = 2L, legacyTotal = 7_500.0)

        assertEquals(2_500.0, result.realizedProfitLoss, 0.001)
        assertEquals(25_000.0, result.unrealizedCost, 0.001)
        assertEquals(5_000.0, result.realizedCost, 0.001)
    }

    @Test
    fun dividendAndCapitalReductionCashAreRealized() {
        val transactions = listOf(
            buy(date = 1L, price = 100.0, shares = 100.0, expense = 10_000.0),
            StockTransaction(
                stockCode = "2330", date = 2L, recordTime = 2L, type = "配息",
                dividendIncome = 500.0
            ),
            StockTransaction(
                stockCode = "2330", date = 3L, recordTime = 3L, type = "減資",
                sharesBeforeReduction = 100.0, sharesAfterReduction = 80.0,
                capitalReductionRatio = 20.0, cashReturned = 2_500.0
            )
        )

        val result = calculate(transactions, valuationDate = 3L, legacyTotal = 1_000.0)

        assertEquals(1_000.0, result.realizedProfitLoss, 0.001)
        assertEquals(0.0, result.unrealizedProfitLoss, 0.001)
        assertEquals(2_000.0, result.realizedCost, 0.001)
        assertEquals(8_000.0, result.unrealizedCost, 0.001)
        assertTrue(result.hasRealizedActivity)
    }

    @Test
    fun excludedDividendDoesNotCreateRealizedActivityOrChangeTheDividendExcludedTotal() {
        val transactions = listOf(
            buy(date = 1L, price = 100.0, shares = 100.0, expense = 10_000.0),
            StockTransaction(
                stockCode = "2330", date = 2L, recordTime = 2L, type = "配息",
                dividendIncome = 500.0
            )
        )

        val result = ProfitLossBreakdownSupport.calculate(
            transactions = transactions,
            valuationDate = 2L,
            legacyTotalProfitLoss = 1_000.0,
            marginSummary = MarginSummary(),
            marginDayCount = 365,
            includeDividendIncome = false
        )

        assertEquals(0.0, result.realizedProfitLoss, 0.0)
        assertEquals(1_000.0, result.unrealizedProfitLoss, 0.0)
        assertFalse(result.hasRealizedActivity)
        assertEquals(1_000.0, result.realizedProfitLoss + result.unrealizedProfitLoss, 0.0)
    }

    @Test
    fun lossReductionWithoutCashKeepsTotalCostAndDoesNotCreateRealizedLoss() {
        val transactions = listOf(
            buy(date = 1L, price = 100.0, shares = 100.0, expense = 10_000.0),
            StockTransaction(
                stockCode = "2330", date = 2L, recordTime = 2L, type = "減資",
                sharesBeforeReduction = 100.0, sharesAfterReduction = 80.0,
                capitalReductionRatio = 20.0, cashReturned = 0.0
            )
        )

        val result = calculate(transactions, valuationDate = 2L, legacyTotal = 0.0)

        assertEquals(0.0, result.realizedProfitLoss, 0.0)
        assertEquals(0.0, result.unrealizedProfitLoss, 0.0)
        assertEquals(0.0, result.realizedCost, 0.0)
        assertEquals(10_000.0, result.unrealizedCost, 0.001)
        assertEquals(125.0, result.unrealizedAverageCost, 0.001)
        assertFalse(result.hasRealizedActivity)
    }

    @Test
    fun futureTransactionsAreExcluded() {
        val transactions = listOf(
            buy(date = 1L, price = 100.0, shares = 100.0, expense = 10_000.0),
            sell(date = 3L, price = 150.0, shares = 100.0, income = 15_000.0)
        )

        val result = calculate(transactions, valuationDate = 2L, legacyTotal = 2_000.0)

        assertFalse(result.hasRealizedActivity)
        assertEquals(0.0, result.realizedProfitLoss, 0.0)
        assertEquals(2_000.0, result.unrealizedProfitLoss, 0.0)
    }

    @Test
    fun stockDividendAndSplitDiluteMovingAverageBeforePartialSale() {
        val transactions = listOf(
            buy(date = 1L, price = 100.0, shares = 100.0, expense = 10_000.0),
            StockTransaction(
                stockCode = "2330", date = 2L, recordTime = 2L, type = "配股",
                dividendShares = 100.0
            ),
            StockTransaction(
                stockCode = "2330", date = 3L, recordTime = 3L, type = "分割",
                sharesBeforeSplit = 200.0, sharesAfterSplit = 400.0, stockSplitRatio = 2.0
            ),
            sell(date = 4L, price = 40.0, shares = 100.0, income = 4_000.0)
        )

        val result = calculate(transactions, valuationDate = 4L, legacyTotal = 3_000.0)

        assertEquals(1_500.0, result.realizedProfitLoss, 0.001)
        assertEquals(1_500.0, result.unrealizedProfitLoss, 0.001)
        assertEquals(2_500.0, result.realizedCost, 0.001)
        assertEquals(7_500.0, result.unrealizedCost, 0.001)
    }

    @Test
    fun fullySoldThenRepurchasedKeepsOldRealizedAndNewUnrealizedCostSeparate() {
        val transactions = listOf(
            buy(date = 1L, price = 100.0, shares = 100.0, expense = 10_000.0),
            sell(date = 2L, price = 120.0, shares = 100.0, income = 12_000.0),
            buy(date = 3L, price = 80.0, shares = 50.0, expense = 4_000.0)
        )

        val result = calculate(transactions, valuationDate = 3L, legacyTotal = 2_500.0)

        assertEquals(2_000.0, result.realizedProfitLoss, 0.001)
        assertEquals(500.0, result.unrealizedProfitLoss, 0.001)
        assertEquals(4_000.0, result.unrealizedCost, 0.001)
    }

    @Test
    fun partialShortCoverAllocatesOpeningIncomeExpenseAndBorrowFee() {
        val day = 24L * 60 * 60 * 1000
        val transactions = listOf(
            StockTransaction(
                stockCode = "2330", date = 0L, recordTime = 0L, type = "融券賣出",
                sellPrice = 100.0, sellShares = 1_000.0, income = 100_000.0,
                shortBorrowPrincipal = 100_000.0, shortBorrowAnnualRate = 3.65,
                shortLotId = "short-1"
            ),
            StockTransaction(
                stockCode = "2330", date = day * 10, recordTime = day * 10, type = "買券還券",
                buyPrice = 90.0, buyShares = 400.0, expense = 36_000.0,
                shortCoverLotId = "short-1", shortCoverShares = 400.0
            )
        )
        val shortSummary = ShortSellingCalculationSupport.calculate(transactions, day * 20, 365)
        val legacyTotal = 100_000.0 - 36_000.0 - 600.0 * 80.0 - shortSummary.accruedBorrowFee

        val result = ProfitLossBreakdownSupport.calculate(
            transactions = transactions,
            valuationDate = day * 20,
            legacyTotalProfitLoss = legacyTotal,
            marginSummary = MarginSummary(),
            marginDayCount = 365
        )

        assertEquals(3_960.0, result.realizedProfitLoss, 0.001)
        assertEquals(11_880.0, result.unrealizedProfitLoss, 0.001)
        assertEquals(400.0, result.coveredShortShares, 0.0)
        assertEquals(40_000.0, result.realizedCost, 0.001)
        assertEquals(60_000.0, result.unrealizedCost, 0.001)
        assertEquals(legacyTotal, result.realizedProfitLoss + result.unrealizedProfitLoss, 0.001)
    }

    @Test
    fun paidMarginInterestIsRealizedWhileLegacyRemainderStaysUnrealized() {
        val result = ProfitLossBreakdownSupport.calculate(
            transactions = listOf(buy(date = 1L, price = 100.0, shares = 100.0, expense = 10_000.0)),
            valuationDate = 2L,
            legacyTotalProfitLoss = 400.0,
            marginSummary = MarginSummary(actualInterestPaid = 600.0, accruedInterest = 100.0),
            marginDayCount = 365
        )

        assertEquals(-600.0, result.realizedProfitLoss, 0.0)
        assertEquals(1_000.0, result.unrealizedProfitLoss, 0.0)
        assertEquals(400.0, result.realizedProfitLoss + result.unrealizedProfitLoss, 0.0)
    }

    @Test
    fun disabledDisplayKeepsLegacyRowsAndValuesExactly() {
        val stock = Stock(code = "2330", name = "台積電", market = "TW")
        val active = HoldingInfo(stock = stock, shares = 50.0, totalPL = 1_234.0, totalPLPercentage = 12.34)
        val closed = HoldingInfo(stock = stock.copy(code = "2317"), totalPL = 567.0, totalPLPercentage = 5.67)

        val sections = buildHoldingSections(listOf(active, closed), partialSalesAsRealized = false)

        assertEquals(listOf(active), sections.unrealized)
        assertEquals(listOf(closed), sections.realized)
        assertTrue(sections.unrealized.single() === active)
        assertTrue(sections.realized.single() === closed)
    }

    @Test
    fun enabledDisplayUsesEachSliceOnceWithoutChangingTheirSum() {
        val breakdown = ProfitLossBreakdown(
            realizedProfitLoss = 2_000.0,
            unrealizedProfitLoss = 500.0,
            realizedCost = 10_000.0,
            unrealizedCost = 4_000.0,
            soldShares = 100.0,
            coveredShortShares = 0.0,
            realizedBuyAverage = 100.0,
            realizedSellAverage = 120.0,
            unrealizedAverageCost = 80.0,
            hasRealizedActivity = true
        )
        val holding = HoldingInfo(
            stock = Stock(code = "2330", name = "台積電", market = "TW"),
            shares = 50.0,
            totalPL = 2_500.0,
            profitLossBreakdown = breakdown
        )

        val sections = buildHoldingSections(listOf(holding), partialSalesAsRealized = true)

        assertEquals(1, sections.unrealized.size)
        assertEquals(1, sections.realized.size)
        assertEquals(500.0, sections.unrealized.single().totalPL, 0.0)
        assertEquals(2_000.0, sections.realized.single().totalPL, 0.0)
        assertEquals(100.0, sections.realized.single().averageCost, 0.0)
        assertEquals(2_500.0, sections.unrealized.single().totalPL + sections.realized.single().totalPL, 0.0)
    }

    private fun calculate(
        transactions: List<StockTransaction>,
        valuationDate: Long,
        legacyTotal: Double
    ) = ProfitLossBreakdownSupport.calculate(
        transactions = transactions,
        valuationDate = valuationDate,
        legacyTotalProfitLoss = legacyTotal,
        marginSummary = MarginSummary(),
        marginDayCount = 365
    )

    private fun buy(
        date: Long,
        price: Double,
        shares: Double,
        expense: Double,
        accountId: Int = 1
    ) = StockTransaction(
        stockCode = "2330", accountId = accountId, date = date, recordTime = date,
        type = "買進", buyPrice = price, buyShares = shares, expense = expense
    )

    private fun sell(
        date: Long,
        price: Double,
        shares: Double,
        income: Double,
        accountId: Int = 1
    ) = StockTransaction(
        stockCode = "2330", accountId = accountId, date = date, recordTime = date,
        type = "賣出", sellPrice = price, sellShares = shares, income = income
    )
}
