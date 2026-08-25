package com.rsps1008.stockify.data

/**
 * Incrementally collects the non-short cash flows used by historical XIRR.
 * Transaction ordering is identical to the financial replay ordering.
 */
internal class HistoricalTransactionCashFlowTimeline(
    transactions: List<StockTransaction>,
    private val currencyRate: Double = 1.0,
    private val transactionDateMapper: (Long) -> Long = { it },
    transactionsAreOrdered: Boolean = false
) {
    private val orderedTransactions = if (transactionsAreOrdered) {
        transactions
    } else {
        transactions.sortedWith(
            compareBy<StockTransaction> { it.date }
                .thenBy { it.recordTime }
                .thenBy { it.id }
        )
    }
    private val cashFlows = mutableListOf<CashFlow>()
    private var nextIndex = 0
    private var lastValuationDate = Long.MIN_VALUE

    fun cashFlowsAt(valuationDate: Long): List<CashFlow> {
        require(valuationDate >= lastValuationDate) {
            "歷史 XIRR 現金流日期必須遞增"
        }
        while (nextIndex < orderedTransactions.size && orderedTransactions[nextIndex].date <= valuationDate) {
            transactionCashFlow(orderedTransactions[nextIndex])?.let(cashFlows::add)
            nextIndex++
        }
        lastValuationDate = valuationDate
        return cashFlows.toList()
    }

    private fun transactionCashFlow(transaction: StockTransaction): CashFlow? {
        val amount = when (transaction.type) {
            "買進" -> -transaction.expense * currencyRate
            "融資買進" -> -(if (transaction.marginSelfFundedOverridden) {
                transaction.marginSelfFunded
            } else {
                transaction.expense - transaction.marginPrincipal
            }) * currencyRate
            "賣出" -> (transaction.income - transaction.marginRepayment - transaction.marginActualInterest) * currencyRate
            "融資還款" -> -(transaction.marginRepayment + transaction.marginActualInterest) * currencyRate
            "配息" -> HoldingCalculationSupport.resolveDividendIncome(transaction) * currencyRate
            "減資" -> transaction.cashReturned * currencyRate
            else -> return null
        }
        return CashFlow(transactionDateMapper(transaction.date), amount)
    }
}
