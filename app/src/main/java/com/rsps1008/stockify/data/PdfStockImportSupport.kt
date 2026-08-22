package com.rsps1008.stockify.data

object PdfStockImportSupport {
    fun itemsReadyForImport(items: List<PdfStockImportPreviewItem>): List<PdfStockImportPreviewItem> =
        items.filter { item ->
            item.balance > 0 && item.currentPrice?.let { it.isFinite() && it > 0.0 } == true
        }

    fun stockKeysToRefresh(items: List<PdfStockImportPreviewItem>): Set<StockKey> =
        itemsReadyForImport(items).asSequence()
            .map { StockKey(StockMarket.inferFromCode(it.stockCode), it.stockCode) }
            .toCollection(linkedSetOf())
}
