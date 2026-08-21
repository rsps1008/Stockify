package com.rsps1008.stockify

import com.rsps1008.stockify.data.PdfStockImportPreviewItem
import com.rsps1008.stockify.data.PdfStockImportSupport
import org.junit.Assert.assertEquals
import org.junit.Test

class PdfStockImportSupportTest {
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
