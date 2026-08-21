package com.rsps1008.stockify.data

/**
 * Advances the long-position replay once per historical date instead of
 * replaying every preceding transaction for every chart point.
 */
internal class HistoricalLongPositionTimeline(
    transactions: List<StockTransaction>,
    transactionsAreOrdered: Boolean = false
) {
    private data class AccountPositionState(
        var shares: Double = 0.0,
        var buySharesTotal: Double = 0.0,
        var sellSharesTotal: Double = 0.0
    )

    private val orderedTransactions = if (transactionsAreOrdered) {
        transactions
    } else {
        transactions.sortedWith(
            compareBy<StockTransaction> { it.date }
                .thenBy { it.recordTime }
                .thenBy { it.id }
        )
    }
    private var nextIndex = 0
    private val positions = mutableMapOf<Int, AccountPositionState>()
    private var totalBuyExpense = 0.0
    private var totalSellIncome = 0.0
    private var totalSellNetIncome = 0.0
    private var sellSharesTotal = 0.0
    private var sellAmountBeforeFee = 0.0
    private var totalDividendIncome = 0.0
    private var buySharesTotal = 0.0
    private var buyCostTotal = 0.0
    var shortIncome = 0.0
        private set
    var shortCoverExpense = 0.0
        private set
    var hasMarginPurchase = false
        private set

    val hasMarginActivity = orderedTransactions.any { transaction ->
        transaction.type == "融資買進" ||
            transaction.marginRepaymentLotId.isNotBlank() ||
            transaction.marginRepayment > 0.0 ||
            transaction.marginActualInterest > 0.0
    }
    val hasShortActivity = orderedTransactions.any { transaction ->
        transaction.type == "融券賣出" ||
            transaction.type == "買券還券" ||
            transaction.type == "融券補償"
    }

    fun advanceTo(valuationDate: Long): LongPositionReplaySummary {
        while (nextIndex < orderedTransactions.size && orderedTransactions[nextIndex].date <= valuationDate) {
            apply(orderedTransactions[nextIndex])
            nextIndex++
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

    /** Returns the already replayed prefix without copying the transactions. */
    fun transactionsAtCurrentDate(): List<StockTransaction> = orderedTransactions.subList(0, nextIndex)

    private fun apply(transaction: StockTransaction) {
        val position = positions.getOrPut(transaction.accountId) { AccountPositionState() }
        when (transaction.type) {
            "買進", "融資買進" -> {
                position.shares += transaction.buyShares
                position.buySharesTotal += transaction.buyShares
                totalBuyExpense += transaction.expense
                buyCostTotal += transaction.expense
                if (transaction.type == "融資買進") {
                    hasMarginPurchase = true
                }
            }
            "賣出" -> {
                position.shares -= transaction.sellShares
                position.sellSharesTotal += transaction.sellShares
                sellAmountBeforeFee += transaction.sellPrice * transaction.sellShares
                totalSellIncome += transaction.income
                totalSellNetIncome += transaction.income
            }
            "配股" -> position.shares += transaction.dividendShares
            "配息" -> totalDividendIncome += HoldingCalculationSupport.resolveDividendIncome(transaction)
            "減資" -> {
                position.shares += HoldingCalculationSupport.capitalReductionShareChange(transaction, position.shares)
                totalSellIncome += transaction.cashReturned
            }
            "分割" -> {
                position.shares += HoldingCalculationSupport.splitShareChange(transaction, position.shares)
                val splitFactor = HoldingCalculationSupport.splitShareFactor(transaction)
                position.buySharesTotal *= splitFactor
                position.sellSharesTotal *= splitFactor
            }
            "融券賣出" -> shortIncome += transaction.income
            "買券還券" -> shortCoverExpense += transaction.expense
        }
    }
}
