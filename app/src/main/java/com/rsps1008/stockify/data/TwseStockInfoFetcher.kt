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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
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

            val root = try {
                Json.parseToJsonElement(responseText) as? JsonObject
            } catch (e: Exception) {
                Log.e(
                    "TwseStockInfoFetcher",
                    "Invalid TWSE JSON for ${stockCodes.joinToString(",")}: ${responseText.take(200)}",
                    e
                )
                null
            } ?: return@retryOnTransientNetworkFailure emptyMap()
            val msgArray = root["msgArray"] as? JsonArray
                ?: return@retryOnTransientNetworkFailure emptyMap()
            msgArray.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val code = obj["c"].stringOrNull()?.trim().orEmpty()
                if (code !in stockCodes) return@mapNotNull null
                parseRealtimeInfo(obj)?.let { code to it }
            }.toMap()
        } ?: emptyMap()
    }

    private fun parseRealtimeInfo(obj: JsonObject): RealtimeStockInfo? {
        val zRaw = obj["z"].stringOrNull()
        val price = zRaw?.takeIf { it != "-" }?.toDoubleOrNull()
            ?: firstValidPrice(obj["a"].stringOrNull())
            ?: firstValidPrice(obj["b"].stringOrNull())
        val yesterday = obj["y"].stringOrNull()?.toDoubleOrNull()
        val validPrice = price.takeIf { it.isFinitePositive() } ?: return null
        val validYesterday = yesterday.takeIf { it.isFinitePositive() } ?: return null

        val change = validPrice - validYesterday
        val up = obj["u"].stringOrNull()?.toDoubleOrNull().takeIf { it?.isFinite() == true }
        val down = obj["w"].stringOrNull()?.toDoubleOrNull().takeIf { it?.isFinite() == true }
        return RealtimeStockInfo(
            currentPrice = validPrice,
            change = change,
            changePercent = (change / validYesterday) * 100,
            limitState = when {
                up != null && validPrice == up -> LimitState.LIMIT_UP
                down != null && validPrice == down -> LimitState.LIMIT_DOWN
                else -> LimitState.NONE
            }
        )
    }

    fun firstValidPrice(raw: String?): Double? =
        raw?.split("_")
            ?.mapNotNull { it.toDoubleOrNull() }
            ?.firstOrNull { it.isFinite() && it > 0.0 }

    private fun JsonElement?.stringOrNull(): String? =
        (this as? JsonPrimitive)?.contentOrNull
}
