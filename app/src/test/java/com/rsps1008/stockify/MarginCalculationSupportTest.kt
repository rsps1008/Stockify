package com.rsps1008.stockify

import com.rsps1008.stockify.data.MarginCalculationSupport
import com.rsps1008.stockify.data.StockTransaction
import org.junit.Assert.assertEquals
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
}
