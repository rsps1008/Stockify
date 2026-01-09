package com.rsps1008.stockify.data

import android.R
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

class StockDataFetcher {
    suspend fun fetchStockList(): List<Stock> = withContext(Dispatchers.IO) {
        val modes = listOf("2", "4")   // 同時抓上市跟上櫃
        val stocks = mutableListOf<Stock>()

        for (mode in modes) {
            val url = "https://isin.twse.com.tw/isin/C_public.jsp?strMode=$mode"

            try {
                val response = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0")
                    .timeout(30000)
                    .maxBodySize(0)
                    .execute()

                val doc = Jsoup.parse(response.bodyAsBytes().inputStream(), "Big5", url)
                val rows = doc.select("table.h4 tr")

                var stockType: String = "股票"

                for (row in rows) {
                    val cols = row.select("td")

                    // 單欄位 → 用來判斷目前類別（股票、ETF、受益證券...）
                    if (cols.size == 1) {
                        stockType = cols[0].text()
                    }
                    // 7 欄位 → 真正的股票資料列
                    else if (cols.size == 7) {
                        val fullText = cols[0].text()

                        if (fullText.contains("　")) {
                            val codeAndName = fullText.split("　")
                            if (codeAndName.size >= 2) {
                                val code = codeAndName[0].trim()
                                val name = codeAndName[1].trim()
                                val market = cols[3].text().trim()
                                val industry = cols[4].text().trim()

                                // 過濾掉不需要的分類
                                if (!stockType.contains("上市認購")
                                    && !stockType.contains("上櫃認購")
                                    && !stockType.contains("臺灣存託憑證")
                                    && !stockType.contains("不動產投資信託")
                                    && !stockType.contains("受益證券")) {

                                    stocks.add(
                                        Stock(
                                            id = 0,
                                            code = code,
                                            name = name,
                                            market = market,
                                            industry = industry,
                                            stockType = stockType
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        stocks
    }
}