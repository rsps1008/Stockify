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
            "https://mis.twse.com.tw/stock/api/getStockInfo.jsp?ex_ch=tse_$code&json=1&delay=0",
            "https://mis.twse.com.tw/stock/api/getStockInfo.jsp?ex_ch=otc_$code&json=1&delay=0"
        )

        for (url in urls) {
            try {
                val responseText = client.get(url) {
                    headers.append("User-Agent", "Mozilla/5.0")
                }.bodyAsText()

                if (responseText.isBlank() ||
                    !responseText.trim().startsWith("{") ||
                    !responseText.trim().endsWith("}")
                ) {
                    continue
                }

                val root = Json.parseToJsonElement(responseText).jsonObject
                val msgArray = root["msgArray"]?.jsonArray ?: continue
                if (msgArray.isEmpty()) continue

                val obj = msgArray[0].jsonObject

                // -------- 新增：買賣一，用來補 z="-" 的情況 --------
                val zRaw = obj["z"]?.jsonPrimitive?.content
                val price: Double? =
                    zRaw?.takeIf { it != "-" }?.toDoubleOrNull()
                        ?: firstValidPrice(obj["a"]?.jsonPrimitive?.content)
                        ?: firstValidPrice(obj["b"]?.jsonPrimitive?.content)

                val yesterday = obj["y"]?.jsonPrimitive?.content?.toDoubleOrNull()
                if (price != null && yesterday != null && yesterday != 0.0) {
                    val change = price - yesterday
                    val changePercent = (change / yesterday) * 100

                    val z = zRaw?.takeIf { it != "-" }?.toDoubleOrNull()
                    val up = obj["u"]?.jsonPrimitive?.content?.toDoubleOrNull()
                    val down = obj["w"]?.jsonPrimitive?.content?.toDoubleOrNull()
                    val limitState =
                        when {
                            up != null && price == up -> LimitState.LIMIT_UP
                            down != null && price == down -> LimitState.LIMIT_DOWN
                            else -> LimitState.NONE
                        }

                    val info = RealtimeStockInfo(
                        currentPrice = price,
                        change = change,
                        changePercent = changePercent,
                        limitState = limitState
                    )

                    Log.d("TwseStockInfoFetcher", "TWSE Fetched $stockCode → $info from $url")
                    return info
                }

            } catch (e: Exception) {
                Log.e("TwseStockInfoFetcher",
                    "Exception for $stockCode from $url: ${e.javaClass.simpleName} ${e.message}"
                )
            }
        }

        Log.e("TwseStockInfoFetcher", "Failed to fetch price data for $stockCode from all TWSE URLs")
        return null
    }

    fun firstValidPrice(raw: String?): Double? =
        raw?.split("_")
            ?.mapNotNull { it.toDoubleOrNull() }
            ?.firstOrNull { it > 0.0 }
}
