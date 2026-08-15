package com.rsps1008.stockify.data.dividend

import android.annotation.SuppressLint
import com.rsps1008.stockify.data.retryOnTransientNetworkFailure
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.Serializable
import org.jsoup.Jsoup
import java.io.IOException

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

data class YahooDividendSummary(
    val cashDividend: DividendResult?,
    val stockDividend: DividendResult?
)

internal fun parseYahooDividendPage(html: String): YahooDividendSummary {
    var cashDividend: DividendResult? = null
    var stockDividend: DividendResult? = null

    val rows = Jsoup.parse(html).select(".table-body ul > li")
    for (li in rows) {
        val cols = li.select("div")
        if (cols.size < 9) continue

        if (cols[3].text().trim().isEmpty()) continue

        val rawDate = cols[8].text().trim()
        if (cashDividend == null) {
            val value = cols[4].text().trim().replace(",", "").toDoubleOrNull()
            if (value != null) {
                cashDividend = DividendResult(value, rawDate)
            }
        }
        if (stockDividend == null) {
            val value = cols[5].text().trim().replace(",", "").toDoubleOrNull()
            if (value != null) {
                stockDividend = DividendResult(value, rawDate)
            }
        }

        if (cashDividend != null && stockDividend != null) break
    }

    return YahooDividendSummary(
        cashDividend = cashDividend,
        stockDividend = stockDividend
    )
}

class YahooDividendRepository(
    private val client: HttpClient
) {

    companion object {
        private const val TAG = "YahooDividendRepository"
        private const val USER_AGENT = "Mozilla/5.0"
    }

    private suspend fun fetchLatestDividendsFromYahoo(stockCode: String): YahooDividendSummary {
        val url = "https://tw.stock.yahoo.com/quote/${stockCode}/dividend"
        val html = retryOnTransientNetworkFailure(TAG, stockCode) {
            client.get(url) {
                header("User-Agent", USER_AGENT)
            }.bodyAsText()
        } ?: throw IOException("Yahoo 股利資料請求失敗: $stockCode")

        return parseYahooDividendPage(html)
    }

    /**
     * 一次取得同一個 Yahoo 頁面的現金股利與股票股利。
     */
    suspend fun fetchLatestDividends(stockCode: String): YahooDividendSummary {
        return fetchLatestDividendsFromYahoo(stockCode)
    }

    /**
     * 取得「最新一筆現金股利（息）」
     */
    suspend fun fetchLatestCashDividend(stockCode: String): DividendResult? =
        fetchLatestDividends(stockCode).cashDividend

    /**
     * 從 Yahoo 取得最新一筆「有所屬期間」的股票股利
     */
    suspend fun fetchLatestStockDividend(stockCode: String): DividendResult? =
        fetchLatestDividends(stockCode).stockDividend

}
