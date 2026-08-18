package com.rsps1008.stockify.data

import kotlinx.coroutines.sync.Mutex

/** Prevents lifecycle callbacks and manual refreshes from updating one market twice at once. */
object StockListSyncCoordinator {
    private val marketLocks = mapOf(
        StockMarket.TW to Mutex(),
        StockMarket.US to Mutex()
    )

    suspend fun runIfNotRunning(market: String, block: suspend () -> Unit): Boolean {
        val lock = marketLocks.getValue(StockMarket.normalize(market))
        if (!lock.tryLock()) return false
        try {
            block()
            return true
        } finally {
            lock.unlock()
        }
    }
}
