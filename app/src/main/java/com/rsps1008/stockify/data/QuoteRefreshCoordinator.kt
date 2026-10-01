package com.rsps1008.stockify.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal const val TWSE_QUOTE_BATCH_SIZE = 5

internal data class QuoteFetchOutcome(
    val info: RealtimeStockInfo?,
    val fallbackUsed: Boolean = false,
    val certificateFailure: Boolean = false
)

internal data class QuoteBatchOutcome(
    val infos: Map<String, RealtimeStockInfo>,
    val certificateFailure: Boolean = false
)

internal data class QuoteFetchResult(val key: String, val outcome: QuoteFetchOutcome)

/** Publish each completed request before waiting for slower batches or fallback requests. */
internal suspend fun refreshQuotesIncrementally(
    stocks: List<Stock>,
    useTwseBatches: Boolean,
    fetchBatch: suspend (List<Stock>) -> QuoteBatchOutcome,
    fetchSingle: suspend (Stock) -> QuoteFetchOutcome,
    fetchTaiwanFallback: suspend (Stock) -> QuoteFetchOutcome,
    publish: suspend (Map<String, RealtimeStockInfo>) -> Unit
): List<QuoteFetchResult> = coroutineScope {
    val permits = Semaphore(MAX_PARALLEL_STOCK_REQUESTS)
    val (batchStocks, singleStocks) = stocks.distinctBy { it.toStockKey().cacheKey() }
        .partition { useTwseBatches && StockMarket.isTw(it.market) && !StockExchange.isEmerging(it.exchange) }

    suspend fun publishResult(stock: Stock, outcome: QuoteFetchOutcome): QuoteFetchResult {
        val key = stock.toStockKey().cacheKey()
        outcome.info?.let { publish(mapOf(key to it)) }
        return QuoteFetchResult(key, outcome)
    }

    val batches = batchStocks.chunked(TWSE_QUOTE_BATCH_SIZE).map { batch ->
        async {
            val response = permits.withPermit { fetchBatch(batch) }
            val updates = batch.mapNotNull { stock ->
                response.infos[stock.code]?.let { stock.toStockKey().cacheKey() to it }
            }.toMap()
            if (updates.isNotEmpty()) publish(updates)

            batch.map { stock ->
                async {
                    val info = response.infos[stock.code]
                    if (info != null) {
                        QuoteFetchResult(stock.toStockKey().cacheKey(), QuoteFetchOutcome(info))
                    } else {
                        val fallback = permits.withPermit { fetchTaiwanFallback(stock) }
                        publishResult(stock, fallback.copy(
                            fallbackUsed = true,
                            certificateFailure = fallback.certificateFailure || response.certificateFailure
                        ))
                    }
                }
            }.awaitAll()
        }
    }
    val singles = singleStocks.map { stock ->
        async { publishResult(stock, permits.withPermit { fetchSingle(stock) }) }
    }
    batches.awaitAll().flatten() + singles.awaitAll()
}
