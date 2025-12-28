package com.rsps1008.stockify.data

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class StockListRepository(private val context: Context) {

    private val jsonFile: File by lazy {
        File(context.filesDir, "stocks.json")
    }

    suspend fun saveStocks(stocks: List<Stock>) {
        val jsonString = Json.encodeToString(stocks)
        jsonFile.writeText(jsonString)
    }

    // Optional: Add a function to read stocks if needed later
    fun readStocks(): List<Stock> {
        return if (jsonFile.exists()) {
            val jsonString = jsonFile.readText()
            Json.decodeFromString(jsonString)
        } else {
            emptyList()
        }
    }
}
