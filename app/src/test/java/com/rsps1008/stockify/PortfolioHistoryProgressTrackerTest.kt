package com.rsps1008.stockify

import com.rsps1008.stockify.ui.viewmodel.PortfolioHistoryProgressTracker
import org.junit.Assert.assertEquals
import org.junit.Test

class PortfolioHistoryProgressTrackerTest {

    @Test
    fun progressAggregatesOutOfOrderStocksAndNeverRegresses() {
        val tracker = PortfolioHistoryProgressTracker(stockCount = 2)

        assertEquals(0.125f, tracker.update(stockIndex = 0, step = 1, total = 4), 0.0001f)
        assertEquals(0.625f, tracker.update(stockIndex = 1, step = 1, total = 1), 0.0001f)
        assertEquals(0.875f, tracker.update(stockIndex = 0, step = 3, total = 4), 0.0001f)
        assertEquals(0.875f, tracker.update(stockIndex = 0, step = 1, total = 4), 0.0001f)
        assertEquals(1.0f, tracker.markComplete(stockIndex = 0), 0.0001f)
    }
}
