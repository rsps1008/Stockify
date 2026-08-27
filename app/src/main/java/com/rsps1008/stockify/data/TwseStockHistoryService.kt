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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Calendar
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.YearMonth
import java.time.ZonedDateTime
import java.util.Locale

@kotlinx.serialization.Serializable
data class StockHistoryPoint(
    val date: String, // "YYYY-MM-DD"
    val price: Double
)

internal fun shouldUseHistoryMonthWithoutRefresh(
    month: String,
    latestChartMonth: String,
    latestChartDate: String,
    points: List<StockHistoryPoint>,
    forceRefreshCurrentMonth: Boolean = false
): Boolean {
    if (month == latestChartMonth && forceRefreshCurrentMonth) return false
    return month != latestChartMonth || points.any {
        it.date == latestChartDate && it.price.isFinite() && it.price > 0.0
    }
}

internal fun latestExpectedHistoryDate(
    now: ZonedDateTime,
    market: String
): java.time.LocalDate {
    val marketCloseTime = if (StockMarket.isUs(market)) {
        LocalTime.of(16, 0)
    } else {
        LocalTime.of(13, 30)
    }
    val isWeekday = now.dayOfWeek in DayOfWeek.MONDAY..DayOfWeek.FRIDAY
    var latestDate = if (isWeekday && !now.toLocalTime().isBefore(marketCloseTime)) {
        now.toLocalDate()
    } else {
        now.toLocalDate().minusDays(1)
    }
    while (latestDate.dayOfWeek == DayOfWeek.SATURDAY || latestDate.dayOfWeek == DayOfWeek.SUNDAY) {
        latestDate = latestDate.minusDays(1)
    }
    return latestDate
}

internal fun targetHistoryMonths(
    rangeMonths: Int,
    currentMonth: YearMonth
): List<String> = (0..rangeMonths)
    .map { offset ->
        val month = currentMonth.minusMonths(offset.toLong())
        String.format(Locale.US, "%04d%02d", month.year, month.monthValue)
    }
    .reversed()

internal fun mergeHistoryPointsPreferLatest(
    points: List<StockHistoryPoint>
): List<StockHistoryPoint> = points
    .associateBy { it.date }
    .values
    .sortedBy { it.date }

internal class StockHistoryCache {
    private val lock = Any()
    private val values = mutableMapOf<String, List<StockHistoryPoint>>()
    private var generation = 0L

    fun currentGeneration(): Long = synchronized(lock) { generation }

    fun get(key: String): List<StockHistoryPoint>? = synchronized(lock) {
        values[key]
    }

    fun putIfCurrent(
        key: String,
        points: List<StockHistoryPoint>,
        expectedGeneration: Long
    ): Boolean = synchronized(lock) {
        if (generation != expectedGeneration) {
            false
        } else {
            values[key] = points
            true
        }
    }

    fun clear() = synchronized(lock) {
        values.clear()
        generation++
    }
}

