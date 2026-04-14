package com.rsps1008.stockify.data

import android.content.Context
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

class UsStockListRepository(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun readStocks(): List<Stock> {
        return runCatching {
            context.assets.open("us_stocks.json").use { inputStream ->
                val jsonString = inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                json.decodeFromString<List<JsonStock>>(jsonString).map { item ->
                    Stock(
                        name = item.name,
                        code = item.code,
                        market = StockMarket.US,
                        industry = item.industry,
                        stockType = item.stockType
                    )
                }
            }
        }.getOrElse {
            emptyList()
        }
    }
}
