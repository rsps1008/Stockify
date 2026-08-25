package com.rsps1008.stockify.data

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class UsYahooStockInfoFetcher : StockInfoFetcher {

    private val fetchSemaphore = Semaphore(permits = 3)

    private val client = HttpClient(CIO) {
        engine {
            requestTimeout = 5000
        }
    }

    override fun isMarketOpen(): Boolean {
        val newYorkZone = ZoneId.of("America/New_York")
        val now = ZonedDateTime.now(newYorkZone)
        val dayOfWeek = now.dayOfWeek
        val currentTime = now.toLocalTime()

        val isWeekday = dayOfWeek in DayOfWeek.MONDAY..DayOfWeek.FRIDAY
        val isTradingTime =
            !currentTime.isBefore(LocalTime.of(9, 30)) &&
                currentTime.isBefore(LocalTime.of(16, 0))

        return isWeekday && isTradingTime
    }

    override suspend fun fetchStockInfoList(stockCodes: List<String>): Map<String, RealtimeStockInfo> =
        withContext(Dispatchers.IO) {
            stockCodes.map { code ->
                async {
                    fetchStockInfoInternal(code)?.let { info ->
                        code to info
                    }
                }
            }.awaitAll().filterNotNull().toMap()
        }

    override suspend fun fetchStockInfo(stockCode: String, stockType: String): RealtimeStockInfo? = withContext(Dispatchers.IO) {
        fetchStockInfoInternal(stockCode)
    }

    private suspend fun fetchStockInfoInternal(stockCode: String): RealtimeStockInfo? {
        val url = "https://query1.finance.yahoo.com/v8/finance/chart/$stockCode?interval=1d&range=1d&includePrePost=false&events=div%2Csplits"
        return retryOnTransientNetworkFailure("UsYahooStockInfoFetcher", stockCode) {
            val responseText = fetchSemaphore.withPermit {
                client.get(url) {
                    headers.append("User-Agent", "Mozilla/5.0")
                }.bodyAsText()
            }

            val root = try {
                Json.parseToJsonElement(responseText) as? JsonObject
            } catch (e: Exception) {
                Log.e(
                    "UsYahooStockInfoFetcher",
                    "Invalid Yahoo JSON for $stockCode from $url: ${responseText.take(200)}",
                    e
                )
                null
            } ?: run {
                Log.e(
                    "UsYahooStockInfoFetcher",
                    "Unexpected Yahoo root JSON for $stockCode from $url: ${responseText.take(200)}"
                )
                return@retryOnTransientNetworkFailure null
            }
            val chart = root["chart"] as? JsonObject ?: run {
                Log.e("UsYahooStockInfoFetcher", "Missing Yahoo chart object for $stockCode from $url")
                return@retryOnTransientNetworkFailure null
            }
            val results = chart["result"] as? JsonArray ?: run {
                Log.e("UsYahooStockInfoFetcher", "Missing Yahoo result array for $stockCode from $url")
                return@retryOnTransientNetworkFailure null
            }
            val result = results.firstOrNull() as? JsonObject ?: run {
                Log.e("UsYahooStockInfoFetcher", "Missing Yahoo result item for $stockCode from $url")
                return@retryOnTransientNetworkFailure null
            }
            val meta = result["meta"] as? JsonObject ?: run {
                Log.e("UsYahooStockInfoFetcher", "Missing Yahoo meta object for $stockCode from $url")
                return@retryOnTransientNetworkFailure null
            }

            val price = meta["regularMarketPrice"].asDoubleOrNull()
            val previousClose = meta["chartPreviousClose"].asDoubleOrNull()
                ?: meta["previousClose"].asDoubleOrNull()
            val change = meta["regularMarketChange"].asDoubleOrNull()
                ?: (price?.let { current -> previousClose?.let { current - it } })
            val changePercent = meta["regularMarketChangePercent"].asDoubleOrNull()
                ?: (price?.let { current -> previousClose?.let { if (it != 0.0) ((current - it) / it) * 100 else 0.0 } })

            val validPrice = price.takeIf { it.isFinitePositive() }
            val validPreviousClose = previousClose.takeIf { it.isFinitePositive() }
            if (validPrice != null && validPreviousClose != null) {
                val info = RealtimeStockInfo(
                    currentPrice = validPrice,
                    change = change.finiteOrZero(),
                    changePercent = changePercent.finiteOrZero(),
                    limitState = LimitState.NONE
                )
                Log.d("UsYahooStockInfoFetcher", "US Yahoo Fetched $stockCode -> $info from $url")
                info
            } else {
                Log.e("UsYahooStockInfoFetcher", "Missing price data for $stockCode from $url")
                null
            }
        }
    }

    private fun JsonElement?.asDoubleOrNull(): Double? =
        (this as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }
}
