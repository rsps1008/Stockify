package com.rsps1008.stockify.data

object HoldingCalculationSupport {
    /**
     * Applies a split to the shares that were actually recorded for that event.
     * Older CSV backups may only contain the ratio, so they fall back to the
     * current holding at the time of the event.
     */
    fun splitShareChange(transaction: StockTransaction, currentShares: Double): Double {
        val sharesBefore = transaction.sharesBeforeSplit.takeIf { it > 0.0 } ?: currentShares
        val sharesAfter = transaction.sharesAfterSplit.takeIf { it > 0.0 }
            ?: (sharesBefore * (transaction.stockSplitRatio.takeIf { it > 0.0 } ?: 1.0))
        return sharesAfter - sharesBefore
    }

    /**
     * Applies a capital reduction using recorded counts, with a ratio fallback
     * for data created before the post-reduction count was stored.
     */
    fun capitalReductionShareChange(transaction: StockTransaction, currentShares: Double): Double {
        val sharesBefore = transaction.sharesBeforeReduction.takeIf { it > 0.0 } ?: currentShares
        val sharesAfter = transaction.sharesAfterReduction.takeIf { it > 0.0 }
            ?: (sharesBefore * (1.0 - transaction.capitalReductionRatio / 100.0))
        return sharesAfter - sharesBefore
    }

    fun resolveDividendIncome(transaction: StockTransaction): Double {
        return if (transaction.dividendIncome != 0.0 || transaction.income == 0.0) {
            transaction.dividendIncome
        } else {
            transaction.income
        }
    }

    fun remainingPositionDenominator(
        shares: Double,
        costBasis: Double,
        totalInvestment: Double
    ): Double {
        return if (shares > 0.0 && costBasis > 0.0) {
            costBasis
        } else {
            totalInvestment
        }
    }
}
