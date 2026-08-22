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
    fun partialRepaymentActualInterestOnlyReplacesTheRepaidPrincipalShare() {
        val day = 24L * 60 * 60 * 1000
        val transactions = listOf(
            loan(0, "lot-1", 100_000.0, 36.5),
            StockTransaction(
                stockCode = "2330", accountId = 1, date = day * 10, recordTime = day * 10,
                type = "融資還款", marginRepaymentLotId = "lot-1", marginRepayment = 40_000.0,
                marginActualInterest = 450.0
            )
        )

        val summary = MarginCalculationSupport.calculate(transactions, day * 10, 365)

        assertEquals(60_000.0, summary.outstandingPrincipal, 0.0)
        assertEquals(600.0, summary.accruedInterest, 0.01)
        assertEquals(450.0, summary.actualInterestPaid, 0.0)
        assertEquals(1_050.0, summary.totalInterestExpense, 0.01)
    }

    @Test
    fun interestOnlyPaymentReplacesAllAccruedInterestAtThePaymentDate() {
        val day = 24L * 60 * 60 * 1000
        val transactions = listOf(
            loan(0, "lot-1", 100_000.0, 36.5),
            StockTransaction(
                stockCode = "2330", accountId = 1, date = day * 10, recordTime = day * 10,
                type = "融資還款", marginRepaymentLotId = "lot-1",
                marginActualInterest = 1_050.0
            )
        )

        val summary = MarginCalculationSupport.calculate(transactions, day * 10, 365)

        assertEquals(100_000.0, summary.outstandingPrincipal, 0.0)
        assertEquals(0.0, summary.accruedInterest, 0.0)
        assertEquals(1_050.0, summary.actualInterestPaid, 0.0)
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

    @Test
    fun databaseIdBreaksTiesWhenLegacyRecordTimesAreEqual() {
        val opening = loan(0L, "lot-1", 80_000.0, 3.65).copy(id = 1, recordTime = 0L)
        val repayment = StockTransaction(
            id = 2,
            stockCode = "2330",
            date = 0L,
            recordTime = 0L,
            type = "融資還款",
            marginRepaymentLotId = "lot-1",
            marginRepayment = 80_000.0
        )

        assertTrue(MarginCalculationSupport.hasValidRepaymentBalances(listOf(repayment, opening)))
    }

    @Test
    fun identicalLotIdsInSeparateAccountsDoNotOverwriteEachOtherDuringReplay() {
        val day = 24L * 60 * 60 * 1000
        val firstAccountLoan = loan(0L, "shared", 50_000.0, 3.65)
        val secondAccountLoan = loan(day, "shared", 70_000.0, 3.65).copy(accountId = 2)
        val firstAccountRepayment = StockTransaction(
            stockCode = "2330",
            accountId = 1,
            date = day * 2,
            recordTime = day * 2,
            type = "融資還款",
            marginRepaymentLotId = "shared",
            marginRepayment = 50_000.0
        )

        val transactions = listOf(firstAccountLoan, secondAccountLoan, firstAccountRepayment)
        val summary = MarginCalculationSupport.calculate(transactions, day * 2, 365)

        assertTrue(MarginCalculationSupport.hasValidRepaymentBalances(transactions))
        assertEquals(70_000.0, summary.outstandingPrincipal, 0.0)
    }

    @Test
    fun historicalTimelineMatchesFreshReplayAtEveryValuationDate() {
        val day = 24L * 60 * 60 * 1000
        val transactions = listOf(
            loan(0L, "first", 100_000.0, 36.5),
            loan(day * 2, "second", 50_000.0, 7.3),
            StockTransaction(
                stockCode = "2330", accountId = 1, date = day * 5, recordTime = day * 5,
                type = "融資還款", marginRepaymentLotId = "first", marginRepayment = 40_000.0,
                marginActualInterest = 450.0
            ),
            StockTransaction(
                stockCode = "2330", accountId = 1, date = day * 8, recordTime = day * 8,
                type = "融資還款", marginRepaymentLotId = "second", marginActualInterest = 100.0
            )
        )
        val timeline = MarginCalculationSupport.HistoricalTimeline(
            transactions = transactions,
            dayCount = 365,
            transactionsAreOrdered = true
        )

        listOf(0L, day, day * 3, day * 5, day * 8, day * 10).forEach { valuationDate ->
            assertMarginSummaryEquals(
                expected = MarginCalculationSupport.calculate(transactions, valuationDate, 365),
                actual = timeline.advanceTo(valuationDate)
            )
        }
    }

    private fun assertMarginSummaryEquals(
        expected: com.rsps1008.stockify.data.MarginSummary,
        actual: com.rsps1008.stockify.data.MarginSummary
    ) {
        assertEquals(expected.outstandingPrincipal, actual.outstandingPrincipal, 1e-7)
        assertEquals(expected.accruedInterest, actual.accruedInterest, 1e-7)
        assertEquals(expected.selfFundedCapital, actual.selfFundedCapital, 1e-7)
        assertEquals(expected.cashBalance, actual.cashBalance, 1e-7)
        assertEquals(expected.actualInterestPaid, actual.actualInterestPaid, 1e-7)
        assertEquals(expected.lots.map { it.lotId }, actual.lots.map { it.lotId })
        expected.lots.zip(actual.lots).forEach { (expectedLot, actualLot) ->
            assertEquals(expectedLot.remainingPrincipal, actualLot.remainingPrincipal, 1e-7)
            assertEquals(expectedLot.accruedInterest, actualLot.accruedInterest, 1e-7)
        }
    }
}
