package com.rsps1008.stockify.data.dividend

import android.annotation.SuppressLint
import android.util.Log
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class YahooExRightResponse(
    val stat: String,
    val data: List<List<String>>
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class DividendResult(
    val amount: Double,
    val date: String
)

class YahooDividendRepository(
    private val client: HttpClient
) {

    companion object {
        private const val IDX_DATE = 0
        private const val IDX_CODE = 1
        private const val IDX_NAME = 2
        private const val IDX_DIVIDEND = 5   // 權值+息值
        private const val IDX_TYPE = 6       // 權 / 息
    }

    /**
     * 取得「最新一筆現金股利（息）」
     */
    suspend fun fetchLatestCashDividend(
        stockCode: String
    ): DividendResult? {

        val url = "https://tw.stock.yahoo.com/quote/${stockCode}/dividend"
        val html: String = client.get(url).body()
        val doc = org.jsoup.Jsoup.parse(html)

        val rows = doc.select(".table-body ul > li")

        for (li in rows) {
            val cols = li.select("div")
            if (cols.size < 9) continue

            val belong = cols[3].text().trim()
            if (belong.isEmpty()) continue

            val cashText = cols[4].text().trim()
            val rawDate = cols[8].text().trim()   // ★ 取得日期，如 2025/10/23

            val value = cashText.replace(",", "").toDoubleOrNull()
            if (value != null) {
                return DividendResult(value, rawDate)
            }
        }
        return null
    }

    /**
     * 從 Yahoo 取得最新一筆「有所屬期間」的股票股利
     */
    suspend fun fetchLatestStockDividend(stockCode: String): DividendResult? {

        val url = "https://tw.stock.yahoo.com/quote/${stockCode}/dividend"
        val html: String = client.get(url).body()
        val doc = org.jsoup.Jsoup.parse(html)

        val rows = doc.select(".table-body ul > li")

        for (li in rows) {
            val cols = li.select("div")
            if (cols.size < 9) continue

            val belong = cols[3].text().trim()
            if (belong.isEmpty()) continue

            val stockText = cols[5].text().trim()
            val rawDate = cols[8].text().trim() // ★ 加上日期

            val value = stockText.replace(",", "").toDoubleOrNull()
            if (value != null) {
                return DividendResult(value, rawDate)
            }
        }
        return null
    }

}
