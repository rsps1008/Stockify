package com.rsps1008.stockify.data

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Calendar

@kotlinx.serialization.Serializable
data class StockHistoryPoint(
    val date: String, // "YYYY-MM-DD"
    val price: Double
)

class TwseStockHistoryService(
    private val client: HttpClient,
    private val stockDao: StockDao
) {

    companion object {
        private const val TAG = "TwseStockHistoryService"
    }

    // Cache structure: Map of "stockCode_YYYYMM" to List<StockHistoryPoint>
    private val cache = mutableMapOf<String, List<StockHistoryPoint>>()

    suspend fun fetchHistory(
        stockCode: String,
        rangeMonths: Int,
        onProgress: (step: Int, total: Int) -> Unit
    ): List<StockHistoryPoint> {
        val market = StockMarket.inferFromCode(stockCode)
        return if (StockMarket.isTw(market)) {
            fetchTwHistory(stockCode, rangeMonths, onProgress)
        } else {
            fetchUsHistory(stockCode, rangeMonths, onProgress)
        }
    }

    private suspend fun fetchTwHistory(
        stockCode: String,
        rangeMonths: Int,
        onProgress: (step: Int, total: Int) -> Unit
    ): List<StockHistoryPoint> = withContext(Dispatchers.IO) {
        val targetMonths = getTargetMonths(rangeMonths)
        val total = targetMonths.size
        val resultPoints = mutableListOf<StockHistoryPoint>()

        val currentMonthStr = java.text.SimpleDateFormat("yyyyMM", java.util.Locale.getDefault()).format(java.util.Date())

        for ((index, monthStr) in targetMonths.withIndex()) {
            onProgress(index + 1, total)
            val cacheKey = "${stockCode}_$monthStr"
            val cached = cache[cacheKey]

            if (cached != null) {
                resultPoints.addAll(cached)
                continue
            }

            val isCurrentMonth = monthStr == currentMonthStr

            // Try to load from database first for historical (non-current) months
            if (!isCurrentMonth) {
                val monthPrefix = "${monthStr.substring(0, 4)}-${monthStr.substring(4, 6)}"
                val localData = stockDao.getHistoryPricesForMonth(stockCode, monthPrefix)
                if (localData.isNotEmpty()) {
                    val localPoints = localData.map { StockHistoryPoint(it.date, it.price) }
                    cache[cacheKey] = localPoints
                    resultPoints.addAll(localPoints)
                    continue
                }
            }

            // Fetch from TWSE API
            val dateParam = "${monthStr}01" // YYYYMM01
            val url = "https://www.twse.com.tw/exchangeReport/STOCK_DAY?response=json&date=$dateParam&stockNo=$stockCode"

            val body = retryOnTransientNetworkFailure(TAG, stockCode) {
                client.get(url) {
                    header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                }.bodyAsText()
            }

            if (body != null) {
                try {
                    val points = parseTwseResponse(body)
                    if (points.isNotEmpty()) {
                        cache[cacheKey] = points
                        resultPoints.addAll(points)

                        // Save fetched points to database
                        val dbEntities = points.map { StockHistoryPrice(stockCode, it.date, it.price) }
                        stockDao.insertHistoryPrices(dbEntities)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing TWSE response for $stockCode in $monthStr", e)
                }
            }

            // Add delay to prevent TWSE from blocking us
            if (index < total - 1) {
                delay(500L)
            }
        }

        // Return sorted chronologically by date
        resultPoints.distinctBy { it.date }.sortedBy { it.date }
    }

    private suspend fun fetchUsHistory(
        stockCode: String,
        rangeMonths: Int,
        onProgress: (step: Int, total: Int) -> Unit
    ): List<StockHistoryPoint> = withContext(Dispatchers.IO) {
        val targetMonths = getTargetMonths(rangeMonths)
        val resultPoints = mutableListOf<StockHistoryPoint>()

        val currentMonthStr = java.text.SimpleDateFormat("yyyyMM", java.util.Locale.getDefault()).format(java.util.Date())

        val missingMonths = mutableListOf<String>()

        for (monthStr in targetMonths) {
            val cacheKey = "${stockCode}_$monthStr"
            val cached = cache[cacheKey]
            if (cached != null) {
                resultPoints.addAll(cached)
                continue
            }

            val isCurrentMonth = monthStr == currentMonthStr
            if (!isCurrentMonth) {
                val monthPrefix = "${monthStr.substring(0, 4)}-${monthStr.substring(4, 6)}"
                val localData = stockDao.getHistoryPricesForMonth(stockCode, monthPrefix)
                if (localData.isNotEmpty()) {
                    val localPoints = localData.map { StockHistoryPoint(it.date, it.price) }
                    cache[cacheKey] = localPoints
                    resultPoints.addAll(localPoints)
                    continue
                }
            }
            missingMonths.add(monthStr)
        }

        // If we have missing months or need to refresh the current month
        if (missingMonths.isNotEmpty() || !cache.containsKey("${stockCode}_$currentMonthStr")) {
            onProgress(1, 1) // US uses a single API call for the whole range
            
            // Get stock type to determine asset class
            val stock = stockDao.getStockByCode(stockCode)
            val stockType = stock?.stockType ?: ""
            val assetClass = if (stockType.equals("ETF", ignoreCase = true)) "etf" else "stocks"

            // Compute date range
            val calendar = Calendar.getInstance(java.util.TimeZone.getTimeZone("America/New_York"))
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("America/New_York")
            }
            val toDate = sdf.format(calendar.time)
            calendar.add(Calendar.MONTH, -rangeMonths)
            val fromDate = sdf.format(calendar.time)

            // Nasdaq API limit is required
            val url = "https://api.nasdaq.com/api/quote/$stockCode/historical?assetclass=$assetClass&fromdate=$fromDate&todate=$toDate&limit=400"

            val body = retryOnTransientNetworkFailure(TAG, stockCode) {
                client.get(url) {
                    header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    header("Referer", "https://www.nasdaq.com/")
                    header("Origin", "https://www.nasdaq.com")
                    header("Accept", "application/json, text/plain, */*")
                    header("Accept-Language", "en-US,en;q=0.9")
                }.bodyAsText()
            }

            if (body != null) {
                try {
                    val points = parseNasdaqResponse(body)
                    if (points.isNotEmpty()) {
                        // Save to database
                        val dbEntities = points.map { StockHistoryPrice(stockCode, it.date, it.price) }
                        stockDao.insertHistoryPrices(dbEntities)

                        // Update memory cache
                        val groupedByMonth = points.groupBy { it.date.replace("-", "").substring(0, 6) }
                        for ((mStr, mPoints) in groupedByMonth) {
                            cache["${stockCode}_$mStr"] = mPoints
                        }

                        // Rebuild resultPoints from cache
                        resultPoints.clear()
                        for (monthStr in targetMonths) {
                            val cacheKey = "${stockCode}_$monthStr"
                            val cached = cache[cacheKey] ?: groupedByMonth[monthStr] ?: emptyList()
                            resultPoints.addAll(cached)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing Nasdaq response for $stockCode", e)
                }
            }
        }

        // Return sorted chronologically by date
        resultPoints.distinctBy { it.date }.sortedBy { it.date }
    }

    private fun parseNasdaqResponse(jsonText: String): List<StockHistoryPoint> {
        val points = mutableListOf<StockHistoryPoint>()
        if (jsonText.isBlank()) return points

        val root = Json.parseToJsonElement(jsonText).jsonObject
        val dataObj = root["data"]?.jsonObject ?: return points
        val tradesTable = dataObj["tradesTable"]?.jsonObject ?: return points
        val rowsArray = tradesTable["rows"]?.jsonArray ?: return points

        for (rowElement in rowsArray) {
            val rowObj = rowElement.jsonObject
            val dateStr = rowObj["date"]?.jsonPrimitive?.content ?: continue // "07/01/2026"
            val closeStr = rowObj["close"]?.jsonPrimitive?.content ?: continue // "$294.38"

            val dateParts = dateStr.split("/")
            if (dateParts.size != 3) return points // Return what we have or empty
            val formattedDate = String.format("%s-%s-%s", dateParts[2], dateParts[0], dateParts[1])

            val price = closeStr.replace("$", "").replace(",", "").trim().toDoubleOrNull() ?: continue
            points.add(StockHistoryPoint(formattedDate, price))
        }
        return points
    }

    private fun getTargetMonths(rangeMonths: Int): List<String> {
        val list = mutableListOf<String>()
        val calendar = Calendar.getInstance()
        
        for (i in 0..rangeMonths) {
            val temp = calendar.clone() as Calendar
            temp.add(Calendar.MONTH, -i)
            val y = temp.get(Calendar.YEAR)
            val m = temp.get(Calendar.MONTH) + 1
            val monthStr = String.format("%04d%02d", y, m)
            list.add(monthStr)
        }
        return list.reversed() // Oldest month first
    }

    private fun parseTwseResponse(jsonText: String): List<StockHistoryPoint> {
        val points = mutableListOf<StockHistoryPoint>()
        if (jsonText.isBlank()) return points

        val root = Json.parseToJsonElement(jsonText).jsonObject
        val stat = root["stat"]?.jsonPrimitive?.content ?: ""
        if (stat != "OK") return points

        val dataArray = root["data"]?.jsonArray ?: return points
        for (rowElement in dataArray) {
            val row = rowElement.jsonArray
            if (row.size < 9) continue
            val rawDate = row[0].jsonPrimitive.content // "115/05/04"
            val rawPrice = row[6].jsonPrimitive.content // "832.00"

            val date = parseRocDate(rawDate) ?: continue
            val price = rawPrice.replace(",", "").toDoubleOrNull() ?: continue
            points.add(StockHistoryPoint(date, price))
        }
        return points
    }

    private fun parseRocDate(rocDate: String): String? {
        val parts = rocDate.split("/")
        if (parts.size != 3) return null
        val rocYear = parts[0].toIntOrNull() ?: return null
        val month = parts[1].toIntOrNull() ?: return null
        val day = parts[2].toIntOrNull() ?: return null
        val gregorianYear = rocYear + 1911
        return String.format("%04d-%02d-%02d", gregorianYear, month, day)
    }
}
