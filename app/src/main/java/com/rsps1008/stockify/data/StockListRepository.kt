package com.rsps1008.stockify.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.security.MessageDigest

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
            copyBundledStocksToJsonFile()
        }
    }

    fun readBundledStocks(): List<Stock> {
        return readStocksFromAsset("stocks.json")
    }

    fun refreshBundledCacheFromAsset() {
        copyBundledStocksToJsonFile()
    }

    fun getBundledStocksChecksum(): String? {
        return runCatching {
            context.assets.open("stocks.json").use { inputStream ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = inputStream.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            }
        }.getOrNull()
    }

    private fun readStocksFromAsset(assetName: String): List<Stock> {
        return runCatching {
            context.assets.open(assetName).use { inputStream ->
                val jsonString = inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val jsonStocks = Json.decodeFromString<List<JsonStock>>(jsonString)
                jsonStocks.map {
                    Stock(
                        name = it.name,
                        code = it.code,
                        market = if (it.market == StockMarket.US) StockMarket.US else StockMarket.TW,
                        exchange = StockExchange.normalize(it.market),
                        industry = it.industry,
                        stockType = it.stockType
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun copyBundledStocksToJsonFile() {
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

    private fun repairAndReloadStocks(): List<Stock> {
        return runCatching {
            if (jsonFile.exists()) {
                jsonFile.delete()
            }
            copyBundledStocksToJsonFile()
            readStocksFromJsonFile()
        }.getOrDefault(emptyList())
    }

    private fun readStocksFromJsonFile(): List<Stock> {
        val jsonString = jsonFile.readText()
        val jsonStocks = Json.decodeFromString<List<JsonStock>>(jsonString)
        return jsonStocks.map {
            Stock(
                name = it.name,
                code = it.code,
                market = if (it.market == StockMarket.US) StockMarket.US else StockMarket.TW,
                exchange = StockExchange.normalize(it.market),
                industry = it.industry,
                stockType = it.stockType
            )
        }
    }

    suspend fun saveStocks(stocks: List<Stock>) {
        val jsonStocks = stocks.map { 
            JsonStock(
                name = it.name,
                code = it.code,
                market = if (StockMarket.isUs(it.market)) StockMarket.US else it.exchange,
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
            runCatching {
                readStocksFromJsonFile()
            }.getOrElse { e ->
                e.printStackTrace()
                repairAndReloadStocks()
            }
        } else {
            emptyList()
        }
    }
}
