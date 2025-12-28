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
            // 修改點 1: 使用 .execute() 並指定編碼，或是先抓 byte 再解析
            val response = Jsoup.connect(url)
                .userAgent("Mozilla/5.0")
                .timeout(30000)
                .maxBodySize(0) // 關鍵：0 代表不限制大小
                .execute();
            val doc = Jsoup.parse(response.bodyAsBytes().inputStream(), "Big5", url)

            // 修改點 2: 定位更精準的 table 標籤 (比照 Python 的 table.h4)
            val rows = doc.select("table.h4 tr")

            for (row in rows) {
                val cols = row.select("td")

                // 必須要有 7 個欄位才是我們要的資料列 (0050 那一行有 7 個 td)
                if (cols.size == 7) {
                    val fullText = cols[0].text()

                    // 修改點 3: 處理全形空白切割，先取代為半形或直接用 Regex
                    if (fullText.contains("　")) {
                        val codeAndName = fullText.split("　")
                        if (codeAndName.size >= 2) {
                            val code = codeAndName[0].trim()
                            val name = codeAndName[1].trim()
                            val market = cols[3].text().trim() // 索引位置請對照網頁，通常市場在第 4 欄
                            val industry = cols[4].text().trim()

                            if (isValidStock(code, name)) {
                                stocks.add(Stock(id = 0, code = code, name = name, market = market, industry = industry))
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        stocks
    }
}