package com.rsps1008.stockify.data

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.jsoup.Jsoup
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

@Serializable
data class RealtimeStockInfo(
    val currentPrice: Double,
    val change: Double,
    val changePercent: Double
)

class YahooStockInfoFetcher {

    private val client = HttpClient(CIO) {
        engine {
            requestTimeout = 5000
        }
    }

    fun isMarketOpen(): Boolean {
        val taipeiZone = ZoneId.of("Asia/Taipei")
        val now = ZonedDateTime.now(taipeiZone)
        val dayOfWeek = now.dayOfWeek
        val currentTime = now.toLocalTime()

        val isWeekday = dayOfWeek in DayOfWeek.MONDAY..DayOfWeek.FRIDAY
        val isTradingTime = currentTime.isAfter(LocalTime.of(9, 0)) && currentTime.isBefore(LocalTime.of(13, 30))

        return isWeekday && isTradingTime
    }

    suspend fun fetchStockInfo(stockCode: String): RealtimeStockInfo? = withContext(Dispatchers.IO) {
        val url = "https://tw.stock.yahoo.com/quote/$stockCode"
        //Log.d("YahooStockInfoFetcher", "Fetching stock info for: $stockCode from url: $url")
        try {
            val responseText = client.get(url) {
                headers.append("User-Agent", "Mozilla/5.0")
            }.bodyAsText()

            val soup = Jsoup.parse(responseText)

            val ul = soup.selectFirst("section#qsp-overview-realtime-info ul")
            if (ul == null) {
                Log.e("YahooStockInfoFetcher", "Could not find the target 'ul' element for $stockCode")
                return@withContext null
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
            //Log.d("YahooStockInfoFetcher", "Parsed key-values for $stockCode: $kv")

            val priceStr = kv["成交"]
            val yesterdayPriceStr = kv["昨收"]

            val price = priceStr?.toDoubleOrNull()
            val yesterdayPrice = yesterdayPriceStr?.toDoubleOrNull()

            if (price != null && yesterdayPrice != null) {
                val change = price - yesterdayPrice
                val changePercent = if (yesterdayPrice != 0.0) (change / yesterdayPrice) * 100 else 0.0
                val info = RealtimeStockInfo(
                    currentPrice = price,
                    change = change,
                    changePercent = changePercent
                )
                Log.d("YahooStockInfoFetcher", "Successfully fetched info for $stockCode: $info")
                return@withContext info
            }

            Log.e("YahooStockInfoFetcher", "Failed to parse price or yesterday's price for $stockCode. PriceStr: $priceStr, YesterdayPriceStr: $yesterdayPriceStr")
            return@withContext null

        } catch (e: Exception) {
            Log.e("YahooStockInfoFetcher", "Exception while fetching stock info for $stockCode: ${e.message}", e)
            return@withContext null
        }
    }
}