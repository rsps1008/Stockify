package com.rsps1008.stockify.data

object HoldingCalculationSupport {
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
