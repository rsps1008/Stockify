package com.rsps1008.stockify.data

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@kotlinx.serialization.Serializable
data class TaiwanWeightedIndexInfo(
    val name: String,
    val current: Double,
    val previousClose: Double,
    val open: Double,
    val high: Double,
    val low: Double,
    val updatedAtMillis: Long
) {
    val change: Double get() = current - previousClose
    val changePercent: Double
        get() = if (previousClose == 0.0) 0.0 else (change / previousClose) * 100.0
}

class TaiwanWeightedIndexService(
    private val client: HttpClient,
    private val settingsDataStore: SettingsDataStore
) {
    private companion object {
        private const val TAG = "TaiwanWeightedIndexSvc"
        private const val INDEX_CODE = "tse_t00.tw"
        private const val YAHOO_SYMBOL = "^TWII"
        private const val TWSE_URL =
            "https://mis.twse.com.tw/stock/api/getStockInfo.jsp?ex_ch=$INDEX_CODE&json=1&delay=0"
        private const val YAHOO_URL =
            "https://query1.finance.yahoo.com/v8/finance/chart/%5ETWII?interval=1d&range=1d&includePrePost=false&events=div%2Csplits"
    }

    private val _indexInfo = MutableStateFlow<TaiwanWeightedIndexInfo?>(null)
    val indexInfo: StateFlow<TaiwanWeightedIndexInfo?> = _indexInfo.asStateFlow()
    private val scope = CoroutineScope(Dispatchers.IO)

    init {
        scope.launch {
            _indexInfo.value = settingsDataStore.taiwanWeightedIndexCacheFlow.first()
        }
    }

    suspend fun refreshOnce(preferredSource: String) {
        val info = fetchWithSourcePreference(preferredSource)
        if (info != null) {
            _indexInfo.value = info
            settingsDataStore.setTaiwanWeightedIndexCache(info)
        }
    }

    private suspend fun fetchWithSourcePreference(preferredSource: String): TaiwanWeightedIndexInfo? {
        val normalizedPreferred = when (preferredSource.trim().uppercase()) {
            "YAHOO" -> "Yahoo"
            else -> "TWSE"
        }
        val sourceOrder = if (normalizedPreferred == "Yahoo") {
            listOf("Yahoo", "TWSE")
        } else {
            listOf("TWSE", "Yahoo")
        }

        for (source in sourceOrder) {
            val info = when (source) {
                "Yahoo" -> fetchYahooIndexInfo()
                else -> fetchTwseIndexInfo()
            }
            if (info != null) {
                Log.d(TAG, "Fetched Taiwan weighted index via $source")
                return info
            }
        }

        Log.e(TAG, "Failed to fetch Taiwan weighted index via all configured sources")
        return null
    }

    private suspend fun fetchTwseIndexInfo(): TaiwanWeightedIndexInfo? {
        return try {
            val body = retryOnTransientNetworkFailure(TAG, INDEX_CODE) {
                client.get(TWSE_URL) {
                    headers.append("User-Agent", "Mozilla/5.0")
                }.bodyAsText()
            } ?: return null
            val root = Json.parseToJsonElement(body).jsonObject
            val item = root["msgArray"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null

            TaiwanWeightedIndexInfo(
                name = item["n"]?.jsonPrimitive?.content ?: "台灣加權",
                current = item["z"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return null,
                previousClose = item["y"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return null,
                open = item["o"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return null,
                high = item["h"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return null,
                low = item["l"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return null,
                updatedAtMillis = item["tlong"]?.jsonPrimitive?.content?.toLongOrNull()
                    ?: System.currentTimeMillis()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch Taiwan weighted index from TWSE: ${e.message}", e)
            null
        }
    }

    private suspend fun fetchYahooIndexInfo(): TaiwanWeightedIndexInfo? {
        return try {
            val body = retryOnTransientNetworkFailure(TAG, YAHOO_SYMBOL) {
                client.get(YAHOO_URL) {
                    headers.append("User-Agent", "Mozilla/5.0")
                }.bodyAsText()
            } ?: return null

            val root = Json.parseToJsonElement(body).jsonObject
            val result = root["chart"]?.jsonObject
                ?.get("result")?.jsonArray
                ?.firstOrNull()?.jsonObject ?: return null
            val meta = result["meta"]?.jsonObject ?: return null

            val current = meta["regularMarketPrice"]?.jsonPrimitive?.doubleOrNull ?: return null
            val previousClose =
                meta["chartPreviousClose"]?.jsonPrimitive?.doubleOrNull
                    ?: meta["previousClose"]?.jsonPrimitive?.doubleOrNull
                    ?: return null
            val open = meta["regularMarketOpen"]?.jsonPrimitive?.doubleOrNull ?: current
            val high = meta["regularMarketDayHigh"]?.jsonPrimitive?.doubleOrNull ?: current
            val low = meta["regularMarketDayLow"]?.jsonPrimitive?.doubleOrNull ?: current
            val updatedAtMillis =
                meta["regularMarketTime"]?.jsonPrimitive?.content?.toLongOrNull()?.times(1000)
                    ?: System.currentTimeMillis()

            TaiwanWeightedIndexInfo(
                name = meta["shortName"]?.jsonPrimitive?.content ?: "台灣加權",
                current = current,
                previousClose = previousClose,
                open = open,
                high = high,
                low = low,
                updatedAtMillis = updatedAtMillis
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch Taiwan weighted index from Yahoo: ${e.message}", e)
            null
        }
    }
}
