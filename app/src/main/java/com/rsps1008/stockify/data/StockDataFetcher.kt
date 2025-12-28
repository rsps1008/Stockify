package com.rsps1008.stockify.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

class StockDataFetcher {

    private val client = HttpClient(CIO)

    suspend fun fetchStocks(): List<Stock> = withContext(Dispatchers.IO) {
        val url = "https://isin.twse.com.tw/isin/C_public.jsp?strMode=2"
        val response = client.get(url)
        val htmlBody = response.bodyAsText()
        parseStocks(htmlBody)
    }

    private fun isValidStock(code: String, name: String): Boolean {
        val blacklist = listOf("購", "售", "牛", "熊")
        if (blacklist.any { name.contains(it) }) {
            return false
        }

        if (code.all { it.isDigit() } && code.length == 6) {
            return false
        }

        if (code.all { it.isDigit() } && (code.length == 4 || code.length == 5)) {
            return true
        }

        if (code.length == 6 && code.dropLast(1).all { it.isDigit() } && code.last().isLetter()) {
            return true
        }

        return false
    }

    private fun parseStocks(html: String): List<Stock> {
        val document = Jsoup.parse(html)
        val stocks = mutableListOf<Stock>()
        val rows = document.select("tr")

        for (row in rows) {
            val columns = row.select("td")
            if (columns.size > 1) {
                val codeAndName = columns[0].text().split(" ", "　")
                if (codeAndName.size >= 2) {
                    val stockCode = codeAndName[0]
                    val stockName = codeAndName.drop(1).joinToString(" ").trim()
                    if (isValidStock(stockCode, stockName)) {
                        stocks.add(Stock(name = stockName, code = stockCode))
                    }
                }
            }
        }
        return stocks
    }
}