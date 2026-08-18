package com.rsps1008.stockify

import com.rsps1008.stockify.data.RealtimeStockInfo
import com.rsps1008.stockify.data.mergeRealtimeStockInfoMaps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeStockDataServiceTest {

    @Test
    fun mergeRealtimeStockInfoMapsPreservesConcurrentKeysAndReplacesUpdatedKeys() {
        val current = mapOf(
            "2330" to RealtimeStockInfo(currentPrice = 100.0, change = 1.0, changePercent = 1.0),
            "AAPL" to RealtimeStockInfo(currentPrice = 200.0, change = 2.0, changePercent = 1.0)
        )
        val updates = mapOf(
            "2330" to RealtimeStockInfo(currentPrice = 101.0, change = 2.0, changePercent = 2.0),
            "TSM" to RealtimeStockInfo(currentPrice = 300.0, change = 3.0, changePercent = 1.0)
        )

        val merged = mergeRealtimeStockInfoMaps(current, updates)

        assertEquals(3, merged.size)
        assertEquals(101.0, merged.getValue("2330").currentPrice, 0.0)
        assertEquals(200.0, merged.getValue("AAPL").currentPrice, 0.0)
        assertEquals(300.0, merged.getValue("TSM").currentPrice, 0.0)
        assertTrue(merged !== current)
    }
}