class TwseStockHistoryService(
    private val client: HttpClient,
    private val stockDao: StockDao
) {

    private fun historyCacheKey(market: String, stockCode: String, month: String): String =
        "${stockCacheKey(market, stockCode)}_$month"

    companion object {
        private const val TAG = "TwseStockHistoryService"
    }

    // Cache structure: Map of "MARKET:stockCode_YYYYMM" to List<StockHistoryPoint>
    private val cache = StockHistoryCache()

    fun clearCache() {
        cache.clear()
    }

    suspend fun getCachedHistory(
        stockCode: String,
        rangeMonths: Int,
        market: String = StockMarket.inferFromCode(stockCode)
    ): List<StockHistoryPoint> = withContext(Dispatchers.IO) {
        val normalizedCode = canonicalStockCode(stockCode)
        val normalizedMarket = StockMarket.normalize(market)
        val targetMonths = getTargetMonths(rangeMonths, normalizedMarket)
        val latestChartDateStr = getLatestChartDateString(normalizedMarket)
        val latestChartMonthStr = latestChartDateStr.take(7).replace("-", "")
        val resultPoints = mutableListOf<StockHistoryPoint>()
        val cacheGeneration = cache.currentGeneration()

        for (monthStr in targetMonths) {
            val cacheKey = historyCacheKey(normalizedMarket, normalizedCode, monthStr)
            val cached = cache.get(cacheKey)
            if (cached != null) {
                resultPoints.addAll(cached.filterForChart(latestChartDateStr))
                continue
            }

            val monthPrefix = "${monthStr.substring(0, 4)}-${monthStr.substring(4, 6)}"
            val localData = stockDao.getHistoryPricesForMonth(normalizedCode, normalizedMarket, monthPrefix)
            if (localData.isNotEmpty()) {
                val localPoints = localData.map { StockHistoryPoint(it.date, it.price) }.filterForChart(latestChartDateStr)
                if (shouldUseHistoryMonthWithoutRefresh(
                        monthStr,
                        latestChartMonthStr,
                        latestChartDateStr,
                        localPoints
                    )
                ) {
                    cache.putIfCurrent(cacheKey, localPoints, cacheGeneration)
                }
                resultPoints.addAll(localPoints)
            }
        }

        mergeHistoryPointsPreferLatest(resultPoints)
    }

    suspend fun fetchHistory(
        stockCode: String,
        rangeMonths: Int,
        market: String = StockMarket.inferFromCode(stockCode),
        forceRefreshCurrentMonth: Boolean = false,
        onProgress: (step: Int, total: Int) -> Unit
    ): List<StockHistoryPoint> {
        val normalizedCode = canonicalStockCode(stockCode)
        val normalizedMarket = StockMarket.normalize(market)
        val stock = stockDao.getStockByCode(normalizedCode, normalizedMarket)
        return if (StockMarket.isTw(normalizedMarket)) {
            fetchTwHistory(
                stockCode = normalizedCode,
                rangeMonths = rangeMonths,
                exchange = StockExchange.normalize(stock?.exchange),
                onProgress = onProgress,
                market = normalizedMarket,
                forceRefreshCurrentMonth = forceRefreshCurrentMonth
            )
        } else {
            fetchUsHistory(
                normalizedCode,
                rangeMonths,
                onProgress,
                normalizedMarket,
                forceRefreshCurrentMonth
            )
        }
    }

    private suspend fun fetchTwHistory(
        stockCode: String,
        rangeMonths: Int,
        exchange: String,
        onProgress: (step: Int, total: Int) -> Unit,
        market: String,
        forceRefreshCurrentMonth: Boolean
    ): List<StockHistoryPoint> = withContext(Dispatchers.IO) {
        val targetMonths = getTargetMonths(rangeMonths, market)
        val total = targetMonths.size
        val resultPoints = mutableListOf<StockHistoryPoint>()

        val latestChartDateStr = getLatestChartDateString(StockMarket.TW)
        val latestChartMonthStr = latestChartDateStr.take(7).replace("-", "")
        val cacheGeneration = cache.currentGeneration()

        for ((index, monthStr) in targetMonths.withIndex()) {
            onProgress(index + 1, total)
            val cacheKey = historyCacheKey(market, stockCode, monthStr)
            val cached = cache.get(cacheKey)

            if (cached != null) {
                resultPoints.addAll(cached.filterForChart(latestChartDateStr))
                if (shouldUseHistoryMonthWithoutRefresh(
                        month = monthStr,
                        latestChartMonth = latestChartMonthStr,
                        latestChartDate = latestChartDateStr,
                        points = cached,
                        forceRefreshCurrentMonth = forceRefreshCurrentMonth
                    )
                ) {
                    continue
                }
            }

            if (monthStr > latestChartMonthStr) {
                continue
            }

            val monthPrefix = "${monthStr.substring(0, 4)}-${monthStr.substring(4, 6)}"
            val localData = stockDao.getHistoryPricesForMonth(stockCode, market, monthPrefix)
            if (localData.isNotEmpty()) {
                val localPoints = localData.map { StockHistoryPoint(it.date, it.price) }.filterForChart(latestChartDateStr)
                resultPoints.addAll(localPoints)
                if (shouldUseHistoryMonthWithoutRefresh(
                        monthStr,
                        latestChartMonthStr,
                        latestChartDateStr,
                        localPoints,
                        forceRefreshCurrentMonth
                    )
                ) {
                    cache.putIfCurrent(cacheKey, localPoints, cacheGeneration)
                    continue
                }
            }

            // 上市／上櫃使用 TWSE；興櫃使用 TPEx 月歷史行情 API。
            val dateParam = "${monthStr}01" // YYYYMM01
            val isEmerging = StockExchange.isEmerging(exchange)
            val url = if (isEmerging) {
                val formattedDate = "${monthStr.substring(0, 4)}%2F${monthStr.substring(4, 6)}%2F01"
                "https://www.tpex.org.tw/www/zh-tw/emerging/historical?type=Monthly&date=$formattedDate&code=$stockCode&response=json"
            } else {
                "https://www.twse.com.tw/exchangeReport/STOCK_DAY?response=json&date=$dateParam&stockNo=$stockCode"
            }

            val body = retryOnTransientNetworkFailure(TAG, stockCode) {
                client.get(url) {
                    header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                }.bodyAsText()
            }

            if (body != null) {
                try {
                    val points = if (isEmerging) {
                        parseTpexEmergingResponse(body)
                    } else {
                        parseTwseResponse(body)
                    }.filterForChart(latestChartDateStr)
                    if (points.isNotEmpty()) {
                        if (shouldUseHistoryMonthWithoutRefresh(
                                monthStr,
                                latestChartMonthStr,
                                latestChartDateStr,
                                points
                            )
                        ) {
                            cache.putIfCurrent(cacheKey, points, cacheGeneration)
                        }
                        resultPoints.addAll(points)

                        // Save fetched points to database
                        val dbEntities = points.map { StockHistoryPrice(stockCode, it.date, it.price, market) }
                        stockDao.insertHistoryPrices(dbEntities)
                    }
                } catch (e: Exception) {
                    safeLogE("Error parsing ${if (isEmerging) "TPEx" else "TWSE"} response for $stockCode in $monthStr", e)
                }
            }

            // Add delay to prevent TWSE from blocking us
            if (index < total - 1) {
                delay(500L)
            }
        }

        // Return sorted chronologically by date
        mergeHistoryPointsPreferLatest(resultPoints)
    }

    private suspend fun fetchUsHistory(
        stockCode: String,
        rangeMonths: Int,
        onProgress: (step: Int, total: Int) -> Unit,
        market: String,
        forceRefreshCurrentMonth: Boolean
    ): List<StockHistoryPoint> = withContext(Dispatchers.IO) {
        val targetMonths = getTargetMonths(rangeMonths, market)
        val resultPoints = mutableListOf<StockHistoryPoint>()

        val latestChartDateStr = getLatestChartDateString(StockMarket.US)
        val latestChartMonthStr = latestChartDateStr.take(7).replace("-", "")
        val cacheGeneration = cache.currentGeneration()

        val missingMonths = mutableListOf<String>()

        for (monthStr in targetMonths) {
            val cacheKey = historyCacheKey(market, stockCode, monthStr)
            val cached = cache.get(cacheKey)
            if (cached != null) {
                resultPoints.addAll(cached.filterForChart(latestChartDateStr))
                if (shouldUseHistoryMonthWithoutRefresh(
                        month = monthStr,
                        latestChartMonth = latestChartMonthStr,
                        latestChartDate = latestChartDateStr,
                        points = cached,
                        forceRefreshCurrentMonth = forceRefreshCurrentMonth
                    )
                ) {
                    continue
                }
            }

            if (monthStr > latestChartMonthStr) {
                continue
            }

            val monthPrefix = "${monthStr.substring(0, 4)}-${monthStr.substring(4, 6)}"
            val localData = stockDao.getHistoryPricesForMonth(stockCode, market, monthPrefix)
            if (localData.isNotEmpty()) {
                val localPoints = localData.map { StockHistoryPoint(it.date, it.price) }.filterForChart(latestChartDateStr)
                resultPoints.addAll(localPoints)
                if (shouldUseHistoryMonthWithoutRefresh(
                        monthStr,
                        latestChartMonthStr,
                        latestChartDateStr,
                        localPoints,
                        forceRefreshCurrentMonth
                    )
                ) {
                    cache.putIfCurrent(cacheKey, localPoints, cacheGeneration)
                    continue
                }
            }
            missingMonths.add(monthStr)
        }

        // US uses a single API call for missing chart months.
        if (missingMonths.isNotEmpty()) {
            onProgress(1, 1) // US uses a single API call for the whole range
            
            // Get stock type to determine asset class
            val stock = stockDao.getStockByCode(stockCode, market)
            val stockType = stock?.stockType ?: ""
            val assetClass = if (stockType.equals("ETF", ignoreCase = true)) "etf" else "stocks"

            // Compute date range
            val calendar = Calendar.getInstance(java.util.TimeZone.getTimeZone("America/New_York"))
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("America/New_York")
            }
            val toDate = latestChartDateStr
            calendar.add(Calendar.MONTH, -rangeMonths)
            val fromDate = sdf.format(calendar.time)

            safeLogD("fetchUsHistory starting for $stockCode, rangeMonths=$rangeMonths, stockType=$stockType, assetClass=$assetClass, fromDate=$fromDate, toDate=$toDate")

            var body = fetchNasdaqBody(stockCode, assetClass, fromDate, toDate)
            var points = body?.let { parseNasdaqResponse(it).filterForChart(latestChartDateStr) } ?: emptyList()

            safeLogD("fetchUsHistory parsed ${points.size} points for $stockCode under assetClass $assetClass")

            // Double-sided asset class fallback
            if (points.isEmpty()) {
                val fallbackAssetClass = if (assetClass == "stocks") "etf" else "stocks"
                safeLogD("Nasdaq history empty for $stockCode with $assetClass, trying fallback with $fallbackAssetClass")
                body = fetchNasdaqBody(stockCode, fallbackAssetClass, fromDate, toDate)
                points = body?.let { parseNasdaqResponse(it).filterForChart(latestChartDateStr) } ?: emptyList()
                safeLogD("Nasdaq fallback parsed ${points.size} points for $stockCode under fallbackAssetClass $fallbackAssetClass")
                if (points.isNotEmpty()) {
                    val updatedStockType = if (fallbackAssetClass == "etf") "ETF" else "STOCK"
                    stock?.let {
                        try {
                            stockDao.insertStock(it.copy(stockType = updatedStockType))
                            safeLogD("Self-healed stockType for $stockCode to $updatedStockType in database")
                        } catch (e: Exception) {
                            safeLogE("Failed to update stockType for $stockCode", e)
                        }
                    }
                }
            }

            if (points.isNotEmpty()) {
                // Save to database
                val dbEntities = points.map { StockHistoryPrice(stockCode, it.date, it.price, market) }
                stockDao.insertHistoryPrices(dbEntities)
                safeLogD("Saved ${dbEntities.size} US history price points to SQLite database for $stockCode")

                // Update memory cache
                val groupedByMonth = points.groupBy { it.date.replace("-", "").substring(0, 6) }
                for ((mStr, mPoints) in groupedByMonth) {
                    if (shouldUseHistoryMonthWithoutRefresh(
                            mStr,
                            latestChartMonthStr,
                            latestChartDateStr,
                            mPoints
                        )
                    ) {
                        cache.putIfCurrent(
                            historyCacheKey(market, stockCode, mStr),
                            mPoints,
                            cacheGeneration
                        )
                    }
                }

                // Rebuild resultPoints from cache
                resultPoints.clear()
                for (monthStr in targetMonths) {
                    val cacheKey = "${monthStr.substring(0, 6)}"
                    val cached = groupedByMonth[cacheKey]
                        ?: cache.get(historyCacheKey(market, stockCode, cacheKey))
                        ?: emptyList()
                    resultPoints.addAll(cached.filterForChart(latestChartDateStr))
                }
            }
        }

        // Return sorted chronologically by date
        mergeHistoryPointsPreferLatest(resultPoints)
    }

    private suspend fun fetchNasdaqBody(
        stockCode: String,
        assetClass: String,
        fromDate: String,
        toDate: String
    ): String? {
        val url = "https://api.nasdaq.com/api/quote/$stockCode/historical?assetclass=$assetClass&fromdate=$fromDate&todate=$toDate&limit=400"
        safeLogD("Requesting Nasdaq historical API: $url")
        return retryOnTransientNetworkFailure(TAG, stockCode) {
            val res = client.get(url) {
                header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                header("Referer", "https://www.nasdaq.com/")
                header("Origin", "https://www.nasdaq.com")
                header("Accept", "application/json, text/plain, */*")
                header("Accept-Language", "en-US,en;q=0.9")
            }.bodyAsText()
            safeLogD("Nasdaq response received for $stockCode ($assetClass), length=${res.length}")
            res
        }
    }

    private fun parseNasdaqResponse(jsonText: String): List<StockHistoryPoint> {
        val points = mutableListOf<StockHistoryPoint>()
        if (jsonText.isBlank()) return points

        return try {
            val root = (Json.parseToJsonElement(jsonText) as? JsonObject) ?: return points
            val dataObj = root["data"] as? JsonObject ?: return points
            val tradesTable = dataObj["tradesTable"] as? JsonObject ?: return points
            val rowsArray = tradesTable["rows"] as? JsonArray ?: return points

            for (rowElement in rowsArray) {
                val rowObj = rowElement as? JsonObject ?: continue
                val dateStr = (rowObj["date"] as? JsonPrimitive)?.contentOrNull ?: continue // "07/01/2026"
                val closeStr = (rowObj["close"] as? JsonPrimitive)?.contentOrNull ?: continue // "$294.38"

                val dateParts = dateStr.split("/")
                if (dateParts.size != 3) continue
                val year = dateParts[2].trim()
                val month = dateParts[0].trim().toIntOrNull()?.let { String.format(Locale.US, "%02d", it) } ?: dateParts[0].trim()
                val day = dateParts[1].trim().toIntOrNull()?.let { String.format(Locale.US, "%02d", it) } ?: dateParts[1].trim()
                val formattedDate = "$year-$month-$day"

                val price = closeStr.replace("$", "").replace(",", "").trim()
                    .toDoubleOrNull()
                    ?.takeIf { it.isFinite() && it > 0.0 }
                    ?: continue
                points.add(StockHistoryPoint(formattedDate, price))
            }
            points
        } catch (e: Exception) {
            safeLogE("Error parsing Nasdaq history response", e)
            emptyList()
        }
    }

    private fun getTargetMonths(rangeMonths: Int, market: String): List<String> {
        val timeZone = if (StockMarket.isUs(market)) "America/New_York" else "Asia/Taipei"
        return targetHistoryMonths(rangeMonths, YearMonth.now(ZoneId.of(timeZone)))
    }

    private fun getLatestChartDateString(market: String): String {
        val timeZone = if (StockMarket.isUs(market)) "America/New_York" else "Asia/Taipei"
        val now = ZonedDateTime.now(ZoneId.of(timeZone))
        return latestExpectedHistoryDate(now, market).toString()
    }

    private fun List<StockHistoryPoint>.filterForChart(latestChartDateStr: String): List<StockHistoryPoint> {
        return filter {
            it.date <= latestChartDateStr && it.price.isFinite() && it.price > 0.0
        }
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
            val price = rawPrice.replace(",", "").toDoubleOrNull()
                ?.takeIf { it.isFinite() && it > 0.0 }
                ?: continue
            points.add(StockHistoryPoint(date, price))
        }
        return points
    }

    private fun parseTpexEmergingResponse(jsonText: String): List<StockHistoryPoint> {
        val points = mutableListOf<StockHistoryPoint>()
        if (jsonText.isBlank()) return points

        val root = Json.parseToJsonElement(jsonText).jsonObject
        if (!root["stat"]?.jsonPrimitive?.content.orEmpty().equals("ok", ignoreCase = true)) {
            return points
        }

        val rows = root["tables"]?.jsonArray
            ?.firstOrNull()
            ?.jsonObject
            ?.get("data")
            ?.jsonArray
            ?: return points

        for (rowElement in rows) {
            val row = rowElement.jsonArray
            if (row.size <= 5) continue

            val rocDate = row[0].jsonPrimitive.content.trim()
            val dateParts = rocDate.split("/")
            if (dateParts.size != 3) continue
            val year = dateParts[0].toIntOrNull()?.plus(1911) ?: continue
            val month = dateParts[1].toIntOrNull() ?: continue
            val day = dateParts[2].toIntOrNull() ?: continue
            val price = row[5].jsonPrimitive.content
                .replace(",", "")
                .trim()
                .toDoubleOrNull()
                ?.takeIf { it.isFinite() && it > 0.0 }
                ?: continue

            points.add(
                StockHistoryPoint(
                    date = "%04d-%02d-%02d".format(year, month, day),
                    price = price
                )
            )
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
        return String.format(Locale.US, "%04d-%02d-%02d", gregorianYear, month, day)
    }

    private fun safeLogD(message: String) {
        try {
            Log.d(TAG, message)
        } catch (_: RuntimeException) {
            // Android's JVM test stub throws for Log calls; logging must not affect data fetching.
        }
    }

    private fun safeLogE(message: String, throwable: Throwable? = null) {
        try {
            if (throwable == null) {
                Log.e(TAG, message)
            } else {
                Log.e(TAG, message, throwable)
            }
        } catch (_: RuntimeException) {
            // Android's JVM test stub throws for Log calls; logging must not affect data fetching.
        }
    }
}
