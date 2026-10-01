package com.rsps1008.stockify

import com.rsps1008.stockify.data.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class QuoteRefreshCoordinatorTest {
    private fun tw(code: String) = Stock(code = code, name = code, market = "TW", exchange = StockExchange.LISTED)
    private fun quote(price: Double = 100.0) = RealtimeStockInfo(price, 1.0, 1.0)

    @Test
    fun completedBatchAndUsQuotePublishWhileEarlierBatchIsStillPending() = runBlocking {
        val blocked = CompletableDeferred<Unit>()
        val updates = Channel<Map<String, RealtimeStockInfo>>(Channel.UNLIMITED)
        val stocks = (1..10).map { tw(it.toString()) } + Stock(code = "AAPL", name = "Apple", market = "US")
        val job = async {
            refreshQuotesIncrementally(stocks, true,
                fetchBatch = { batch ->
                    assertTrue(batch.size <= 5)
                    if (batch.first().code == "1") blocked.await()
                    QuoteBatchOutcome(batch.associate { it.code to quote() })
                },
                fetchSingle = { QuoteFetchOutcome(quote(200.0)) },
                fetchTaiwanFallback = { error("No fallback expected") },
                publish = { updates.send(it) })
        }
        try {
            val early = withTimeout(5_000) { updates.receive().keys + updates.receive().keys }
            assertEquals(setOf("TW:6", "TW:7", "TW:8", "TW:9", "TW:10", "US:AAPL"), early)
            assertFalse(job.isCompleted)
            blocked.complete(Unit)
            assertEquals(11, withTimeout(5_000) { job.await() }.size)
        } finally { job.cancelAndJoin() }
    }

    @Test
    fun successfulBatchRowsAreVisibleBeforeMissingRowFallbackAndFailuresPreserveOldQuotes() = runBlocking {
        val fallbackStarted = CompletableDeferred<Unit>()
        val releaseFallback = CompletableDeferred<Unit>()
        val firstPublished = CompletableDeferred<Unit>()
        var cache = mapOf("TW:2" to quote(80.0), "US:AAPL" to quote(90.0))
        val job = async {
            refreshQuotesIncrementally(listOf(tw("1"), tw("2")), true,
                fetchBatch = { QuoteBatchOutcome(mapOf("1" to quote(101.0))) },
                fetchSingle = { error("Batch expected") },
                fetchTaiwanFallback = {
                    fallbackStarted.complete(Unit)
                    releaseFallback.await()
                    QuoteFetchOutcome(null)
                },
                publish = {
                    cache = mergeRealtimeStockInfoMaps(cache, it)
                    firstPublished.complete(Unit)
                })
        }
        try {
            withTimeout(5_000) { firstPublished.await(); fallbackStarted.await() }
            assertEquals(101.0, cache.getValue("TW:1").currentPrice, 0.0)
            assertEquals(80.0, cache.getValue("TW:2").currentPrice, 0.0)
            assertEquals(90.0, cache.getValue("US:AAPL").currentPrice, 0.0)
            releaseFallback.complete(Unit)
            assertEquals(1, job.await().count { it.outcome.fallbackUsed })
        } finally { job.cancelAndJoin() }
    }

    @Test
    fun yahooPrimaryRefreshesListedOtcEmergingAndUsAndDeduplicatesRequests() = runBlocking {
        val stocks = listOf(tw("2330"), tw("6488").copy(exchange = StockExchange.OTC),
            tw("1234").copy(exchange = StockExchange.EMERGING), Stock(code = "AAPL", name = "Apple", market = "US"))
        val called = mutableSetOf<String>()
        val results = refreshQuotesIncrementally(stocks + stocks, false,
            fetchBatch = { error("Yahoo must include all stocks via single requests") },
            fetchSingle = { called += it.toStockKey().cacheKey(); QuoteFetchOutcome(quote()) },
            fetchTaiwanFallback = { error("No batch fallback expected") }, publish = {})
        assertEquals(setOf("TW:2330", "TW:6488", "TW:1234", "US:AAPL"), called)
        assertEquals(4, results.size)
    }

    @Test
    fun requestsAcrossBatchesMarketsAndFallbacksShareThreePermits() = runBlocking {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val fallbackCalls = AtomicInteger()
        suspend fun request() {
            val count = active.incrementAndGet()
            peak.updateAndGet { maxOf(it, count) }
            delay(10)
            active.decrementAndGet()
        }
        val stocks = (1..21).map { tw(it.toString()) } + (1..6).map { Stock(code = "US$it", name = "US", market = "US") }
        val results = refreshQuotesIncrementally(stocks, true,
            fetchBatch = { request(); QuoteBatchOutcome(emptyMap(), certificateFailure = true) },
            fetchSingle = { request(); QuoteFetchOutcome(quote()) },
            fetchTaiwanFallback = { request(); fallbackCalls.incrementAndGet(); QuoteFetchOutcome(quote()) }, publish = {})
        assertEquals(3, peak.get())
        assertEquals(0, active.get())
        assertEquals(21, fallbackCalls.get())
        assertEquals(21, results.count { it.outcome.certificateFailure })
        assertEquals(27, results.size)
    }

    @Test
    fun cancellationStopsOutstandingRequestsWithoutPublishingEmptyUpdates() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        var published = false
        val job = launch {
            refreshQuotesIncrementally(listOf(tw("1")), true,
                fetchBatch = {
                    started.complete(Unit)
                    try { CompletableDeferred<Unit>().await(); error("unreachable") }
                    catch (e: CancellationException) { stopped.complete(Unit); throw e }
                }, fetchSingle = { error("Unexpected") }, fetchTaiwanFallback = { error("Unexpected") },
                publish = { published = true })
        }
        withTimeout(5_000) { started.await() }
        job.cancelAndJoin()
        assertTrue(stopped.isCompleted)
        assertFalse(published)
    }
}
