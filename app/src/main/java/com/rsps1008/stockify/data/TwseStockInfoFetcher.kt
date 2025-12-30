package com.rsps1008.stockify.data

import android.annotation.SuppressLint
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class TwseStockInfoFetcher : StockInfoFetcher {

    private val client = HttpClient(CIO) {
        engine {
            requestTimeout = 5000
        }
    }

    override fun isMarketOpen(): Boolean {
        val taipeiZone = ZoneId.of("Asia/Taipei")
        val now = ZonedDateTime.now(taipeiZone)
        val dayOfWeek = now.dayOfWeek
        val currentTime = now.toLocalTime()

        val isWeekday = dayOfWeek in DayOfWeek.MONDAY..DayOfWeek.FRIDAY
        val isTradingTime =
            currentTime.isAfter(LocalTime.of(9, 0)) &&
                    currentTime.isBefore(LocalTime.of(13, 30))

        return isWeekday && isTradingTime
    }

    override suspend fun fetchStockInfoList(
        stockCodes: List<String>
    ): Map<String, RealtimeStockInfo> = withContext(Dispatchers.IO) {
        stockCodes.map { code ->
            async {
                fetchStockInfoInternal(code)?.let { info ->
                    code to info
                }
            }
        }.awaitAll().filterNotNull().toMap()
    }

    override suspend fun fetchStockInfo(stockCode: String): RealtimeStockInfo? =
        withContext(Dispatchers.IO) {
            fetchStockInfoInternal(stockCode)
        }

    @SuppressLint("UnsafeOptInUsageError")
    private suspend fun fetchStockInfoInternal(stockCode: String): RealtimeStockInfo? {
        val code = if (stockCode.contains(".")) stockCode else "$stockCode.tw"
        val urls = listOf(
            "https://mis.twse.com.tw/stock/api/getStockInfo.jsp?ex_ch=tse_$code",
            "https://mis.twse.com.tw/stock/api/getStockInfo.jsp?ex_ch=otc_$code"
        )

        for (url in urls) {
            try {
                val responseText = client.get(url) {
                    headers.append("User-Agent", "Mozilla/5.0")
                    headers.append("Referer", "https://mis.twse.com.tw/stock/fibest.jsp")
                }.bodyAsText()

                val root = Json.parseToJsonElement(responseText).jsonObject
                val msgArray = root["msgArray"]?.jsonArray
                if (msgArray == null || msgArray.isEmpty()) {
                    continue // Try next URL if this one has no data
                }

                val obj = msgArray[0].jsonObject

                val price = obj["z"]?.jsonPrimitive?.content?.toDoubleOrNull()
                val yesterday = obj["y"]?.jsonPrimitive?.content?.toDoubleOrNull()

                if (price != null && yesterday != null && yesterday != 0.0) {
                    val change = price - yesterday
                    val changePercent = (change / yesterday) * 100

                    val info = RealtimeStockInfo(
                        currentPrice = price,
                        change = change,
                        changePercent = changePercent
                    )

                    Log.d("TwseStockInfoFetcher", "Fetched $stockCode → $info from $url")
                    return info
                }

            } catch (e: Exception) {
                Log.e(
                    "TwseStockInfoFetcher",
                    "Exception while fetching TWSE data for $stockCode from $url: ${e.message}",
                    e
                )
                // Continue to the next URL on exception
            }
        }

        Log.e("TwseStockInfoFetcher", "Failed to fetch price data for $stockCode from all TWSE URLs")
        return null // Return null if all URLs fail
    }
}
