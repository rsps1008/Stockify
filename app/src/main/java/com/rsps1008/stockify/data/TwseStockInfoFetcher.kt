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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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

    private val fetchSemaphore = Semaphore(permits = 3)

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
        fetchStockInfoListByExchange(stockCodes.associateWith { StockExchange.UNKNOWN })
    }

    suspend fun fetchStockInfoListByExchange(
        stocks: List<Stock>
    ): Map<String, RealtimeStockInfo> = withContext(Dispatchers.IO) {
        fetchStockInfoListByExchange(stocks.associate { it.code to it.exchange })
    }

    override suspend fun fetchStockInfo(stockCode: String, stockType: String): RealtimeStockInfo? =
        withContext(Dispatchers.IO) {
            fetchStockInfoListByExchange(mapOf(stockCode to StockExchange.UNKNOWN))[stockCode]
        }

    private suspend fun fetchStockInfoListByExchange(
        stockExchanges: Map<String, String>
    ): Map<String, RealtimeStockInfo> {
        val normalized = stockExchanges
            .mapKeys { it.key.trim() }
            .filterKeys { it.isNotEmpty() }
        if (normalized.isEmpty()) return emptyMap()

        return normalized.keys.toList()
            .chunked(5)
            .flatMap { chunk ->
                fetchStockInfoBatch(chunk, normalized)
                    .entries
            }
            .associate { it.key to it.value }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private suspend fun fetchStockInfoBatch(
        stockCodes: List<String>,
        stockExchanges: Map<String, String>
    ): Map<String, RealtimeStockInfo> {
        return retryOnTransientNetworkFailure(
            "TwseStockInfoFetcher",
            stockCodes.joinToString(",")
        ) {
            val channels = stockCodes.flatMap { stockCode ->
                val code = if (stockCode.contains(".")) stockCode else "$stockCode.tw"
                when (StockExchange.normalize(stockExchanges[stockCode])) {
                    StockExchange.LISTED -> listOf("tse_$code")
                    StockExchange.OTC -> listOf("otc_$code")
                    else -> listOf("tse_$code", "otc_$code")
                }
            }.distinct().joinToString("%7C")
            val url = "https://mis.twse.com.tw/stock/api/getStockInfo.jsp?ex_ch=$channels&json=1&delay=0"
            val responseText = fetchSemaphore.withPermit {
                client.get(url) {
                    headers.append("User-Agent", "Mozilla/5.0")
                }.bodyAsText()
            }

            if (responseText.isBlank() ||
                !responseText.trim().startsWith("{") ||
                !responseText.trim().endsWith("}")
            ) return@retryOnTransientNetworkFailure emptyMap()

            val msgArray = Json.parseToJsonElement(responseText).jsonObject["msgArray"]?.jsonArray
                ?: return@retryOnTransientNetworkFailure emptyMap()
            msgArray.mapNotNull { element ->
                val obj = element.jsonObject
                val code = obj["c"]?.jsonPrimitive?.content?.trim().orEmpty()
                if (code !in stockCodes) return@mapNotNull null
                parseRealtimeInfo(obj)?.let { code to it }
            }.toMap()
        } ?: emptyMap()
    }

    private fun parseRealtimeInfo(obj: kotlinx.serialization.json.JsonObject): RealtimeStockInfo? {
        val zRaw = obj["z"]?.jsonPrimitive?.content
        val price = zRaw?.takeIf { it != "-" }?.toDoubleOrNull()
            ?: firstValidPrice(obj["a"]?.jsonPrimitive?.content)
            ?: firstValidPrice(obj["b"]?.jsonPrimitive?.content)
        val yesterday = obj["y"]?.jsonPrimitive?.content?.toDoubleOrNull()
        if (price == null || yesterday == null || yesterday == 0.0) return null

        val change = price - yesterday
        val up = obj["u"]?.jsonPrimitive?.content?.toDoubleOrNull()
        val down = obj["w"]?.jsonPrimitive?.content?.toDoubleOrNull()
        return RealtimeStockInfo(
            currentPrice = price,
            change = change,
            changePercent = (change / yesterday) * 100,
            limitState = when {
                up != null && price == up -> LimitState.LIMIT_UP
                down != null && price == down -> LimitState.LIMIT_DOWN
                else -> LimitState.NONE
            }
        )
    }

    fun firstValidPrice(raw: String?): Double? =
        raw?.split("_")
            ?.mapNotNull { it.toDoubleOrNull() }
            ?.firstOrNull { it > 0.0 }
}
