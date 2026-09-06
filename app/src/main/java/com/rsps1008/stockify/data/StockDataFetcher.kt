package com.rsps1008.stockify.data

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.BufferedReader
import java.io.StringReader

private data class TaiwanStockListSection(
    val mode: String,
    val label: String
)

private val requiredTaiwanStockListSections = listOf(
    TaiwanStockListSection(mode = "2", label = "上市"),
    TaiwanStockListSection(mode = "4", label = "上櫃"),
    TaiwanStockListSection(mode = "5", label = "興櫃")
)

internal data class UsStockListSource(
    val url: String,
    val symbolColumn: String,
    val label: String
)

private val requiredUsStockListSources = listOf(
    UsStockListSource(
        url = "https://www.nasdaqtrader.com/dynamic/SymDir/nasdaqlisted.txt",
        symbolColumn = "Symbol",
        label = "Nasdaq Listed"
    ),
    UsStockListSource(
        url = "https://www.nasdaqtrader.com/dynamic/SymDir/otherlisted.txt",
        symbolColumn = "ACT Symbol",
        label = "Other Listed"
    )
)

private const val MINIMUM_US_STOCKS_PER_SOURCE = 100
private const val NASDAQ_TRADER_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

internal fun requireCompleteTaiwanStockLists(
    stocksByMode: Map<String, List<Stock>>
): List<Stock> = requiredTaiwanStockListSections.flatMap { section ->
    val stocks = stocksByMode[section.mode]
        ?: throw IllegalStateException("台股${section.label}清單取得失敗")
    if (stocks.isEmpty()) {
        throw IllegalStateException("台股${section.label}清單解析結果為空")
    }
    stocks
}

internal fun parseUsStockListSource(
    responseText: String,
    source: UsStockListSource
): List<Stock> {
    val reader = BufferedReader(StringReader(responseText))
    val header = reader.lineSequence()
        .firstOrNull { it.isNotBlank() }
        ?.trim()
        ?.removePrefix("\uFEFF")
        ?.trim()
        ?.split('|')
        ?.takeIf { it.isNotEmpty() }
        ?: throw IllegalStateException("${source.label} 缺少標頭")
    val columns = header.withIndex().associate { it.value.trim() to it.index }
    val symbolIndex = columns[source.symbolColumn]
        ?: throw IllegalStateException("${source.label} 缺少 ${source.symbolColumn} 欄位")
    val nameIndex = columns["Security Name"]
        ?: throw IllegalStateException("${source.label} 缺少 Security Name 欄位")
    val testIssueIndex = columns["Test Issue"] ?: -1
    val etfIndex = columns["ETF"] ?: -1
    val lastRequiredFieldIndex = maxOf(symbolIndex, nameIndex, testIssueIndex, etfIndex)
    val stocks = linkedMapOf<String, Stock>()
    var hasFooter = false

    reader.forEachLine { line ->
        val fields = line.split('|')
        val symbol = fields.getOrNull(symbolIndex)?.trim().orEmpty()
        if (symbol.startsWith("File Creation Time:")) {
            hasFooter = true
            return@forEachLine
        }
        if (fields.size <= lastRequiredFieldIndex) return@forEachLine
        if (symbol.isBlank()) return@forEachLine
        if (testIssueIndex >= 0 && fields.getOrNull(testIssueIndex)?.trim() == "Y") return@forEachLine

        val name = fields.getOrNull(nameIndex)?.trim().orEmpty()
        val stockType = if (etfIndex >= 0 && fields.getOrNull(etfIndex)?.trim() == "Y") {
            "ETF"
        } else {
            "Stock"
        }
        stocks.putIfAbsent(
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

    check(hasFooter) { "${source.label} 缺少檔案 footer" }
    check(stocks.size >= MINIMUM_US_STOCKS_PER_SOURCE) {
        "${source.label} 有效美股資料不足 $MINIMUM_US_STOCKS_PER_SOURCE 筆"
    }
    return stocks.values.toList()
}

class StockDataFetcher(
    private val client: HttpClient
) {

    suspend fun fetchStockList(): List<Stock> = withContext(Dispatchers.IO) {
        val stocksByMode = linkedMapOf<String, List<Stock>>()
        for (section in requiredTaiwanStockListSections) {
            try {
                stocksByMode[section.mode] = fetchTaiwanStockListSection(section.mode)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw IllegalStateException("台股${section.label}清單取得失敗", e)
            }
        }
        requireCompleteTaiwanStockLists(stocksByMode)
    }

    private fun fetchTaiwanStockListSection(mode: String): List<Stock> {
        val url = "https://isin.twse.com.tw/isin/C_public.jsp?strMode=$mode"
        val stocks = mutableListOf<Stock>()

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
        return stocks
    }

    suspend fun fetchUsStockList(): List<Stock> = withContext(Dispatchers.IO) {
        val stocksByCode = linkedMapOf<String, Stock>()

        for (source in requiredUsStockListSources) {
            val responseText = client.get(source.url) {
                // Nasdaq Trader may return an Imperva response instead of its text file to unknown clients.
                headers.append("User-Agent", NASDAQ_TRADER_USER_AGENT)
                headers.append("Accept", "text/plain, */*;q=0.8")
            }.bodyAsText()
            val sourceStocks = parseUsStockListSource(responseText, source)
            sourceStocks.forEach { stock ->
                stocksByCode.putIfAbsent(stock.code, stock)
            }
        }

        stocksByCode.values.toList().also {
            if (it.isEmpty()) throw IllegalStateException("Nasdaq Trader 未回傳可用的美股資料")
        }
    }
}
