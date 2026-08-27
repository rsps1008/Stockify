package com.rsps1008.stockify

import com.rsps1008.stockify.data.Account
import com.rsps1008.stockify.data.CsvTransaction
import com.rsps1008.stockify.data.Stock
import com.rsps1008.stockify.data.StockKey
import com.rsps1008.stockify.data.StockTransaction
import com.rsps1008.stockify.ui.viewmodel.requiredExistingTransactionKeysForImport
import com.rsps1008.stockify.ui.viewmodel.accountsForReplacementRestore
import com.rsps1008.stockify.ui.viewmodel.describeCsvImportRow
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsImportSupportTest {

    @Test
    fun importErrorContextIncludesCsvRowAndTransactionIdentity() {
        val row = CsvTransaction(
            stockName = "元大台灣50",
            stockCode = "0050",
            market = "TW",
            transaction = StockTransaction(
                stockCode = "0050",
                accountId = 1,
                date = 1L,
                recordTime = 1L,
                type = "分割"
            ),
            sourceRowNumber = 136,
            sourceId = "413"
        )

        assertEquals(
            "（CSV 第 136 列，id=413，股號=0050，交易=分割，帳戶=1）",
            describeCsvImportRow(row)
        )
    }

    @Test
    fun importValidationLoadsOnlyTargetMarketAndRequiredLegacyRepairMarket() {
        val transactionKeys = requiredExistingTransactionKeysForImport(
            importedStockKeys = listOf(
                StockKey("TW", "2330"),
                StockKey("US", "AAPL")
            ),
            existingStocksByCode = mapOf(
                "2330" to listOf(Stock(name = "台積電 ADR 舊資料", code = "2330", market = "US")),
                "AAPL" to listOf(Stock(name = "Apple", code = "AAPL", market = "US"))
            )
        )

        assertEquals(
            listOf(
                StockKey("TW", "2330"),
                StockKey("US", "AAPL"),
                StockKey("US", "2330")
            ),
            transactionKeys
        )
    }

    @Test
    fun importValidationLoadsAllNonCanonicalLegacyMarketsWhenTargetAlreadyExists() {
        val transactionKeys = requiredExistingTransactionKeysForImport(
            importedStockKeys = listOf(StockKey("TW", "2330")),
            existingStocksByCode = mapOf(
                "2330" to listOf(
                    Stock(name = "台積電", code = "2330", market = "TW"),
                    Stock(name = "台積電 ADR 舊資料", code = "2330", market = "US")
                )
            )
        )

        assertEquals(
            listOf(StockKey("TW", "2330"), StockKey("US", "2330")),
            transactionKeys
        )
    }

    @Test
    fun pdfImportUsesTheExistingActiveAccountWhenItIsValid() {
        assertEquals(
            Account(id = 2, name = "退休帳戶"),
            com.rsps1008.stockify.ui.viewmodel.resolvePdfImportAccount(
                activeAccountId = 2,
                existingAccounts = listOf(
                    Account(id = 1, name = "投資帳戶"),
                    Account(id = 2, name = "退休帳戶")
                )
            )
        )
    }

    @Test
    fun pdfImportFallsBackToDefaultAccountForAllAccountsOrDanglingSelection() {
        val accounts = listOf(Account(id = 2, name = "退休帳戶"))
        assertEquals(
            Account(id = 1, name = "預設帳戶"),
            com.rsps1008.stockify.ui.viewmodel.resolvePdfImportAccount(0, accounts)
        )
        assertEquals(
            Account(id = 1, name = "預設帳戶"),
            com.rsps1008.stockify.ui.viewmodel.resolvePdfImportAccount(3, accounts)
        )
    }

    @Test
    fun replacementRestoreUsesBackedUpAccountsInsteadOfAddingTheDefaultAccount() {
        val restoredAccounts = listOf(
            Account(id = 1, name = "投資帳戶"),
            Account(id = 2, name = "退休帳戶")
        )

        assertEquals(restoredAccounts, accountsForReplacementRestore(restoredAccounts))
    }

    @Test
    fun replacementRestoreKeepsTheDefaultAccountWhenThereIsNoAccountBackup() {
        assertEquals(
            listOf(Account(id = 1, name = "預設帳戶")),
            accountsForReplacementRestore(emptyList())
        )
    }
}
