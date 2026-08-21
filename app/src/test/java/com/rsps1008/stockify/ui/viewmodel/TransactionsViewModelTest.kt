package com.rsps1008.stockify.ui.viewmodel

import com.rsps1008.stockify.data.Stock
import com.rsps1008.stockify.data.StockTransaction
import com.rsps1008.stockify.data.TransactionListSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

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
}
