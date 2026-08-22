package com.rsps1008.stockify

import com.rsps1008.stockify.data.PdfStockImportPreviewItem
import com.rsps1008.stockify.data.PdfHoldingImportService
import com.rsps1008.stockify.data.PdfStockImportSupport
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Test

class PdfStockImportSupportTest {
    @Test
    fun ordinaryPdfRowWithZeroBalanceIsRejectedBeforeImport() {
        assertNull(
            PdfHoldingImportService().parseHoldingRow(
                "1 2330 台積電 0 100 2026/08/22 0"
            )
        )
    }

    @Test
    fun itemsWithZeroSharesOrInvalidPricesAreNotImportable() {
        val ready = PdfStockImportSupport.itemsReadyForImport(
            listOf(
                PdfStockImportPreviewItem("2330", "台積電", 0, 100.0, 0.0),
                PdfStockImportPreviewItem("AAPL", "Apple", 2, 0.0, 0.0),
                PdfStockImportPreviewItem("MSFT", "Microsoft", 1, Double.NaN, null),
                PdfStockImportPreviewItem("2317", "鴻海", 10, 100.0, 1_000.0)
            )
        )

        assertEquals(listOf("2317"), ready.map { it.stockCode })
    }

    @Test
    fun refreshKeysIncludeExistingAndNewStocksWithPrices() {
        val keys = PdfStockImportSupport.stockKeysToRefresh(
            listOf(
                PdfStockImportPreviewItem("2330", "台積電", 10, 100.0, 1_000.0),
                PdfStockImportPreviewItem("AAPL", "Apple", 2, 200.0, 400.0),
                PdfStockImportPreviewItem("2317", "鴻海", 10, null, null)
            )
        )

        assertEquals(setOf("TW:2330", "US:AAPL"), keys.map { it.cacheKey() }.toSet())
    }
}
