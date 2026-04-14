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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class UsYahooStockInfoFetcher : StockInfoFetcher {

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

    override suspend fun fetchStockInfo(stockCode: String): RealtimeStockInfo? = withContext(Dispatchers.IO) {
        fetchStockInfoInternal(stockCode)
    }

    private suspend fun fetchStockInfoInternal(stockCode: String): RealtimeStockInfo? {
        val url = "https://query1.finance.yahoo.com/v8/finance/chart/$stockCode?interval=1d&range=1d&includePrePost=false&events=div%2Csplits"
        return try {
            val responseText = client.get(url) {
                headers.append("User-Agent", "Mozilla/5.0")
            }.bodyAsText()

            val root = Json.parseToJsonElement(responseText).jsonObject
            val chart = root["chart"]?.jsonObject ?: return null
            val results = chart["result"]?.jsonArray ?: return null
            val result = results.firstOrNull()?.jsonObject ?: return null
            val meta = result["meta"]?.jsonObject ?: return null

            val price = meta["regularMarketPrice"]?.jsonPrimitive?.doubleOrNull
            val previousClose = meta["chartPreviousClose"]?.jsonPrimitive?.doubleOrNull
                ?: meta["previousClose"]?.jsonPrimitive?.doubleOrNull
            val change = meta["regularMarketChange"]?.jsonPrimitive?.doubleOrNull
                ?: (price?.let { current -> previousClose?.let { current - it } })
            val changePercent = meta["regularMarketChangePercent"]?.jsonPrimitive?.doubleOrNull
                ?: (price?.let { current -> previousClose?.let { if (it != 0.0) ((current - it) / it) * 100 else 0.0 } })

            if (price != null && previousClose != null) {
                val info = RealtimeStockInfo(
                    currentPrice = price,
                    change = change ?: 0.0,
                    changePercent = changePercent ?: 0.0,
                    limitState = LimitState.NONE
                )
                Log.d("UsYahooStockInfoFetcher", "US Yahoo Fetched $stockCode -> $info from $url")
                return info
            }

            Log.e("UsYahooStockInfoFetcher", "Missing price data for $stockCode from $url")
            null
        } catch (e: Exception) {
            Log.e("UsYahooStockInfoFetcher", "Exception while fetching stock info for $stockCode: ${e.message}", e)
            null
        }
    }
}
