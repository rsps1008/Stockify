package com.rsps1008.stockify

import com.rsps1008.stockify.data.StockHistoryCache
import com.rsps1008.stockify.data.StockHistoryPoint
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
}
