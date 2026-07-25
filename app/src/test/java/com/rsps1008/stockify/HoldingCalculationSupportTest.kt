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
}
