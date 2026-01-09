package com.rsps1008.stockify.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

@Serializable
data class JsonStock(
    val name: String,
    val code: String,
    val market: String,
    val industry: String,
    val stockType: String
)

class StockListRepository(private val context: Context) {

    private val jsonFile: File by lazy {
        File(context.filesDir, "stocks.json")
    }

    private fun ensureJsonFileExists() {
        if (!jsonFile.exists()) {
            try {
                context.assets.open("stocks.json").use { inputStream ->
                    jsonFile.outputStream().use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    suspend fun saveStocks(stocks: List<Stock>) {
        val jsonStocks = stocks.map { 
            JsonStock(
                name = it.name,
                code = it.code,
                market = it.market,
                industry = it.industry,
                stockType = it.stockType
            )
        }
        val jsonString = Json.encodeToString(jsonStocks)
        jsonFile.writeText(jsonString)
    }

    fun readStocks(): List<Stock> {
        ensureJsonFileExists()

        return if (jsonFile.exists()) {
            val jsonString = jsonFile.readText()
            val jsonStocks = Json.decodeFromString<List<JsonStock>>(jsonString)
            jsonStocks.map { 
                Stock(
                    name = it.name,
                    code = it.code,
                    market = it.market,
                    industry = it.industry,
                    stockType = it.stockType
                )
            }
        } else {
            emptyList()
        }
    }
}
