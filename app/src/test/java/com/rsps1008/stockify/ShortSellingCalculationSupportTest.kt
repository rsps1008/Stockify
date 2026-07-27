package com.rsps1008.stockify

import com.rsps1008.stockify.data.ShortSellingCalculationSupport
import com.rsps1008.stockify.data.MarginCalculationSupport
import com.rsps1008.stockify.data.ReturnRateCalculator
import com.rsps1008.stockify.data.StockTransaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortSellingCalculationSupportTest {
    @Test
    fun `cover only reduces the selected short lot and accrues until cover date`() {
        val day = 24L * 60 * 60 * 1000
        val open = StockTransaction(stockCode = "2330", date = 0, recordTime = 0, type = "融券賣出", sellPrice = 100.0, sellShares = 1_000.0, shortBorrowPrincipal = 100_000.0, shortBorrowAnnualRate = 3.65, shortLotId = "short-1")
        val cover = StockTransaction(stockCode = "2330", date = day * 10, recordTime = 1, type = "買券還券", shortCoverLotId = "short-1", shortCoverShares = 400.0)

        val summary = ShortSellingCalculationSupport.calculate(listOf(open, cover), day * 20, 365)

        assertEquals(600.0, summary.outstandingShares, 0.0001)
        assertEquals(160.0, summary.accruedBorrowFee, 0.0001)
        assertEquals(60_000.0, summary.openedPrincipal, 0.0001)
    }

    @Test
    fun `long margin and short lots can coexist without affecting each other`() {
        val day = 24L * 60 * 60 * 1000
        val transactions = listOf(
            StockTransaction(stockCode = "2330", date = 0, recordTime = 0, type = "融資買進", expense = 100_000.0, marginPrincipal = 60_000.0, marginAnnualRate = 3.65, marginLotId = "margin-1"),
            StockTransaction(stockCode = "2330", date = 0, recordTime = 1, type = "融券賣出", sellPrice = 100.0, sellShares = 1_000.0, shortBorrowPrincipal = 100_000.0, shortBorrowAnnualRate = 3.65, shortLotId = "short-1"),
            StockTransaction(stockCode = "2330", date = day * 5, recordTime = 2, type = "買券還券", shortCoverLotId = "short-1", shortCoverShares = 400.0)
        )

        val margin = MarginCalculationSupport.calculate(transactions, day * 10, 365)
        val short = ShortSellingCalculationSupport.calculate(transactions, day * 10, 365)

        assertEquals(60_000.0, margin.outstandingPrincipal, 0.0001)
        assertEquals(600.0, short.outstandingShares, 0.0001)
    }

    @Test
    fun `future short lots are excluded from historical valuation`() {
        val day = 24L * 60 * 60 * 1000
        val futureOpen = StockTransaction(
            stockCode = "2330", date = day * 2, recordTime = 0, type = "融券賣出",
            sellPrice = 100.0, sellShares = 1_000.0, shortBorrowPrincipal = 100_000.0,
            shortBorrowAnnualRate = 3.65, shortLotId = "future-short"
        )

        val summary = ShortSellingCalculationSupport.calculate(listOf(futureOpen), day, 365)

        assertEquals(0.0, summary.outstandingShares, 0.0)
        assertEquals(0, summary.lots.size)
    }

    @Test
    fun `backdated cover cannot exceed a lot after a later cover exists`() {
        val day = 24L * 60 * 60 * 1000
        val transactions = listOf(
            StockTransaction(
                stockCode = "2330", date = 0, recordTime = 0, type = "融券賣出",
                sellPrice = 100.0, sellShares = 1_000.0, shortLotId = "short-1"
            ),
            StockTransaction(
                stockCode = "2330", date = day, recordTime = day, type = "買券還券",
                shortCoverLotId = "short-1", shortCoverShares = 1_000.0
            ),
            StockTransaction(
                stockCode = "2330", date = day * 2, recordTime = day * 2, type = "買券還券",
                shortCoverLotId = "short-1", shortCoverShares = 600.0
            )
        )

        assertFalse(ShortSellingCalculationSupport.hasValidCoverBalances(transactions))
    }

    @Test
    fun `fully covered short lot is excluded from opened principal`() {
        val day = 24L * 60 * 60 * 1000
        val transactions = listOf(
            StockTransaction(
                stockCode = "2330", date = 0, recordTime = 0, type = "融券賣出",
                sellPrice = 100.0, sellShares = 1_000.0, shortBorrowPrincipal = 100_000.0,
                shortLotId = "short-1"
            ),
            StockTransaction(
                stockCode = "2330", date = day, recordTime = day, type = "買券還券",
                shortCoverLotId = "short-1", shortCoverShares = 1_000.0
            )
        )

        val summary = ShortSellingCalculationSupport.calculate(transactions, day, 365)

        assertEquals(0.0, summary.outstandingShares, 0.0)
        assertEquals(0.0, summary.openedPrincipal, 0.0)
    }

    @Test
    fun compensationMustReferenceAnActiveShortLot() {
        val open = StockTransaction(
            stockCode = "2330", date = 0L, recordTime = 0L, type = "融券賣出",
            sellPrice = 100.0, sellShares = 1_000.0, shortLotId = "short-1"
        )
        val blankLot = StockTransaction(
            stockCode = "2330", date = 1L, recordTime = 1L, type = "融券補償",
            shortCompensation = 500.0
        )
        val validCompensation = blankLot.copy(shortCompensationLotId = "short-1")
        val fullCover = StockTransaction(
            stockCode = "2330", date = 1L, recordTime = 0L, type = "買券還券",
            shortCoverLotId = "short-1", shortCoverShares = 1_000.0
        )

        assertFalse(ShortSellingCalculationSupport.hasValidCoverBalances(listOf(open, blankLot)))
        assertTrue(ShortSellingCalculationSupport.hasValidCoverBalances(listOf(open, validCompensation)))
        assertFalse(ShortSellingCalculationSupport.hasValidCoverBalances(listOf(open, fullCover, validCompensation)))
    }

    @Test
    fun `stock split adjusts outstanding shares and cover validation`() {
        val day = 24L * 60 * 60 * 1000
        val open = StockTransaction(
            stockCode = "2330", date = 0L, recordTime = 0L, type = "融券賣出",
            sellPrice = 100.0, sellShares = 1_000.0, shortBorrowPrincipal = 100_000.0,
            shortLotId = "short-1"
        )
        val split = StockTransaction(
            stockCode = "2330", date = day, recordTime = day, type = "分割",
            stockSplitRatio = 10.0, sharesBeforeSplit = 1_000.0, sharesAfterSplit = 10_000.0
        )
        val cover = StockTransaction(
            stockCode = "2330", date = day * 2, recordTime = day * 2, type = "買券還券",
            shortCoverLotId = "short-1", shortCoverShares = 10_000.0
        )

        val afterSplit = ShortSellingCalculationSupport.calculate(listOf(open, split), day, 365)

        assertEquals(10_000.0, afterSplit.outstandingShares, 0.0)
        assertTrue(ShortSellingCalculationSupport.hasValidCoverBalances(listOf(open, split, cover)))
        assertFalse(ShortSellingCalculationSupport.hasValidCoverBalances(listOf(open, cover)))
        assertEquals(
            0.0,
            ShortSellingCalculationSupport.calculate(listOf(open, split, cover), day * 2, 365).outstandingShares,
            0.0
        )
    }

    @Test
    fun `capital reduction adjusts outstanding short shares`() {
        val day = 24L * 60 * 60 * 1000
        val open = StockTransaction(
            stockCode = "2330", date = 0L, recordTime = 0L, type = "融券賣出",
            sellPrice = 100.0, sellShares = 1_000.0, shortBorrowPrincipal = 100_000.0,
            shortLotId = "short-1"
        )
        val reduction = StockTransaction(
            stockCode = "2330", date = day, recordTime = day, type = "減資",
            capitalReductionRatio = 20.0, sharesBeforeReduction = 1_000.0,
            sharesAfterReduction = 800.0
        )

        val summary = ShortSellingCalculationSupport.calculate(listOf(open, reduction), day, 365)

        assertEquals(800.0, summary.outstandingShares, 0.0)
    }

    @Test
    fun `closed short keeps cumulative principal but clears remaining principal`() {
        val day = 365L * 24 * 60 * 60 * 1000
        val transactions = listOf(
            StockTransaction(
                stockCode = "2330", date = 0L, recordTime = 0L, type = "融券賣出",
                sellPrice = 100.0, sellShares = 1_000.0, income = 100_000.0,
                shortBorrowPrincipal = 100_000.0, shortLotId = "short-1"
            ),
            StockTransaction(
                stockCode = "2330", date = day, recordTime = day, type = "買券還券",
                expense = 90_000.0, shortCoverLotId = "short-1", shortCoverShares = 1_000.0
            )
        )

        val summary = ShortSellingCalculationSupport.calculate(transactions, day, 365)

        assertEquals(0.0, summary.openedPrincipal, 0.0)
        assertEquals(100_000.0, summary.cumulativeOpenedPrincipal, 0.0)
    }

    @Test
    fun `profitable closed short produces positive XIRR`() {
        val year = 365L * 24 * 60 * 60 * 1000
        val transactions = listOf(
            StockTransaction(
                stockCode = "2330", date = 0L, recordTime = 0L, type = "融券賣出",
                sellPrice = 100.0, sellShares = 1_000.0, income = 100_000.0,
                shortBorrowPrincipal = 100_000.0, shortLotId = "short-1"
            ),
            StockTransaction(
                stockCode = "2330", date = year, recordTime = year, type = "買券還券",
                expense = 90_000.0, shortCoverLotId = "short-1", shortCoverShares = 1_000.0
            )
        )

        val result = ReturnRateCalculator.calculateXirrPercentage(
            ShortSellingCalculationSupport.buildXirrCashFlows(transactions, year, 0.0, 365)
        )

        assertEquals(10.0, result!!, 0.05)
    }

    @Test
    fun `fully covered short XIRR keeps accrued borrow fee`() {
        val year = 365L * 24 * 60 * 60 * 1000
        val transactions = listOf(
            StockTransaction(
                stockCode = "2330", date = 0L, recordTime = 0L, type = "融券賣出",
                sellPrice = 100.0, sellShares = 1_000.0, income = 100_000.0,
                shortBorrowPrincipal = 100_000.0, shortBorrowAnnualRate = 3.65,
                shortLotId = "short-1"
            ),
            StockTransaction(
                stockCode = "2330", date = year, recordTime = year, type = "買券還券",
                expense = 90_000.0, shortCoverLotId = "short-1", shortCoverShares = 1_000.0
            )
        )

        val cashFlows = ShortSellingCalculationSupport.buildXirrCashFlows(transactions, year, 0.0, 365)
        val result = ReturnRateCalculator.calculateXirrPercentage(cashFlows)

        assertEquals(-3_650.0, cashFlows.filter { it.dateMillis == year }.sumOf { it.amount } - 110_000.0, 0.01)
        assertEquals(6.35, result!!, 0.05)
    }

    @Test
    fun `identical short lot ids in separate accounts do not cross-cover`() {
        val day = 24L * 60 * 60 * 1000
        val firstAccountOpen = StockTransaction(
            stockCode = "2330", accountId = 1, date = 0L, recordTime = 0L,
            type = "融券賣出", sellPrice = 100.0, sellShares = 1_000.0,
            shortBorrowPrincipal = 100_000.0, shortLotId = "shared"
        )
        val secondAccountOpen = firstAccountOpen.copy(
            accountId = 2,
            date = day,
            recordTime = day,
            sellShares = 2_000.0,
            shortBorrowPrincipal = 200_000.0
        )
        val firstAccountCover = StockTransaction(
            stockCode = "2330", accountId = 1, date = day * 2, recordTime = day * 2,
            type = "買券還券", shortCoverLotId = "shared", shortCoverShares = 1_000.0
        )

        val transactions = listOf(firstAccountOpen, secondAccountOpen, firstAccountCover)
        val summary = ShortSellingCalculationSupport.calculate(transactions, day * 2, 365)

        assertTrue(ShortSellingCalculationSupport.hasValidCoverBalances(transactions))
        assertEquals(2_000.0, summary.outstandingShares, 0.0)
    }

    @Test
    fun `company action only adjusts short lots in the matching account`() {
        val day = 24L * 60 * 60 * 1000
        val firstAccountOpen = StockTransaction(
            stockCode = "2330", accountId = 1, date = 0L, recordTime = 0L,
            type = "融券賣出", sellPrice = 100.0, sellShares = 1_000.0,
            income = 100_000.0, shortBorrowPrincipal = 100_000.0,
            shortLotId = "account-1"
        )
        val secondAccountOpen = firstAccountOpen.copy(
            accountId = 2,
            recordTime = 1L,
            shortLotId = "account-2"
        )
        val firstAccountSplit = StockTransaction(
            stockCode = "2330", accountId = 1, date = day, recordTime = day,
            type = "分割", stockSplitRatio = 2.0,
            sharesBeforeSplit = 1_000.0, sharesAfterSplit = 2_000.0
        )
        val validSecondAccountCover = StockTransaction(
            stockCode = "2330", accountId = 2, date = day * 2, recordTime = day * 2,
            type = "買券還券", shortCoverLotId = "account-2", shortCoverShares = 1_000.0
        )
        val excessiveSecondAccountCover = validSecondAccountCover.copy(shortCoverShares = 1_500.0)
        val transactions = listOf(firstAccountOpen, secondAccountOpen, firstAccountSplit)

        val summary = ShortSellingCalculationSupport.calculate(transactions, day, 365)
        val terminalCashFlow = ShortSellingCalculationSupport
            .buildXirrCashFlows(transactions, day, currentPrice = 100.0, dayCount = 365)
            .filter { it.dateMillis == day }
            .sumOf { it.amount }

        assertEquals(3_000.0, summary.outstandingShares, 0.0)
        assertEquals(100_000.0, terminalCashFlow, 0.0)
        assertTrue(
            ShortSellingCalculationSupport.hasValidCoverBalances(
                transactions + validSecondAccountCover
            )
        )
        assertFalse(
            ShortSellingCalculationSupport.hasValidCoverBalances(
                transactions + excessiveSecondAccountCover
            )
        )
    }
}
