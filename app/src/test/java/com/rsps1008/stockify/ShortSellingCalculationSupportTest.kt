package com.rsps1008.stockify

import com.rsps1008.stockify.data.ShortSellingCalculationSupport
import com.rsps1008.stockify.data.StockTransaction
import org.junit.Assert.assertEquals
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
    }
}
