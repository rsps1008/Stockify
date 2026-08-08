package com.rsps1008.stockify.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.BufferedReader
import java.io.StringReader

class StockDataFetcher {
    private val client = HttpClient(CIO) {
        engine {
            requestTimeout = 30000
        }
    }

    suspend fun fetchStockList(): List<Stock> = withContext(Dispatchers.IO) {
        val modes = listOf("2", "4", "5")   // 同時抓上市、上櫃跟興櫃
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
                                            market = StockMarket.TW,
                                            exchange = StockExchange.normalize(cols[3].text()),
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

    suspend fun fetchUsStockList(): List<Stock> = withContext(Dispatchers.IO) {
        val sources = listOf(
            "https://www.nasdaqtrader.com/dynamic/SymDir/nasdaqlisted.txt" to "Symbol",
            "https://www.nasdaqtrader.com/dynamic/SymDir/otherlisted.txt" to "ACT Symbol"
        )
        val stocksByCode = linkedMapOf<String, Stock>()

        for ((url, symbolColumn) in sources) {
            val responseText = client.get(url).bodyAsText()
            val reader = BufferedReader(StringReader(responseText))
            val header = reader.readLine()
                ?.removePrefix("\uFEFF")
                ?.split('|')
                ?: continue
            val columns = header.withIndex().associate { it.value to it.index }
            val symbolIndex = columns[symbolColumn] ?: continue
            val nameIndex = columns["Security Name"] ?: continue
            val testIssueIndex = columns["Test Issue"] ?: -1
            val etfIndex = columns["ETF"] ?: -1

            reader.forEachLine { line ->
                val fields = line.split('|')
                val symbol = fields.getOrNull(symbolIndex)?.trim().orEmpty()
                if (symbol.isBlank() || symbol.startsWith("File Creation Time:")) return@forEachLine
                if (testIssueIndex >= 0 && fields.getOrNull(testIssueIndex)?.trim() == "Y") return@forEachLine

                val name = fields.getOrNull(nameIndex)?.trim().orEmpty()
                val stockType = if (etfIndex >= 0 && fields.getOrNull(etfIndex)?.trim() == "Y") {
                    "ETF"
                } else {
                    "Stock"
                }
                stocksByCode.putIfAbsent(
                    symbol,
                    Stock(
                        id = 0,
                        code = symbol,
                        name = name.ifBlank { symbol },
                        market = StockMarket.US,
                        industry = "",
                        stockType = stockType
                    )
                )
            }
        }

        stocksByCode.values.toList().also {
            if (it.isEmpty()) throw IllegalStateException("Nasdaq Trader 未回傳可用的美股資料")
        }
    }
}
