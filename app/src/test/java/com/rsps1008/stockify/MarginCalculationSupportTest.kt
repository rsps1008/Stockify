package com.rsps1008.stockify

import com.rsps1008.stockify.data.MarginCalculationSupport
import com.rsps1008.stockify.data.StockTransaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarginCalculationSupportTest {
    private fun loan(date: Long, id: String, principal: Double, rate: Double) = StockTransaction(
        stockCode = "2330", accountId = 1, date = date, recordTime = date,
        type = "融資買進", expense = 100_000.0, buyPrice = 100.0, buyShares = 1_000.0,
        marginPrincipal = principal, marginAnnualRate = rate, marginLotId = id
    )

    @Test
    fun interestUsesPurchaseDayAndExcludesRepaymentDay() {
        val start = 0L
        val repaymentDate = 3L * 24 * 60 * 60 * 1000
        val transactions = listOf(
            loan(start, "lot-1", 80_000.0, 36.5),
            StockTransaction(
                stockCode = "2330", accountId = 1, date = repaymentDate, recordTime = repaymentDate,
                type = "融資還款", marginRepaymentLotId = "lot-1", marginRepayment = 80_000.0
            )
        )
        val summary = MarginCalculationSupport.calculate(transactions, repaymentDate, 365)
        assertEquals(0.0, summary.outstandingPrincipal, 0.0)
        assertEquals(240.0, summary.accruedInterest, 0.01)
    }

    @Test
    fun repaymentTargetsTheSelectedLotOnly() {
        val day = 24 * 60 * 60 * 1000L
        val transactions = listOf(
            loan(0L, "first", 50_000.0, 3.65),
            loan(day, "second", 30_000.0, 7.3),
            StockTransaction(
                stockCode = "2330", accountId = 1, date = day * 2, recordTime = day * 2,
                type = "融資還款", marginRepaymentLotId = "second", marginRepayment = 10_000.0
            )
        )
        val summary = MarginCalculationSupport.calculate(transactions, day * 2, 365)
        assertEquals(70_000.0, summary.outstandingPrincipal, 0.0)
        assertEquals(50_000.0, summary.lots.first { it.lotId == "first" }.remainingPrincipal, 0.0)
        assertEquals(20_000.0, summary.lots.first { it.lotId == "second" }.remainingPrincipal, 0.0)
    }

    @Test
    fun actualBrokerInterestReplacesAccruedInterestForTheRepaidLot() {
        val day = 24L * 60 * 60 * 1000
        val transactions = listOf(
            loan(0, "lot-1", 80_000.0, 3.65),
            StockTransaction(
                stockCode = "2330", accountId = 1, date = day * 30, recordTime = day * 30,
                type = "融資還款", marginRepaymentLotId = "lot-1", marginRepayment = 80_000.0,
                marginActualInterest = 340.0
            )
        )

        val summary = MarginCalculationSupport.calculate(transactions, day * 30, 365)

        assertEquals(0.0, summary.accruedInterest, 0.0)
        assertEquals(340.0, summary.actualInterestPaid, 0.0)
        assertEquals(340.0, summary.totalInterestExpense, 0.0)
    }

    @Test
    fun futureMarginLotsAreExcludedFromHistoricalValuation() {
        val day = 24L * 60 * 60 * 1000
        val summary = MarginCalculationSupport.calculate(
            transactions = listOf(loan(day * 2, "future", 80_000.0, 3.65)),
            valuationDate = day,
            dayCount = 365
        )

        assertEquals(0.0, summary.outstandingPrincipal, 0.0)
        assertEquals(0, summary.lots.size)
    }

    @Test
    fun explicitZeroSelfFundedOverrideIsPreserved() {
        val transaction = loan(0L, "lot-zero", 60_000.0, 3.65).copy(
            marginSelfFunded = 0.0,
            marginSelfFundedOverridden = true
        )

        val summary = MarginCalculationSupport.calculate(listOf(transaction), 0L, 365)

        assertEquals(0.0, summary.selfFundedCapital, 0.0)
    }

    @Test
    fun backdatedRepaymentCannotOverpayLotAfterLaterRepaymentExists() {
        val day = 24L * 60 * 60 * 1000
        val transactions = listOf(
            loan(0L, "lot-1", 100_000.0, 3.65),
            StockTransaction(
                stockCode = "2330", date = day, recordTime = day,
                type = "融資還款", marginRepaymentLotId = "lot-1", marginRepayment = 100_000.0
            ),
            StockTransaction(
                stockCode = "2330", date = day * 2, recordTime = day * 2,
                type = "融資還款", marginRepaymentLotId = "lot-1", marginRepayment = 60_000.0
            )
        )

        assertFalse(MarginCalculationSupport.hasValidRepaymentBalances(transactions))
    }

    @Test
    fun interestOnlyRepaymentMustReferenceAnExistingLot() {
        val invalid = StockTransaction(
            stockCode = "2330", date = 0L, recordTime = 0L,
            type = "融資還款", marginActualInterest = 100.0
        )

        assertFalse(MarginCalculationSupport.hasValidRepaymentBalances(listOf(invalid)))
    }

    @Test
    fun interestOnlyRepaymentCanReferenceTheSelectedLot() {
        val interestOnly = StockTransaction(
            stockCode = "2330", date = 24L * 60 * 60 * 1000, recordTime = 1L,
            type = "融資還款", marginRepaymentLotId = "lot-1", marginActualInterest = 100.0
        )

        assertTrue(
            MarginCalculationSupport.hasValidRepaymentBalances(
                listOf(loan(0L, "lot-1", 80_000.0, 3.65), interestOnly)
            )
        )
    }
}
