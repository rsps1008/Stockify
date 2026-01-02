package com.rsps1008.stockify.data

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class YahooStockInfoFetcher : StockInfoFetcher {

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
        val isTradingTime = currentTime.isAfter(LocalTime.of(13, 0)) && currentTime.isBefore(LocalTime.of(13, 30))

        return isWeekday && isTradingTime
    }

    override suspend fun fetchStockInfoList(stockCodes: List<String>): Map<String, RealtimeStockInfo> = withContext(Dispatchers.IO) {
        stockCodes.map { code ->
            async {
                fetchStockInfoInternal(code)?.let { info ->
                    code to info
                }
            }
        }.awaitAll().filterNotNull().toMap()
    }

    override suspend fun fetchStockInfo(stockCode: String): RealtimeStockInfo? = withContext(Dispatchers.IO) {
        fetchStockInfoInternal(stockCode)
    }

    private suspend fun fetchStockInfoInternal(stockCode: String): RealtimeStockInfo? {
        val url = "https://tw.stock.yahoo.com/quote/$stockCode"
        try {
            val responseText = client.get(url) {
                headers.append("User-Agent", "Mozilla/5.0")
            }.bodyAsText()

            val soup = Jsoup.parse(responseText)

            val ul = soup.selectFirst("section#qsp-overview-realtime-info ul")
            if (ul == null) {
                Log.e("YahooStockInfoFetcher", "Could not find the target 'ul' element for $stockCode")
                return null
            }

            val kv = mutableMapOf<String, String>()

            for (li in ul.select("li")) {
                val spans = li.select("> span")
                if (spans.size == 2) {
                    val key = spans[0].text().trim()
                    val valueSpan = spans[1]
                    val valueText = valueSpan.text().trim()
                    kv[key] = valueText
                }
            }

            val priceStr = kv["成交"]
            val yesterdayPriceStr = kv["昨收"]

            val price = priceStr?.toDoubleOrNull()
            val yesterdayPrice = yesterdayPriceStr?.toDoubleOrNull()

            if (price != null && yesterdayPrice != null && yesterdayPrice != 0.0) {
                val change = price - yesterdayPrice
                val changePercent = (change / yesterdayPrice) * 100
                val info = RealtimeStockInfo(
                    currentPrice = price,
                    change = change,
                    changePercent = changePercent
                )
                Log.d("YahooStockInfoFetcher", "Yahoo Fetched $stockCode → $info from $url")
                return info
            } else {
                Log.e("YahooStockInfoFetcher", "Failed to parse price or yesterday's price for $stockCode. PriceStr: $priceStr, YesterdayPriceStr: $yesterdayPriceStr")
                return null
            }

        } catch (e: Exception) {
            Log.e("YahooStockInfoFetcher", "Exception while fetching stock info for $stockCode: ${e.message}", e)
            return null
        }
    }
}