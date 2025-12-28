package com.rsps1008.stockify.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

class StockDataFetcher {

    private fun isValidStock(code: String, name: String): Boolean {
        // 排除權證名稱
        val blacklist = listOf("購", "售", "牛", "熊")
        if (blacklist.any { name.contains(it) }) {
            return false
        }

        // 排除六碼純數字（權證）
        if (code.length == 6 && code.all { it.isDigit() }) {
            return false
        }

        // 保留 4 碼純數字（股票）
        if (code.length == 4 && code.all { it.isDigit() }) {
            return true
        }

        // 保留 5 碼純數字（ETF 新系列）
        if (code.length == 5 && code.all { it.isDigit() }) {
            return true
        }

        // 保留 6 碼（五數字 + 一英文）的槓桿反向 ETF
        if (code.length == 6 && code.dropLast(1).all { it.isDigit() } && code.last().isLetter()) {
            return true
        }

        return false
    }

    suspend fun fetchStockList(): List<Stock> = withContext(Dispatchers.IO) {
        val url = "https://isin.twse.com.tw/isin/C_public.jsp?strMode=2"
        val stocks = mutableListOf<Stock>()
        
        try {
            val doc = Jsoup.connect(url).get()
            val rows = doc.select("tr")

            for (row in rows) {
                val cols = row.select("td")
                if (cols.size > 1) {
                    val codeAndName = cols[0].text().split("　") //注意這裡是全形空白
                    if (codeAndName.size >= 2) {
                        val code = codeAndName[0].trim()
                        val name = codeAndName[1].trim()
                        val market = cols[2].text().trim()
                        val industry = cols[4].text().trim()

                        if (isValidStock(code, name)) {
                            stocks.add(Stock(id = 0, code = code, name = name, market = market, industry = industry))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Handle exceptions, e.g., network errors
            e.printStackTrace()
        }
        stocks
    }
}