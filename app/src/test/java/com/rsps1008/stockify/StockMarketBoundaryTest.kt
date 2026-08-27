package com.rsps1008.stockify

import com.rsps1008.stockify.data.MarginCalculationSupport
import com.rsps1008.stockify.data.StockKey
import com.rsps1008.stockify.data.StockMarket
import com.rsps1008.stockify.data.StockTransaction
import org.junit.Assert.assertEquals
import org.junit.Test

class StockMarketBoundaryTest {

    @Test
    fun sameCodeInDifferentMarketsUsesDifferentIdentity() {
        assertEquals("TW:ABC", StockKey(StockMarket.TW, "abc").cacheKey())
        assertEquals("US:ABC", StockKey(StockMarket.US, "abc").cacheKey())
    }

    @Test
    fun mixedAlphaNumericTaiwanCodeIsNotInferredAsUs() {
        assertEquals(StockMarket.TW, StockMarket.inferFromCode("00981A"))
        assertEquals(StockMarket.TW, StockMarket.inferFromCode("AB12"))
        assertEquals(StockMarket.US, StockMarket.inferFromCode("AAPL"))
        assertEquals(StockMarket.US, StockMarket.inferFromCode("BRK.B"))
    }

    @Test
    fun financingLotsDoNotMixSameCodeAcrossMarkets() {
        val transactions = listOf(
            StockTransaction(
                stockCode = "ABC",
                market = StockMarket.TW,
                date = 1L,
                recordTime = 1L,
                type = "融資買進",
                buyPrice = 1.0,
                buyShares = 100.0,
                expense = 100.0,
                marginPrincipal = 100.0,
                marginLotId = "same-lot"
            ),
            StockTransaction(
                stockCode = "ABC",
                market = StockMarket.US,
                date = 2L,
                recordTime = 2L,
                type = "融資還款",
                expense = 100.0,
                marginRepayment = 100.0,
                marginRepaymentLotId = "same-lot"
            )
        )

        assertEquals(false, MarginCalculationSupport.hasValidRepaymentBalances(transactions))
        assertEquals(
            true,
            MarginCalculationSupport.hasValidRepaymentBalances(
                transactions.map { it.copy(market = StockMarket.TW) }
            )
        )
    }
}
