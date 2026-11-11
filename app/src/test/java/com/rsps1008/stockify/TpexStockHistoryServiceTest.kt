package com.rsps1008.stockify

import com.rsps1008.stockify.data.Stock
import com.rsps1008.stockify.data.StockDao
import com.rsps1008.stockify.data.StockExchange
import com.rsps1008.stockify.data.StockHistoryPoint
import com.rsps1008.stockify.data.StockHistoryPrice
import com.rsps1008.stockify.data.TwseStockHistoryService
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineBase
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.callContext
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.ByteReadChannel
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class TpexStockHistoryServiceTest {
    @Test
    fun otcStockHistoryUsesTpexMonthlyEndpointAndIncludesClosePrice() = runBlocking {
        val fixture = OtcHistoryFixture()
        fixture.client.use { client ->
            val service = TwseStockHistoryService(client, fixture.dao, fixture.clock)

            val points = service.fetchHistory("6488", rangeMonths = 0) { _, _ -> }

            assertEquals(
                "https://www.tpex.org.tw/www/zh-tw/afterTrading/tradingStock",
                fixture.requestPath
            )
            assertEquals("6488", fixture.requestParameters["code"])
            assertEquals("2026/11/01", fixture.requestParameters["date"])
            assertEquals("json", fixture.requestParameters["response"])
            assertEquals(listOf(StockHistoryPoint("2026-11-10", 1085.0)), points)
            assertEquals(1085.0, fixture.savedPrices.single().price, 0.0)
        }
    }

    private class OtcHistoryFixture {
        val clock: Clock = Clock.fixed(Instant.parse("2026-11-10T06:00:00Z"), ZoneOffset.UTC)
        var requestPath: String = ""
        var requestParameters: Map<String, String> = emptyMap()
        val savedPrices = mutableListOf<StockHistoryPrice>()

        val dao = Proxy.newProxyInstance(
            StockDao::class.java.classLoader,
            arrayOf(StockDao::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getStockByCode" -> Stock(
                    name = "環球晶",
                    code = "6488",
                    market = "TW",
                    exchange = StockExchange.OTC
                )
                "getHistoryPricesForMonth" -> emptyList<StockHistoryPrice>()
                "insertHistoryPrices" -> {
                    @Suppress("UNCHECKED_CAST")
                    savedPrices += args!![0] as List<StockHistoryPrice>
                    Unit
                }
                else -> error("Unexpected DAO call: ${method.name}")
            }
        } as StockDao

        val client = HttpClient(object : HttpClientEngineBase("tpex-history-test") {
            override val config = HttpClientEngineConfig()

            @OptIn(io.ktor.utils.io.InternalAPI::class)
            override suspend fun execute(data: HttpRequestData): HttpResponseData {
                requestPath = "https://${data.url.host}${data.url.encodedPath}"
                requestParameters = data.url.parameters.entries().associate { (key, values) ->
                    key to values.single()
                }
                val body = """{"tables":[{"data":[["115/11/10","1,000","1,000","1,080.00","1,090.00","1,070.00","1,085.00","5.00","10"]]}],"stat":"ok"}"""
                return HttpResponseData(
                    statusCode = HttpStatusCode.OK,
                    requestTime = GMTDate(),
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    version = HttpProtocolVersion.HTTP_1_1,
                    body = ByteReadChannel(body),
                    callContext = callContext()
                )
            }
        })
    }
}
