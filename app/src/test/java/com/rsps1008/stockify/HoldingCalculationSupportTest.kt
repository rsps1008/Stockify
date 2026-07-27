package com.rsps1008.stockify

import com.rsps1008.stockify.data.HoldingCalculationSupport
import com.rsps1008.stockify.data.StockTransaction
import org.junit.Assert.assertEquals
import org.junit.Test

class HoldingCalculationSupportTest {

    @Test
    fun splitShareChange_usesRecordedPostSplitSharesForCurrentQuote() {
        val transaction = StockTransaction(
            stockCode = "00685L",
            date = 0L,
            recordTime = 0L,
            type = "分割",
            stockSplitRatio = 10.0,
            sharesBeforeSplit = 1_000.0,
            sharesAfterSplit = 10_000.0
        )

        assertEquals(9_000.0, HoldingCalculationSupport.splitShareChange(transaction, 1_000.0), 0.0)
    }

    @Test
    fun splitShareChange_fallsBackToRatioForLegacyImport() {
        val transaction = StockTransaction(
            stockCode = "00685L",
            date = 0L,
            recordTime = 0L,
            type = "分割",
            stockSplitRatio = 10.0
        )

        assertEquals(9_000.0, HoldingCalculationSupport.splitShareChange(transaction, 1_000.0), 0.0)
        assertEquals(10.0, HoldingCalculationSupport.splitShareFactor(transaction), 0.0)
    }

    @Test
    fun splitShareFactor_prefersRecordedShares() {
        val transaction = StockTransaction(
            stockCode = "00685L",
            date = 0L,
            recordTime = 0L,
            type = "分割",
            stockSplitRatio = 9.0,
            sharesBeforeSplit = 1_000.0,
            sharesAfterSplit = 10_000.0
        )

        assertEquals(10.0, HoldingCalculationSupport.splitShareFactor(transaction), 0.0)
    }

    @Test
    fun capitalReductionShareChange_usesRecordedPostReductionShares() {
        val transaction = StockTransaction(
            stockCode = "0050",
            date = 0L,
            recordTime = 0L,
            type = "減資",
            capitalReductionRatio = 20.0,
            sharesBeforeReduction = 1_000.0,
            sharesAfterReduction = 800.0
        )

        assertEquals(-200.0, HoldingCalculationSupport.capitalReductionShareChange(transaction, 1_000.0), 0.0)
        assertEquals(0.8, HoldingCalculationSupport.capitalReductionShareFactor(transaction), 0.0)
    }

    @Test
    fun resolveDividendIncome_fallsBackToLegacyIncomeField() {
        val transaction = StockTransaction(
            stockCode = "0050",
            date = 0L,
            recordTime = 0L,
            type = "配息",
            income = 120.0,
            dividendIncome = 0.0
        )

        assertEquals(120.0, HoldingCalculationSupport.resolveDividendIncome(transaction), 0.0)
    }

    @Test
    fun resolveDividendIncome_prefersDividendIncomeWhenPresent() {
        val transaction = StockTransaction(
            stockCode = "0050",
            date = 0L,
            recordTime = 0L,
            type = "配息",
            income = 120.0,
            dividendIncome = 100.0
        )

        assertEquals(100.0, HoldingCalculationSupport.resolveDividendIncome(transaction), 0.0)
    }

    @Test
    fun remainingPositionDenominator_fallsBackToTotalInvestmentWhenCostBasisIsNonPositive() {
        val denominator = HoldingCalculationSupport.remainingPositionDenominator(
            shares = 10.0,
            costBasis = -50.0,
            totalInvestment = 500.0
        )

        assertEquals(500.0, denominator, 0.0)
    }

    @Test
    fun remainingPositionDenominator_usesCostBasisWhenItIsPositive() {
        val denominator = HoldingCalculationSupport.remainingPositionDenominator(
            shares = 10.0,
            costBasis = 300.0,
            totalInvestment = 500.0
        )

        assertEquals(300.0, denominator, 0.0)
    }

    @Test
    fun closedShortPositionFallsBackToCumulativeInvestment() {
        val basis = HoldingCalculationSupport.positionInvestmentBasis(
            shares = 0.0,
            costBasis = 0.0,
            longInvestment = 0.0,
            marginDebt = 0.0,
            shortOutstandingShares = 0.0,
            shortRemainingInvestment = 0.0,
            shortCumulativeInvestment = 100_000.0
        )

        assertEquals(100_000.0, basis.remaining, 0.0)
        assertEquals(100_000.0, basis.cumulative, 0.0)
    }

    @Test
    fun partialShortCoverKeepsSeparateRemainingAndCumulativeInvestment() {
        val basis = HoldingCalculationSupport.positionInvestmentBasis(
            shares = 0.0,
            costBasis = 0.0,
            longInvestment = 0.0,
            marginDebt = 0.0,
            shortOutstandingShares = 600.0,
            shortRemainingInvestment = 60_000.0,
            shortCumulativeInvestment = 100_000.0
        )

        assertEquals(60_000.0, basis.remaining, 0.0)
        assertEquals(100_000.0, basis.cumulative, 0.0)
    }

