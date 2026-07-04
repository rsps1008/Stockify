package com.rsps1008.stockify

import com.rsps1008.stockify.data.HistoryChartCalculationSupport
import com.rsps1008.stockify.data.StockHistoryPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HistoryChartCalculationSupportTest {

    @Test
    fun filterEmptyHistorySeries_removesStocksWithoutAnyHistoryPoints() {
        val result = HistoryChartCalculationSupport.filterEmptyHistorySeries(
            mapOf(
                "0050" to listOf(StockHistoryPoint("2026-07-01", 100.0)),
                "TSM" to emptyList()
            )
        )

        assertEquals(1, result.size)
        assertEquals(listOf(StockHistoryPoint("2026-07-01", 100.0)), result["0050"])
        assertFalse(result.containsKey("TSM"))
    }
}
