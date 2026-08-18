package com.rsps1008.stockify.data

private const val OPEN_POSITION_EPSILON = 1e-6

/**
 * Returns the market-qualified stocks that still have a position requiring a
 * current quote. Historical transactions that have been completely closed
 * are intentionally excluded.
 */
internal fun openStockKeysAt(
    transactions: List<StockTransaction>,
    valuationDate: Long
): Set<String> {
    return transactions
        .groupBy { it.toStockKey().cacheKey() }
        .filterValues { it.hasOpenPositionAt(valuationDate) }
        .keys
}

internal fun List<StockTransaction>.hasOpenPositionAt(valuationDate: Long): Boolean {
    val longSummary = HoldingCalculationSupport.replayLongPosition(this, valuationDate)
    if (longSummary.shares > OPEN_POSITION_EPSILON) return true

    val marginSummary = MarginCalculationSupport.calculate(this, valuationDate)
    if (marginSummary.outstandingPrincipal > OPEN_POSITION_EPSILON ||
        marginSummary.accruedInterest > OPEN_POSITION_EPSILON
    ) {
        return true
    }

    return ShortSellingCalculationSupport
        .calculate(this, valuationDate)
        .outstandingShares > OPEN_POSITION_EPSILON
}
