package com.rsps1008.stockify

import com.rsps1008.stockify.data.HistoryChartCalculationSupport
import com.rsps1008.stockify.data.StockHistoryPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun hasHistoryForAllStocks_requiresEveryStockToHaveAUsablePrice() {
        assertFalse(
            HistoryChartCalculationSupport.hasHistoryForAllStocks(
                stockCodes = listOf("0050", "2330"),
                allRawPoints = mapOf(
                    "0050" to listOf(StockHistoryPoint("2026-07-01", 100.0))
                )
            )
        )
        assertTrue(
            HistoryChartCalculationSupport.hasHistoryForAllStocks(
                stockCodes = listOf("0050", "2330"),
                allRawPoints = mapOf(
                    "0050" to listOf(StockHistoryPoint("2026-07-01", 100.0)),
                    "2330" to listOf(StockHistoryPoint("2026-07-01", 80.0))
                )
            )
        )
    }

    @Test
    fun hasHistoryCoverage_rejectsOneMonthOfPointsForLongerRange() {
        val points = listOf(
            StockHistoryPoint("2026-07-01", 100.0),
            StockHistoryPoint("2026-08-01", 110.0)
        )

        assertTrue(HistoryChartCalculationSupport.hasHistoryCoverage(points, 1))
        assertFalse(HistoryChartCalculationSupport.hasHistoryCoverage(points, 6))
    }

    @Test
    fun priceAtOrBefore_doesNotTreatMissingEarlierHistoryAsZero() {
        val points = listOf(StockHistoryPoint("2026-07-20", 120.0))

        assertNull(HistoryChartCalculationSupport.priceAtOrBefore(points, "2026-07-19"))
        assertEquals(120.0, HistoryChartCalculationSupport.priceAtOrBefore(points, "2026-07-20"))
    }

    @Test
    fun hasHistoryAtOrBeforeForStocks_requiresPriceForActiveStocks() {
        val history = mapOf(
            "0050" to listOf(StockHistoryPoint("2026-07-01", 100.0)),
            "2330" to listOf(StockHistoryPoint("2026-07-20", 80.0))
        )

        assertFalse(
            HistoryChartCalculationSupport.hasHistoryAtOrBeforeForStocks(
                date = "2026-07-19",
                stockCodes = listOf("0050", "2330"),
                allRawPoints = history
            )
        )
        assertTrue(
            HistoryChartCalculationSupport.hasHistoryAtOrBeforeForStocks(
                date = "2026-07-19",
                stockCodes = listOf("0050"),
                allRawPoints = history
            )
        )
    }
}
