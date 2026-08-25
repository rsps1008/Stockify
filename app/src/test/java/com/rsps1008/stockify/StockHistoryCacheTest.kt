package com.rsps1008.stockify

import com.rsps1008.stockify.data.StockHistoryCache
import com.rsps1008.stockify.data.StockHistoryPoint
import com.rsps1008.stockify.data.mergeHistoryPointsPreferLatest
import com.rsps1008.stockify.data.shouldUseHistoryMonthWithoutRefresh
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StockHistoryCacheTest {

    @Test
    fun clearRejectsWriteFromPreviousGeneration() {
        val cache = StockHistoryCache()
        val key = "TW:2330_202601"
        val points = listOf(StockHistoryPoint("2026-01-02", 100.0))
        val generation = cache.currentGeneration()

        assertTrue(cache.putIfCurrent(key, points, generation))
        assertEquals(points, cache.get(key))

        cache.clear()

        assertNull(cache.get(key))
        assertFalse(cache.putIfCurrent(key, points, generation))
        assertNull(cache.get(key))
    }

    @Test
    fun currentGenerationCanWriteAfterClear() {
        val cache = StockHistoryCache()
        val key = "US:ETF_202601"
        val points = listOf(StockHistoryPoint("2026-01-02", 100.0))

        cache.clear()
        val generation = cache.currentGeneration()

        assertTrue(cache.putIfCurrent(key, points, generation))
        assertEquals(points, cache.get(key))
    }

    @Test
    fun currentMonthCacheRequiresLatestExpectedDate() {
        val partial = listOf(StockHistoryPoint("2026-08-24", 100.0))
        val complete = partial + StockHistoryPoint("2026-08-25", 101.0)

        assertFalse(
            shouldUseHistoryMonthWithoutRefresh(
                month = "202608",
                latestChartMonth = "202608",
                latestChartDate = "2026-08-25",
                points = partial
            )
        )
        assertTrue(
            shouldUseHistoryMonthWithoutRefresh(
                month = "202608",
                latestChartMonth = "202608",
                latestChartDate = "2026-08-25",
                points = complete
            )
        )
    }

    @Test
    fun manualRefreshBypassesEvenACompleteCurrentMonthCache() {
        assertFalse(
            shouldUseHistoryMonthWithoutRefresh(
                month = "202608",
                latestChartMonth = "202608",
                latestChartDate = "2026-08-25",
                points = listOf(StockHistoryPoint("2026-08-25", 101.0)),
                forceRefreshCurrentMonth = true
            )
        )
        assertTrue(
            shouldUseHistoryMonthWithoutRefresh(
                month = "202607",
                latestChartMonth = "202608",
                latestChartDate = "2026-08-25",
                points = emptyList(),
                forceRefreshCurrentMonth = true
            )
        )
    }

    @Test
    fun refreshedPointReplacesTheCachedValueForTheSameDate() {
        val cached = StockHistoryPoint("2026-08-25", 100.0)
        val refreshed = StockHistoryPoint("2026-08-25", 105.0)

        assertEquals(
            listOf(refreshed),
            mergeHistoryPointsPreferLatest(listOf(cached, refreshed))
        )
    }
}
