package com.rsps1008.stockify.data

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class UsdTwdExchangeRateService(
    private val settingsDataStore: SettingsDataStore
) {
    private val client = HttpClient(CIO) {
        engine {
            requestTimeout = 5000
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private var refreshJob: Job? = null

    private val _usdToTwdRate = MutableStateFlow(32.0)
    val usdToTwdRate: StateFlow<Double> = _usdToTwdRate.asStateFlow()

    init {
        scope.launch {
            _usdToTwdRate.value = settingsDataStore.usdToTwdRateFlow.first()
        }
        startRefreshing()
    }

    fun startRefreshing() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            refreshOnce()
            while (true) {
                delay(6 * 60 * 60 * 1000L)
                refreshOnce()
            }
        }
    }

    suspend fun refreshOnce() {
        val rate = fetchRate()
        if (rate != null && rate > 0.0) {
            _usdToTwdRate.value = rate
            settingsDataStore.setUsdToTwdRate(rate)
        }
    }

    private suspend fun fetchRate(): Double? {
        return try {
            val jsonText = client.get("https://open.er-api.com/v6/latest/USD").bodyAsText()
            val root = Json.parseToJsonElement(jsonText).jsonObject
            if (root["result"]?.jsonPrimitive?.content != "success") return null
            root["rates"]?.jsonObject?.get("TWD")?.jsonPrimitive?.doubleOrNull
        } catch (e: Exception) {
            Log.e("UsdTwdExchangeRateService", "Failed to fetch USD/TWD rate: ${e.message}", e)
            null
        }
    }
}
