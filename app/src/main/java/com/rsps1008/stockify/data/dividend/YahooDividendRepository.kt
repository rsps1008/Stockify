package com.rsps1008.stockify.data.dividend

import android.annotation.SuppressLint
import android.util.Log
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
    ): Double? {

        val url = "https://tw.stock.yahoo.com/quote/${stockCode}/dividend"

        val html: String = client.get(url).body()

        val doc = org.jsoup.Jsoup.parse(html)

        val rows = doc.select(".table-body ul > li")

        for (li in rows) {
            val cols = li.select("div")
            if (cols.size < 6) continue

            val belong = cols[3].text().trim()   // 所屬期間
            if (belong.isEmpty()) continue

            val cashText = cols[4].text().trim() // 現金股利

            return cashText
                .replace(",", "")
                .toDoubleOrNull()
        }

        return null
    }

    /**
     * 從 Yahoo 取得最新一筆「有所屬期間」的股票股利
     */
    suspend fun fetchLatestStockDividend(stockCode: String): Double? {

        val url = "https://tw.stock.yahoo.com/quote/${stockCode}/dividend"
        val html: String = client.get(url).body()
        val doc = org.jsoup.Jsoup.parse(html)

        val rows = doc.select(".table-body ul > li")

        for (li in rows) {
            val cols = li.select("div")
            if (cols.size < 6) continue

            val belong = cols[3].text().trim()
            if (belong.isEmpty()) continue

            val stockText = cols[5].text().trim()   // ★ index=5 → 股票股利

            return stockText.replace(",", "").toDoubleOrNull()
        }

        return null
    }
}