    @Test
    fun financedOpenPositionUsesRemainingCashInvestmentInsteadOfFullCostBasis() {
        val basis = HoldingCalculationSupport.positionInvestmentBasis(
            shares = 1_000.0,
            costBasis = 100_000.0,
            longInvestment = 40_000.0,
            financedRemainingInvestment = 40_000.0,
            marginDebt = 60_000.0,
            shortOutstandingShares = 0.0,
            shortRemainingInvestment = 0.0,
            shortCumulativeInvestment = 0.0
        )

        assertEquals(40_000.0, basis.remaining, 0.0)
        assertEquals(40_000.0, basis.cumulative, 0.0)
    }

    @Test
    fun ordinaryOpenPositionStillUsesCostBasis() {
        val basis = HoldingCalculationSupport.positionInvestmentBasis(
            shares = 1_000.0,
            costBasis = 90_000.0,
            longInvestment = 100_000.0,
            marginDebt = 0.0,
            shortOutstandingShares = 0.0,
            shortRemainingInvestment = 0.0,
            shortCumulativeInvestment = 0.0
        )

        assertEquals(90_000.0, basis.remaining, 0.0)
        assertEquals(100_000.0, basis.cumulative, 0.0)
    }

    @Test
    fun financedPositionFallsBackToFinancedCumulativeBasisAfterCashIsRecovered() {
        val basis = HoldingCalculationSupport.positionInvestmentBasis(
            shares = 500.0,
            costBasis = 45_000.0,
            longInvestment = 40_000.0,
            financedRemainingInvestment = 0.0,
            marginDebt = 60_000.0,
            shortOutstandingShares = 0.0,
            shortRemainingInvestment = 0.0,
            shortCumulativeInvestment = 0.0
        )

        assertEquals(40_000.0, basis.remaining, 0.0)
        assertEquals(40_000.0, basis.cumulative, 0.0)
    }

    @Test
    fun companyActionOnlyAdjustsTheMatchingAccountLongPosition() {
        val day = 24L * 60 * 60 * 1000
        val firstAccountBuy = StockTransaction(
            stockCode = "2330",
            accountId = 1,
            date = 0L,
            recordTime = 0L,
            type = "融資買進",
            buyPrice = 100.0,
            buyShares = 1_000.0,
            expense = 100_000.0
        )
        val secondAccountBuy = firstAccountBuy.copy(accountId = 2, recordTime = 1L)
        val firstAccountSplit = StockTransaction(
            stockCode = "2330",
            accountId = 1,
            date = day,
            recordTime = day,
            type = "分割",
            stockSplitRatio = 2.0,
            sharesBeforeSplit = 1_000.0,
            sharesAfterSplit = 2_000.0
        )

        val summary = HoldingCalculationSupport.replayLongPosition(
            listOf(firstAccountBuy, secondAccountBuy, firstAccountSplit),
            day
        )

        assertEquals(3_000.0, summary.shares, 0.0)
        assertEquals(3_000.0, summary.buySharesTotal, 0.0)
        assertEquals(200_000.0, summary.totalBuyExpense, 0.0)
    }

    @Test
    fun futureFinancingTransactionsDoNotEnterCurrentLongReplay() {
        val day = 24L * 60 * 60 * 1000
        val currentBuy = StockTransaction(
            stockCode = "2330",
            accountId = 1,
            date = 0L,
            recordTime = 0L,
            type = "買進",
            buyPrice = 100.0,
            buyShares = 1_000.0,
            expense = 100_000.0
        )
        val futureMarginBuy = currentBuy.copy(
            date = day * 2,
            recordTime = day * 2,
            type = "融資買進",
            buyShares = 2_000.0,
            expense = 200_000.0
        )

        val summary = HoldingCalculationSupport.replayLongPosition(
            listOf(currentBuy, futureMarginBuy),
            day
        )
        val effectiveTransactions = HoldingCalculationSupport.transactionsAtOrBefore(
            listOf(
                currentBuy,
                futureMarginBuy,
                futureMarginBuy.copy(type = "融券賣出", sellShares = 500.0)
            ),
            day
        )

        assertEquals(1_000.0, summary.shares, 0.0)
        assertEquals(100_000.0, summary.totalBuyExpense, 0.0)
        assertEquals(listOf(currentBuy), effectiveTransactions)
    }

    @Test
    fun ordinaryTradesKeepTheirExistingReplayTotals() {
        val transactions = listOf(
            StockTransaction(
                stockCode = "2330",
                accountId = 1,
                date = 1L,
                recordTime = 1L,
                type = "買進",
                buyPrice = 100.0,
                buyShares = 1_000.0,
                expense = 100_100.0
            ),
            StockTransaction(
                stockCode = "2330",
                accountId = 1,
                date = 2L,
                recordTime = 2L,
                type = "賣出",
                sellPrice = 120.0,
                sellShares = 400.0,
                income = 47_700.0
            ),
            StockTransaction(
                stockCode = "2330",
                accountId = 1,
                date = 3L,
                recordTime = 3L,
                type = "配息",
                dividendIncome = 1_000.0
            )
        )

        val summary = HoldingCalculationSupport.replayLongPosition(
            transactions,
            valuationDate = 3L
        )

        assertEquals(600.0, summary.shares, 0.0)
        assertEquals(100_100.0, summary.totalBuyExpense, 0.0)
        assertEquals(47_700.0, summary.totalSellIncome, 0.0)
        assertEquals(48_000.0, summary.sellAmountBeforeFee, 0.0)
        assertEquals(1_000.0, summary.totalDividendIncome, 0.0)
    }
}
