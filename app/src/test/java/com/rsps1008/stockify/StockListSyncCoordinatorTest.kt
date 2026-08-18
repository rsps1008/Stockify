package com.rsps1008.stockify

import com.rsps1008.stockify.data.StockListSyncCoordinator
import com.rsps1008.stockify.data.StockMarket
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StockListSyncCoordinatorTest {

    @Test
    fun concurrentSameMarketSyncRunsOnlyOneBlock() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val executions = AtomicInteger(0)

        val first = async {
            StockListSyncCoordinator.runIfNotRunning(StockMarket.TW) {
                executions.incrementAndGet()
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()

        val second = async {
            StockListSyncCoordinator.runIfNotRunning(StockMarket.TW) {
                executions.incrementAndGet()
            }
        }

        assertFalse(second.await())
        release.complete(Unit)
        assertTrue(first.await())
        assertTrue(executions.get() == 1)
    }
}
