package com.rsps1008.stockify.ui.viewmodel

import com.rsps1008.stockify.data.Stock
import com.rsps1008.stockify.data.StockTransaction
import com.rsps1008.stockify.data.TransactionListSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicInteger

class TransactionsViewModelTest {
    @Test
    fun buildTransactionDateSectionsMapsAndGroupsOffscreenData() {
        val snapshot = TransactionListSnapshot(
            stocks = listOf(
                Stock(name = "台積電", code = "2330", market = "TW"),
                Stock(name = "Apple", code = "AAPL", market = "US")
            ),
            transactions = listOf(
                StockTransaction(id = 1, stockCode = "2330", market = "TW", accountId = 1, date = 0L, recordTime = 1L, type = "買進"),
                StockTransaction(id = 2, stockCode = "AAPL", market = "US", accountId = 1, date = 86_400_000L, recordTime = 1L, type = "買進"),
                StockTransaction(id = 3, stockCode = "2330", market = "TW", accountId = 1, date = 0L, recordTime = 2L, type = "賣出")
            ),
            accountId = 1
        )

        val sections = buildTransactionDateSections(
            snapshot = snapshot,
            activeAccountId = 1,
            locale = Locale.US,
            timeZone = TimeZone.getTimeZone("UTC")
        )

        assertEquals(2, sections.size)
        assertEquals("1970/01/01 (Thu)", sections[0].date)
        assertEquals(2, sections[0].transactions.size)
        assertEquals("台積電", sections[0].transactions[0].stockName)
        assertEquals("1970/01/02 (Fri)", sections[1].date)
        assertEquals("US", sections[1].transactions.single().market)
    }

    @Test
    fun buildTransactionDateSectionsDropsStaleAccountSnapshot() {
        val sections = buildTransactionDateSections(
            snapshot = TransactionListSnapshot(accountId = 2),
            activeAccountId = 1,
            locale = Locale.US,
            timeZone = TimeZone.getTimeZone("UTC")
        )

        assertEquals(emptyList<TransactionDateSection>(), sections)
    }

    @Test
    fun missingStockKeepsTransactionCurrencyForDisplay() {
        val sections = buildTransactionDateSections(
            snapshot = TransactionListSnapshot(
                transactions = listOf(
                    StockTransaction(
                        id = 1, stockCode = "AAPL", market = "US", accountId = 1,
                        date = 0L, recordTime = 1L, type = "買進"
                    )
                ),
                accountId = 1
            ),
            activeAccountId = 1
        )

        assertEquals("US", sections.single().transactions.single().market)
    }

    @Test
    fun searchMatchesCodeNameTypeNoteAndDateWithoutChangingOrder() {
        val sections = buildTransactionDateSections(searchSnapshot(), 1, Locale.US, TimeZone.getTimeZone("UTC"))
        fun ids(query: String) = filterTransactionDateSections(sections, query)
            .flatMap { it.transactions }.map { it.transaction.id }

        assertEquals(listOf(2, 3), ids(" aApL "))
        assertEquals(listOf(1), ids("台積"))
        assertEquals(listOf(3), ids("Apple 配息"))
        assertEquals(listOf(2), ids("定期定額"))
        assertEquals(listOf(1, 2), ids("1970/01/02"))
        assertEquals(emptyList<Int>(), ids("2330 配息"))
        assertEquals(emptyList<TransactionDateSection>(), filterTransactionDateSections(sections, "查無股票"))
        assertSame(sections, filterTransactionDateSections(sections, "  \t "))
        assertSame(sections[0].transactions[1], filterTransactionDateSections(sections, "aapl")[0].transactions[0])
    }

    @Test
    fun searchReusesPreparedRowsAndTracksAccountAndRoomChanges() = runBlocking {
        val stockReads = AtomicInteger()
        val source = searchSnapshot()
        val countedStocks = object : AbstractList<Stock>() {
            override val size: Int get() = source.stocks.size
            override fun get(index: Int): Stock {
                stockReads.incrementAndGet()
                return source.stocks[index]
            }
        }
        val snapshots = MutableStateFlow(TransactionListSnapshot())
        val accounts = MutableStateFlow(1)
        val queries = MutableStateFlow("")
        val states = Channel<TransactionsUiState>(Channel.UNLIMITED)
        val job = launch {
            transactionListUiStates(snapshots, accounts, queries).collect { states.send(it) }
        }
        suspend fun next(predicate: (TransactionsUiState) -> Boolean): TransactionsUiState = withTimeout(5_000) {
            var state = states.receive()
            while (!predicate(state)) state = states.receive()
            state
        }
        try {
            val loading = next { it.isLoading }
            assertTrue(loading.sections.isEmpty())
            snapshots.value = source.copy(stocks = countedStocks)
            val ready = next { !it.isLoading }
            assertEquals(3, ready.resultCount)
            val readsAfterPreparation = stockReads.get()
            assertTrue(readsAfterPreparation > 0)

            queries.value = "aapl"
            val searched = next { it.query == "aapl" }
            assertEquals(2, searched.resultCount)
            assertEquals(3, searched.totalCount)
            assertEquals(readsAfterPreparation, stockReads.get())

            queries.value = ""
            val cleared = next { it.query.isEmpty() }
            assertSame(ready.sections, cleared.sections)
            assertEquals(readsAfterPreparation, stockReads.get())

            accounts.value = 2
            val switching = next { it.accountId == 2 }
            assertTrue(switching.isLoading)
            assertTrue(switching.sections.isEmpty())
            snapshots.value = TransactionListSnapshot(accountId = 2, isLoaded = true)
            val empty = next { !it.isLoading }
            assertEquals(0, empty.totalCount)

            val imported = source.transactions[1].copy(accountId = 2)
            queries.value = "定期定額"
            snapshots.value = TransactionListSnapshot(
                stocks = source.stocks, transactions = listOf(imported), accountId = 2, isLoaded = true
            )
            val updated = next { it.query == "定期定額" && it.resultCount == 1 }
            assertFalse(updated.isLoading)
            assertEquals(listOf(imported), updated.sections.flatMap { it.transactions }.map { it.transaction })

            snapshots.value = snapshots.value.copy(transactions = emptyList())
            assertEquals(0, next { it.totalCount == 0 }.resultCount)
        } finally {
            job.cancel()
            states.cancel()
        }
    }

    private fun searchSnapshot() = TransactionListSnapshot(
        stocks = listOf(
            Stock(name = "台積電", code = "2330", market = "TW"),
            Stock(name = "Apple", code = "AAPL", market = "US")
        ),
        transactions = listOf(
            StockTransaction(id = 1, stockCode = "2330", market = "TW", accountId = 1, date = 86_400_000L, recordTime = 3L, type = "買進"),
            StockTransaction(id = 2, stockCode = "AAPL", market = "US", accountId = 1, date = 86_400_000L, recordTime = 2L, type = "買進", note = "定期定額"),
            StockTransaction(id = 3, stockCode = "AAPL", market = "US", accountId = 1, date = 0L, recordTime = 1L, type = "配息")
        ),
        accountId = 1,
        isLoaded = true
    )
}
