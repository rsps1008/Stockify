package com.rsps1008.stockify.data

data class PositionInvestmentBasis(
    val remaining: Double,
    val cumulative: Double
)

data class LongPositionReplaySummary(
    val shares: Double = 0.0,
    val totalBuyExpense: Double = 0.0,
    val totalSellIncome: Double = 0.0,
    val totalSellNetIncome: Double = 0.0,
    val sellSharesTotal: Double = 0.0,
    val sellAmountBeforeFee: Double = 0.0,
    val totalDividendIncome: Double = 0.0,
    val buySharesTotal: Double = 0.0,
    val buyCostTotal: Double = 0.0
)

object HoldingCalculationSupport {
    private data class PositionKey(val stockCode: String, val accountId: Int)
    private data class AccountPositionState(
        var shares: Double = 0.0,
        var buySharesTotal: Double = 0.0,
        var sellSharesTotal: Double = 0.0
    )

    fun transactionsAtOrBefore(
        transactions: List<StockTransaction>,
        valuationDate: Long
    ): List<StockTransaction> {
        return transactions
            .filter { it.date <= valuationDate }
            .sortedWith(
                compareBy<StockTransaction> { it.date }
                    .thenBy { it.recordTime }
                    .thenBy { it.id }
            )
    }

    fun replayLongPosition(
        transactions: List<StockTransaction>,
        valuationDate: Long
    ): LongPositionReplaySummary {
        val positions = mutableMapOf<PositionKey, AccountPositionState>()
        var totalBuyExpense = 0.0
        var totalSellIncome = 0.0
        var totalSellNetIncome = 0.0
        var sellAmountBeforeFee = 0.0
        var totalDividendIncome = 0.0
        var buyCostTotal = 0.0

        transactionsAtOrBefore(transactions, valuationDate).forEach { transaction ->
            val key = PositionKey(transaction.stockCode, transaction.accountId)
            val position = positions.getOrPut(key) { AccountPositionState() }
            when (transaction.type) {
                "買進", "融資買進" -> {
                    position.shares += transaction.buyShares
                    position.buySharesTotal += transaction.buyShares
                    totalBuyExpense += transaction.expense
                    buyCostTotal += transaction.expense
                }
                "賣出" -> {
                    position.shares -= transaction.sellShares
                    position.sellSharesTotal += transaction.sellShares
                    sellAmountBeforeFee += transaction.sellPrice * transaction.sellShares
                    totalSellIncome += transaction.income
                    totalSellNetIncome += transaction.income
                }
                "配股" -> position.shares += transaction.dividendShares
                "配息" -> totalDividendIncome += resolveDividendIncome(transaction)
                "減資" -> {
                    position.shares += capitalReductionShareChange(transaction, position.shares)
                    totalSellIncome += transaction.cashReturned
                }
                "分割" -> {
                    position.shares += splitShareChange(transaction, position.shares)
                    val splitFactor = splitShareFactor(transaction)
                    position.buySharesTotal *= splitFactor
                    position.sellSharesTotal *= splitFactor
                }
            }
        }

        return LongPositionReplaySummary(
            shares = positions.values.sumOf { it.shares }.coerceAtLeast(0.0),
            totalBuyExpense = totalBuyExpense,
            totalSellIncome = totalSellIncome,
            totalSellNetIncome = totalSellNetIncome,
            sellSharesTotal = positions.values.sumOf { it.sellSharesTotal },
            sellAmountBeforeFee = sellAmountBeforeFee,
            totalDividendIncome = totalDividendIncome,
            buySharesTotal = positions.values.sumOf { it.buySharesTotal },
            buyCostTotal = buyCostTotal
        )
    }

    /**
     * Validates the long position before a transaction list is persisted.
     *
     * The replay used for display remains tolerant of legacy data, but new
     * writes must not create a negative long position and then rely on the
     * display-side clamp to hide it.  The key intentionally includes both
     * stock and account because the same list can contain multiple scopes
     * during import or all-account validation.
     */
    fun validateLongPositionBalances(transactions: List<StockTransaction>): String? {
        val positions = mutableMapOf<PositionKey, Double>()
        val orderedTransactions = transactions.sortedWith(
            compareBy<StockTransaction> { it.date }
                .thenBy { it.recordTime }
                .thenBy { it.id }
        )

        for (transaction in orderedTransactions) {
            val key = PositionKey(transaction.stockCode, transaction.accountId)
            val currentShares = positions[key] ?: 0.0
            val nextShares = when (transaction.type) {
                "買進", "融資買進" -> currentShares + transaction.buyShares
                "賣出" -> {
                    if (!transaction.sellShares.isFinite() || transaction.sellShares < 0.0) {
                        return "賣出股數必須是非負的有效數字"
                    }
                    if (currentShares + POSITION_EPSILON < transaction.sellShares) {
                        return "賣出股數超過交易當下可用持股"
                    }
                    currentShares - transaction.sellShares
                }
                "配股" -> currentShares + transaction.dividendShares
                "減資" -> currentShares + capitalReductionShareChange(transaction, currentShares)
                "分割" -> currentShares + splitShareChange(transaction, currentShares)
                else -> currentShares
            }

            if (!nextShares.isFinite() || nextShares < -POSITION_EPSILON) {
                return "交易回放後的持股數無效"
            }
            positions[key] = nextShares.coerceAtLeast(0.0)
        }

        return null
    }

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

    fun splitShareFactor(transaction: StockTransaction): Double {
        val sharesBefore = transaction.sharesBeforeSplit
        val sharesAfter = transaction.sharesAfterSplit
        return when {
            sharesBefore > 0.0 && sharesAfter > 0.0 -> sharesAfter / sharesBefore
            transaction.stockSplitRatio > 0.0 -> transaction.stockSplitRatio
            else -> 1.0
        }
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

    fun capitalReductionShareFactor(transaction: StockTransaction): Double {
        val sharesBefore = transaction.sharesBeforeReduction
        val sharesAfter = transaction.sharesAfterReduction
        return when {
            sharesBefore > 0.0 && sharesAfter > 0.0 -> sharesAfter / sharesBefore
            transaction.capitalReductionRatio in 0.0..100.0 ->
                1.0 - transaction.capitalReductionRatio / 100.0
            else -> 1.0
        }
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

    fun positionInvestmentBasis(
        shares: Double,
        costBasis: Double,
        longInvestment: Double,
        financedRemainingInvestment: Double? = null,
        marginDebt: Double,
        shortOutstandingShares: Double,
        shortRemainingInvestment: Double,
        shortCumulativeInvestment: Double
    ): PositionInvestmentBasis {
        val cumulative = longInvestment + shortCumulativeInvestment
        val hasOpenPosition = shares > POSITION_EPSILON ||
            marginDebt > POSITION_EPSILON ||
            shortOutstandingShares > POSITION_EPSILON
        if (!hasOpenPosition) {
            return PositionInvestmentBasis(remaining = cumulative, cumulative = cumulative)
        }

        val longRemaining = when {
            financedRemainingInvestment != null &&
                (shares > POSITION_EPSILON || marginDebt > POSITION_EPSILON) ->
                financedRemainingInvestment.takeIf { it > POSITION_EPSILON } ?: longInvestment
            shares > POSITION_EPSILON && costBasis > 0.0 -> costBasis
            shares > POSITION_EPSILON || marginDebt > POSITION_EPSILON -> longInvestment
            else -> 0.0
        }
        return PositionInvestmentBasis(
            remaining = longRemaining + shortRemainingInvestment,
            cumulative = cumulative
        )
    }

    private const val POSITION_EPSILON = 1e-6
}
