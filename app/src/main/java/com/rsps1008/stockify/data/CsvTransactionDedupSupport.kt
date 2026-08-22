package com.rsps1008.stockify.data

object CsvTransactionDedupSupport {
    fun filterNewTransactions(
        importedTransactions: List<CsvTransaction>,
        existingTransactions: Collection<StockTransaction>,
        marketAliases: Map<String, String> = emptyMap()
    ): List<CsvTransaction> {
        val seen = existingTransactions
            .asSequence()
            .map { identityKey(it, marketAliases) }
            .toMutableSet()
        return importedTransactions.filter { imported ->
            seen.add(identityKey(imported.transaction, marketAliases))
        }
    }

    fun marketRepairAliases(
        importedStockKeys: Collection<StockKey>,
        existingStocksByCode: Map<String, List<Stock>>
    ): Map<String, String> = buildMap {
        importedStockKeys.forEach { targetKey ->
            val existingStocks = existingStocksByCode[targetKey.normalizedCode].orEmpty()
            val hasTargetMarket = existingStocks.any {
                StockMarket.normalize(it.market) == targetKey.normalizedMarket
            }
            val legacyStock = existingStocks
                .singleOrNull()
                ?.takeIf {
                    !hasTargetMarket &&
                        StockMarket.normalize(it.market) != targetKey.normalizedMarket
                }
                ?: return@forEach

            put(
                StockKey(legacyStock.market, targetKey.code).cacheKey(),
                targetKey.normalizedMarket
            )
        }
    }

    fun canonicalizeExistingTransactions(
        existingTransactions: Collection<StockTransaction>,
        marketAliases: Map<String, String>
    ): List<StockTransaction> = existingTransactions.map { transaction ->
        val targetMarket = marketAliases[StockKey(transaction.market, transaction.stockCode).cacheKey()]
        if (targetMarket == null) transaction else transaction.copy(market = targetMarket)
    }

    private fun identityKey(
        transaction: StockTransaction,
        marketAliases: Map<String, String>
    ): StockTransaction = transaction.copy(
        id = 0,
        stockCode = transaction.stockCode.trim().uppercase(),
        market = marketAliases[StockKey(transaction.market, transaction.stockCode).cacheKey()]
            ?: StockMarket.normalize(transaction.market)
    )
}
