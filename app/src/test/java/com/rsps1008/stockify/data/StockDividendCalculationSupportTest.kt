package com.rsps1008.stockify.data

import org.junit.Assert.assertEquals
import org.junit.Test

class StockDividendCalculationSupportTest {
    @Test
    fun taiwanStockDividendConvertsAmountUsingTenDollarParValue() {
        assertEquals(
            45.0,
            calculateStockDividendShares(
                stockDividend = 0.45,
                exRightsShares = 1_000.0,
                market = StockMarket.TW
            ),
            0.0
        )
    }

    @Test
    fun usStockDividendRemainsSharesPerShare() {
        assertEquals(
            450.0,
            calculateStockDividendShares(
                stockDividend = 0.45,
                exRightsShares = 1_000.0,
                market = StockMarket.US
            ),
            0.0
        )
    }
}
