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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class NasdaqStockInfoFetcher : StockInfoFetcher {

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
        fetchStockInfoInternal(stockCode, stockType)
    }

    private suspend fun fetchStockInfoInternal(stockCode: String, stockType: String = ""): RealtimeStockInfo? {
        val assetClass = if (stockType.trim().equals("ETF", ignoreCase = true)) "etf" else "stocks"
        val url = "https://api.nasdaq.com/api/quote/$stockCode/info?assetclass=$assetClass"
        return retryOnTransientNetworkFailure("NasdaqStockInfoFetcher", stockCode) {
            val responseText = fetchSemaphore.withPermit {
                client.get(url) {
                    headers.append("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                    headers.append("Accept", "application/json, text/plain, */*")
                    headers.append("Accept-Language", "en-US,en;q=0.9")
                    headers.append("Referer", "https://www.nasdaq.com/")
                    headers.append("Origin", "https://www.nasdaq.com")
                    headers.append("Sec-Fetch-Site", "same-site")
                    headers.append("Sec-Fetch-Mode", "cors")
                    headers.append("Sec-Fetch-Dest", "empty")
                    headers.append("Cache-Control", "no-cache")
                    headers.append("Pragma", "no-cache")
                }.bodyAsText()
            }

            Log.d(
                "NasdaqStockInfoFetcher",
                "Raw response for $stockCode ($assetClass) from $url: ${responseText.take(2000)}"
            )

            val root = Json.parseToJsonElement(responseText) as? JsonObject
                ?: run {
                    Log.e("NasdaqStockInfoFetcher", "Unexpected Nasdaq root JSON for $stockCode from $url: ${responseText.take(200)}")
                    return@retryOnTransientNetworkFailure null
                }
            val data = root["data"].asJsonObjectOrNull()
                ?: run {
                    Log.e("NasdaqStockInfoFetcher", "Missing Nasdaq data object for $stockCode from $url: ${responseText.take(200)}")
                    return@retryOnTransientNetworkFailure null
                }
            val primaryData = data["primaryData"].asJsonObjectOrNull()
                ?: run {
                    Log.e("NasdaqStockInfoFetcher", "Missing Nasdaq primaryData object for $stockCode from $url: ${responseText.take(200)}")
                    return@retryOnTransientNetworkFailure null
                }

            val lastSalePrice = primaryData["lastSalePrice"]?.jsonPrimitive?.contentOrNull
            val netChange = primaryData["netChange"]?.jsonPrimitive?.contentOrNull
            val percentageChange = primaryData["percentageChange"]?.jsonPrimitive?.contentOrNull

            val price = lastSalePrice
                ?.removePrefix("$")
                ?.replace(",", "")
                ?.toDoubleOrNull()
            val change = netChange
                ?.replace(",", "")
                ?.replace("%", "")
                ?.replace("$", "")
                ?.toDoubleOrNull() ?: 0.0
            val changePercent = percentageChange
                ?.replace("%", "")
                ?.replace(",", "")
                ?.toDoubleOrNull() ?: 0.0

            if (price != null) {
                val info = RealtimeStockInfo(
                    currentPrice = price,
                    change = change,
                    changePercent = changePercent,
                    limitState = LimitState.NONE
                )
                Log.d("NasdaqStockInfoFetcher", "Nasdaq Fetched $stockCode -> $info from $url")
                info
            } else {
                Log.e(
                    "NasdaqStockInfoFetcher",
                    "Missing price data for $stockCode from $url. lastSalePrice=$lastSalePrice, netChange=$netChange, percentageChange=$percentageChange"
                )
                null
            }
        }
    }

    private fun JsonElement?.asJsonObjectOrNull(): JsonObject? = this as? JsonObject
}
