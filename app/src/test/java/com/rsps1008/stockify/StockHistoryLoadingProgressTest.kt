package com.rsps1008.stockify

import com.rsps1008.stockify.data.Stock
import com.rsps1008.stockify.data.StockDao
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
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StockHistoryLoadingProgressTest {
    private val clock = Clock.fixed(Instant.parse("2026-08-25T06:00:00Z"), ZoneOffset.UTC)

    @Test
    fun expandingRangeCountsMonthsAlreadyDownloadedInEarlierStages() = runBlocking {
        val fixture = HistoryFixture()
        fixture.client.use { client ->
            val service = TwseStockHistoryService(client, fixture.dao, clock)
            val progress = mutableListOf<Pair<Int, Int>>()
            service.fetchHistory("2330", 1) { step, total -> progress += step to total }
            assertEquals(listOf(1 to 2, 2 to 2), progress)
            assertEquals(listOf("202607", "202608"), fixture.requestedMonths)

            progress.clear()
            fixture.requestedMonths.clear()
            val sixMonths = service.fetchHistory("2330", 6) { step, total -> progress += step to total }
            assertEquals((3..7).map { it to 7 }, progress)
            assertEquals(listOf("202602", "202603", "202604", "202605", "202606"), fixture.requestedMonths)
            assertEquals(7, sixMonths.size)
            assertEquals(sixMonths.sortedBy { it.date }, sixMonths)

            progress.clear()
            fixture.requestedMonths.clear()
            val year = service.fetchHistory("2330", 12) { step, total -> progress += step to total }
            assertEquals((8..13).map { it to 13 }, progress)
            assertEquals(listOf("202508", "202509", "202510", "202511", "202512", "202601"), fixture.requestedMonths)
            assertEquals(13, year.size)
        }
    }

    @Test
    fun roomMonthsAreCountedBeforeTheFirstMissingMonthIsDownloaded() = runBlocking {
        val fixture = HistoryFixture()
        (4..8).forEach { fixture.seed(LocalDate.of(2026, it, 25).toString()) }
        fixture.client.use { client ->
            val service = TwseStockHistoryService(client, fixture.dao, clock)
            val progress = mutableListOf<Pair<Int, Int>>()
            service.fetchHistory("2330", 6) { step, total -> progress += step to total }

            assertEquals(listOf(6 to 7, 7 to 7), progress)
            assertEquals(listOf("202602", "202603"), fixture.requestedMonths)
        }
    }

    @Test
    fun fullyCachedRangeReportsOnlyCompletionWithoutDownloading() = runBlocking {
        val fixture = HistoryFixture()
        (2..8).forEach { fixture.seed(LocalDate.of(2026, it, 25).toString()) }
        fixture.client.use { client ->
            val service = TwseStockHistoryService(client, fixture.dao, clock)
            val progress = mutableListOf<Pair<Int, Int>>()
            val points = service.fetchHistory("2330", 6) { step, total -> progress += step to total }

            assertEquals(listOf(7 to 7), progress)
            assertTrue(fixture.requestedMonths.isEmpty())
            assertEquals(7, points.size)
        }
    }

    @Test
    fun incompleteCurrentMonthStillLoadsAfterAllReusableMonths() = runBlocking {
        val fixture = HistoryFixture()
        (2..7).forEach { fixture.seed(LocalDate.of(2026, it, 25).toString()) }
        fixture.seed("2026-08-24")
        fixture.client.use { client ->
            val service = TwseStockHistoryService(client, fixture.dao, clock)
            val progress = mutableListOf<Pair<Int, Int>>()
            val points = service.fetchHistory("2330", 6) { step, total -> progress += step to total }

            assertEquals(listOf(7 to 7), progress)
            assertEquals(listOf("202608"), fixture.requestedMonths)
            assertEquals("2026-08-25", points.last().date)
            assertTrue(points.any { it.date == "2026-08-24" })
        }
    }

    @Test
    fun forcedRefreshCountsHistoricalCacheButRefreshesTheCurrentMonth() = runBlocking {
        val fixture = HistoryFixture()
        (2..8).forEach { fixture.seed(LocalDate.of(2026, it, 25).toString(), price = 99.0) }
        fixture.client.use { client ->
            val service = TwseStockHistoryService(client, fixture.dao, clock)
            service.getCachedHistory("2330", 6)
            val progress = mutableListOf<Pair<Int, Int>>()
            val points = service.fetchHistory("2330", 6, forceRefreshCurrentMonth = true) { step, total ->
                progress += step to total
            }

            assertEquals(listOf(7 to 7), progress)
            assertEquals(listOf("202608"), fixture.requestedMonths)
            assertEquals(7, points.size)
            assertEquals(100.0, points.last().price, 0.0)
        }
    }

    @Test
    fun monthBeforeItsFirstCloseDoesNotInflateTheProgressTotal() = runBlocking {
        val fixture = HistoryFixture()
        fixture.seed("2026-08-31")
        val beforeSeptemberClose = Clock.fixed(Instant.parse("2026-09-01T02:00:00Z"), ZoneOffset.UTC)
        fixture.client.use { client ->
            val service = TwseStockHistoryService(client, fixture.dao, beforeSeptemberClose)
            val progress = mutableListOf<Pair<Int, Int>>()
            service.fetchHistory("2330", 1) { step, total -> progress += step to total }

            assertEquals(listOf(1 to 1), progress)
            assertTrue(fixture.requestedMonths.isEmpty())
        }
    }

    private class HistoryFixture {
        val requestedMonths = mutableListOf<String>()
        private val prices = mutableMapOf<String, StockHistoryPrice>()

        fun seed(date: String, price: Double = 100.0) {
            prices[date] = StockHistoryPrice("2330", date, price, "TW")
        }

        val dao = Proxy.newProxyInstance(
            StockDao::class.java.classLoader,
            arrayOf(StockDao::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getStockByCode" -> Stock(name = "台積電", code = "2330", market = "TW")
                "getHistoryPricesForMonth" -> prices.values.filter { it.date.startsWith(args!![2] as String) }
                "insertHistoryPrices" -> {
                    @Suppress("UNCHECKED_CAST")
                    (args!![0] as List<StockHistoryPrice>).forEach { prices[it.date] = it }
                    Unit
                }
                else -> error("Unexpected DAO call: ${method.name}")
            }
        } as StockDao

        val client = HttpClient(object : HttpClientEngineBase("history-progress-test") {
            override val config = HttpClientEngineConfig()

            @OptIn(io.ktor.utils.io.InternalAPI::class)
            override suspend fun execute(data: HttpRequestData): HttpResponseData {
                val month = requireNotNull(data.url.parameters["date"]).take(6)
                requestedMonths += month
                val date = LocalDate.of(month.take(4).toInt(), month.takeLast(2).toInt(), 25)
                val body = """{"stat":"OK","data":[["${date.year - 1911}/${date.monthValue}/${date.dayOfMonth}","0","0","0","0","0","100.00","0","0"]]}"""
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
