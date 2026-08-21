package com.rsps1008.stockify.data

object PdfStockImportSupport {
    fun stockKeysToRefresh(items: List<PdfStockImportPreviewItem>): Set<StockKey> =
        items.asSequence()
            .filter { it.currentPrice != null }
            .map { StockKey(StockMarket.inferFromCode(it.stockCode), it.stockCode) }
            .toCollection(linkedSetOf())
}
