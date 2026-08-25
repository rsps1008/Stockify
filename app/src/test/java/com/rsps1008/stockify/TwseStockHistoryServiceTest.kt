package com.rsps1008.stockify

import com.rsps1008.stockify.data.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*

class TwseStockHistoryServiceTest {

    private val mockDao = object : StockDao {
        override suspend fun insertStocks(stocks: List<Stock>) {}
        override suspend fun replaceStocks(stocks: List<Stock>) {}
        override suspend fun updateStocks(stocks: List<Stock>) {}
        override suspend fun insertStock(stock: Stock) {
            println("DAO insertStock: $stock")
        }
        override suspend fun updateStock(stock: Stock) {}
        override suspend fun insertTransaction(transaction: StockTransaction) {}
        override suspend fun insertTransactions(transactions: List<StockTransaction>) {}
        override suspend fun updateTransaction(transaction: StockTransaction): Int = 1
        override suspend fun deleteTransaction(transaction: StockTransaction) {}
        override suspend fun deleteAllTransactions() {}
        override suspend fun deleteAllStocks() {}
        override suspend fun deleteAccount(account: Account) {}
        override suspend fun deleteAllAccounts() {}
        override suspend fun getAccountCount(): Int = 0
        override suspend fun insertAccount(account: Account) {}
        override suspend fun updateAccount(account: Account) {}
        override fun getAllAccountsFlow(): Flow<List<Account>> = flowOf(emptyList())
        override suspend fun getAccountById(accountId: Int): Account? = null
        override suspend fun getAccountsByIds(accountIds: List<Int>): List<Account> = emptyList()
        override suspend fun insertAccounts(accounts: List<Account>) {}
        override suspend fun replaceAccounts(accounts: List<Account>) {}
        override suspend fun getStocksByMarketAndCodes(market: String, codes: List<String>): List<Stock> = emptyList()
        override suspend fun getStocksByCodesForImportRepair(codes: List<String>): List<Stock> = emptyList()
        override fun searchStocks(query: String, likeQuery: String, limit: Int): Flow<List<Stock>> = flowOf(emptyList())
        override fun getHeldStocks(): Flow<List<Stock>> = flowOf(emptyList())
        override fun getTransactionsForStock(stockCode: String, market: String): Flow<List<StockTransaction>> = flowOf(emptyList())
        override fun getTransactionsForStockAndAccount(stockCode: String, market: String, accountId: Int): Flow<List<StockTransaction>> = flowOf(emptyList())
        override fun getAllTransactions(): Flow<List<StockTransaction>> = flowOf(emptyList())
        override suspend fun getTransactionsForStockCodesAndMarket(market: String, stockCodes: List<String>): List<StockTransaction> = emptyList()
        override suspend fun getMaxTransactionId(): Int = 0
        override fun getTransactionsForAccount(accountId: Int): Flow<List<StockTransaction>> = flowOf(emptyList())
        override fun getTransactionsWithStock(): Flow<List<TransactionWithStock>> = flowOf(emptyList())
        override fun getTransactionById(transactionId: Int): Flow<StockTransaction?> = flowOf(null)
        override fun getMarginRepaymentsForLot(lotId: String, stockCode: String, market: String, accountId: Int): Flow<List<StockTransaction>> = flowOf(emptyList())
        override fun getShortDependentsForLot(lotId: String, stockCode: String, market: String, accountId: Int): Flow<List<StockTransaction>> = flowOf(emptyList())
        override fun getStockById(stockId: Int): Flow<Stock?> = flowOf(null)
        override suspend fun getStockByCode(code: String, market: String): Stock? {
            println("DAO getStockByCode: $code/$market")
            return Stock(name = "MAIN INTERNATIONAL ETF", code = "INTL", market = "US", stockType = "ETF")
        }
        override fun getStockByCodeFlow(code: String, market: String): Flow<Stock?> = flowOf(null)
        override suspend fun getStocksCount(): Int = 0
        override suspend fun getStockCountByMarket(market: String): Int = 0
        override suspend fun getStockCountByMarketAndExchange(market: String, exchange: String): Int = 0
        override suspend fun getStocksByMarket(market: String): List<Stock> = emptyList()
        override suspend fun deleteStocksByMarket(market: String) {}
        override suspend fun deleteUnreferencedStocksByMarket(market: String) {}
        override suspend fun updateTaiwanStockExchange(code: String, exchange: String) {}
        override suspend fun deleteTransactionsByStockCode(stockCode: String, market: String) {}
        override suspend fun deleteTransactionsByStockCodeAndAccountId(stockCode: String, market: String, accountId: Int) {}
        override suspend fun updateTransactionMarket(stockCode: String, fromMarket: String, toMarket: String) {}
        override suspend fun deleteTransactionsByAccountId(accountId: Int) {}
        override suspend fun getHoldingShares(stockCode: String, market: String): Double = 0.0
        override suspend fun insertHistoryPrices(prices: List<StockHistoryPrice>) {
            println("DAO insertHistoryPrices: ${prices.size} points")
        }
        override suspend fun deleteAllHistoryPrices() {}
        override suspend fun getHistoryPricesForMonth(stockCode: String, market: String, monthPrefix: String): List<StockHistoryPrice> {
            return emptyList()
        }
    }

    @Test
    fun testFetchUsHistory() = runBlocking {
        val client = HttpClient(CIO)
        val service = TwseStockHistoryService(client, mockDao)
        val points = service.fetchHistory("INTL", 1) { step, total ->
            println("Progress: $step / $total")
        }
        println("Fetched points size: ${points.size}")
        if (points.isNotEmpty()) {
            println("First point: ${points.first()}")
            println("Last point: ${points.last()}")
        }
        assertTrue(points.isNotEmpty())
    }
}
