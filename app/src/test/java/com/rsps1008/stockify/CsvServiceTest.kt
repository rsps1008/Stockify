package com.rsps1008.stockify

import com.rsps1008.stockify.data.CsvService
import com.rsps1008.stockify.data.MarginCalculationSupport
import com.rsps1008.stockify.data.Stock
import com.rsps1008.stockify.data.StockMarket
import com.rsps1008.stockify.data.StockTransaction
import com.rsps1008.stockify.data.TransactionWithStock
import com.rsps1008.stockify.data.assignProvisionalImportIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class CsvServiceTest {
    @Test
    fun `csv round trip preserves multiline note`() {
        val service = CsvService()
        val note = "第一行，含逗號\n第二行\"含引號\""
        val output = ByteArrayOutputStream()
        service.export(
            listOf(
                TransactionWithStock(
                    transaction = StockTransaction(
                        stockCode = "2330",
                        date = 1_753_401_600_000L,
                        recordTime = 1_753_456_789_123L,
                        type = "買進",
                        buyPrice = 100.0,
                        buyShares = 10.0,
                        expense = 1_000.0,
                        note = note
                    ),
                    stock = Stock(name = "台積電", code = "2330")
                )
            ),
            output
        )

        val imported = service.import(ByteArrayInputStream(output.toByteArray())).single().transaction

        assertEquals(note, imported.note)
    }

    @Test
    fun `csv round trip preserves dividend supplementary health insurance premium`() {
        val output = ByteArrayOutputStream()
        CsvService().export(
            listOf(
                TransactionWithStock(
                    transaction = StockTransaction(
                        stockCode = "2330",
                        date = 1_753_401_600_000L,
                        recordTime = 1_753_456_789_123L,
                        type = "配息",
                        cashDividend = 100.0,
                        exDividendShares = 1_000.0,
                        fee = 10.0,
                        income = 97_880.0,
                        dividendIncome = 97_880.0,
                        supplementaryHealthInsurancePremium = 2_110.0
                    ),
                    stock = Stock(name = "台積電", code = "2330")
                )
            ),
            output
        )

        val imported = CsvService().import(ByteArrayInputStream(output.toByteArray())).single().transaction

        assertEquals(2_110.0, imported.supplementaryHealthInsurancePremium, 0.0)
        assertEquals(97_880.0, imported.dividendIncome, 0.0)
    }

    @Test
    fun `old csv without self funded override flag does not infer an override`() {
        val service = CsvService()
        val exported = ByteArrayOutputStream().also { output ->
            service.export(
                listOf(
                    TransactionWithStock(
                        transaction = StockTransaction(
                            stockCode = "2330",
                            date = 0L,
                            recordTime = 0L,
                            type = "融資買進",
                            expense = 100_000.0,
                            marginPrincipal = 60_000.0,
                            marginSelfFunded = 40_000.0,
                            marginSelfFundedOverridden = true
                        ),
                        stock = Stock(name = "台積電", code = "2330")
                    )
                ),
                output
            )
        }.toString(Charsets.UTF_8.name())
        val oldCsv = exported.lineSequence()
            .map { line ->
                line.split(',').filterIndexed { index, _ ->
                    exported.lineSequence().first().split(',')[index] != "融資自備款是否覆寫"
                }.joinToString(",")
            }
            .joinToString("\n")

        val imported = service.import(ByteArrayInputStream(oldCsv.toByteArray(Charsets.UTF_8))).single().transaction

        assertEquals(40_000.0, imported.marginSelfFunded, 0.0)
        assertFalse(imported.marginSelfFundedOverridden)
    }

    @Test
    fun `old csv without note column still imports`() {
        val service = CsvService()
        val exported = exportSingleMarginTransaction(service)
        val oldCsv = removeColumn(exported, "筆記")

        val imported = service.import(ByteArrayInputStream(oldCsv.toByteArray(Charsets.UTF_8))).single().transaction

        assertEquals("", imported.note)
    }

    @Test
    fun `csv round trip preserves record time milliseconds`() {
        val service = CsvService()
        val recordTime = 1_753_456_789_123L
        val output = ByteArrayOutputStream()
        service.export(
            listOf(
                TransactionWithStock(
                    transaction = StockTransaction(
                        stockCode = "2330",
                        date = 1_753_401_600_000L,
                        recordTime = recordTime,
                        type = "買進",
                        buyPrice = 100.0,
                        buyShares = 1_000.0,
                        expense = 100_000.0
                    ),
                    stock = Stock(name = "台積電", code = "2330")
                )
            ),
            output
        )

        val imported = service.import(ByteArrayInputStream(output.toByteArray())).single().transaction

        assertEquals(recordTime, imported.recordTime)
    }

    @Test
    fun `csv import rejects missing required columns instead of silently dropping rows`() {
        val invalidCsv = "股名,股號\n台積電,2330\n"

        assertThrows(IllegalArgumentException::class.java) {
            CsvService().import(ByteArrayInputStream(invalidCsv.toByteArray(Charsets.UTF_8)))
        }
    }

    @Test
    fun `csv import rejects malformed financing number instead of converting it to zero`() {
        val service = CsvService()
        val exported = exportSingleMarginTransaction(service)
        val lines = exported.lineSequence().toList()
        val headers = lines.first().split(',')
        val rateIndex = headers.indexOf("融資年利率")
        val values = lines[1].split(',').toMutableList()
        values[rateIndex] = "abc"
        val invalidCsv = listOf(lines.first(), values.joinToString(",")).joinToString("\n")

        assertThrows(IllegalArgumentException::class.java) {
            service.import(ByteArrayInputStream(invalidCsv.toByteArray(Charsets.UTF_8)))
        }
    }

    @Test
    fun `csv import rejects invalid record time instead of replacing its ordering`() {
        val service = CsvService()
        val exported = exportSingleMarginTransaction(service)
        val lines = exported.lineSequence().toList()
        val headers = lines.first().split(',')
        val recordTimeIndex = headers.indexOf("紀錄時間")
        val values = lines[1].split(',').toMutableList()
        values[recordTimeIndex] = "invalid"
        val invalidCsv = listOf(lines.first(), values.joinToString(",")).joinToString("\n")

        assertThrows(IllegalArgumentException::class.java) {
            service.import(ByteArrayInputStream(invalidCsv.toByteArray(Charsets.UTF_8)))
        }
    }

    @Test
    fun `csv import rejects an unknown market instead of treating it as Taiwan`() {
        val service = CsvService()
        val exported = exportSingleMarginTransaction(service)
        val lines = exported.lineSequence().toList()
        val headers = lines.first().split(',')
        val marketIndex = headers.indexOf("市場")
        val values = lines[1].split(',').toMutableList()
        values[marketIndex] = "XX"
        val invalidCsv = listOf(lines.first(), values.joinToString(",")).joinToString("\n")

        assertThrows(IllegalArgumentException::class.java) {
            service.import(ByteArrayInputStream(invalidCsv.toByteArray(Charsets.UTF_8)))
        }
    }

    @Test
    fun `csv import rejects an explicit market that contradicts the stock code`() {
        val service = CsvService()
        val exported = exportSingleMarginTransaction(service)
        val lines = exported.lineSequence().toList()
        val headers = lines.first().split(',')
        val marketIndex = headers.indexOf("市場")
        val values = lines[1].split(',').toMutableList()
        values[marketIndex] = StockMarket.US
        val invalidCsv = listOf(lines.first(), values.joinToString(",")).joinToString("\n")

        assertThrows(IllegalArgumentException::class.java) {
            service.import(ByteArrayInputStream(invalidCsv.toByteArray(Charsets.UTF_8)))
        }
    }

    @Test
    fun `legacy csv without market infers US from its ticker`() {
        val service = CsvService()
        val output = ByteArrayOutputStream()
        service.export(
            listOf(
                TransactionWithStock(
                    transaction = StockTransaction(
                        stockCode = "AAPL",
                        date = 0L,
                        recordTime = 0L,
                        type = "買進",
                        buyPrice = 100.0,
                        buyShares = 1.0,
                        expense = 100.0
                    ),
                    stock = Stock(name = "Apple", code = "AAPL", market = StockMarket.US)
                )
            ),
            output
        )
        val legacyCsv = removeColumn(output.toString(Charsets.UTF_8.name()), "市場")

        val imported = service.import(
            ByteArrayInputStream(legacyCsv.toByteArray(Charsets.UTF_8))
        ).single()

        assertEquals(StockMarket.US, imported.market)
    }

    @Test
    fun `csv export repairs a stale stock market using its code`() {
        val service = CsvService()
        val output = ByteArrayOutputStream()
        service.export(
            listOf(
                TransactionWithStock(
                    transaction = StockTransaction(
                        stockCode = "2330",
                        date = 0L,
                        recordTime = 0L,
                        type = "買進",
                        buyPrice = 100.0,
                        buyShares = 1_000.0,
                        expense = 100_000.0
                    ),
                    stock = Stock(
                        name = "台積電",
                        code = "2330",
                        market = StockMarket.US
                    )
                )
            ),
            output
        )

        val imported = service.import(ByteArrayInputStream(output.toByteArray())).single()

        assertEquals(StockMarket.TW, imported.market)
    }

    @Test
    fun `csv stock code is trimmed consistently for its master and transaction`() {
        val service = CsvService()
        val exported = exportSingleMarginTransaction(service)
        val lines = exported.lineSequence().toList()
        val headers = lines.first().split(',')
        val codeIndex = headers.indexOf("股號")
        val values = lines[1].split(',').toMutableList()
        values[codeIndex] = " 2330 "
        val csv = listOf(lines.first(), values.joinToString(",")).joinToString("\n")

        val imported = service.import(
            ByteArrayInputStream(csv.toByteArray(Charsets.UTF_8))
        ).single()

        assertEquals("2330", imported.stockCode)
        assertEquals(imported.stockCode, imported.transaction.stockCode)
    }

    @Test
    fun `appended import ids preserve database order for same timestamp validation`() {
        val existingOpening = StockTransaction(
            id = 10,
            stockCode = "2330",
            accountId = 1,
            date = 1L,
            recordTime = 1L,
            type = "融資買進",
            marginPrincipal = 60_000.0,
            marginLotId = "margin-1"
        )
        val importedRepayment = StockTransaction(
            stockCode = "2330",
            accountId = 1,
            date = 1L,
            recordTime = 1L,
            type = "融資還款",
            marginRepaymentLotId = "margin-1",
            marginRepayment = 10_000.0
        )

        assertFalse(
            MarginCalculationSupport.hasValidRepaymentBalances(
                listOf(existingOpening, importedRepayment)
            )
        )

        val provisionalImport = assignProvisionalImportIds(
            existingTransactions = listOf(existingOpening),
            importedTransactions = listOf(importedRepayment)
        ).single()

        assertTrue(provisionalImport.id > existingOpening.id)
        assertTrue(
            MarginCalculationSupport.hasValidRepaymentBalances(
                listOf(existingOpening, provisionalImport)
            )
        )
    }

    @Test
    fun `csv import rejects a blank stock code or a nonpositive account id`() {
        val service = CsvService()
        val exported = exportSingleMarginTransaction(service)
        val lines = exported.lineSequence().toList()
        val headers = lines.first().split(',')
        val codeIndex = headers.indexOf("股號")
        val accountIndex = headers.indexOf("帳戶ID")
        val blankCodeValues = lines[1].split(',').toMutableList().also { it[codeIndex] = "" }
        val zeroAccountValues = lines[1].split(',').toMutableList().also { it[accountIndex] = "0" }

        assertThrows(IllegalArgumentException::class.java) {
            service.import(ByteArrayInputStream(listOf(lines.first(), blankCodeValues.joinToString(",")).joinToString("\n").toByteArray(Charsets.UTF_8)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            service.import(ByteArrayInputStream(listOf(lines.first(), zeroAccountValues.joinToString(",")).joinToString("\n").toByteArray(Charsets.UTF_8)))
        }
    }

    private fun exportSingleMarginTransaction(service: CsvService): String {
        return ByteArrayOutputStream().also { output ->
            service.export(
                listOf(
                    TransactionWithStock(
                        transaction = StockTransaction(
                            stockCode = "2330",
                            date = 0L,
                            recordTime = 0L,
                            type = "融資買進",
                            buyPrice = 100.0,
                            buyShares = 1_000.0,
                            expense = 100_000.0,
                            marginPrincipal = 60_000.0,
                            marginAnnualRate = 6.5,
                            marginLotId = "margin-lot-1",
                            marginSelfFunded = 40_000.0,
                            marginSelfFundedOverridden = true
                        ),
                        stock = Stock(name = "台積電", code = "2330")
                    )
                ),
                output
            )
        }.toString(Charsets.UTF_8.name())
    }

    private fun removeColumn(csv: String, columnName: String): String {
        val lines = csv.lineSequence().toList()
        val headers = lines.first().split(',')
        val removedIndex = headers.indexOf(columnName)
        return lines.joinToString("\n") { line ->
            line.split(',').filterIndexed { index, _ -> index != removedIndex }.joinToString(",")
        }
    }
}
