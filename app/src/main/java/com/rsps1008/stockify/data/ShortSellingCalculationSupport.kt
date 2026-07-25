package com.rsps1008.stockify.data

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

data class ShortLotSummary(
    val lotId: String,
    val openedAt: Long,
    val annualRate: Double,
    val originalShares: Double,
    val remainingShares: Double,
    val originalPrincipal: Double,
    val accruedBorrowFee: Double
)

data class ShortSellingSummary(
    val outstandingShares: Double = 0.0,
    val accruedBorrowFee: Double = 0.0,
    val compensationExpense: Double = 0.0,
    val lots: List<ShortLotSummary> = emptyList(),
    val openedPrincipal: Double = 0.0,
    val cumulativeOpenedPrincipal: Double = 0.0
)

/** Replays short-sale lots. Rates are user-entered estimates, not broker settlement values. */
object ShortSellingCalculationSupport {
    private data class LotState(
        val id: String, val openedAt: Long, val annualRate: Double,
        var originalShares: Double, val originalPrincipal: Double,
        var remainingShares: Double, var accruedBorrowFee: Double, var lastAccrualDate: Long
    )

    fun calculate(transactions: List<StockTransaction>, valuationDate: Long, dayCount: Int = 365): ShortSellingSummary {
        val denominator = if (dayCount == 360) 360 else 365
        val lots = linkedMapOf<String, LotState>()
        var compensationExpense = 0.0
        var cumulativeOpenedPrincipal = 0.0
        fun accrue(lot: LotState, date: Long) {
            if (date <= lot.lastAccrualDate || lot.remainingShares <= 0.0) return
            val days = daysBetween(lot.lastAccrualDate, date)
            val principal = lot.originalPrincipal * lot.remainingShares / lot.originalShares
            lot.accruedBorrowFee += principal * lot.annualRate / 100.0 * days / denominator
            lot.lastAccrualDate = date
        }
        transactions.asSequence()
            .filter { it.date <= valuationDate }
            .sortedWith(compareBy<StockTransaction> { it.date }.thenBy { it.recordTime })
            .forEach { tx ->
            lots.values.forEach { accrue(it, tx.date) }
            when (tx.type) {
                "融券賣出" -> {
                    val shares = tx.sellShares.coerceAtLeast(0.0)
                    val principal = tx.shortBorrowPrincipal.takeIf { it > 0.0 } ?: tx.sellPrice * shares
                    if (shares > 0.0) {
                        val id = tx.shortLotId.ifBlank { "legacy-short-${tx.id}" }
                        lots[id] = LotState(id, tx.date, tx.shortBorrowAnnualRate, shares, principal, shares, 0.0, tx.date)
                        cumulativeOpenedPrincipal += principal
                    }
                }
                "買券還券" -> if (tx.shortCoverLotId.isNotBlank() && tx.shortCoverShares > 0.0) {
                    lots[tx.shortCoverLotId]?.let { it.remainingShares = (it.remainingShares - tx.shortCoverShares).coerceAtLeast(0.0); it.lastAccrualDate = tx.date }
                }
                "融券補償" -> compensationExpense += tx.shortCompensation.coerceAtLeast(0.0)
                "分割", "減資" -> {
                    val factor = shareAdjustmentFactor(tx)
                    if (factor > 0.0 && factor != 1.0) {
                        lots.values.forEach { lot ->
                            lot.originalShares *= factor
                            lot.remainingShares *= factor
                        }
                    }
                }
            }
        }
        lots.values.forEach { accrue(it, valuationDate) }
        return ShortSellingSummary(
            outstandingShares = lots.values.sumOf { it.remainingShares },
            accruedBorrowFee = lots.values.sumOf { it.accruedBorrowFee },
            compensationExpense = compensationExpense,
            openedPrincipal = lots.values
                .filter { it.remainingShares > 0.0 }
                .sumOf { it.originalPrincipal * (it.remainingShares / it.originalShares) },
            cumulativeOpenedPrincipal = cumulativeOpenedPrincipal,
            lots = lots.values.filter { it.remainingShares > 0.0 }.map {
                ShortLotSummary(it.id, it.openedAt, it.annualRate, it.originalShares, it.remainingShares, it.originalPrincipal, it.accruedBorrowFee)
            }
        )
    }

