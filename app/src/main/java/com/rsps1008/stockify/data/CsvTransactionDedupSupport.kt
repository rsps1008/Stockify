package com.rsps1008.stockify.data

object CsvTransactionDedupSupport {
    fun filterNewTransactions(
        importedTransactions: List<CsvTransaction>,
        existingTransactions: Collection<StockTransaction>
    ): List<CsvTransaction> {
        val seen = existingTransactions
            .asSequence()
            .map(::identityKey)
            .toMutableSet()
        return importedTransactions.filter { imported ->
            seen.add(identityKey(imported.transaction))
        }
    }

    private fun identityKey(transaction: StockTransaction): StockTransaction = transaction.copy(
        id = 0,
        stockCode = transaction.stockCode.trim().uppercase(),
        market = StockMarket.normalize(transaction.market)
    )
}
