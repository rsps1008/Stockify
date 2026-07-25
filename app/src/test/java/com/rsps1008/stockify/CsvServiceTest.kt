package com.rsps1008.stockify

import com.rsps1008.stockify.data.CsvService
import com.rsps1008.stockify.data.Stock
import com.rsps1008.stockify.data.StockTransaction
import com.rsps1008.stockify.data.TransactionWithStock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class CsvServiceTest {
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
}