    fun buildXirrCashFlows(
        transactions: List<StockTransaction>,
        valuationDate: Long,
        currentPrice: Double,
        dayCount: Int = 365
    ): List<CashFlow> {
        data class XirrLotState(
            var originalShares: Double,
            var remainingShares: Double,
            val originalPrincipal: Double,
            val openingIncome: Double
        )

        val lots = linkedMapOf<String, XirrLotState>()
        val cashFlows = mutableListOf<CashFlow>()
        transactions.asSequence()
            .filter { it.date <= valuationDate }
            .sortedWith(compareBy<StockTransaction> { it.date }.thenBy { it.recordTime })
            .forEach { transaction ->
                when (transaction.type) {
                    "融券賣出" -> {
                        val shares = transaction.sellShares.coerceAtLeast(0.0)
                        if (shares > 0.0) {
                            val principal = transaction.shortBorrowPrincipal.takeIf { it > 0.0 }
                                ?: transaction.sellPrice * shares
                            val lotId = transaction.shortLotId.ifBlank { "legacy-short-${transaction.id}" }
                            lots[lotId] = XirrLotState(
                                originalShares = shares,
                                remainingShares = shares,
                                originalPrincipal = principal,
                                openingIncome = transaction.income
                            )
                            cashFlows += CashFlow(transaction.date, -principal)
                        }
                    }
                    "買券還券" -> {
                        val requestedShares = transaction.shortCoverShares.coerceAtLeast(0.0)
                        val lot = lots[transaction.shortCoverLotId]
                        if (lot != null && requestedShares > 0.0 && lot.remainingShares > 0.0) {
                            val coveredShares = requestedShares.coerceAtMost(lot.remainingShares)
                            val coveredRatio = coveredShares / lot.originalShares
                            val allocatedCoverExpense = transaction.expense * (coveredShares / requestedShares)
                            val returnedAmount =
                                lot.originalPrincipal * coveredRatio +
                                    lot.openingIncome * coveredRatio -
                                    allocatedCoverExpense
                            cashFlows += CashFlow(transaction.date, returnedAmount)
                            lot.remainingShares -= coveredShares
                        } else if (transaction.expense > 0.0) {
                            cashFlows += CashFlow(transaction.date, -transaction.expense)
                        }
                    }
                    "融券補償" -> if (transaction.shortCompensation > 0.0) {
                        cashFlows += CashFlow(transaction.date, -transaction.shortCompensation)
                    }
                    "分割", "減資" -> {
                        val factor = shareAdjustmentFactor(transaction)
                        if (factor > 0.0 && factor != 1.0) {
                            lots.values.forEach { lot ->
                                lot.originalShares *= factor
                                lot.remainingShares *= factor
                            }
                        }
                    }
                }
            }

        if (currentPrice > 0.0) {
            lots.values.filter { it.remainingShares > 0.0 }.forEach { lot ->
                val remainingRatio = lot.remainingShares / lot.originalShares
                cashFlows += CashFlow(
                    valuationDate,
                    lot.originalPrincipal * remainingRatio +
                        lot.openingIncome * remainingRatio -
                        currentPrice * lot.remainingShares
                )
            }
        }

        val accruedBorrowFee = calculate(transactions, valuationDate, dayCount).accruedBorrowFee
        if (accruedBorrowFee > 0.0) {
            cashFlows += CashFlow(valuationDate, -accruedBorrowFee)
        }
        return cashFlows
    }

    /** Validates selected-lot covers without silently clamping excess shares. */
    fun hasValidCoverBalances(transactions: List<StockTransaction>): Boolean {
        val remainingByLot = mutableMapOf<String, Double>()

        transactions
            .sortedWith(compareBy<StockTransaction> { it.date }.thenBy { it.recordTime })
            .forEach { transaction ->
                if (transaction.type == "融券賣出") {
                    val lotId = transaction.shortLotId.ifBlank { "legacy-short-${transaction.id}" }
                    remainingByLot[lotId] = transaction.sellShares.coerceAtLeast(0.0)
                }

                if (transaction.shortCoverShares > 0.0) {
                    val lotId = transaction.shortCoverLotId
                    val remaining = remainingByLot[lotId] ?: return false
                    if (transaction.shortCoverShares > remaining + BALANCE_EPSILON) return false
                    remainingByLot[lotId] = (remaining - transaction.shortCoverShares).coerceAtLeast(0.0)
                }

                if (transaction.type == "融券補償") {
                    val remaining = remainingByLot[transaction.shortCompensationLotId] ?: return false
                    if (remaining <= BALANCE_EPSILON) return false
                }

                if (transaction.type == "分割" || transaction.type == "減資") {
                    val factor = shareAdjustmentFactor(transaction)
                    if (factor > 0.0 && factor != 1.0) {
                        remainingByLot.replaceAll { _, remaining -> remaining * factor }
                    }
                }
            }

        return true
    }

    private fun daysBetween(start: Long, end: Long): Long = ChronoUnit.DAYS.between(
        Instant.ofEpochMilli(start).atZone(ZoneId.systemDefault()).toLocalDate(),
        Instant.ofEpochMilli(end).atZone(ZoneId.systemDefault()).toLocalDate()
    ).coerceAtLeast(0L)

    private fun shareAdjustmentFactor(transaction: StockTransaction): Double {
        return when (transaction.type) {
            "分割" -> HoldingCalculationSupport.splitShareFactor(transaction)
            "減資" -> HoldingCalculationSupport.capitalReductionShareFactor(transaction)
            else -> 1.0
        }
    }

    private const val BALANCE_EPSILON = 1e-6
}
